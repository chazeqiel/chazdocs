package com.chazdocs.wear.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class SearchHit(
    val title: String,
    val snippet: String,
    val targetPage: Int = 0,
    val targetChapter: Int = 0,
    val targetBlockIndex: Int = 0,
)

/**
 * Pure-Kotlin PDF page text extractor and cancellable full-text search engine.
 * Reuses [PdfLowLevelReader] for xref, indirect object, stream, and page-tree access.
 */
object PdfTextExtractor {

    private const val MAX_SEARCH_HITS = 80
    private const val SNIPPET_TARGET_LENGTH = 90
    private const val SNIPPET_CONTEXT_BEFORE = 35

    internal fun extractPageText(reader: PdfLowLevelReader, pageObjNum: Int): String {
        return extractPageText(reader, pageObjNum, HashMap())
    }

    suspend fun searchPdf(
        source: PdfByteSource,
        query: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<SearchHit> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) return emptyList()

        val reader = PdfLowLevelReader(source)
        if (!reader.parseXrefChain()) return emptyList()

        val pageObjects = reader.getPageObjectNumbers()
        val totalPages = pageObjects.size
        if (totalPages == 0) return emptyList()

        val cMapCache = HashMap<Int, ToUnicodeCMap?>()
        val hits = ArrayList<SearchHit>()

        for (pageIndex in 0 until totalPages) {
            currentCoroutineContext().ensureActive()
            val pageText = extractPageText(reader, pageObjects[pageIndex], cMapCache)
            onProgress(pageIndex + 1, totalPages)

            val matchIdx = pageText.indexOf(trimmedQuery, ignoreCase = true)
            if (matchIdx >= 0) {
                hits.add(
                    SearchHit(
                        title = "Page ${pageIndex + 1}",
                        snippet = buildSnippet(pageText, matchIdx, trimmedQuery.length),
                        targetPage = pageIndex,
                    )
                )
                if (hits.size >= MAX_SEARCH_HITS) break
            }
        }
        return hits
    }

    private fun extractPageText(
        reader: PdfLowLevelReader,
        pageObjNum: Int,
        cMapCache: MutableMap<Int, ToUnicodeCMap?>,
    ): String {
        val pageBody = reader.readObjectBody(pageObjNum) ?: return ""
        val contentRefs = resolveContentRefs(reader, pageBody)
        if (contentRefs.isEmpty()) return ""

        val fontCMaps = resolveFontCMaps(reader, pageBody, cMapCache)
        val combinedStream = StringBuilder()

        for (ref in contentRefs) {
            val raw = reader.readObjectRawStream(ref.objNum) ?: continue
            if (raw.isEmpty()) continue
            if (combinedStream.isNotEmpty()) combinedStream.append('\n')
            combinedStream.append(String(raw, StandardCharsets.ISO_8859_1))
        }
        if (combinedStream.isEmpty()) return ""

        val rawText = extractTextFromContentStream(combinedStream.toString(), fontCMaps)
        return normalizeWhitespace(rawText)
    }

    private fun resolveContentRefs(reader: PdfLowLevelReader, pageBody: String): List<ObjRef> {
        val arrayRefs = reader.extractRefArray(pageBody, "/Contents")
        if (arrayRefs.isNotEmpty()) return arrayRefs
        val singleRef = reader.extractRef(pageBody, "/Contents") ?: return emptyList()
        return listOf(singleRef)
    }

    private fun resolveFontCMaps(
        reader: PdfLowLevelReader,
        pageBody: String,
        cMapCache: MutableMap<Int, ToUnicodeCMap?>,
    ): Map<String, ToUnicodeCMap> {
        val resourcesBody = resolveResourcesDict(reader, pageBody) ?: return emptyMap()
        val fontDictBody = resolveSubDict(reader, resourcesBody, "/Font") ?: return emptyMap()

        val fontRefRegex = Regex("/([A-Za-z0-9_+.-]+)\\s+(\\d+)\\s+(\\d+)\\s+R")
        val result = HashMap<String, ToUnicodeCMap>()
        for (match in fontRefRegex.findAll(fontDictBody)) {
            val fontName = match.groupValues[1]
            val fontObjNum = match.groupValues[2].toIntOrNull() ?: continue
            val cmap = cMapCache.getOrPut(fontObjNum) {
                loadFontToUnicode(reader, fontObjNum)
            }
            if (cmap != null && cmap.isNotEmpty()) {
                result[fontName] = cmap
            }
        }
        return result
    }

    private fun resolveResourcesDict(reader: PdfLowLevelReader, pageBody: String): String? {
        var currentBody: String? = pageBody
        var depth = 0
        while (currentBody != null && depth < 8) {
            val sub = resolveSubDict(reader, currentBody, "/Resources")
            if (sub != null) return sub
            val parentRef = reader.extractRef(currentBody, "/Parent") ?: break
            currentBody = reader.readObjectBody(parentRef.objNum)
            depth++
        }
        return null
    }

    private fun resolveSubDict(reader: PdfLowLevelReader, body: String, key: String): String? {
        val ref = reader.extractRef(body, key)
        if (ref != null) {
            return reader.readObjectBody(ref.objNum)
        }
        val inlineMatch = Regex("${Regex.escape(key)}\\s*<<").find(body) ?: return null
        return reader.extractFirstDictionary(body.substring(inlineMatch.range.last - 1))
    }

    private fun loadFontToUnicode(reader: PdfLowLevelReader, fontObjNum: Int): ToUnicodeCMap? {
        val fontBody = reader.readObjectBody(fontObjNum) ?: return null
        val toUnicodeRef = reader.extractRef(fontBody, "/ToUnicode") ?: return null
        val cmapBytes = reader.readObjectRawStream(toUnicodeRef.objNum) ?: return null
        val cmapText = String(cmapBytes, StandardCharsets.ISO_8859_1)
        return parseToUnicodeCMap(cmapText)
    }

    private fun buildSnippet(text: String, matchIdx: Int, queryLen: Int): String {
        val minEnd = minOf(text.length, matchIdx + queryLen + 10)
        var start = maxOf(0, matchIdx - SNIPPET_CONTEXT_BEFORE)
        val end = minOf(text.length, maxOf(start + SNIPPET_TARGET_LENGTH, minEnd))
        if (end - start < SNIPPET_TARGET_LENGTH && end == text.length) {
            start = maxOf(0, end - SNIPPET_TARGET_LENGTH)
        }
        val core = text.substring(start, end).trim()
        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < text.length) "..." else ""
        return "$prefix$core$suffix"
    }

    private fun normalizeWhitespace(text: String): String {
        if (text.isBlank()) return ""
        val sb = StringBuilder(text.length)
        var lastWasSpace = true
        for (ch in text) {
            if (ch.isWhitespace() || ch == '\u0000') {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(ch)
                lastWasSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

    // ---- PDF Content Stream Tokenizer & Text Extractor ----

    private sealed interface StreamToken {
        data class LiteralBytes(val bytes: ByteArray) : StreamToken
        data class HexChars(val hex: String) : StreamToken
        data class ArrayItems(val items: List<StreamToken>) : StreamToken
        data class Name(val value: String) : StreamToken
        data class Number(val value: Double) : StreamToken
        data class Operator(val name: String) : StreamToken
    }

    private fun extractTextFromContentStream(
        content: String,
        fontCMaps: Map<String, ToUnicodeCMap>,
    ): String {
        val out = StringBuilder()
        val operands = ArrayList<StreamToken>(12)
        var inTextObject = false
        var currentFont: String? = null
        var prevTmX: Double? = null
        var prevTmY: Double? = null

        var i = 0
        while (i < content.length) {
            val ch = content[i]
            when {
                isPdfWhitespace(ch) -> i++
                ch == '%' -> {
                    while (i < content.length && content[i] != '\r' && content[i] != '\n') i++
                }
                ch == '(' -> {
                    val (bytes, nextIdx) = readLiteralStringBytes(content, i)
                    pushOperand(operands, StreamToken.LiteralBytes(bytes))
                    i = nextIdx
                }
                ch == '<' -> {
                    if (i + 1 < content.length && content[i + 1] == '<') {
                        i = skipDictionary(content, i)
                    } else {
                        val close = content.indexOf('>', i + 1)
                        if (close < 0) {
                            i = content.length
                        } else {
                            pushOperand(operands, StreamToken.HexChars(content.substring(i + 1, close)))
                            i = close + 1
                        }
                    }
                }
                ch == '[' -> {
                    val (items, nextIdx) = readArrayToken(content, i)
                    pushOperand(operands, StreamToken.ArrayItems(items))
                    i = nextIdx
                }
                ch == '/' -> {
                    val end = scanWordEnd(content, i + 1)
                    val name = content.substring(i + 1, end)
                    pushOperand(operands, StreamToken.Name(name))
                    i = end
                }
                ch == ']' || ch == '>' || ch == ')' || ch == '{' || ch == '}' -> i++
                else -> {
                    val end = scanWordEnd(content, i)
                    val word = content.substring(i, end)
                    i = end
                    val num = word.toDoubleOrNull()
                    if (num != null) {
                        pushOperand(operands, StreamToken.Number(num))
                    } else {
                        when (word) {
                            "BT" -> {
                                inTextObject = true
                                prevTmX = null
                                prevTmY = null
                                operands.clear()
                            }
                            "ET" -> {
                                inTextObject = false
                                appendSeparatorIfNeeded(out)
                                operands.clear()
                            }
                            "Tf" -> {
                                if (operands.size >= 2) {
                                    val fontTok = operands[operands.size - 2] as? StreamToken.Name
                                    if (fontTok != null) currentFont = fontTok.value
                                }
                                operands.clear()
                            }
                            else -> {
                                if (inTextObject) {
                                    val cmap = currentFont?.let { fontCMaps[it] }
                                    handleTextOperator(
                                        op = word,
                                        operands = operands,
                                        cmap = cmap,
                                        out = out,
                                        prevTmX = prevTmX,
                                        prevTmY = prevTmY,
                                        onTmUpdate = { x, y ->
                                            prevTmX = x
                                            prevTmY = y
                                        },
                                    )
                                }
                                operands.clear()
                            }
                        }
                    }
                }
            }
        }
        return out.toString()
    }

    private inline fun handleTextOperator(
        op: String,
        operands: List<StreamToken>,
        cmap: ToUnicodeCMap?,
        out: StringBuilder,
        prevTmX: Double?,
        prevTmY: Double?,
        onTmUpdate: (Double?, Double?) -> Unit,
    ) {
        when (op) {
            "Tj" -> {
                operands.lastOrNull()?.let { appendDecodedToken(it, cmap, out) }
            }
            "'", "\"" -> {
                appendSeparatorIfNeeded(out)
                operands.lastOrNull()?.let { appendDecodedToken(it, cmap, out) }
            }
            "TJ" -> {
                val arr = operands.lastOrNull() as? StreamToken.ArrayItems ?: return
                for (item in arr.items) {
                    when (item) {
                        is StreamToken.LiteralBytes, is StreamToken.HexChars -> {
                            appendDecodedToken(item, cmap, out)
                        }
                        is StreamToken.Number -> {
                            if (item.value <= -150.0) {
                                appendSeparatorIfNeeded(out)
                            }
                        }
                        else -> Unit
                    }
                }
            }
            "T*" -> appendSeparatorIfNeeded(out)
            "Td", "TD" -> {
                if (operands.size >= 2) {
                    val tx = (operands[operands.size - 2] as? StreamToken.Number)?.value ?: 0.0
                    val ty = (operands[operands.size - 1] as? StreamToken.Number)?.value ?: 0.0
                    if (kotlin.math.abs(ty) > 0.5 || kotlin.math.abs(tx) > 0.5) {
                        appendSeparatorIfNeeded(out)
                    }
                }
            }
            "Tm" -> {
                if (operands.size >= 6) {
                    val e = (operands[operands.size - 2] as? StreamToken.Number)?.value
                    val f = (operands[operands.size - 1] as? StreamToken.Number)?.value
                    if (prevTmY != null && f != null && kotlin.math.abs(f - prevTmY) > 0.5) {
                        appendSeparatorIfNeeded(out)
                    } else if (prevTmX != null && e != null && kotlin.math.abs(e - prevTmX) > 2.0) {
                        appendSeparatorIfNeeded(out)
                    }
                    onTmUpdate(e, f)
                }
            }
        }
    }

    private fun appendDecodedToken(token: StreamToken, cmap: ToUnicodeCMap?, out: StringBuilder) {
        when (token) {
            is StreamToken.LiteralBytes -> out.append(decodeLiteralBytes(token.bytes, cmap))
            is StreamToken.HexChars -> out.append(decodeHexChars(token.hex, cmap))
            else -> Unit
        }
    }

    private fun appendSeparatorIfNeeded(out: StringBuilder) {
        if (out.isNotEmpty() && !out[out.length - 1].isWhitespace()) {
            out.append(' ')
        }
    }

    private fun pushOperand(operands: MutableList<StreamToken>, token: StreamToken) {
        if (operands.size >= 16) operands.removeAt(0)
        operands.add(token)
    }

    private fun readArrayToken(content: String, openBracketIdx: Int): Pair<List<StreamToken>, Int> {
        val items = ArrayList<StreamToken>()
        var i = openBracketIdx + 1
        while (i < content.length) {
            val ch = content[i]
            when {
                isPdfWhitespace(ch) -> i++
                ch == '%' -> {
                    while (i < content.length && content[i] != '\r' && content[i] != '\n') i++
                }
                ch == ']' -> return items to (i + 1)
                ch == '(' -> {
                    val (bytes, nextIdx) = readLiteralStringBytes(content, i)
                    items.add(StreamToken.LiteralBytes(bytes))
                    i = nextIdx
                }
                ch == '<' -> {
                    if (i + 1 < content.length && content[i + 1] == '<') {
                        i = skipDictionary(content, i)
                    } else {
                        val close = content.indexOf('>', i + 1)
                        if (close < 0) return items to content.length
                        items.add(StreamToken.HexChars(content.substring(i + 1, close)))
                        i = close + 1
                    }
                }
                else -> {
                    val end = scanWordEnd(content, i)
                    val word = content.substring(i, end)
                    word.toDoubleOrNull()?.let { items.add(StreamToken.Number(it)) }
                    i = if (end > i) end else i + 1
                }
            }
        }
        return items to content.length
    }

    private fun skipDictionary(content: String, startIdx: Int): Int {
        var depth = 0
        var i = startIdx
        while (i < content.length - 1) {
            if (content[i] == '<' && content[i + 1] == '<') {
                depth++
                i += 2
            } else if (content[i] == '>' && content[i + 1] == '>') {
                depth--
                i += 2
                if (depth <= 0) return i
            } else if (content[i] == '(') {
                i = readLiteralStringBytes(content, i).second
            } else {
                i++
            }
        }
        return content.length
    }

    private fun readLiteralStringBytes(text: String, openParenIdx: Int): Pair<ByteArray, Int> {
        val out = ByteArrayOutputStream()
        var depth = 1
        var i = openParenIdx + 1
        while (i < text.length && depth > 0) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                i++
                when (val esc = text[i]) {
                    'n' -> out.write('\n'.code)
                    'r' -> out.write('\r'.code)
                    't' -> out.write('\t'.code)
                    'b' -> out.write('\b'.code)
                    'f' -> out.write(0x0C)
                    '(', ')', '\\' -> out.write(esc.code)
                    in '0'..'7' -> {
                        var oct = esc - '0'
                        var count = 1
                        while (count < 3 && i + 1 < text.length && text[i + 1] in '0'..'7') {
                            i++
                            oct = oct * 8 + (text[i] - '0')
                            count++
                        }
                        out.write(oct and 0xFF)
                    }
                    '\r' -> {
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                    }
                    '\n' -> Unit
                    else -> out.write(esc.code and 0xFF)
                }
            } else if (ch == '(') {
                depth++
                out.write(ch.code)
            } else if (ch == ')') {
                depth--
                if (depth > 0) out.write(ch.code)
            } else {
                out.write(ch.code and 0xFF)
            }
            i++
        }
        return out.toByteArray() to i
    }

    private fun scanWordEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (isPdfWhitespace(ch) || isPdfDelimiter(ch)) break
            i++
        }
        return i
    }

    private fun isPdfWhitespace(ch: Char): Boolean {
        return ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '\u0000' || ch == '\u000C'
    }

    private fun isPdfDelimiter(ch: Char): Boolean {
        return ch == '(' || ch == ')' || ch == '<' || ch == '>' ||
            ch == '[' || ch == ']' || ch == '{' || ch == '}' ||
            ch == '/' || ch == '%'
    }

    // ---- String & ToUnicode CMap Decoding ----

    private fun decodeLiteralBytes(raw: ByteArray, cmap: ToUnicodeCMap?): String {
        if (raw.isEmpty()) return ""
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        }
        if (cmap != null && cmap.isNotEmpty()) {
            val hex = bytesToUpperHex(raw)
            return cmap.decodeHex(hex)
        }
        return decodeWinAnsiBytes(raw)
    }

    private fun decodeHexChars(hex: String, cmap: ToUnicodeCMap?): String {
        val clean = buildString(hex.length) {
            for (ch in hex) {
                if (!ch.isWhitespace()) append(ch.uppercaseChar())
            }
        }
        if (clean.isEmpty()) return ""
        val padded = if (clean.length % 2 == 1) "${clean}0" else clean

        if (cmap != null && cmap.isNotEmpty()) {
            return cmap.decodeHex(padded)
        }

        val raw = hexToBytes(padded)
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        }
        return decodeWinAnsiBytes(raw)
    }

    private fun decodeWinAnsiBytes(raw: ByteArray): String {
        val sb = StringBuilder(raw.size)
        for (b in raw) {
            val code = b.toInt() and 0xFF
            val ch = when (code) {
                0x85 -> '…'
                0x91, 0x92 -> '\''
                0x93, 0x94 -> '"'
                0x95 -> '•'
                0x96 -> '–'
                0x97 -> '—'
                else -> code.toChar()
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    private class ToUnicodeCMap(
        private val mapping: Map<String, String>,
        private val hasTwoByteKeys: Boolean,
        private val hasOneByteKeys: Boolean,
    ) {
        fun isNotEmpty(): Boolean = mapping.isNotEmpty()

        fun decodeHex(upperEvenHex: String): String {
            val out = StringBuilder()
            val step = if (hasTwoByteKeys && upperEvenHex.length % 4 == 0) 4 else 2
            var i = 0
            while (i + step <= upperEvenHex.length) {
                val key = upperEvenHex.substring(i, i + step)
                val mapped = mapping[key]
                if (mapped != null) {
                    out.append(mapped)
                    i += step
                } else if (step == 4 && hasOneByteKeys) {
                    val k1 = upperEvenHex.substring(i, i + 2)
                    val k2 = upperEvenHex.substring(i + 2, i + 4)
                    out.append(mapping[k1] ?: fallbackCodePoint(k1.toIntOrNull(16) ?: 0))
                    out.append(mapping[k2] ?: fallbackCodePoint(k2.toIntOrNull(16) ?: 0))
                    i += 4
                } else {
                    val code = key.toIntOrNull(16) ?: 0
                    out.append(fallbackCodePoint(code))
                    i += step
                }
            }
            return out.toString()
        }

        private fun fallbackCodePoint(code: Int): String {
            return if (code in 0x20..0x7E || code in 0xA0..0xFF) code.toChar().toString() else ""
        }
    }

    private fun parseToUnicodeCMap(cmapText: String): ToUnicodeCMap {
        val map = HashMap<String, String>()
        var hasTwoByte = false
        var hasOneByte = false

        val bfCharBlockRegex = Regex("beginbfchar\\s*(.*?)\\s*endbfchar", RegexOption.DOT_MATCHES_ALL)
        val bfCharPairRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")

        for (block in bfCharBlockRegex.findAll(cmapText)) {
            for (pair in bfCharPairRegex.findAll(block.groupValues[1])) {
                val src = pair.groupValues[1].uppercase()
                val dst = decodeCMapDestHex(pair.groupValues[2])
                if (src.isNotEmpty() && dst.isNotEmpty()) {
                    map[src] = dst
                    if (src.length >= 4) hasTwoByte = true else hasOneByte = true
                }
            }
        }

        val bfRangeBlockRegex = Regex("beginbfrange\\s*(.*?)\\s*endbfrange", RegexOption.DOT_MATCHES_ALL)
        val bfRangeArrayRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\[([^\\]]*)\\]")
        val bfRangeScalarRegex = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
        val hexTokenRegex = Regex("<([0-9A-Fa-f]+)>")

        for (block in bfRangeBlockRegex.findAll(cmapText)) {
            val body = block.groupValues[1]

            for (arrMatch in bfRangeArrayRegex.findAll(body)) {
                val startHex = arrMatch.groupValues[1].uppercase()
                val endHex = arrMatch.groupValues[2].uppercase()
                val startCode = startHex.toIntOrNull(16) ?: continue
                val endCode = endHex.toIntOrNull(16) ?: continue
                if (endCode < startCode) continue
                val count = minOf(endCode - startCode + 1, 4096)
                val dests = hexTokenRegex.findAll(arrMatch.groupValues[3]).map { it.groupValues[1] }.toList()
                for (offset in 0 until minOf(count, dests.size)) {
                    val src = (startCode + offset).toString(16).uppercase().padStart(startHex.length, '0')
                    val dst = decodeCMapDestHex(dests[offset])
                    if (dst.isNotEmpty()) {
                        map[src] = dst
                        if (src.length >= 4) hasTwoByte = true else hasOneByte = true
                    }
                }
            }

            for (scalarMatch in bfRangeScalarRegex.findAll(body)) {
                val startHex = scalarMatch.groupValues[1].uppercase()
                val endHex = scalarMatch.groupValues[2].uppercase()
                val dstStartHex = scalarMatch.groupValues[3].uppercase()
                val startCode = startHex.toIntOrNull(16) ?: continue
                val endCode = endHex.toIntOrNull(16) ?: continue
                if (endCode < startCode) continue
                val count = minOf(endCode - startCode + 1, 4096)
                for (offset in 0 until count) {
                    val src = (startCode + offset).toString(16).uppercase().padStart(startHex.length, '0')
                    val dst = decodeIncrementedCMapDestHex(dstStartHex, offset)
                    if (dst.isNotEmpty()) {
                        map[src] = dst
                        if (src.length >= 4) hasTwoByte = true else hasOneByte = true
                    }
                }
            }
        }

        return ToUnicodeCMap(map, hasTwoByte, hasOneByte)
    }

    private fun decodeCMapDestHex(hex: String): String {
        val clean = hex.filter { !it.isWhitespace() }
        if (clean.isEmpty()) return ""
        val padded = if (clean.length % 2 == 1) "${clean}0" else clean
        val bytes = hexToBytes(padded)
        return if (bytes.size == 1) {
            (bytes[0].toInt() and 0xFF).toChar().toString()
        } else {
            String(bytes, Charsets.UTF_16BE)
        }
    }

    private fun decodeIncrementedCMapDestHex(dstStartHex: String, offset: Int): String {
        if (offset == 0) return decodeCMapDestHex(dstStartHex)
        val clean = dstStartHex.filter { !it.isWhitespace() }
        if (clean.length <= 4) {
            val base = clean.toIntOrNull(16) ?: return ""
            return ((base + offset) and 0xFFFF).toChar().toString()
        }
        val prefixHex = clean.substring(0, clean.length - 4)
        val tailHex = clean.substring(clean.length - 4)
        val tailBase = tailHex.toIntOrNull(16) ?: return ""
        val incremented = ((tailBase + offset) and 0xFFFF).toString(16).uppercase().padStart(4, '0')
        return decodeCMapDestHex(prefixHex + incremented)
    }

    private fun hexToBytes(evenHex: String): ByteArray {
        val bytes = ByteArray(evenHex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = evenHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun bytesToUpperHex(raw: ByteArray): String {
        val hexChars = "0123456789ABCDEF"
        val sb = StringBuilder(raw.size * 2)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }
}
