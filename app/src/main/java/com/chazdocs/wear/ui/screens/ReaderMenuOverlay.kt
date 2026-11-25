package com.chazdocs.wear.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.chazdocs.wear.data.AppPreferences
import com.chazdocs.wear.data.CrownReaderMode
import com.chazdocs.wear.data.ReaderTheme
import com.chazdocs.wear.engine.DocFormat
import com.chazdocs.wear.ui.components.ChazIcons

@Composable
fun ReaderMenuOverlay(
    documentTitle: String,
    format: DocFormat,
    positionSummary: String,
    zoomSummary: String,
    preferences: AppPreferences,
    onMainMenu: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenToc: () -> Unit,
    onToggleCrownMode: () -> Unit,
    onResetZoom: () -> Unit,
    onCycleTheme: () -> Unit,
    onToggleInvertPdf: (Boolean) -> Unit,
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
                        item(key = "menu_header") {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = documentTitle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "$positionSummary • $zoomSummary",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        item(key = "search") {
                            Button(
                                onClick = onOpenSearch,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                icon = {
                                    Icon(
                                        imageVector = ChazIcons.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                label = { Text("Search in Doc", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec)
                                    .fillMaxWidth(),
                            )
                        }

                        item(key = "main_menu") {
                            Button(
                                onClick = onMainMenu,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                                icon = {
                                    Icon(
                                        imageVector = ChazIcons.Home,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                label = { Text("Main Menu", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                                secondaryLabel = { Text("Home & Documents", fontSize = 10.sp) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec)
                                    .fillMaxWidth(),
                            )
                        }

                        item(key = "settings") {
                            Button(
                                onClick = onOpenSettings,
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                icon = {
                                    Icon(
                                        imageVector = ChazIcons.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                label = { Text("Settings", fontSize = 13.sp) },
                                secondaryLabel = { Text("Crown mode, theme & display", fontSize = 10.sp) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec)
                                    .fillMaxWidth(),
                            )
                        }

                        item(key = "crown_quick_toggle") {
                            val nextCrown = if (preferences.crownReaderMode == CrownReaderMode.ZOOM) {
                                CrownReaderMode.SCROLL
                            } else {
                                CrownReaderMode.ZOOM
                            }
                            Button(
                                onClick = onToggleCrownMode,
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                label = { Text("Crown: ${preferences.crownReaderMode.label}", fontSize = 12.sp) },
                                secondaryLabel = { Text("Tap to switch to ${nextCrown.label}", fontSize = 10.sp) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec)
                                    .fillMaxWidth(),
                            )
                        }

                        item(key = "toc_shortcut") {
                            Button(
                                onClick = onOpenToc,
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                icon = {
                                    Icon(
                                        imageVector = ChazIcons.Toc,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                label = { Text("Table of Contents", fontSize = 13.sp) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec)
                                    .fillMaxWidth(),
                            )
                        }

                        item(key = "reset_zoom") {
                            Button(
                                onClick = onResetZoom,
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                label = { Text("Reset Zoom", fontSize = 12.sp) },
                                secondaryLabel = { Text("Current: $zoomSummary", fontSize = 10.sp) },
                                transformation = SurfaceTransformation(transformationSpec),
                                modifier = Modifier
                                    .transformedHeight(this, transformationSpec)
                                    .fillMaxWidth(),
                            )
                        }

                        if (format == DocFormat.PDF) {
                            item(key = "invert_pdf") {
                                SwitchButton(
                                    checked = preferences.invertPdfColors,
                                    onCheckedChange = onToggleInvertPdf,
                                    label = { Text("Night PDF Colors", fontSize = 12.sp) },
                                    secondaryLabel = { Text("Invert white pages", fontSize = 10.sp) },
                                    transformation = SurfaceTransformation(transformationSpec),
                                    modifier = Modifier
                                        .transformedHeight(this, transformationSpec)
                                        .fillMaxWidth(),
                                )
                            }
                        } else {
                            item(key = "cycle_theme") {
                                val nextTheme = ReaderTheme.entries[(preferences.readerTheme.ordinal + 1) % ReaderTheme.entries.size]
                                Button(
                                    onClick = onCycleTheme,
                                    colors = ButtonDefaults.filledTonalButtonColors(),
                                    label = { Text("Theme: ${preferences.readerTheme.label}", fontSize = 12.sp) },
                                    secondaryLabel = { Text("Tap for ${nextTheme.label}", fontSize = 10.sp) },
                                    transformation = SurfaceTransformation(transformationSpec),
                                    modifier = Modifier
                                        .transformedHeight(this, transformationSpec)
                                        .fillMaxWidth(),
                                )
                            }
                        }

                        item(key = "resume") {
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                icon = {
                                    Icon(
                                        imageVector = ChazIcons.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = { Text("Resume Reading", fontSize = 12.sp) },
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
