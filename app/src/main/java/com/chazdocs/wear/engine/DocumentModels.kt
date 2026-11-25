package com.chazdocs.wear.engine

enum class DocFormat(val label: String, val extensions: Set<String>) {
    PDF("PDF", setOf("pdf")),
    EPUB("EPUB", setOf("epub")),
    DOCX("DOCX", setOf("docx")),
    PPTX("PPTX", setOf("pptx")),
    MARKDOWN("MD", setOf("md", "markdown")),
    TEXT("TXT", setOf("txt", "log"));

    companion object {
        fun fromExtension(ext: String): DocFormat? {
            val lower = ext.lowercase()
            return entries.firstOrNull { lower in it.extensions }
        }
    }
}

data class DocumentItem(
    val id: String,
    val title: String,
    val fileName: String,
    val path: String?,
    val contentUriString: String?,
    val format: DocFormat,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val lastOpenedMs: Long = 0L,
    val progressFraction: Float = 0f,
    val subtitle: String = "",
)

data class TocEntry(
    val title: String,
    val level: Int,
    val targetPage: Int = 0,
    val targetChapter: Int = 0,
    val targetBlockIndex: Int = 0,
)

enum class BlockStyle {
    H1,
    H2,
    H3,
    BODY,
    QUOTE,
    CODE,
    DIVIDER,
}

data class ReflowBlock(
    val text: String,
    val style: BlockStyle = BlockStyle.BODY,
)

data class TextChapter(
    val title: String,
    val blocks: List<ReflowBlock>,
)
