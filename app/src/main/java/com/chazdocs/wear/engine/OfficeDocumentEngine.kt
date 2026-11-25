package com.chazdocs.wear.engine

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedPptxDeck(
    val slides: List<TextChapter>,
    val tocEntries: List<TocEntry>,
)

/**
 * Pure-Kotlin lightweight parser for OOXML Office documents (.docx and .pptx)
 * using ZipInputStream and standard DOM XML parsing with zero external dependencies.
 */
object OfficeDocumentEngine {

    private const val MAX_XML_ENTRY_BYTES = 8_000_000L
    private val slideFileRegex = Regex("^ppt/slides/slide(\\d+)\\.xml$", RegexOption.IGNORE_CASE)

    fun parseDocxFromStream(inputStream: InputStream): ParsedTextDocument {
        var documentXmlBytes: ByteArray? = null

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalized = entry.name.replace('\\', '/').trimStart('/')
                    if (normalized.equals("word/document.xml", ignoreCase = true) &&
                        entry.size <= MAX_XML_ENTRY_BYTES
                    ) {
                        documentXmlBytes = zis.readBytes()
                        zis.closeEntry()
                        break
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val xmlBytes = documentXmlBytes
            ?: throw IllegalArgumentException("Invalid DOCX: missing word/document.xml")

        val doc = parseXml(xmlBytes)
        val body = findFirstElementByLocalName(doc.documentElement, "body")
            ?: throw IllegalArgumentException("Invalid DOCX: missing w:body")

        val blocks = ArrayList<ReflowBlock>()
        val toc = ArrayList<TocEntry>()

        walkDocxBody(body, blocks, toc)

        val finalToc = if (toc.isNotEmpty()) toc else buildMilestoneToc(blocks.size)
        return ParsedTextDocument(blocks = blocks, tocEntries = finalToc)
    }

    fun parsePptxFromStream(inputStream: InputStream): ParsedPptxDeck {
        val entries = HashMap<String, ByteArray>()

        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalized = entry.name.replace('\\', '/').trimStart('/')
                    val isXmlOrRels = normalized.endsWith(".xml", ignoreCase = true) ||
                        normalized.endsWith(".rels", ignoreCase = true)
                    if (isXmlOrRels && entry.size <= MAX_XML_ENTRY_BYTES) {
                        entries[normalized] = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val slidePaths = resolvePptxSlideOrder(entries)
        if (slidePaths.isEmpty()) {
            throw IllegalArgumentException("Invalid PPTX: no slides found")
        }

        val slides = ArrayList<TextChapter>(slidePaths.size)
        val tocEntries = ArrayList<TocEntry>(slidePaths.size)

        for ((index, slidePath) in slidePaths.withIndex()) {
            val slideBytes = findEntryBytes(entries, slidePath) ?: continue
            val slideDoc = parseXml(slideBytes)
            val parsedSlide = parsePptxSlide(slideDoc)

            val blocks = ArrayList<ReflowBlock>(parsedSlide.blocks)
            val notesText = extractPptxSlideNotes(entries, slidePath)
            if (notesText != null) {
                blocks.add(ReflowBlock("Note: $notesText", BlockStyle.QUOTE))
            }

            val finalBlocks = if (blocks.isNotEmpty()) {
                blocks
            } else {
                listOf(ReflowBlock("(Empty slide)", BlockStyle.BODY))
            }

            val slideTitle = parsedSlide.title
            val chapterTitle = slideTitle ?: "Slide ${index + 1}"
            val tocTitle = if (slideTitle != null) {
                "Slide ${index + 1}: $slideTitle"
            } else {
                "Slide ${index + 1}"
            }

            slides.add(TextChapter(title = chapterTitle, blocks = finalBlocks))
            tocEntries.add(TocEntry(title = tocTitle, level = 0, targetChapter = slides.size - 1))
        }

        if (slides.isEmpty()) {
            throw IllegalArgumentException("Invalid PPTX: slide XML entries could not be resolved")
        }

        return ParsedPptxDeck(slides = slides, tocEntries = tocEntries)
    }

    // -------------------------------------------------------------------------
    // DOCX Helpers
    // -------------------------------------------------------------------------

    private fun walkDocxBody(
        container: Element,
        blocks: MutableList<ReflowBlock>,
        toc: MutableList<TocEntry>,
    ) {
        for (child in childElements(container)) {
            when (child.localOrTagName().lowercase()) {
                "p" -> parseDocxParagraph(child, blocks, toc)
                "tbl" -> parseDocxTable(child, blocks)
                "sdt", "sdtcontent" -> walkDocxBody(child, blocks, toc)
            }
        }
    }

    private fun parseDocxParagraph(
        p: Element,
        blocks: MutableList<ReflowBlock>,
        toc: MutableList<TocEntry>,
    ) {
        val pPr = firstChildByLocalName(p, "pPr")
        val pStyleVal = pPr
            ?.let { firstChildByLocalName(it, "pStyle") }
            ?.attrByLocalName("val")
        val hasNumPr = pPr?.let { firstChildByLocalName(it, "numPr") != null } == true

        val (style, tocLevel) = mapDocxStyle(pStyleVal)
        val rawText = collectDocxParagraphText(p)
        val text = if (style == BlockStyle.CODE) rawText.trimEnd() else rawText.trim()
        if (text.isBlank()) return

        if (tocLevel != null) {
            val blockIndex = blocks.size
            blocks.add(ReflowBlock(text, style))
            toc.add(TocEntry(title = text, level = tocLevel, targetBlockIndex = blockIndex))
            return
        }

        val finalText = if (hasNumPr && style == BlockStyle.BODY && !isAlreadyBulleted(text)) {
            "• $text"
        } else {
            text
        }
        blocks.add(ReflowBlock(finalText, style))
    }

    private fun mapDocxStyle(styleVal: String?): Pair<BlockStyle, Int?> {
        val normalized = styleVal?.trim()?.lowercase().orEmpty()
        return when (normalized) {
            "title", "heading1", "1" -> BlockStyle.H1 to 0
            "subtitle", "heading2", "2" -> BlockStyle.H2 to 1
            "heading3", "heading4", "heading5", "heading6", "3", "4", "5", "6" -> BlockStyle.H3 to 2
            "quote", "intensequote" -> BlockStyle.QUOTE to null
            "code" -> BlockStyle.CODE to null
            else -> BlockStyle.BODY to null
        }
    }

    private fun collectDocxParagraphText(element: Element): String {
        val sb = StringBuilder()
        appendDocxTextRecursive(element, sb)
        return sb.toString()
    }

    private fun appendDocxTextRecursive(node: Node, sb: StringBuilder) {
        if (node.nodeType != Node.ELEMENT_NODE) return
        val el = node as Element
        when (el.localOrTagName().lowercase()) {
            "ppr", "del", "instrtext" -> return
            "t" -> sb.append(el.textContent.orEmpty())
            "tab" -> sb.append(' ')
            "br", "cr" -> sb.append('\n')
            else -> {
                val children = el.childNodes
                for (i in 0 until children.length) {
                    appendDocxTextRecursive(children.item(i), sb)
                }
            }
        }
    }

    private fun parseDocxTable(tbl: Element, blocks: MutableList<ReflowBlock>) {
        for (row in childElements(tbl, "tr")) {
            val cellTexts = ArrayList<String>()
            for (cell in childElements(row, "tc")) {
                val cellParagraphs = childElements(cell, "p")
                    .map { collectDocxParagraphText(it).trim() }
                    .filter { it.isNotEmpty() }
                if (cellParagraphs.isNotEmpty()) {
                    cellTexts.add(cellParagraphs.joinToString(" "))
                }
            }
            if (cellTexts.isNotEmpty()) {
                blocks.add(ReflowBlock(cellTexts.joinToString(" | "), BlockStyle.BODY))
            }
        }
    }

    private fun buildMilestoneToc(blockCount: Int): List<TocEntry> {
        if (blockCount <= 0) return emptyList()
        if (blockCount <= 10) {
            return listOf(TocEntry("Beginning", level = 0, targetBlockIndex = 0))
        }
        return listOf(
            TocEntry("Beginning (0%)", level = 0, targetBlockIndex = 0),
            TocEntry("Quarter (25%)", level = 0, targetBlockIndex = (blockCount * 0.25f).toInt()),
            TocEntry("Middle (50%)", level = 0, targetBlockIndex = (blockCount * 0.50f).toInt()),
            TocEntry("Three-Quarters (75%)", level = 0, targetBlockIndex = (blockCount * 0.75f).toInt()),
            TocEntry("End (100%)", level = 0, targetBlockIndex = blockCount - 1),
        )
    }

    // -------------------------------------------------------------------------
    // PPTX Helpers
    // -------------------------------------------------------------------------

    private data class ParsedSlideContent(
        val title: String?,
        val blocks: List<ReflowBlock>,
    )

    private fun resolvePptxSlideOrder(entries: Map<String, ByteArray>): List<String> {
        val presentationBytes = findEntryBytes(entries, "ppt/presentation.xml")
        val relsBytes = findEntryBytes(entries, "ppt/_rels/presentation.xml.rels")

        if (presentationBytes != null && relsBytes != null) {
            val relTargetById = parseRelationships(relsBytes)
            val presentationDoc = parseXml(presentationBytes)
            val sldIdNodes = elementsByLocalName(presentationDoc.documentElement, "sldId")
            val ordered = ArrayList<String>(sldIdNodes.size)
            for (sldId in sldIdNodes) {
                val relId = extractRelId(sldId, relTargetById) ?: continue
                val target = relTargetById[relId] ?: continue
                val resolved = resolvePath("ppt", target)
                if (findEntryKey(entries, resolved) != null) {
                    ordered.add(resolved)
                }
            }
            if (ordered.isNotEmpty()) {
                return ordered
            }
        }

        return entries.keys
            .mapNotNull { key ->
                val match = slideFileRegex.matchEntire(key)
                if (match != null) {
                    match.groupValues[1].toIntOrNull()?.let { num -> num to key }
                } else {
                    null
                }
            }
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun extractRelId(el: Element, relTargetById: Map<String, String>): String? {
        el.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
            .takeIf { it.isNotBlank() }
            ?.let { return it }
        el.getAttribute("r:id")
            .takeIf { it.isNotBlank() }
            ?.let { return it }
        val attrs = el.attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            val isRelAttr = attr.nodeName.equals("r:id", ignoreCase = true) ||
                (attr.prefix == "r" && attr.localName == "id")
            if (isRelAttr && !attr.nodeValue.isNullOrBlank()) {
                return attr.nodeValue
            }
        }
        for (i in 0 until attrs.length) {
            val value = attrs.item(i).nodeValue
            if (!value.isNullOrBlank() && relTargetById.containsKey(value)) {
                return value
            }
        }
        return null
    }

    private fun parseRelationships(relsBytes: ByteArray): Map<String, String> {
        val doc = parseXml(relsBytes)
        val map = HashMap<String, String>()
        for (rel in elementsByLocalName(doc.documentElement, "Relationship")) {
            val id = rel.attrByLocalName("Id")
            val target = rel.attrByLocalName("Target")
            if (!id.isNullOrBlank() && !target.isNullOrBlank()) {
                map[id] = target
            }
        }
        return map
    }

    private fun parsePptxSlide(slideDoc: Document): ParsedSlideContent {
        val spTree = findFirstElementByLocalName(slideDoc.documentElement, "spTree")
            ?: return ParsedSlideContent(title = null, blocks = emptyList())

        val blocks = ArrayList<ReflowBlock>()
        var primaryTitle: String? = null
        var subtitleFallback: String? = null

        fun walkShapeTree(container: Element) {
            for (child in childElements(container)) {
                when (child.localOrTagName().lowercase()) {
                    "sp" -> {
                        val phType = extractPlaceholderType(child)?.lowercase()
                        if (phType == "sldnum") continue

                        val isTitle = phType == "title" || phType == "ctrtitle" || phType == "subtitle"
                        val txBody = firstChildByLocalName(child, "txBody") ?: continue
                        val paragraphs = childElements(txBody, "p")

                        for (p in paragraphs) {
                            val text = collectDrawingParagraphText(p).trim()
                            if (text.isEmpty()) continue

                            if (isTitle) {
                                blocks.add(ReflowBlock(text, BlockStyle.H1))
                                if (phType == "title" || phType == "ctrtitle") {
                                    if (primaryTitle == null) primaryTitle = text
                                } else if (subtitleFallback == null) {
                                    subtitleFallback = text
                                }
                            } else {
                                val bulleted = shouldPrefixPptxBullet(p, phType, paragraphs.size)
                                val line = if (bulleted && !isAlreadyBulleted(text)) "• $text" else text
                                blocks.add(ReflowBlock(line, BlockStyle.BODY))
                            }
                        }
                    }

                    "tbl" -> parsePptxTable(child, blocks)

                    else -> walkShapeTree(child)
                }
            }
        }

        walkShapeTree(spTree)
        val resolvedTitle = primaryTitle ?: subtitleFallback
        return ParsedSlideContent(title = resolvedTitle, blocks = blocks)
    }

    private fun shouldPrefixPptxBullet(
        p: Element,
        phType: String?,
        shapeParagraphCount: Int,
    ): Boolean {
        val pPr = firstChildByLocalName(p, "pPr")
        if (pPr != null) {
            if (firstChildByLocalName(pPr, "buNone") != null) return false
            if (firstChildByLocalName(pPr, "buChar") != null ||
                firstChildByLocalName(pPr, "buAutoNum") != null ||
                pPr.attrByLocalName("lvl") != null
            ) {
                return true
            }
        }
        return phType == "body" && shapeParagraphCount > 1
    }

    private fun parsePptxTable(tbl: Element, blocks: MutableList<ReflowBlock>) {
        for (row in childElements(tbl, "tr")) {
            val cellTexts = ArrayList<String>()
            for (cell in childElements(row, "tc")) {
                val txBody = firstChildByLocalName(cell, "txBody") ?: continue
                val cellText = childElements(txBody, "p")
                    .map { collectDrawingParagraphText(it).trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
                if (cellText.isNotEmpty()) {
                    cellTexts.add(cellText)
                }
            }
            if (cellTexts.isNotEmpty()) {
                blocks.add(ReflowBlock(cellTexts.joinToString(" | "), BlockStyle.BODY))
            }
        }
    }

    private fun extractPptxSlideNotes(entries: Map<String, ByteArray>, slidePath: String): String? {
        val slideDir = slidePath.substringBeforeLast('/', "")
        val slideFile = slidePath.substringAfterLast('/')
        val relsPath = if (slideDir.isEmpty()) "_rels/$slideFile.rels" else "$slideDir/_rels/$slideFile.rels"
        val relsBytes = findEntryBytes(entries, relsPath) ?: return null

        val relsDoc = parseXml(relsBytes)
        var notesTarget: String? = null
        for (rel in elementsByLocalName(relsDoc.documentElement, "Relationship")) {
            val type = rel.attrByLocalName("Type").orEmpty()
            if (type.endsWith("/notesSlide", ignoreCase = true) ||
                type.equals("notesSlide", ignoreCase = true)
            ) {
                notesTarget = rel.attrByLocalName("Target")
                break
            }
        }
        val target = notesTarget ?: return null
        val notesPath = resolvePath(slideDir, target)
        val notesBytes = findEntryBytes(entries, notesPath) ?: return null

        val notesDoc = parseXml(notesBytes)
        val noteLines = ArrayList<String>()
        for (sp in elementsByLocalName(notesDoc.documentElement, "sp")) {
            val phType = extractPlaceholderType(sp)?.lowercase()
            if (phType == "sldnum") continue

            val txBody = firstChildByLocalName(sp, "txBody") ?: continue
            for (p in childElements(txBody, "p")) {
                val text = collectDrawingParagraphText(p).trim()
                if (text.isNotEmpty()) {
                    noteLines.add(text)
                }
            }
        }

        val combined = noteLines.joinToString("\n").trim()
        return combined.takeIf { it.isNotEmpty() }
    }

    private fun extractPlaceholderType(sp: Element): String? {
        val nvSpPr = firstChildByLocalName(sp, "nvSpPr") ?: return null
        val nvPr = firstChildByLocalName(nvSpPr, "nvPr") ?: return null
        val ph = firstChildByLocalName(nvPr, "ph") ?: return null
        return ph.attrByLocalName("type") ?: ""
    }

    private fun collectDrawingParagraphText(p: Element): String {
        val sb = StringBuilder()
        appendDrawingTextRecursive(p, sb)
        return sb.toString()
    }

    private fun appendDrawingTextRecursive(node: Node, sb: StringBuilder) {
        if (node.nodeType != Node.ELEMENT_NODE) return
        val el = node as Element
        when (el.localOrTagName().lowercase()) {
            "ppr", "endpararpr" -> return
            "t" -> sb.append(el.textContent.orEmpty())
            "tab" -> sb.append(' ')
            "br" -> sb.append('\n')
            else -> {
                val children = el.childNodes
                for (i in 0 until children.length) {
                    appendDrawingTextRecursive(children.item(i), sb)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared XML / ZIP Utilities
    // -------------------------------------------------------------------------

    private fun parseXml(bytes: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun findEntryBytes(entries: Map<String, ByteArray>, path: String): ByteArray? {
        return entries[path] ?: entries.entries.firstOrNull { it.key.equals(path, ignoreCase = true) }?.value
    }

    private fun findEntryKey(entries: Map<String, ByteArray>, path: String): String? {
        if (entries.containsKey(path)) return path
        return entries.keys.firstOrNull { it.equals(path, ignoreCase = true) }
    }

    private fun resolvePath(baseDir: String, relative: String): String {
        val normalizedRel = relative.replace('\\', '/').substringBefore('#')
        val combined = when {
            normalizedRel.startsWith("/") -> normalizedRel.trimStart('/')
            baseDir.isEmpty() -> normalizedRel
            else -> "$baseDir/$normalizedRel"
        }
        val stack = ArrayDeque<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(segment)
            }
        }
        return stack.joinToString("/")
    }

    private fun isAlreadyBulleted(text: String): Boolean {
        return text.startsWith("•") || text.startsWith("- ") || text.startsWith("* ")
    }

    private fun Element.localOrTagName(): String {
        return localName ?: tagName.substringAfter(':')
    }

    private fun Element.attrByLocalName(name: String): String? {
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            val attrLocal = attr.localName ?: attr.nodeName.substringAfter(':')
            if (attrLocal.equals(name, ignoreCase = true)) {
                return attr.nodeValue
            }
        }
        return null
    }

    private fun childElements(parent: Element, localNameFilter: String? = null): List<Element> {
        val out = ArrayList<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            if (localNameFilter == null || el.localOrTagName().equals(localNameFilter, ignoreCase = true)) {
                out.add(el)
            }
        }
        return out
    }

    private fun firstChildByLocalName(parent: Element, localName: String): Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            if (el.localOrTagName().equals(localName, ignoreCase = true)) {
                return el
            }
        }
        return null
    }

    private fun findFirstElementByLocalName(root: Element, localName: String): Element? {
        if (root.localOrTagName().equals(localName, ignoreCase = true)) return root
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val found = findFirstElementByLocalName(node as Element, localName)
            if (found != null) return found
        }
        return null
    }

    private fun elementsByLocalName(root: Element, localName: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(el: Element) {
            if (el.localOrTagName().equals(localName, ignoreCase = true)) {
                out.add(el)
            }
            val children = el.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeType == Node.ELEMENT_NODE) {
                    walk(node as Element)
                }
            }
        }
        walk(root)
        return out
    }
}
