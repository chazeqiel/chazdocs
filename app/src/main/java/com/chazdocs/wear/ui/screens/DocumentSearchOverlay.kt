package com.chazdocs.wear.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.chazdocs.wear.engine.DocFormat
import com.chazdocs.wear.engine.PdfDocumentEngine
import com.chazdocs.wear.engine.PdfTextExtractor
import com.chazdocs.wear.engine.ReflowBlock
import com.chazdocs.wear.engine.SearchHit
import com.chazdocs.wear.engine.TextChapter
import com.chazdocs.wear.ui.components.ChazIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DocumentSearchOverlay(
    format: DocFormat,
    pdfEngine: PdfDocumentEngine?,
    chapters: List<TextChapter>,
    blocks: List<ReflowBlock>,
    initialQuery: String,
    onSelectHit: (SearchHit, String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(enabled = true) { onDismiss() }

    val swipeState = rememberSwipeToDismissBoxState()

    SwipeToDismissBox(
        state = swipeState,
        onDismissed = onDismiss,
        modifier = Modifier.fillMaxSize(),
    ) { isBackground ->
        if (isBackground) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.4f))
            )
        } else {
            val listState = rememberTransformingLazyColumnState()
            val transformationSpec = rememberTransformationSpec()
            val keyboardController = LocalSoftwareKeyboardController.current
            val scope = rememberCoroutineScope()

            var query by remember { mutableStateOf(initialQuery) }
            var isSearching by remember { mutableStateOf(false) }
            var progressText by remember { mutableStateOf("") }
            var hasSearched by remember { mutableStateOf(false) }
            var results by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
            var activeJob by remember { mutableStateOf<Job?>(null) }

            fun runSearch() {
                val trimmed = query.trim()
                if (trimmed.isEmpty()) return
                keyboardController?.hide()
                activeJob?.cancel()
                activeJob = scope.launch {
                    isSearching = true
                    hasSearched = true
                    progressText = "Searching…"
                    results = withContext(Dispatchers.IO) {
                        when (format) {
                            DocFormat.PDF -> {
                                pdfEngine?.openByteSource()?.use { src ->
                                    PdfTextExtractor.searchPdf(src, trimmed) { done, total ->
                                        progressText = "Page $done / $total"
                                    }
                                } ?: emptyList()
                            }
                            DocFormat.EPUB, DocFormat.PPTX -> {
                                searchChapters(chapters, trimmed, isSlides = format == DocFormat.PPTX)
                            }
                            else -> {
                                searchBlocks(blocks, trimmed)
                            }
                        }
                    }
                    isSearching = false
                    progressText = ""
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f)),
            ) {
                ScreenScaffold(scrollState = listState) { contentPadding ->
                    TransformingLazyColumn(
                        state = listState,
                        contentPadding = contentPadding,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "search_header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "Search Document",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = ChazIcons.Close,
                                        contentDescription = "Close Search",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        item(key = "search_input") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 6.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.CenterStart,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .border(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.outline,
                                            shape = RoundedCornerShape(14.dp),
                                        )
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                ) {
                                    if (query.isEmpty()) {
                                        Text(
                                            text = "Tap to type keyword…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    BasicTextField(
                                        value = query,
                                        onValueChange = { query = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp,
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Button(
                                        onClick = { runSearch() },
                                        enabled = query.isNotBlank() && !isSearching,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary,
                                            contentColor = MaterialTheme.colorScheme.onPrimary,
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp),
                                        label = {
                                            Text(
                                                text = if (isSearching) progressText else "Find",
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                            )
                                        },
                                    )
                                    if (query.isNotEmpty()) {
                                        FilledTonalButton(
                                            onClick = {
                                                query = ""
                                                results = emptyList()
                                                hasSearched = false
                                            },
                                            modifier = Modifier.height(36.dp),
                                            label = { Text("Clear", fontSize = 11.sp) },
                                        )
                                    }
                                }
                            }
                        }

                        if (hasSearched && !isSearching && results.isEmpty()) {
                            item(key = "no_hits") {
                                Text(
                                    text = "No matches found for \"$query\"",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                )
                            }
                        } else if (results.isNotEmpty()) {
                            item(key = "hits_count") {
                                Text(
                                    text = "${results.size} match${if (results.size == 1) "" else "es"}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            itemsIndexed(
                                items = results,
                                key = { idx, hit -> "hit_${idx}_${hit.targetPage}_${hit.targetChapter}_${hit.targetBlockIndex}" },
                            ) { _, hit ->
                                Button(
                                    onClick = { onSelectHit(hit, query.trim()) },
                                    colors = ButtonDefaults.filledTonalButtonColors(),
                                    label = {
                                        Text(
                                            text = hit.title,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    secondaryLabel = {
                                        Text(
                                            text = hit.snippet,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    transformation = SurfaceTransformation(transformationSpec),
                                    modifier = Modifier
                                        .transformedHeight(this, transformationSpec)
                                        .fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun searchChapters(
    chapters: List<TextChapter>,
    query: String,
    isSlides: Boolean,
): List<SearchHit> {
    val hits = ArrayList<SearchHit>()
    for ((chIdx, ch) in chapters.withIndex()) {
        for ((bIdx, block) in ch.blocks.withIndex()) {
            val pos = block.text.indexOf(query, ignoreCase = true)
            if (pos >= 0) {
                val label = if (isSlides) "Slide ${chIdx + 1}" else "Ch. ${chIdx + 1}"
                hits.add(
                    SearchHit(
                        title = "$label • ${ch.title}",
                        snippet = extractSnippet(block.text, pos, query.length),
                        targetChapter = chIdx,
                        targetBlockIndex = bIdx,
                    )
                )
                if (hits.size >= 80) return hits
            }
        }
    }
    return hits
}

private fun searchBlocks(blocks: List<ReflowBlock>, query: String): List<SearchHit> {
    val hits = ArrayList<SearchHit>()
    for ((bIdx, block) in blocks.withIndex()) {
        val pos = block.text.indexOf(query, ignoreCase = true)
        if (pos >= 0) {
            val pct = if (blocks.size > 1) ((bIdx * 100) / (blocks.size - 1)) else 0
            hits.add(
                SearchHit(
                    title = "Match ~$pct%",
                    snippet = extractSnippet(block.text, pos, query.length),
                    targetBlockIndex = bIdx,
                )
            )
            if (hits.size >= 80) return hits
        }
    }
    return hits
}

private fun extractSnippet(text: String, matchStart: Int, matchLen: Int): String {
    val start = (matchStart - 28).coerceAtLeast(0)
    val end = (matchStart + matchLen + 48).coerceAtMost(text.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + text.substring(start, end).replace('\n', ' ').trim() + suffix
}
