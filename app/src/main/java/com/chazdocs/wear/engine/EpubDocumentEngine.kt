package com.chazdocs.wear.engine

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedEpubBook(
    val title: String,
    val chapters: List<TextChapter>,
    val tocEntries: List<TocEntry>,
)

/**
 * Pure-Kotlin lightweight EPUB parser (ZipInputStream + standard DOM XML + fast HTML block extractor).
 * Keeps zero heavy external dependencies and is 100% JVM unit-testable.
 */
object EpubDocumentEngine {

    fun parseFromStream(inputStream: InputStream, fallbackTitle: String): ParsedEpubBook {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(inputStream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val normalized = entry.name.replace('\\', '/').trimStart('/')
                    // Guard against giant embedded media files in EPUBs (e.g. > 8 MB per entry)
                    if (isTextOrXmlAsset(normalized) || entry.size in 0..4_000_000L) {
                        entries[normalized] = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val containerBytes = entries["META-INF/container.xml"]
            ?: throw IllegalArgumentException("Invalid EPUB: missing META-INF/container.xml")
        val opfPath = parseContainerOpfPath(containerBytes)
            ?: entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: throw IllegalArgumentException("Invalid EPUB: missing OPF package")

        val opfBytes = entries[opfPath]
            ?: throw IllegalArgumentException("Invalid EPUB: OPF not found at $opfPath")
        val opfDir = opfPath.substringBeforeLast('/', "")

        val opfDoc = parseXml(opfBytes)
        val bookTitle = extractOpfTitle(opfDoc) ?: fallbackTitle

        val manifestById = HashMap<String, ManifestItem>()
        val itemNodes = opfDoc.getElementsByTagNameNS("*", "item")
        val fallbackItemNodes = if (itemNodes.length == 0) opfDoc.getElementsByTagName("item") else itemNodes
        for (i in 0 until fallbackItemNodes.length) {
            val el = fallbackItemNodes.item(i) as? Element ?: continue
            val id = el.getAttribute("id")
            val href = el.getAttribute("href")
            val mediaType = el.getAttribute("media-type")
            val properties = el.getAttribute("properties")
            if (id.isNotBlank() && href.isNotBlank()) {
                val resolved = resolvePath(opfDir, href)
                manifestById[id] = ManifestItem(id, resolved, mediaType, properties)
            }
        }

        val spinePaths = ArrayList<String>()
        val itemRefNodes = opfDoc.getElementsByTagNameNS("*", "itemref")
        val fallbackRefNodes = if (itemRefNodes.length == 0) opfDoc.getElementsByTagName("itemref") else itemRefNodes
        for (i in 0 until fallbackRefNodes.length) {
            val el = fallbackRefNodes.item(i) as? Element ?: continue
            val idRef = el.getAttribute("idref")
            val item = manifestById[idRef] ?: continue
            spinePaths.add(item.fullPath)
        }

        val spineIndexByPath = HashMap<String, Int>()
        val chapters = ArrayList<TextChapter>(spinePaths.size)
        for ((index, path) in spinePaths.withIndex()) {
            spineIndexByPath[path] = index
            val htmlBytes = entries[path] ?: entries.entries.firstOrNull {
                it.key.equals(path, ignoreCase = true)
            }?.value
            val html = htmlBytes?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
            val extractedTitle = extractHtmlTitle(html) ?: "Section ${index + 1}"
            val blocks = htmlToBlocks(html)
            chapters.add(
                TextChapter(
                    title = extractedTitle,
                    blocks = if (blocks.isNotEmpty()) blocks else listOf(ReflowBlock("(Empty section)", BlockStyle.BODY)),
                )
            )
        }

        // Parse EPUB3 nav.xhtml first, then NCX fallback
        val navItem = manifestById.values.firstOrNull {
            it.properties.split(Regex("\\s+")).contains("nav")
        }
        val ncxItem = manifestById.values.firstOrNull {
            it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) ||
                it.fullPath.endsWith(".ncx", ignoreCase = true)
        }

        var tocEntries = if (navItem != null && entries[navItem.fullPath] != null) {
            parseNavToc(entries[navItem.fullPath]!!, navItem.fullPath, spineIndexByPath)
        } else {
            emptyList()
        }

        if (tocEntries.isEmpty() && ncxItem != null && entries[ncxItem.fullPath] != null) {
            tocEntries = parseNcxToc(entries[ncxItem.fullPath]!!, ncxItem.fullPath, spineIndexByPath)
        }

        if (tocEntries.isEmpty()) {
            tocEntries = chapters.mapIndexed { idx, ch ->
                TocEntry(title = ch.title, level = 0, targetChapter = idx)
            }
        }

        return ParsedEpubBook(
            title = bookTitle,
            chapters = chapters,
            tocEntries = tocEntries,
        )
    }

    private data class ManifestItem(
        val id: String,
        val fullPath: String,
        val mediaType: String,
        val properties: String,
    )

    private fun isTextOrXmlAsset(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".xml") ||
            lower.endsWith(".opf") ||
            lower.endsWith(".ncx") ||
            lower.endsWith(".xhtml") ||
            lower.endsWith(".html") ||
            lower.endsWith(".htm")
    }

    private fun parseContainerOpfPath(bytes: ByteArray): String? {
        val doc = parseXml(bytes)
        val rootfiles = doc.getElementsByTagNameNS("*", "rootfile")
        val nodes = if (rootfiles.length == 0) doc.getElementsByTagName("rootfile") else rootfiles
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            val path = el.getAttribute("full-path")
            if (path.isNotBlank()) return path.trimStart('/')
        }
        return null
    }

    private fun extractOpfTitle(doc: org.w3c.dom.Document): String? {
        val titles = doc.getElementsByTagNameNS("*", "title")
        val nodes = if (titles.length == 0) doc.getElementsByTagName("dc:title") else titles
        if (nodes.length > 0) {
            val text = nodes.item(0)?.textContent?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun parseNavToc(
        bytes: ByteArray,
        navPath: String,
        spineIndexByPath: Map<String, Int>,
    ): List<TocEntry> {
        return runCatching {
            val navDir = navPath.substringBeforeLast('/', "")
            val doc = parseXml(bytes)
            val navNodes = doc.getElementsByTagNameNS("*", "nav")
            val nodes = if (navNodes.length == 0) doc.getElementsByTagName("nav") else navNodes
            var tocNav: Element? = null
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val type = el.getAttribute("epub:type").ifBlank {
                    el.getAttributeNS("http://www.idpf.org/2007/ops", "type")
                }
                if (type.contains("toc", ignoreCase = true) || tocNav == null) {
                    tocNav = el
                    if (type.contains("toc", ignoreCase = true)) break
                }
            }
            val rootNav = tocNav ?: return emptyList()
            val out = ArrayList<TocEntry>()
            for (i in 0 until rootNav.childNodes.length) {
                val child = rootNav.childNodes.item(i)
                if (child is Element && child.localName.equals("ol", ignoreCase = true)) {
                    walkNavOl(child, 0, navDir, spineIndexByPath, out)
                }
            }
            out
        }.getOrElse { emptyList() }
    }

    private fun walkNavOl(
        ol: Element,
        level: Int,
        navDir: String,
        spineIndexByPath: Map<String, Int>,
        out: MutableList<TocEntry>,
    ) {
        if (level > 5 || out.size > 500) return
        for (i in 0 until ol.childNodes.length) {
            val li = ol.childNodes.item(i) as? Element ?: continue
            if (!li.localName.equals("li", ignoreCase = true)) continue
            var label: String? = null
            var href: String? = null
            var nestedOl: Element? = null
            for (j in 0 until li.childNodes.length) {
                val child = li.childNodes.item(j) as? Element ?: continue
                when (child.localName.lowercase()) {
                    "a", "span" -> {
                        if (label == null) label = child.textContent?.trim()
                        if (child.localName.equals("a", ignoreCase = true) && href == null) {
                            href = child.getAttribute("href")
                        }
                    }
                    "ol" -> nestedOl = child
                }
            }
            if (!label.isNullOrBlank()) {
                val targetChapter = resolveTargetChapter(navDir, href, spineIndexByPath)
                out.add(TocEntry(title = label, level = level, targetChapter = targetChapter))
            }
            if (nestedOl != null) {
                walkNavOl(nestedOl, level + 1, navDir, spineIndexByPath, out)
            }
        }
    }

    private fun parseNcxToc(
        bytes: ByteArray,
        ncxPath: String,
        spineIndexByPath: Map<String, Int>,
    ): List<TocEntry> {
        return runCatching {
            val ncxDir = ncxPath.substringBeforeLast('/', "")
            val doc = parseXml(bytes)
            val navMaps = doc.getElementsByTagNameNS("*", "navMap")
            val root = if (navMaps.length > 0) navMaps.item(0) as? Element else doc.getElementsByTagName("navMap").item(0) as? Element
            root ?: return emptyList()
            val out = ArrayList<TocEntry>()
            walkNcxNavPoints(root, 0, ncxDir, spineIndexByPath, out)
            out
        }.getOrElse { emptyList() }
    }

    private fun walkNcxNavPoints(
        parent: Element,
        level: Int,
        ncxDir: String,
        spineIndexByPath: Map<String, Int>,
        out: MutableList<TocEntry>,
    ) {
        if (level > 5 || out.size > 500) return
        for (i in 0 until parent.childNodes.length) {
            val node = parent.childNodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            if (!el.localName.equals("navPoint", ignoreCase = true) && !el.tagName.equals("navPoint", ignoreCase = true)) {
                continue
            }
            var label: String? = null
            var src: String? = null
            for (j in 0 until el.childNodes.length) {
                val child = el.childNodes.item(j) as? Element ?: continue
                val name = child.localName ?: child.tagName
                if (name.equals("navLabel", ignoreCase = true)) {
                    label = child.textContent?.trim()
                } else if (name.equals("content", ignoreCase = true)) {
                    src = child.getAttribute("src")
                }
            }
            if (!label.isNullOrBlank()) {
                val targetChapter = resolveTargetChapter(ncxDir, src, spineIndexByPath)
                out.add(TocEntry(title = label, level = level, targetChapter = targetChapter))
            }
            walkNcxNavPoints(el, level + 1, ncxDir, spineIndexByPath, out)
        }
    }

    private fun resolveTargetChapter(
        baseDir: String,
        rawHref: String?,
        spineIndexByPath: Map<String, Int>,
    ): Int {
        if (rawHref.isNullOrBlank()) return 0
        val cleanHref = rawHref.substringBefore('#').trim()
        if (cleanHref.isEmpty()) return 0
        val resolved = resolvePath(baseDir, cleanHref)
        return spineIndexByPath[resolved]
            ?: spineIndexByPath.entries.firstOrNull { it.key.equals(resolved, ignoreCase = true) }?.value
            ?: 0
    }

    private fun resolvePath(baseDir: String, relative: String): String {
        val clean = relative.substringBefore('#').trimStart('/')
        val combined = if (baseDir.isEmpty()) clean else "$baseDir/$clean"
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

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun extractHtmlTitle(html: String): String? {
        val heading = Regex("<h[1-3][^>]*>(.*?)</h[1-3]>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)
        val cleanedHeading = heading?.let { stripTagsAndDecodeEntities(it).trim() }
        if (!cleanedHeading.isNullOrBlank()) return cleanedHeading.take(80)

        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { stripTagsAndDecodeEntities(it).trim() }
        return title?.takeIf { it.isNotBlank() }?.take(80)
    }

    fun htmlToBlocks(rawHtml: String): List<ReflowBlock> {
        if (rawHtml.isBlank()) return emptyList()
        val s = rawHtml
            .replace(Regex("(?is)<head[^>]*>.*?</head>"), "")
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")

        val blocks = ArrayList<ReflowBlock>()
        // Split around common block tags while preserving tag identity
        val blockPattern = Regex(
            "(?is)<(h1|h2|h3|h4|blockquote|pre|li|p|div)[^>]*>(.*?)</\\1>|<hr\\s*/?>"
        )
        var lastEnd = 0
        for (match in blockPattern.findAll(s)) {
            val between = s.substring(lastEnd, match.range.first)
            val plainBetween = stripTagsAndDecodeEntities(between).trim()
            if (plainBetween.isNotEmpty() && !match.value.contains("<p", ignoreCase = true)) {
                blocks.add(ReflowBlock(plainBetween, BlockStyle.BODY))
            }

            if (match.value.startsWith("<hr", ignoreCase = true)) {
                blocks.add(ReflowBlock("———", BlockStyle.DIVIDER))
            } else {
                val tag = match.groupValues[1].lowercase()
                val inner = match.groupValues[2]
                // If div contains nested blocks, recurse once cleanly
                if (tag == "div" && Regex("(?i)<(p|h1|h2|h3|blockquote|pre|li)\\b").containsMatchIn(inner)) {
                    blocks.addAll(htmlToBlocks(inner))
                } else {
                    val text = stripTagsAndDecodeEntities(inner).trim()
                    if (text.isNotEmpty()) {
                        val style = when (tag) {
                            "h1" -> BlockStyle.H1
                            "h2" -> BlockStyle.H2
                            "h3", "h4" -> BlockStyle.H3
                            "blockquote" -> BlockStyle.QUOTE
                            "pre" -> BlockStyle.CODE
                            else -> BlockStyle.BODY
                        }
                        val formatted = if (tag == "li") "• $text" else text
                        blocks.add(ReflowBlock(formatted, style))
                    }
                }
            }
            lastEnd = match.range.last + 1
        }

        if (blocks.isEmpty()) {
            val fallback = stripTagsAndDecodeEntities(s)
                .split(Regex("\\n{2,}"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            for (p in fallback) {
                blocks.add(ReflowBlock(p, BlockStyle.BODY))
            }
        }
        return blocks
    }

    private fun stripTagsAndDecodeEntities(input: String): String {
        val withBreaks = input
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
        val decoded = decodeHtmlEntities(withBreaks)
        return decoded
            .lines()
            .joinToString("\n") { it.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").trim() }
            .replace(Regex("\\n{3,}"), "\n\n")
    }

    private fun decodeHtmlEntities(text: String): String {
        var out = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")

        out = Regex("&#x([0-9A-Fa-f]+);").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        out = Regex("&#(\\d+);").replace(out) { m ->
            val code = m.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return out
    }
}
