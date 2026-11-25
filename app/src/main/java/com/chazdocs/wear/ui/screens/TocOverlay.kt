package com.chazdocs.wear.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
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
import com.chazdocs.wear.engine.TocEntry
import com.chazdocs.wear.ui.components.ChazIcons

@Composable
fun TocOverlay(
    entries: List<TocEntry>,
    format: DocFormat,
    currentPage: Int,
    currentChapter: Int,
    onSelectEntry: (TocEntry) -> Unit,
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
                        item(key = "toc_header") {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Table of Contents",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = "${entries.size} entries",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(34.dp),
                                ) {
                                    Icon(
                                        imageVector = ChazIcons.Close,
                                        contentDescription = "Close TOC",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }

                        if (entries.isEmpty()) {
                            item(key = "toc_empty") {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                ) {
                                    Text(
                                        text = "No sections found",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = onDismiss,
                                        label = { Text("Back to Document", fontSize = 12.sp) },
                                    )
                                }
                            }
                        } else {
                            itemsIndexed(
                                items = entries,
                                key = { idx, item -> "toc_${idx}_${item.title.hashCode()}" },
                            ) { _, entry ->
                                val isActive = when (format) {
                                    DocFormat.PDF -> entry.targetPage == currentPage
                                    DocFormat.EPUB, DocFormat.PPTX -> entry.targetChapter == currentChapter
                                    else -> false
                                }
                                val indentDp = (entry.level.coerceIn(0, 3) * 8).dp
                                Button(
                                    onClick = { onSelectEntry(entry) },
                                    colors = if (isActive) {
                                        ButtonDefaults.filledTonalButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    } else {
                                        ButtonDefaults.filledTonalButtonColors()
                                    },
                                    label = {
                                        Text(
                                            text = entry.title,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(start = indentDp),
                                        )
                                    },
                                    secondaryLabel = {
                                        val targetLabel = when (format) {
                                            DocFormat.PDF -> "Page ${entry.targetPage + 1}"
                                            DocFormat.EPUB -> "Section ${entry.targetChapter + 1}"
                                            DocFormat.PPTX -> "Slide ${entry.targetChapter + 1}"
                                            else -> "Jump"
                                        }
                                        Text(
                                            text = targetLabel,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(start = indentDp),
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
