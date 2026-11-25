package com.chazdocs.wear.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OfficeParsersTest {

    @Test
    fun `docx parser extracts headings, styles, tabs, line breaks, and TOC`() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:pStyle w:val="Title"/></w:pPr>
                  <w:r><w:t>Wearable Manual</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading2"/></w:pPr>
                  <w:r><w:t>Quick Start</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t>Line one</w:t><w:tab/><w:t>tabbed</w:t><w:br/><w:t>Line two</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:pStyle w:val="Heading4"/></w:pPr>
                  <w:r><w:t>Deep Section</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:pStyle w:val="IntenseQuote"/></w:pPr>
                  <w:r><w:t>Stay concise on small screens.</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:pStyle w:val="Code"/></w:pPr>
                  <w:r><w:t>val watch = true</w:t></w:r>
                </w:p>
              </w:body>
            </w:document>
        """.trimIndent()

        val docxBytes = buildZip(
            mapOf(
                "word/media/cover.png" to ByteArray(1024) { 1 },
                "word/document.xml" to documentXml.toByteArray(StandardCharsets.UTF_8),
            )
        )

        val parsed = OfficeDocumentEngine.parseDocxFromStream(ByteArrayInputStream(docxBytes))

        assertEquals(6, parsed.blocks.size)
        assertEquals(ReflowBlock("Wearable Manual", BlockStyle.H1), parsed.blocks[0])
        assertEquals(ReflowBlock("Quick Start", BlockStyle.H2), parsed.blocks[1])
        assertEquals(ReflowBlock("Line one tabbed\nLine two", BlockStyle.BODY), parsed.blocks[2])
        assertEquals(ReflowBlock("Deep Section", BlockStyle.H3), parsed.blocks[3])
        assertEquals(ReflowBlock("Stay concise on small screens.", BlockStyle.QUOTE), parsed.blocks[4])
        assertEquals(ReflowBlock("val watch = true", BlockStyle.CODE), parsed.blocks[5])

        assertEquals(3, parsed.tocEntries.size)
        assertEquals(TocEntry("Wearable Manual", level = 0, targetBlockIndex = 0), parsed.tocEntries[0])
        assertEquals(TocEntry("Quick Start", level = 1, targetBlockIndex = 1), parsed.tocEntries[1])
        assertEquals(TocEntry("Deep Section", level = 2, targetBlockIndex = 3), parsed.tocEntries[2])
    }

    @Test
    fun `docx parser formats numPr bullets and table rows`() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                  <w:r><w:t>First item</w:t></w:r>
                </w:p>
                <w:p>
                  <w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr>
                  <w:r><w:t>• Already bulleted</w:t></w:r>
                </w:p>
                <w:tbl>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>Format</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Status</w:t></w:r></w:p></w:tc>
                  </w:tr>
                  <w:tr>
                    <w:tc><w:p><w:r><w:t>DOCX</w:t></w:r></w:p></w:tc>
                    <w:tc><w:p><w:r><w:t>Supported</w:t></w:r></w:p></w:tc>
                  </w:tr>
                </w:tbl>
              </w:body>
            </w:document>
        """.trimIndent()

        val docxBytes = buildZip(
            mapOf("word/document.xml" to documentXml.toByteArray(StandardCharsets.UTF_8))
        )

        val parsed = OfficeDocumentEngine.parseDocxFromStream(ByteArrayInputStream(docxBytes))

        assertEquals(4, parsed.blocks.size)
        assertEquals(ReflowBlock("• First item", BlockStyle.BODY), parsed.blocks[0])
        assertEquals(ReflowBlock("• Already bulleted", BlockStyle.BODY), parsed.blocks[1])
        assertEquals(ReflowBlock("Format | Status", BlockStyle.BODY), parsed.blocks[2])
        assertEquals(ReflowBlock("DOCX | Supported", BlockStyle.BODY), parsed.blocks[3])

        // No headings -> Beginning milestone TOC for <= 10 blocks
        assertEquals(1, parsed.tocEntries.size)
        assertEquals("Beginning", parsed.tocEntries[0].title)
        assertEquals(0, parsed.tocEntries[0].targetBlockIndex)
    }

    @Test
    fun `docx parser builds percentage milestone TOC when document has no headings and over 10 blocks`() {
        val paragraphs = (1..12).joinToString("\n") { idx ->
            "<w:p><w:r><w:t>Paragraph $idx</w:t></w:r></w:p>"
        }
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>__PARAGRAPHS__</w:body>
            </w:document>
        """.trimIndent().replace("__PARAGRAPHS__", paragraphs)

        val docxBytes = buildZip(
            mapOf("word/document.xml" to documentXml.toByteArray(StandardCharsets.UTF_8))
        )

        val parsed = OfficeDocumentEngine.parseDocxFromStream(ByteArrayInputStream(docxBytes))

        assertEquals(12, parsed.blocks.size)
        assertEquals(5, parsed.tocEntries.size)
        assertEquals(0, parsed.tocEntries[0].targetBlockIndex)
        assertEquals(3, parsed.tocEntries[1].targetBlockIndex)
        assertEquals(6, parsed.tocEntries[2].targetBlockIndex)
        assertEquals(9, parsed.tocEntries[3].targetBlockIndex)
        assertEquals(11, parsed.tocEntries[4].targetBlockIndex)
    }

    @Test
    fun `pptx parser respects presentation rels slide order, titles, bullets, tables, and speaker notes`() {
        val presentationXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <p:sldIdLst>
                <p:sldId id="256" r:id="rId3"/>
                <p:sldId id="257" r:id="rId1"/>
                <p:sldId id="258" r:id="rId2"/>
              </p:sldIdLst>
            </p:presentation>
        """.trimIndent()

        val presentationRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
              <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
              <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide3.xml"/>
            </Relationships>
        """.trimIndent()

        // slide3 is ordered FIRST via rId3
        val slide3Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld>
                <p:spTree>
                  <p:sp>
                    <p:nvSpPr><p:cNvPr id="1" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="ctrTitle"/></p:nvPr></p:nvSpPr>
                    <p:txBody>
                      <a:p><a:r><a:t>Opening Deck</a:t></a:r></a:p>
                    </p:txBody>
                  </p:sp>
                  <p:sp>
                    <p:nvSpPr><p:cNvPr id="2" name="Bullets"/><p:cNvSpPr/><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr>
                    <p:txBody>
                      <a:p><a:pPr lvl="0"><a:buChar char="•"/></a:pPr><a:r><a:t>Fast glanceable UI</a:t></a:r></a:p>
                      <a:p><a:pPr><a:buNone/></a:pPr><a:r><a:t>Plain caption</a:t></a:r></a:p>
                    </p:txBody>
                  </p:sp>
                </p:spTree>
              </p:cSld>
            </p:sld>
        """.trimIndent()

        // slide1 is ordered SECOND via rId1, has a table and speaker notes (with sldNum excluded)
        val slide1Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld>
                <p:spTree>
                  <p:sp>
                    <p:nvSpPr><p:cNvPr id="1" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
                    <p:txBody><a:p><a:r><a:t>Metrics</a:t></a:r></a:p></p:txBody>
                  </p:sp>
                  <p:graphicFrame>
                    <a:graphic>
                      <a:graphicData>
                        <a:tbl>
                          <a:tr>
                            <a:tc><a:txBody><a:p><a:r><a:t>Metric</a:t></a:r></a:p></a:txBody></a:tc>
                            <a:tc><a:txBody><a:p><a:r><a:t>Target</a:t></a:r></a:p></a:txBody></a:tc>
                          </a:tr>
                          <a:tr>
                            <a:tc><a:txBody><a:p><a:r><a:t>FPS</a:t></a:r></a:p></a:txBody></a:tc>
                            <a:tc><a:txBody><a:p><a:r><a:t>60</a:t></a:r></a:p></a:txBody></a:tc>
                          </a:tr>
                        </a:tbl>
                      </a:graphicData>
                    </a:graphic>
                  </p:graphicFrame>
                </p:spTree>
              </p:cSld>
            </p:sld>
        """.trimIndent()

        val slide1Rels = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide" Target="../notesSlides/notesSlide1.xml"/>
            </Relationships>
        """.trimIndent()

        val notesSlide1 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:notes xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                     xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld>
                <p:spTree>
                  <p:sp>
                    <p:nvSpPr><p:cNvPr id="2" name="Notes"/><p:cNvSpPr/><p:nvPr><p:ph type="body"/></p:nvPr></p:nvSpPr>
                    <p:txBody><a:p><a:r><a:t>Mention battery efficiency.</a:t></a:r></a:p></p:txBody>
                  </p:sp>
                  <p:sp>
                    <p:nvSpPr><p:cNvPr id="3" name="Slide Number"/><p:cNvSpPr/><p:nvPr><p:ph type="sldNum"/></p:nvPr></p:nvSpPr>
                    <p:txBody><a:p><a:r><a:t>2</a:t></a:r></a:p></p:txBody>
                  </p:sp>
                </p:spTree>
              </p:cSld>
            </p:notes>
        """.trimIndent()

        // slide2 is ordered THIRD via rId2 and has no title placeholder
        val slide2Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld>
                <p:spTree>
                  <p:sp>
                    <p:nvSpPr><p:cNvPr id="1" name="TextBox"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
                    <p:txBody><a:p><a:r><a:t>Thank you!</a:t></a:r></a:p></p:txBody>
                  </p:sp>
                </p:spTree>
              </p:cSld>
            </p:sld>
        """.trimIndent()

        val pptxBytes = buildZip(
            mapOf(
                "ppt/presentation.xml" to presentationXml.toByteArray(StandardCharsets.UTF_8),
                "ppt/_rels/presentation.xml.rels" to presentationRels.toByteArray(StandardCharsets.UTF_8),
                "ppt/slides/slide1.xml" to slide1Xml.toByteArray(StandardCharsets.UTF_8),
                "ppt/slides/_rels/slide1.xml.rels" to slide1Rels.toByteArray(StandardCharsets.UTF_8),
                "ppt/notesSlides/notesSlide1.xml" to notesSlide1.toByteArray(StandardCharsets.UTF_8),
                "ppt/slides/slide2.xml" to slide2Xml.toByteArray(StandardCharsets.UTF_8),
                "ppt/slides/slide3.xml" to slide3Xml.toByteArray(StandardCharsets.UTF_8),
                "ppt/media/background.jpg" to ByteArray(2048) { 7 },
            )
        )

        val deck = OfficeDocumentEngine.parsePptxFromStream(ByteArrayInputStream(pptxBytes))

        assertEquals(3, deck.slides.size)
        assertEquals(3, deck.tocEntries.size)

        // Slide 1 in deck order is slide3.xml ("Opening Deck")
        assertEquals("Opening Deck", deck.slides[0].title)
        assertEquals(
            listOf(
                ReflowBlock("Opening Deck", BlockStyle.H1),
                ReflowBlock("• Fast glanceable UI", BlockStyle.BODY),
                ReflowBlock("Plain caption", BlockStyle.BODY),
            ),
            deck.slides[0].blocks,
        )
        assertEquals(TocEntry("Slide 1: Opening Deck", level = 0, targetChapter = 0), deck.tocEntries[0])

        // Slide 2 in deck order is slide1.xml ("Metrics") with table + speaker note (excluding sldNum "2")
        assertEquals("Metrics", deck.slides[1].title)
        assertEquals(
            listOf(
                ReflowBlock("Metrics", BlockStyle.H1),
                ReflowBlock("Metric | Target", BlockStyle.BODY),
                ReflowBlock("FPS | 60", BlockStyle.BODY),
                ReflowBlock("Note: Mention battery efficiency.", BlockStyle.QUOTE),
            ),
            deck.slides[1].blocks,
        )
        assertEquals(TocEntry("Slide 2: Metrics", level = 0, targetChapter = 1), deck.tocEntries[1])

        // Slide 3 in deck order is slide2.xml (no title placeholder -> "Slide 3" fallback)
        assertEquals("Slide 3", deck.slides[2].title)
        assertEquals(listOf(ReflowBlock("Thank you!", BlockStyle.BODY)), deck.slides[2].blocks)
        assertEquals(TocEntry("Slide 3", level = 0, targetChapter = 2), deck.tocEntries[2])
    }

    @Test
    fun `pptx parser falls back to numeric slide filename order when rels are missing`() {
        fun minimalSlide(title: String): ByteArray = """
            <?xml version="1.0" encoding="UTF-8"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld>
                <p:spTree>
                  <p:sp>
                    <p:nvSpPr><p:cNvPr id="1" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
                    <p:txBody><a:p><a:r><a:t>$title</a:t></a:r></a:p></p:txBody>
                  </p:sp>
                </p:spTree>
              </p:cSld>
            </p:sld>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val pptxBytes = buildZip(
            mapOf(
                "ppt/slides/slide10.xml" to minimalSlide("Tenth"),
                "ppt/slides/slide2.xml" to minimalSlide("Second"),
                "ppt/slides/slide1.xml" to minimalSlide("First"),
            )
        )

        val deck = OfficeDocumentEngine.parsePptxFromStream(ByteArrayInputStream(pptxBytes))

        assertEquals(listOf("First", "Second", "Tenth"), deck.slides.map { it.title })
        assertTrue(deck.tocEntries.map { it.title } == listOf("Slide 1: First", "Slide 2: Second", "Slide 3: Tenth"))
    }

    private fun buildZip(entries: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
