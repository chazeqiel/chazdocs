package com.chazdocs.wear.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TitleCard
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.chazdocs.wear.data.AppPreferences
import com.chazdocs.wear.data.CrownReaderMode
import com.chazdocs.wear.data.CrownSensitivity
import com.chazdocs.wear.data.ReaderTheme
import com.chazdocs.wear.data.TextZoomMode
import com.chazdocs.wear.ui.components.ChazIcons

@Composable
fun SettingsScreen(
    preferences: AppPreferences,
    hasAllFilesAccess: Boolean,
    documentsPath: String,
    onUpdatePreferences: ((AppPreferences) -> AppPreferences) -> Unit,
    onRequestStoragePermission: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "settings_header") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            item(key = "crown_reader_mode") {
                Button(
                    onClick = {
                        onUpdatePreferences { prefs ->
                            val next = if (prefs.crownReaderMode == CrownReaderMode.ZOOM) {
                                CrownReaderMode.SCROLL
                            } else {
                                CrownReaderMode.ZOOM
                            }
                            prefs.copy(crownReaderMode = next)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    label = { Text("Crown in Document", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) },
                    secondaryLabel = { Text("Mode: ${preferences.crownReaderMode.label}", fontSize = 11.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "theme") {
                Button(
                    onClick = {
                        onUpdatePreferences { prefs ->
                            val next = ReaderTheme.entries[(prefs.readerTheme.ordinal + 1) % ReaderTheme.entries.size]
                            prefs.copy(readerTheme = next)
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Reader Theme", fontSize = 13.sp) },
                    secondaryLabel = { Text(preferences.readerTheme.label, fontSize = 11.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "text_zoom_mode") {
                Button(
                    onClick = {
                        onUpdatePreferences { prefs ->
                            val next = if (prefs.textZoomMode == TextZoomMode.REFLOW_FONT) {
                                TextZoomMode.CANVAS_ZOOM
                            } else {
                                TextZoomMode.REFLOW_FONT
                            }
                            prefs.copy(textZoomMode = next)
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Text Zoom Style", fontSize = 13.sp) },
                    secondaryLabel = { Text(preferences.textZoomMode.label, fontSize = 11.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "crown_sensitivity") {
                Button(
                    onClick = {
                        onUpdatePreferences { prefs ->
                            val next = CrownSensitivity.entries[
                                (prefs.crownSensitivity.ordinal + 1) % CrownSensitivity.entries.size
                            ]
                            prefs.copy(crownSensitivity = next)
                        }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text("Crown Sensitivity", fontSize = 13.sp) },
                    secondaryLabel = { Text(preferences.crownSensitivity.label, fontSize = 11.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "invert_crown") {
                SwitchButton(
                    checked = preferences.invertCrown,
                    onCheckedChange = { checked ->
                        onUpdatePreferences { it.copy(invertCrown = checked) }
                    },
                    label = { Text("Invert Crown", fontSize = 12.sp) },
                    secondaryLabel = { Text("Reverse crown direction", fontSize = 10.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "invert_pdf") {
                SwitchButton(
                    checked = preferences.invertPdfColors,
                    onCheckedChange = { checked ->
                        onUpdatePreferences { it.copy(invertPdfColors = checked) }
                    },
                    label = { Text("Night PDF Colors", fontSize = 12.sp) },
                    secondaryLabel = { Text("Dark background for PDFs", fontSize = 10.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "keep_screen_on") {
                SwitchButton(
                    checked = preferences.keepScreenOn,
                    onCheckedChange = { checked ->
                        onUpdatePreferences { it.copy(keepScreenOn = checked) }
                    },
                    label = { Text("Keep Screen On", fontSize = 12.sp) },
                    secondaryLabel = { Text("While reading documents", fontSize = 10.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "hud_clock") {
                SwitchButton(
                    checked = preferences.showHudClock,
                    onCheckedChange = { checked ->
                        onUpdatePreferences { it.copy(showHudClock = checked) }
                    },
                    label = { Text("Clock in Reader HUD", fontSize = 12.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "storage_info") {
                TitleCard(
                    onClick = onRequestStoragePermission,
                    title = {
                        Text(
                            text = if (hasAllFilesAccess) "Storage: All Files OK" else "Storage: Limited",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    subtitle = {
                        Text(
                            text = "Folder: $documentsPath\nTap to manage permission.",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }

            item(key = "gesture_guide") {
                Text(
                    text = "Inside Document:\n• Crown: Zoom or Scroll (see above)\n• 2× Tap: Table of Contents\n• 3× Tap: Menu, Search & Home\n• Back swipe disabled in reader",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            item(key = "back_btn") {
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = {
                        Icon(
                            imageVector = ChazIcons.ChevronLeft,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = { Text("Back", fontSize = 13.sp) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier
                        .transformedHeight(this, transformationSpec)
                        .fillMaxWidth(),
                )
            }
        }
    }
}
