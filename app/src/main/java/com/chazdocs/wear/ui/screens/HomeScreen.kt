package com.chazdocs.wear.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.chazdocs.wear.data.DocumentScanner
import com.chazdocs.wear.data.DocumentSort
import com.chazdocs.wear.data.ScanResult
import com.chazdocs.wear.engine.DocFormat
import com.chazdocs.wear.engine.DocumentItem
import com.chazdocs.wear.ui.components.ChazIcons
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    scanResult: ScanResult,
    currentSort: DocumentSort,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onChangeSort: (DocumentSort) -> Unit,
    onOpenDocument: (DocumentItem) -> Unit,
    onOpenSettings: () -> Unit,
    onRequestStoragePermission: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var selectedFilter by remember { mutableStateOf<DocFormat?>(null) }

    val sortedDocs = remember(scanResult.documents, currentSort) {
        DocumentScanner.sortDocuments(scanResult.documents, currentSort)
    }
    val filteredDocs = remember(sortedDocs, selectedFilter) {
        if (selectedFilter == null) sortedDocs
        else sortedDocs.filter { it.format == selectedFilter }
    }
    val recentDoc = remember(scanResult.documents) {
        scanResult.documents
            .filter { it.lastOpenedMs > 0L }
            .maxByOrNull { it.lastOpenedMs }
    }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 6.dp),
                ) {
                    Text(
                        text = "ChazDocs",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "${scanResult.documents.size} docs • Documents/",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = ChazIcons.Refresh,
                                contentDescription = "Refresh Documents",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        FilledTonalButton(
                            onClick = {
                                val next = DocumentSort.entries[(currentSort.ordinal + 1) % DocumentSort.entries.size]
                                onChangeSort(next)
                            },
                            modifier = Modifier.height(36.dp),
                            icon = {
                                Icon(
                                    imageVector = ChazIcons.Sort,
                                    contentDescription = "Sort",
                                    modifier = Modifier.size(15.dp),
                                )
                            },
                            label = {
                                Text(text = currentSort.label, fontSize = 11.sp)
                            },
                        )
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = ChazIcons.Settings,
                                contentDescription = "Settings",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            if (!scanResult.hasAllFilesAccess) {
                item(key = "storage_banner") {
                    TitleCard(
                        onClick = onRequestStoragePermission,
                        title = {
                            Text(
                                text = "Grant All-Files Access",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                        subtitle = {
                            Text(
                                text = "Needed to read ADB-pushed files in /sdcard/Documents. Tap to allow.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec)
                            .fillMaxWidth(),
                    )
                }
            }

            if (recentDoc != null && selectedFilter == null) {
                item(key = "continue_reading") {
                    Button(
                        onClick = { onOpenDocument(recentDoc) },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        label = {
                            Text(
                                text = "Continue: ${recentDoc.title}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                            )
                        },
                        secondaryLabel = {
                            val pct = (recentDoc.progressFraction * 100f).roundToInt()
                            Text(
                                text = "${recentDoc.format.label} • $pct% read",
                                fontSize = 11.sp,
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = ChazIcons.Book,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .transformedHeight(this, transformationSpec)
                            .fillMaxWidth(),
                    )
                }
            }

            val availableFormats = DocFormat.entries.filter { fmt ->
                scanResult.documents.any { it.format == fmt }
            }
            if (availableFormats.size > 1) {
                item(key = "format_filters") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        FilterChipPill(
                            label = "All",
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                        )
                        availableFormats.forEach { fmt ->
                            FilterChipPill(
                                label = fmt.label,
                                selected = selectedFilter == fmt,
                                onClick = {
                                    selectedFilter = if (selectedFilter == fmt) null else fmt
                                },
                            )
                        }
                    }
                }
            }

            if (filteredDocs.isEmpty()) {
                item(key = "empty") {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = if (isScanning) "Scanning Documents…" else "No Documents Found",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Push PDF, EPUB, DOCX, PPTX, TXT or MD files via ADB:\nadb push file.pdf /sdcard/Documents/",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = onRefresh,
                            label = { Text("Scan Again", fontSize = 12.sp) },
                        )
                    }
                }
            } else {
                items(
                    items = filteredDocs,
                    key = { it.id },
                ) { doc ->
                    Button(
                        onClick = { onOpenDocument(doc) },
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = {
                            FormatBadge(format = doc.format)
                        },
                        label = {
                            Text(
                                text = doc.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                            )
                        },
                        secondaryLabel = {
                            val pctText = if (doc.lastOpenedMs > 0L) {
                                " • ${(doc.progressFraction * 100f).roundToInt()}%"
                            } else {
                                ""
                            }
                            Text(
                                text = "${doc.subtitle}$pctText",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 11.sp,
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

@Composable
private fun FilterChipPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    FilledTonalButton(
        onClick = onClick,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = bg,
            contentColor = fg,
        ),
        modifier = Modifier.height(28.dp),
        label = {
            Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        },
    )
}

@Composable
private fun FormatBadge(format: DocFormat) {
    val bg = when (format) {
        DocFormat.PDF -> Color(0xFF8C2D2D)
        DocFormat.EPUB -> Color(0xFF1F5E48)
        DocFormat.DOCX -> Color(0xFF1E4E8C)
        DocFormat.PPTX -> Color(0xFF9E3E22)
        DocFormat.MARKDOWN -> Color(0xFF284D7A)
        DocFormat.TEXT -> Color(0xFF474E5B)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(34.dp)
            .height(22.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg),
    ) {
        Text(
            text = format.label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
