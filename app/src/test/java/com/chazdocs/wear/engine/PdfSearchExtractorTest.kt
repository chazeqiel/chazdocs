package com.chazdocs.wear.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.Deflater

class PdfSearchExtractorTest {

    @Test
    fun `extracts uncompressed, flate, TJ array, ToUnicode CMap, searches pages, and preserves outlines`() = runBlocking {
        val pdfBytes = buildSyntheticMultiPagePdf()
        val source = InMemoryPdfByteSource(pdfBytes)

        // 1. Verify PdfOutlineParser.extractOutlines still works after low-level reader refactor
        val outlines = PdfOutlineParser.extractOutlines(source)
        assertEquals(2, outlines.size)
        assertEquals(TocEntry(title = "Chapter 1: Hello Watch", level = 0, targetPage = 0), outlines[0])
        assertEquals(TocEntry(title = "Chapter 2: Wear OS", level = 0, targetPage = 1), outlines[1])

        // 2. Verify PdfLowLevelReader + PdfTextExtractor.extractPageText per page
        val reader = PdfLowLevelReader(source)
        assertTrue(reader.parseXrefChain())
        val pages = reader.getPageObjectNumbers()
        assertEquals(listOf(3, 4, 5), pages)

        val page0Text = PdfTextExtractor.extractPageText(reader, pages[0])
        assertEquals("Hello Watch First page intro", page0Text)

        val page1Text = PdfTextExtractor.extractPageText(reader, pages[1])
        assertEquals("Wear OS companion reader search target", page1Text)

        val page2Text = PdfTextExtractor.extractPageText(reader, pages[2])
        assertEquals("ChazDocs W", page2Text)

        // 3. Verify PdfTextExtractor.searchPdf finds exact page indices, snippets, and progress
        val progressCalls = mutableListOf<Pair<Int, Int>>()
        val wearHits = PdfTextExtractor.searchPdf(source, "wear os") { current, total ->
            progressCalls.add(current to total)
        }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progressCalls)
        assertEquals(1, wearHits.size)
        assertEquals(1, wearHits[0].targetPage)
        assertEquals("Page 2", wearHits[0].title)
        assertTrue(wearHits[0].snippet.contains("Wear OS"))

        val helloHits = PdfTextExtractor.searchPdf(source, "HELLO WATCH")
        assertEquals(1, helloHits.size)
        assertEquals(0, helloHits[0].targetPage)
        assertTrue(helloHits[0].snippet.contains("Hello Watch"))

        val cmapHits = PdfTextExtractor.searchPdf(source, "chazdocs")
        assertEquals(1, cmapHits.size)
        assertEquals(2, cmapHits[0].targetPage)
        assertTrue(cmapHits[0].snippet.contains("ChazDocs"))

        assertTrue(PdfTextExtractor.searchPdf(source, "   ").isEmpty())
    }

    @Test
    fun `searchPdf builds concise snippet around match and respects 80-hit cap`() = runBlocking {
        val longBody = "alpha ".repeat(20) + "TargetKeyword" + " omega".repeat(20)
        val pageBodies = List(90) { idx ->
            "BT /F1 12 Tf 20 250 Td (Page $idx $longBody) Tj ET"
        }
        val source = InMemoryPdfByteSource(buildManyPagePdf(pageBodies))

        val hits = PdfTextExtractor.searchPdf(source, "targetkeyword")
        assertEquals(80, hits.size)
        assertEquals(0, hits[0].targetPage)
        assertEquals(79, hits[79].targetPage)

        val snippet = hits[0].snippet
        assertTrue(snippet.contains("TargetKeyword"))
        assertTrue(snippet.length <= 105) // ~90 chars + optional "..." markers
    }

    @Test
    fun `searchPdf stops promptly when coroutine is cancelled`() = runBlocking {
        val pageBodies = List(20) { idx ->
            "BT 20 250 Td (Page $idx text) Tj ET"
        }
        val source = InMemoryPdfByteSource(buildManyPagePdf(pageBodies))

        var cancelled = false
        val job: Job = launch {
            try {
                PdfTextExtractor.searchPdf(source, "text") { current, _ ->
                    if (current == 2) {
                        this@launch.cancel()
                    }
                }
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        job.join()
        assertTrue(cancelled)
    }

    private fun buildSyntheticMultiPagePdf(): ByteArray {
        val page1Stream = "BT /F1 12 Tf 20 250 Td (Hello Watch) Tj 0 -18 Td (First page intro) ' ET"
        val page2StreamPartA = deflate("BT /F1 12 Tf 20 250 Td [(Wear ) -10 (OS)] TJ ET".toLatin1())
        val page2StreamPartB = deflate("BT 20 220 Td (companion reader search target) Tj ET".toLatin1())
        val page3Stream = deflate(
            "BT /F2 12 Tf 20 250 Td <0001000200030004> Tj <0010001100120013> Tj 0 -16 Td <0020> Tj ET".toLatin1()
        )
        val cmapStream = deflate(
            """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            1 begincodespacerange
            <0000> <FFFF>
            endcodespacerange
            4 beginbfchar
            <0001> <0043>
            <0002> <0068>
            <0003> <0061>
            <0004> <007A>
            endbfchar
            2 beginbfrange
            <0010> <0013> [<0044> <006F> <0063> <0073>]
            <0020> <0023> <0057>
            endbfrange
            endcmap
            """.trimIndent().toLatin1()
        )

        val objects = linkedMapOf(
            1 to "<< /Type /Catalog /Pages 2 0 R /Outlines 10 0 R >>".toLatin1(),
            2 to "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>".toLatin1(),
            3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents 6 0 R >>".toLatin1(),
            4 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Contents [7 0 R 8 0 R] >>".toLatin1(),
            5 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] /Resources << /Font << /F2 9 0 R >> >> /Contents 13 0 R >>".toLatin1(),
            6 to streamObj(page1Stream.toLatin1(), compressed = false),
            7 to streamObj(page2StreamPartA, compressed = true),
            8 to streamObj(page2StreamPartB, compressed = true),
            9 to "<< /Type /Font /Subtype /Type0 /BaseFont /TestFont /ToUnicode 14 0 R >>".toLatin1(),
            10 to "<< /Type /Outlines /First 11 0 R /Last 12 0 R /Count 2 >>".toLatin1(),
            11 to "<< /Title (Chapter 1: Hello Watch) /Parent 10 0 R /Next 12 0 R /Dest [3 0 R /XYZ 0 300 0] >>".toLatin1(),
            12 to "<< /Title (Chapter 2: Wear OS) /Parent 10 0 R /Prev 11 0 R /Dest [4 0 R /XYZ 0 300 0] >>".toLatin1(),
            13 to streamObj(page3Stream, compressed = true),
            14 to streamObj(cmapStream, compressed = true),
        )
        return assembleClassicPdf(objects, rootObjNum = 1)
    }

    private fun buildManyPagePdf(contentStreams: List<String>): ByteArray {
        val objects = linkedMapOf<Int, ByteArray>()
        val pageRefs = mutableListOf<String>()
        var nextObj = 3
        for (content in contentStreams) {
            val pageObj = nextObj++
            val streamObjNum = nextObj++
            pageRefs.add("$pageObj 0 R")
            objects[pageObj] = "<< /Type /Page /Parent 2 0 R /Contents $streamObjNum 0 R >>".toLatin1()
            objects[streamObjNum] = streamObj(content.toLatin1(), compressed = false)
        }
        objects[1] = "<< /Type /Catalog /Pages 2 0 R >>".toLatin1()
        objects[2] = "<< /Type /Pages /Kids [${pageRefs.joinToString(" ")}] /Count ${contentStreams.size} >>".toLatin1()
        return assembleClassicPdf(objects, rootObjNum = 1)
    }

    private fun assembleClassicPdf(objects: Map<Int, ByteArray>, rootObjNum: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toLatin1())

        val maxObjNum = objects.keys.maxOrNull() ?: 0
        val offsets = LongArray(maxObjNum + 1)

        for (objNum in objects.keys.sorted()) {
            offsets[objNum] = out.size().toLong()
            out.write("$objNum 0 obj\n".toLatin1())
            out.write(objects.getValue(objNum))
            out.write("\nendobj\n".toLatin1())
        }

        val xrefOffset = out.size().toLong()
        out.write("xref\n".toLatin1())
        out.write("0 ${maxObjNum + 1}\n".toLatin1())
        out.write("0000000000 65535 f \n".toLatin1())
        for (i in 1..maxObjNum) {
            val off = offsets[i]
            out.write(String.format("%010d 00000 n \n", off).toLatin1())
        }
        out.write("trailer\n".toLatin1())
        out.write("<< /Size ${maxObjNum + 1} /Root $rootObjNum 0 R >>\n".toLatin1())
        out.write("startxref\n".toLatin1())
        out.write("$xrefOffset\n".toLatin1())
        out.write("%%EOF\n".toLatin1())
        return out.toByteArray()
    }

    private fun streamObj(data: ByteArray, compressed: Boolean): ByteArray {
        val header = if (compressed) {
            "<< /Length ${data.size} /Filter /FlateDecode >>\nstream\n"
        } else {
            "<< /Length ${data.size} >>\nstream\n"
        }
        val out = ByteArrayOutputStream()
        out.write(header.toLatin1())
        out.write(data)
        out.write("\nendstream".toLatin1())
        return out.toByteArray()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            out.write(buf, 0, count)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun String.toLatin1(): ByteArray = toByteArray(StandardCharsets.ISO_8859_1)
}
