package com.chazdocs.wear.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentParsersTest {

    @Test
    fun `markdown parser extracts headings into TOC and strips inline syntax`() {
        val md = """
            # Main Title
            Welcome to **ChazDocs** on [Wear OS](https://example.com).
            
            ## Chapter One
            > A short quote
            
            - First point
            - Second point
            
            ```
            val x = 42
            ```
        """.trimIndent()

        val parsed = TextDocumentEngine.parseFromStream(
            inputStream = ByteArrayInputStream(md.toByteArray(StandardCharsets.UTF_8)),
            isMarkdown = true,
        )

        assertEquals(2, parsed.tocEntries.size)
        assertEquals("Main Title", parsed.tocEntries[0].title)
        assertEquals(0, parsed.tocEntries[0].level)
        assertEquals("Chapter One", parsed.tocEntries[1].title)
        assertEquals(1, parsed.tocEntries[1].level)

        val bodyBlock = parsed.blocks.first { it.style == BlockStyle.BODY }
        assertTrue(bodyBlock.text.contains("ChazDocs"))
        assertTrue(bodyBlock.text.contains("Wear OS"))
        assertTrue(!bodyBlock.text.contains("**"))
    }

    @Test
    fun `plain text parser detects chapters for TOC`() {
        val txt = """
            Chapter 1: Getting Started
            This is the opening paragraph.
            
            Chapter 2: Next Steps
            More text follows here.
        """.trimIndent()

        val parsed = TextDocumentEngine.parseFromStream(
            inputStream = ByteArrayInputStream(txt.toByteArray(StandardCharsets.UTF_8)),
            isMarkdown = false,
        )

        assertEquals(2, parsed.tocEntries.size)
        assertEquals("Chapter 1: Getting Started", parsed.tocEntries[0].title)
        assertEquals("Chapter 2: Next Steps", parsed.tocEntries[1].title)
        assertTrue(parsed.tocEntries[1].targetBlockIndex > parsed.tocEntries[0].targetBlockIndex)
    }

    @Test
    fun `epub parser reads OPF spine, NCX TOC, and HTML blocks`() {
        val epubBytes = buildMinimalEpubZip()
        val parsed = EpubDocumentEngine.parseFromStream(
            inputStream = ByteArrayInputStream(epubBytes),
            fallbackTitle = "Fallback",
        )

        assertEquals("Sample Watch Book", parsed.title)
        assertEquals(2, parsed.chapters.size)
        assertEquals(2, parsed.tocEntries.size)
        assertEquals("Intro", parsed.tocEntries[0].title)
        assertEquals(0, parsed.tocEntries[0].targetChapter)
        assertEquals("Deep Dive", parsed.tocEntries[1].title)
        assertEquals(1, parsed.tocEntries[1].targetChapter)
        assertTrue(parsed.chapters[1].blocks.any { it.text.contains("Hello Watch") })
    }

    @Test
    fun `pdf outline parser extracts classic xref bookmarks and page targets`() {
        val pdfBytes = buildMinimalPdfWithOutlines()
        val toc = InMemoryPdfByteSource(pdfBytes).use { PdfOutlineParser.extractOutlines(it) }

        assertEquals(2, toc.size)
        assertEquals("Cover", toc[0].title)
        assertEquals(0, toc[0].targetPage)
        assertEquals("Second Page", toc[1].title)
        assertEquals(1, toc[1].targetPage)
    }

    private fun buildMinimalEpubZip(): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            fun add(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
            }

            add(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent(),
            )
            add(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>Sample Watch Book</dc:title>
                  </metadata>
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx">
                    <itemref idref="c1"/>
                    <itemref idref="c2"/>
                  </spine>
                </package>
                """.trimIndent(),
            )
            add(
                "OEBPS/toc.ncx",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                  <navMap>
                    <navPoint id="n1" playOrder="1">
                      <navLabel><text>Intro</text></navLabel>
                      <content src="ch1.xhtml"/>
                    </navPoint>
                    <navPoint id="n2" playOrder="2">
                      <navLabel><text>Deep Dive</text></navLabel>
                      <content src="ch2.xhtml"/>
                    </navPoint>
                  </navMap>
                </ncx>
                """.trimIndent(),
            )
            add("OEBPS/ch1.xhtml", "<html><body><h1>Intro</h1><p>First chapter text.</p></body></html>")
            add("OEBPS/ch2.xhtml", "<html><body><h1>Deep Dive</h1><p>Hello Watch &amp; Crown!</p></body></html>")
        }
        return baos.toByteArray()
    }

    private fun buildMinimalPdfWithOutlines(): ByteArray {
        val sb = StringBuilder("%PDF-1.4\n")
        val offsets = LinkedHashMap<Int, Int>()

        fun addObj(id: Int, body: String) {
            offsets[id] = sb.length
            sb.append("$id 0 obj\n$body\nendobj\n")
        }

        addObj(1, "<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R >>")
        addObj(2, "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
        addObj(3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")
        addObj(4, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>")
        addObj(5, "<< /Type /Outlines /First 6 0 R /Last 7 0 R /Count 2 >>")
        addObj(6, "<< /Title (Cover) /Parent 5 0 R /Next 7 0 R /Dest [3 0 R /Fit] >>")
        addObj(7, "<< /Title (Second Page) /Parent 5 0 R /Prev 6 0 R /Dest [4 0 R /Fit] >>")

        val xrefStart = sb.length
        sb.append("xref\n0 8\n")
        sb.append("0000000000 65535 f \n")
        for (id in 1..7) {
            sb.append(String.format("%010d 00000 n \n", offsets[id] ?: 0))
        }
        sb.append("trailer\n<< /Size 8 /Root 1 0 R >>\nstartxref\n$xrefStart\n%%EOF")
        return sb.toString().toByteArray(StandardCharsets.ISO_8859_1)
    }
}
