package com.chazdocs.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.chazdocs.wear.data.ReaderTheme

@Immutable
data class ReaderPalette(
    val background: Color,
    val onBackground: Color,
    val muted: Color,
    val accent: Color,
    val surface: Color,
)

val LocalReaderPalette = staticCompositionLocalOf {
    ReaderPalette(
        background = Color(0xFF000000),
        onBackground = Color(0xFFECEFF4),
        muted = Color(0xFF8B95A5),
        accent = Color(0xFF7CC4FF),
        surface = Color(0xFF12161D),
    )
}

fun ReaderTheme.toPalette(): ReaderPalette = ReaderPalette(
    background = Color(bgHex),
    onBackground = Color(fgHex),
    muted = Color(mutedHex),
    accent = Color(accentHex),
    surface = Color(surfaceHex),
)

private val ChazColorScheme = ColorScheme(
    primary = Color(0xFF7CC4FF),
    onPrimary = Color(0xFF061B2E),
    primaryContainer = Color(0xFF16324F),
    onPrimaryContainer = Color(0xFFD4ECFF),
    secondary = Color(0xFF9DD9C4),
    onSecondary = Color(0xFF08271E),
    secondaryContainer = Color(0xFF1A3A30),
    onSecondaryContainer = Color(0xFFD1F4E6),
    tertiary = Color(0xFFFFC97C),
    onTertiary = Color(0xFF2D1C02),
    surfaceContainerLow = Color(0xFF0D1117),
    surfaceContainer = Color(0xFF141A23),
    surfaceContainerHigh = Color(0xFF1C2430),
    onSurface = Color(0xFFECEFF4),
    onSurfaceVariant = Color(0xFF9AA5B4),
    background = Color(0xFF000000),
    onBackground = Color(0xFFECEFF4),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1F2937),
    error = Color(0xFFFF7A7A),
    onError = Color(0xFF2B0505),
)

@Composable
fun ChazDocsTheme(
    readerTheme: ReaderTheme,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalReaderPalette provides readerTheme.toPalette()) {
        MaterialTheme(
            colorScheme = ChazColorScheme,
            content = content,
        )
    }
}
