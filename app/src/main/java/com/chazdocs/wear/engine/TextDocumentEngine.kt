package com.chazdocs.wear.engine

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

data class ParsedTextDocument(
    val blocks: List<ReflowBlock>,
    val tocEntries: List<TocEntry>,
)

/**
 * Streaming parser for .txt and .md/.markdown files.
 * Builds structured ReflowBlocks and extracts automatic TOC headings/chapters.
 */
object TextDocumentEngine {

    private val chapterRegex = Regex(
        "^(?:chapter\\s+[\\divxlcdm]+.*|part\\s+[\\divxlcdm]+.*|book\\s+[\\divxlcdm]+.*|section\\s+\\d+.*|prologue|epilogue|preface|introduction|appendix.*)$",
        RegexOption.IGNORE_CASE,
    )

    fun parseFromStream(
        inputStream: InputStream,
        isMarkdown: Boolean,
    ): ParsedTextDocument {
        val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        return if (isMarkdown) {
            parseMarkdown(reader)
        } else {
            parsePlainText(reader)
        }
    }

    private fun parseMarkdown(reader: BufferedReader): ParsedTextDocument {
        val blocks = ArrayList<ReflowBlock>()
        val toc = ArrayList<TocEntry>()
        val paragraphBuf = StringBuilder()
        val codeBuf = StringBuilder()
        var inCodeBlock = false

        fun flushParagraph() {
            val text = paragraphBuf.toString().trim()
            if (text.isNotEmpty()) {
                blocks.add(ReflowBlock(cleanInlineMarkdown(text), BlockStyle.BODY))
            }
            paragraphBuf.clear()
        }

        reader.forEachLine { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trim()

            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    val code = codeBuf.toString().trimEnd()
                    if (code.isNotEmpty()) {
                        blocks.add(ReflowBlock(code, BlockStyle.CODE))
                    }
                    codeBuf.clear()
                    inCodeBlock = false
                } else {
                    flushParagraph()
                    inCodeBlock = true
                }
                return@forEachLine
            }

            if (inCodeBlock) {
                codeBuf.append(line).append('\n')
                return@forEachLine
            }

            if (trimmed.isEmpty()) {
                flushParagraph()
                return@forEachLine
            }

            if (trimmed.matches(Regex("^[-*_]{3,}$"))) {
                flushParagraph()
                blocks.add(ReflowBlock("———", BlockStyle.DIVIDER))
                return@forEachLine
            }

            val headingMatch = Regex("^(#{1,6})\\s+(.+)$").matchEntire(trimmed)
            if (headingMatch != null) {
                flushParagraph()
                val hashes = headingMatch.groupValues[1].length
                val title = cleanInlineMarkdown(headingMatch.groupValues[2].trim())
                val style = when (hashes) {
                    1 -> BlockStyle.H1
                    2 -> BlockStyle.H2
                    else -> BlockStyle.H3
                }
                val idx = blocks.size
                blocks.add(ReflowBlock(title, style))
                toc.add(
                    TocEntry(
                        title = title,
                        level = (hashes - 1).coerceIn(0, 3),
                        targetBlockIndex = idx,
                    )
                )
                return@forEachLine
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quoteText = cleanInlineMarkdown(trimmed.removePrefix(">").trim())
                if (quoteText.isNotEmpty()) {
                    blocks.add(ReflowBlock(quoteText, BlockStyle.QUOTE))
                }
                return@forEachLine
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                flushParagraph()
                val itemText = cleanInlineMarkdown(trimmed.substring(2).trim())
                blocks.add(ReflowBlock("• $itemText", BlockStyle.BODY))
                return@forEachLine
            }

            if (paragraphBuf.isNotEmpty()) paragraphBuf.append(' ')
            paragraphBuf.append(trimmed)
        }

        if (inCodeBlock && codeBuf.isNotEmpty()) {
            blocks.add(ReflowBlock(codeBuf.toString().trimEnd(), BlockStyle.CODE))
        }
        flushParagraph()

        val finalToc = if (toc.isNotEmpty()) toc else buildMilestoneToc(blocks.size)
        return ParsedTextDocument(blocks = blocks, tocEntries = finalToc)
    }

    private fun parsePlainText(reader: BufferedReader): ParsedTextDocument {
        val blocks = ArrayList<ReflowBlock>()
        val toc = ArrayList<TocEntry>()
        val paragraphBuf = StringBuilder()

        fun flushParagraph() {
            val text = paragraphBuf.toString().trim()
            if (text.isNotEmpty()) {
                // Split extra-long single paragraphs (>1200 chars) into chunks so Compose LazyColumn stays silky smooth
                if (text.length <= 1200) {
                    blocks.add(ReflowBlock(text, BlockStyle.BODY))
                } else {
                    var start = 0
                    while (start < text.length) {
                        var end = minOf(text.length, start + 950)
                        if (end < text.length) {
                            val space = text.lastIndexOf(' ', end)
                            if (space > start + 400) end = space + 1
                        }
                        blocks.add(ReflowBlock(text.substring(start, end).trim(), BlockStyle.BODY))
                        start = end
                    }
                }
            }
            paragraphBuf.clear()
        }

        reader.forEachLine { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) {
                flushParagraph()
            } else if (trimmed.length <= 80 && chapterRegex.matches(trimmed)) {
                flushParagraph()
                val idx = blocks.size
                blocks.add(ReflowBlock(trimmed, BlockStyle.H2))
                toc.add(
                    TocEntry(
                        title = trimmed,
                        level = 0,
                        targetBlockIndex = idx,
                    )
                )
            } else {
                if (paragraphBuf.isNotEmpty()) paragraphBuf.append(' ')
                paragraphBuf.append(trimmed)
            }
        }
        flushParagraph()

        val finalToc = if (toc.isNotEmpty()) toc else buildMilestoneToc(blocks.size)
        return ParsedTextDocument(blocks = blocks, tocEntries = finalToc)
    }

    private fun cleanInlineMarkdown(text: String): String {
        return text
            .replace(Regex("!\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("__(.+?)__"), "$1")
            .replace(Regex("`([^`]+)`"), "$1")
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
}
