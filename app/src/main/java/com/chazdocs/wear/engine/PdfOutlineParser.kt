package com.chazdocs.wear.engine

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.Inflater

interface PdfByteSource : Closeable {
    val length: Long
    fun readBytes(offset: Long, size: Int): ByteArray
}

class FilePdfByteSource(file: File) : PdfByteSource {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    override fun readBytes(offset: Long, size: Int): ByteArray {
        if (offset < 0 || offset >= length || size <= 0) return ByteArray(0)
        val actual = minOf(size.toLong(), length - offset).toInt()
        val buf = ByteArray(actual)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    override fun close() {
        raf.close()
    }
}

class InMemoryPdfByteSource(private val data: ByteArray) : PdfByteSource {
    override val length: Long = data.size.toLong()

    override fun readBytes(offset: Long, size: Int): ByteArray {
        if (offset < 0 || offset >= length || size <= 0) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(data.size, start + size)
        return data.copyOfRange(start, end)
    }

    override fun close() = Unit
}

internal data class ObjRef(val objNum: Int, val genNum: Int)

/**
 * Shared low-level PDF xref, indirect object, stream, and page-tree reader.
 * Supports both classic xref tables and PDF 1.5+ /XRef + /ObjStm streams.
 */
internal class PdfLowLevelReader(private val source: PdfByteSource) {

    private sealed interface XrefLoc {
        data class Uncompressed(val offset: Long) : XrefLoc
        data class Compressed(val objStmNum: Int, val indexInStream: Int) : XrefLoc
    }

    private val xref = HashMap<Int, XrefLoc>()
    private val objStreamCache = HashMap<Int, Map<Int, String>>()

    var rootRef: ObjRef? = null
        private set

    fun parseXrefChain(): Boolean {
        val tailSize = minOf(4096L, source.length).toInt()
        val tail = String(
            source.readBytes(source.length - tailSize, tailSize),
            StandardCharsets.ISO_8859_1,
        )
        val startXrefIdx = tail.lastIndexOf("startxref")
        if (startXrefIdx < 0) return false
        val after = tail.substring(startXrefIdx + 9).trimStart()
        val offsetStr = after.takeWhile { it.isDigit() }
        val startOffset = offsetStr.toLongOrNull() ?: return false

        val visitedOffsets = HashSet<Long>()
        var nextOffset: Long? = startOffset
        var steps = 0
        while (nextOffset != null && steps < 16 && visitedOffsets.add(nextOffset)) {
            steps++
            nextOffset = parseXrefAt(nextOffset)
        }
        return xref.isNotEmpty() && rootRef != null
    }

    fun readObjectBody(objNum: Int): String? {
        return when (val loc = xref[objNum]) {
            is XrefLoc.Uncompressed -> {
                val readLen = minOf(32768L, source.length - loc.offset).toInt()
                if (readLen <= 0) return null
                val text = String(source.readBytes(loc.offset, readLen), StandardCharsets.ISO_8859_1)
                val endObj = text.indexOf("endobj")
                val objHeader = text.indexOf("obj")
                if (objHeader >= 0) {
                    val end = if (endObj > objHeader) endObj else text.length
                    text.substring(objHeader + 3, end)
                } else {
                    text
                }
            }
            is XrefLoc.Compressed -> {
                val map = objStreamCache.getOrPut(loc.objStmNum) {
                    decodeObjectStream(loc.objStmNum)
                }
                map[objNum]
            }
            null -> null
        }
    }

    fun readObjectRawStream(objNum: Int): ByteArray? {
        val loc = xref[objNum] as? XrefLoc.Uncompressed ?: return null
        return readStreamWithDictAtOffset(loc.offset)?.second
    }

    fun getPageObjectNumbers(): List<Int> {
        val catalogObj = rootRef?.objNum ?: return emptyList()
        val catalogBody = readObjectBody(catalogObj) ?: return emptyList()
        val pagesRef = extractRef(catalogBody, "/Pages") ?: return emptyList()
        val out = ArrayList<Int>()
        collectPages(pagesRef.objNum, out, HashSet())
        return out
    }

    fun extractRef(dict: String, key: String): ObjRef? {
        val regex = Regex("${Regex.escape(key)}\\s+(\\d+)\\s+(\\d+)\\s+R")
        val m = regex.find(dict) ?: return null
        val obj = m.groupValues[1].toIntOrNull() ?: return null
        val gen = m.groupValues[2].toIntOrNull() ?: 0
        return ObjRef(obj, gen)
    }

    fun extractRefArray(dict: String, key: String): List<ObjRef> {
        val startMatch = Regex("${Regex.escape(key)}\\s*\\[").find(dict) ?: return emptyList()
        val startIdx = startMatch.range.last + 1
        val endIdx = dict.indexOf(']', startIdx)
        if (endIdx < 0) return emptyList()
        val slice = dict.substring(startIdx, endIdx)
        return Regex("(\\d+)\\s+(\\d+)\\s+R").findAll(slice).mapNotNull { m ->
            val obj = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val gen = m.groupValues[2].toIntOrNull() ?: 0
            ObjRef(obj, gen)
        }.toList()
    }

    internal fun extractFirstDictionary(text: String): String? {
        val bounds = findFirstDictionaryBounds(text) ?: return null
        return text.substring(bounds.first, bounds.last + 1)
    }

    private fun parseXrefAt(offset: Long): Long? {
        val probe = String(source.readBytes(offset, 64), StandardCharsets.ISO_8859_1).trimStart()
        return if (probe.startsWith("xref")) {
            parseClassicXref(offset)
        } else {
            parseXrefStream(offset)
        }
    }

    private fun parseClassicXref(offset: Long): Long? {
        val chunkSize = minOf(512 * 1024L, source.length - offset).toInt()
        if (chunkSize <= 0) return null
        val text = String(source.readBytes(offset, chunkSize), StandardCharsets.ISO_8859_1)
        val trailerIdx = text.indexOf("trailer")
        if (trailerIdx < 0) return null

        val xrefSection = text.substring(0, trailerIdx)
        val lines = xrefSection.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var i = 0
        if (i < lines.size && lines[i].startsWith("xref")) i++

        while (i < lines.size) {
            val parts = lines[i].split(Regex("\\s+"))
            if (parts.size == 2 && parts[0].all { it.isDigit() } && parts[1].all { it.isDigit() }) {
                val startObj = parts[0].toInt()
                val count = parts[1].toInt()
                i++
                for (k in 0 until count) {
                    if (i >= lines.size) break
                    val entry = lines[i].split(Regex("\\s+"))
                    if (entry.size >= 3 && entry[2] == "n") {
                        val objOffset = entry[0].toLongOrNull()
                        val objNum = startObj + k
                        if (objOffset != null && !xref.containsKey(objNum)) {
                            xref[objNum] = XrefLoc.Uncompressed(objOffset)
                        }
                    }
                    i++
                }
            } else {
                i++
            }
        }

        val trailerText = text.substring(trailerIdx)
        val dict = extractFirstDictionary(trailerText) ?: return null
        if (rootRef == null) rootRef = extractRef(dict, "/Root")
        return extractLong(dict, "/Prev")
    }

    private fun parseXrefStream(offset: Long): Long? {
        val (dict, streamData) = readStreamWithDictAtOffset(offset) ?: return null
        if (!dict.contains("/XRef")) return null
        if (rootRef == null) rootRef = extractRef(dict, "/Root")

        val w = extractIntArray(dict, "/W") ?: return null
        if (w.size < 3) return null
        val size = extractInt(dict, "/Size") ?: return null
        val indexArr = extractIntArray(dict, "/Index") ?: listOf(0, size)

        val predictor = extractInt(dict, "/Predictor") ?: 1
        val columns = extractInt(dict, "/Columns") ?: w.sum()
        val decoded = if (predictor >= 10) {
            unpredictPng(streamData, columns)
        } else {
            streamData
        }

        val rowBytes = w[0] + w[1] + w[2]
        if (rowBytes <= 0) return null

        var bytePos = 0
        var idx = 0
        while (idx + 1 < indexArr.size) {
            val startObj = indexArr[idx]
            val count = indexArr[idx + 1]
            for (k in 0 until count) {
                if (bytePos + rowBytes > decoded.size) break
                val type = if (w[0] == 0) 1 else readUIntBE(decoded, bytePos, w[0]).toInt()
                val f2 = readUIntBE(decoded, bytePos + w[0], w[1])
                val f3 = readUIntBE(decoded, bytePos + w[0] + w[1], w[2]).toInt()
                val objNum = startObj + k
                if (!xref.containsKey(objNum)) {
                    when (type) {
                        1 -> xref[objNum] = XrefLoc.Uncompressed(f2)
                        2 -> xref[objNum] = XrefLoc.Compressed(f2.toInt(), f3)
                    }
                }
                bytePos += rowBytes
            }
            idx += 2
        }

        return extractLong(dict, "/Prev")
    }

    private fun decodeObjectStream(objStmNum: Int): Map<Int, String> {
        val loc = xref[objStmNum] as? XrefLoc.Uncompressed ?: return emptyMap()
        val (dict, decoded) = readStreamWithDictAtOffset(loc.offset) ?: return emptyMap()
        val n = extractInt(dict, "/N") ?: return emptyMap()
        val first = extractInt(dict, "/First") ?: return emptyMap()
        if (first < 0 || first > decoded.size) return emptyMap()

        val header = String(decoded, 0, first, StandardCharsets.ISO_8859_1).trim()
        val tokens = header.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val result = HashMap<Int, String>(n)
        val pairs = minOf(n, tokens.size / 2)
        val offsets = ArrayList<Pair<Int, Int>>(pairs)
        for (i in 0 until pairs) {
            val id = tokens[i * 2].toIntOrNull() ?: continue
            val relOff = tokens[i * 2 + 1].toIntOrNull() ?: continue
            offsets.add(id to (first + relOff))
        }
        for (i in offsets.indices) {
            val (id, start) = offsets[i]
            val end = if (i + 1 < offsets.size) offsets[i + 1].second else decoded.size
            if (start in 0..end && end <= decoded.size) {
                result[id] = String(decoded, start, end - start, StandardCharsets.ISO_8859_1)
            }
        }
        return result
    }

    private fun collectPages(
        nodeObjNum: Int,
        out: MutableList<Int>,
        visited: MutableSet<Int>,
    ) {
        if (!visited.add(nodeObjNum)) return
        val body = readObjectBody(nodeObjNum) ?: return
        if (body.contains("/Kids")) {
            val kids = extractRefArray(body, "/Kids")
            for (kid in kids) {
                collectPages(kid.objNum, out, visited)
            }
        } else if (body.contains("/Type") && body.contains("/Page")) {
            out.add(nodeObjNum)
        } else if (!body.contains("/Count")) {
            // Leaf without explicit /Type /Page fallback
            out.add(nodeObjNum)
        }
    }

    private fun readStreamWithDictAtOffset(offset: Long): Pair<String, ByteArray>? {
        val headerSize = minOf(16384L, source.length - offset).toInt()
        if (headerSize <= 0) return null
        val headerRaw = source.readBytes(offset, headerSize)
        val headerText = String(headerRaw, StandardCharsets.ISO_8859_1)
        val dictBounds = findFirstDictionaryBounds(headerText) ?: return null
        val dict = headerText.substring(dictBounds.first, dictBounds.last + 1)

        val streamStart = findStreamDataStart(headerText, dictBounds.last + 1) ?: return null
        val streamLen = resolveStreamLength(dict)

        val slice = if (streamLen != null && streamLen >= 0) {
            val absStart = offset + streamStart
            val clampedLen = minOf(
                streamLen.toLong(),
                maxOf(0L, source.length - absStart),
                MAX_STREAM_BYTES,
            ).toInt()
            if (streamStart + clampedLen <= headerRaw.size) {
                headerRaw.copyOfRange(streamStart, streamStart + clampedLen)
            } else {
                source.readBytes(absStart, clampedLen)
            }
        } else {
            val absStart = offset + streamStart
            val maxRead = minOf(MAX_FALLBACK_STREAM_BYTES, maxOf(0L, source.length - absStart)).toInt()
            if (maxRead <= 0) return null
            val tailBytes = if (streamStart + maxRead <= headerRaw.size) {
                headerRaw.copyOfRange(streamStart, streamStart + maxRead)
            } else {
                source.readBytes(absStart, maxRead)
            }
            val tailText = String(tailBytes, StandardCharsets.ISO_8859_1)
            val endStream = tailText.indexOf("endstream")
            if (endStream < 0) return null
            var end = endStream
            if (end > 0 && tailBytes[end - 1] == '\n'.code.toByte()) end--
            if (end > 0 && tailBytes[end - 1] == '\r'.code.toByte()) end--
            tailBytes.copyOfRange(0, end)
        }

        val decoded = if (dict.contains("/FlateDecode")) {
            inflate(slice) ?: return null
        } else {
            slice
        }
        return dict to decoded
    }

    private fun resolveStreamLength(dict: String): Int? {
        val lenRef = extractRef(dict, "/Length")
        if (lenRef != null) {
            return readObjectBody(lenRef.objNum)?.trim()?.toIntOrNull()
        }
        return extractInt(dict, "/Length")
    }

    private fun findStreamDataStart(text: String, fromIndex: Int): Int? {
        var searchFrom = fromIndex
        while (searchFrom < text.length) {
            val idx = text.indexOf("stream", searchFrom)
            if (idx < 0) return null
            val prevOk = idx == 0 || text[idx - 1].isWhitespace() || text[idx - 1] == '>'
            val after = idx + 6
            if (prevOk && after < text.length && (text[after] == '\r' || text[after] == '\n')) {
                var dataStart = after
                if (text[dataStart] == '\r') dataStart++
                if (dataStart < text.length && text[dataStart] == '\n') dataStart++
                return dataStart
            }
            searchFrom = idx + 6
        }
        return null
    }

    private fun findFirstDictionaryBounds(text: String): IntRange? {
        val start = text.indexOf("<<")
        if (start < 0) return null
        var depth = 0
        var i = start
        while (i < text.length - 1) {
            if (text[i] == '<' && text[i + 1] == '<') {
                depth++
                i += 2
            } else if (text[i] == '>' && text[i + 1] == '>') {
                depth--
                i += 2
                if (depth == 0) return start until i
            } else {
                i++
            }
        }
        return null
    }

    private fun extractInt(dict: String, key: String): Int? {
        val regex = Regex("${Regex.escape(key)}\\s+(-?\\d+)(?!\\s+\\d+\\s+R)")
        return regex.find(dict)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractLong(dict: String, key: String): Long? {
        val regex = Regex("${Regex.escape(key)}\\s+(-?\\d+)(?!\\s+\\d+\\s+R)")
        return regex.find(dict)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun extractIntArray(dict: String, key: String): List<Int>? {
        val startMatch = Regex("${Regex.escape(key)}\\s*\\[").find(dict) ?: return null
        val startIdx = startMatch.range.last + 1
        val endIdx = dict.indexOf(']', startIdx)
        if (endIdx < 0) return null
        val slice = dict.substring(startIdx, endIdx).trim()
        if (slice.isEmpty()) return emptyList()
        return slice.split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
    }

    private fun inflate(compressed: ByteArray): ByteArray? {
        val inflater = Inflater()
        return try {
            inflater.setInput(compressed)
            val out = ByteArrayOutputStream(compressed.size * 2)
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun unpredictPng(data: ByteArray, columns: Int): ByteArray {
        val rowStride = columns + 1
        if (rowStride <= 1) return data
        val rows = data.size / rowStride
        val out = ByteArray(rows * columns)
        val prev = ByteArray(columns)
        for (r in 0 until rows) {
            val inBase = r * rowStride
            val outBase = r * columns
            val filter = data[inBase].toInt() and 0xFF
            for (c in 0 until columns) {
                val cur = data[inBase + 1 + c].toInt() and 0xFF
                val left = if (c > 0) (out[outBase + c - 1].toInt() and 0xFF) else 0
                val up = prev[c].toInt() and 0xFF
                val decodedByte = when (filter) {
                    0 -> cur
                    1 -> (cur + left) and 0xFF
                    2 -> (cur + up) and 0xFF
                    3 -> (cur + ((left + up) / 2)) and 0xFF
                    4 -> {
                        val upLeft = if (c > 0) (prev[c - 1].toInt() and 0xFF) else 0
                        (cur + paeth(left, up, upLeft)) and 0xFF
                    }
                    else -> cur
                }
                out[outBase + c] = decodedByte.toByte()
            }
            System.arraycopy(out, outBase, prev, 0, columns)
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun readUIntBE(data: ByteArray, offset: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) {
            v = (v shl 8) or (data[offset + i].toLong() and 0xFFL)
        }
        return v
    }

    private companion object {
        const val MAX_STREAM_BYTES = 16L * 1024 * 1024
        const val MAX_FALLBACK_STREAM_BYTES = 4L * 1024 * 1024
    }
}

/**
 * Lightweight pure-Kotlin parser for PDF /Outlines (bookmarks).
 * Supports both classic xref tables and PDF 1.5+ /XRef + /ObjStm streams.
 */
object PdfOutlineParser {

    fun extractOutlines(source: PdfByteSource): List<TocEntry> {
        return runCatching {
            val reader = PdfLowLevelReader(source)
            if (!reader.parseXrefChain()) return emptyList()
            val rootRef = reader.rootRef ?: return emptyList()
            val catalogBody = reader.readObjectBody(rootRef.objNum) ?: return emptyList()
            val outlinesRef = reader.extractRef(catalogBody, "/Outlines") ?: return emptyList()

            val pageList = reader.getPageObjectNumbers()
            if (pageList.isEmpty()) return emptyList()
            val pageObjToIndex = LinkedHashMap<Int, Int>(pageList.size)
            for ((idx, objNum) in pageList.withIndex()) {
                pageObjToIndex[objNum] = idx
            }

            val namedDests = collectNamedDests(reader, catalogBody)
            val outlineRoot = reader.readObjectBody(outlinesRef.objNum) ?: return emptyList()
            val firstRef = reader.extractRef(outlineRoot, "/First") ?: return emptyList()

            val result = ArrayList<TocEntry>()
            val visited = HashSet<Int>()
            walkOutlines(
                reader = reader,
                firstObjNum = firstRef.objNum,
                level = 0,
                pageMap = pageObjToIndex,
                namedDests = namedDests,
                visited = visited,
                out = result,
            )
            result
        }.getOrElse { emptyList() }
    }

    private fun collectNamedDests(reader: PdfLowLevelReader, catalogBody: String): Map<String, Int> {
        val destsRef = reader.extractRef(catalogBody, "/Dests") ?: return emptyMap()
        val body = reader.readObjectBody(destsRef.objNum) ?: return emptyMap()
        val regex = Regex("/([A-Za-z0-9_.-]+)\\s*\\[\\s*(\\d+)\\s+\\d+\\s+R")
        val map = HashMap<String, Int>()
        for (m in regex.findAll(body)) {
            val name = m.groupValues[1]
            val obj = m.groupValues[2].toIntOrNull() ?: continue
            map[name] = obj
        }
        return map
    }

    private fun walkOutlines(
        reader: PdfLowLevelReader,
        firstObjNum: Int,
        level: Int,
        pageMap: Map<Int, Int>,
        namedDests: Map<String, Int>,
        visited: MutableSet<Int>,
        out: MutableList<TocEntry>,
    ) {
        var currentObjNum: Int? = firstObjNum
        while (currentObjNum != null && out.size < 400 && level <= 6) {
            if (!visited.add(currentObjNum)) break
            val body = reader.readObjectBody(currentObjNum) ?: break

            val title = extractPdfString(body, "/Title")?.trim().orEmpty()
            if (title.isNotEmpty()) {
                val targetPageObj = resolveTargetPageObj(reader, body, namedDests)
                val pageIdx = targetPageObj?.let { pageMap[it] } ?: 0
                out.add(
                    TocEntry(
                        title = title,
                        level = level,
                        targetPage = pageIdx,
                    )
                )
            }

            val childFirst = reader.extractRef(body, "/First")
            if (childFirst != null) {
                walkOutlines(reader, childFirst.objNum, level + 1, pageMap, namedDests, visited, out)
            }

            currentObjNum = reader.extractRef(body, "/Next")?.objNum
        }
    }

    private fun resolveTargetPageObj(
        reader: PdfLowLevelReader,
        body: String,
        namedDests: Map<String, Int>,
    ): Int? {
        val destMatch = Regex("/Dest\\s*\\[\\s*(\\d+)\\s+\\d+\\s+R").find(body)
        if (destMatch != null) return destMatch.groupValues[1].toIntOrNull()

        val actionInlineRef = Regex("/A\\s*<<[^>]*?/D\\s*\\[\\s*(\\d+)\\s+\\d+\\s+R", RegexOption.DOT_MATCHES_ALL)
            .find(body)
        if (actionInlineRef != null) return actionInlineRef.groupValues[1].toIntOrNull()

        val actionRef = reader.extractRef(body, "/A")
        if (actionRef != null) {
            val actionBody = reader.readObjectBody(actionRef.objNum)
            if (actionBody != null) {
                val dMatch = Regex("/D\\s*\\[\\s*(\\d+)\\s+\\d+\\s+R").find(actionBody)
                if (dMatch != null) return dMatch.groupValues[1].toIntOrNull()
                val dStr = extractPdfString(actionBody, "/D")
                if (dStr != null && namedDests.containsKey(dStr)) {
                    return namedDests[dStr]
                }
            }
        }

        val destStr = extractPdfString(body, "/Dest")
        if (destStr != null && namedDests.containsKey(destStr)) {
            return namedDests[destStr]
        }
        return null
    }

    private fun extractPdfString(body: String, key: String): String? {
        val keyIdx = body.indexOf(key)
        if (keyIdx < 0) return null
        var i = keyIdx + key.length
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length) return null

        return when (body[i]) {
            '(' -> decodeLiteralPdfString(body, i)
            '<' -> {
                if (i + 1 < body.length && body[i + 1] == '<') return null
                val close = body.indexOf('>', i + 1)
                if (close < 0) null else decodeHexPdfString(body.substring(i + 1, close))
            }
            else -> null
        }
    }

    private fun decodeLiteralPdfString(text: String, openParenIndex: Int): String {
        val bytes = ByteArrayOutputStream()
        var depth = 1
        var i = openParenIndex + 1
        while (i < text.length && depth > 0) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                i++
                when (val esc = text[i]) {
                    'n' -> bytes.write('\n'.code)
                    'r' -> bytes.write('\r'.code)
                    't' -> bytes.write('\t'.code)
                    'b' -> bytes.write('\b'.code)
                    'f' -> bytes.write(0x0C)
                    '(', ')', '\\' -> bytes.write(esc.code)
                    in '0'..'7' -> {
                        var oct = esc - '0'
                        var count = 1
                        while (count < 3 && i + 1 < text.length && text[i + 1] in '0'..'7') {
                            i++
                            oct = oct * 8 + (text[i] - '0')
                            count++
                        }
                        bytes.write(oct and 0xFF)
                    }
                    '\r', '\n' -> { /* line continuation */ }
                    else -> bytes.write(esc.code)
                }
            } else if (ch == '(') {
                depth++
                bytes.write(ch.code)
            } else if (ch == ')') {
                depth--
                if (depth > 0) bytes.write(ch.code)
            } else {
                bytes.write(ch.code and 0xFF)
            }
            i++
        }
        return decodePdfTextBytes(bytes.toByteArray())
    }

    private fun decodeHexPdfString(hex: String): String {
        val clean = hex.filter { !it.isWhitespace() }
        val padded = if (clean.length % 2 == 1) clean + "0" else clean
        val bytes = ByteArray(padded.length / 2)
        for (i in bytes.indices) {
            bytes[i] = padded.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return decodePdfTextBytes(bytes)
    }

    private fun decodePdfTextBytes(raw: ByteArray): String {
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        }
        return String(raw, StandardCharsets.ISO_8859_1)
    }
}
