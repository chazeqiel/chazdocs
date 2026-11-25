package com.chazdocs.wear.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class ReaderTheme(
    val label: String,
    val bgHex: Long,
    val fgHex: Long,
    val mutedHex: Long,
    val accentHex: Long,
    val surfaceHex: Long,
) {
    AMOLED("AMOLED", 0xFF000000L, 0xFFECEFF4L, 0xFF8B95A5L, 0xFF7CC4FFL, 0xFF12161DL),
    MIDNIGHT("Midnight", 0xFF0B111BL, 0xFFDCE5F2L, 0xFF7D8FA7L, 0xFF64B5F6L, 0xFF152031L),
    SEPIA("Sepia", 0xFF19130DL, 0xFFE9DCC9L, 0xFFA19079L, 0xFFDCA15DL, 0xFF261E14L),
    PAPER("Paper", 0xFFF5F1E8L, 0xFF1C1B18L, 0xFF6A655BL, 0xFF1D62D0L, 0xFFE6E0D3L),
}

enum class CrownReaderMode(val label: String) {
    ZOOM("Zoom"),
    SCROLL("Scroll"),
}

enum class TextZoomMode(val label: String) {
    REFLOW_FONT("Reflow Font"),
    CANVAS_ZOOM("Canvas Zoom"),
}

enum class CrownSensitivity(val label: String, val multiplier: Float) {
    LOW("Low", 0.55f),
    MEDIUM("Medium", 1.0f),
    HIGH("High", 1.75f),
}

enum class DocumentSort(val label: String) {
    DATE("Date"),
    NAME("Name"),
    SIZE("Size"),
}

data class AppPreferences(
    val readerTheme: ReaderTheme = ReaderTheme.AMOLED,
    val crownReaderMode: CrownReaderMode = CrownReaderMode.ZOOM,
    val textZoomMode: TextZoomMode = TextZoomMode.REFLOW_FONT,
    val crownSensitivity: CrownSensitivity = CrownSensitivity.MEDIUM,
    val invertCrown: Boolean = false,
    val invertPdfColors: Boolean = false,
    val keepScreenOn: Boolean = true,
    val showHudClock: Boolean = true,
    val defaultFontSizeSp: Float = 14f,
    val documentSort: DocumentSort = DocumentSort.DATE,
)

data class ReadingPosition(
    val pageIndex: Int = 0,
    val chapterIndex: Int = 0,
    val blockIndex: Int = 0,
    val scrollOffsetPx: Int = 0,
    val zoomScale: Float = 1f,
    val fontSizeSp: Float = 14f,
    val lastOpenedMs: Long = 0L,
    val totalUnits: Int = 1,
) {
    val progressFraction: Float
        get() {
            if (totalUnits <= 1) return 0f
            val current = maxOf(pageIndex, chapterIndex, 0)
            return (current.toFloat() / (totalUnits - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
}

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<AppPreferences> = _preferences.asStateFlow()

    private fun loadPreferences(): AppPreferences {
        val themeName = prefs.getString(KEY_THEME, ReaderTheme.AMOLED.name)
        val crownModeName = prefs.getString(KEY_CROWN_READER_MODE, CrownReaderMode.ZOOM.name)
        val zoomModeName = prefs.getString(KEY_TEXT_ZOOM_MODE, TextZoomMode.REFLOW_FONT.name)
        val sensitivityName = prefs.getString(KEY_CROWN_SENSITIVITY, CrownSensitivity.MEDIUM.name)
        val sortName = prefs.getString(KEY_DOC_SORT, DocumentSort.DATE.name)

        return AppPreferences(
            readerTheme = ReaderTheme.entries.firstOrNull { it.name == themeName } ?: ReaderTheme.AMOLED,
            crownReaderMode = CrownReaderMode.entries.firstOrNull { it.name == crownModeName } ?: CrownReaderMode.ZOOM,
            textZoomMode = TextZoomMode.entries.firstOrNull { it.name == zoomModeName } ?: TextZoomMode.REFLOW_FONT,
            crownSensitivity = CrownSensitivity.entries.firstOrNull { it.name == sensitivityName } ?: CrownSensitivity.MEDIUM,
            invertCrown = prefs.getBoolean(KEY_INVERT_CROWN, false),
            invertPdfColors = prefs.getBoolean(KEY_INVERT_PDF, false),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true),
            showHudClock = prefs.getBoolean(KEY_SHOW_HUD_CLOCK, true),
            defaultFontSizeSp = prefs.getFloat(KEY_DEFAULT_FONT_SP, 14f).coerceIn(10f, 28f),
            documentSort = DocumentSort.entries.firstOrNull { it.name == sortName } ?: DocumentSort.DATE,
        )
    }

    fun updatePreferences(transform: (AppPreferences) -> AppPreferences) {
        val updated = transform(_preferences.value)
        _preferences.value = updated
        prefs.edit()
            .putString(KEY_THEME, updated.readerTheme.name)
            .putString(KEY_CROWN_READER_MODE, updated.crownReaderMode.name)
            .putString(KEY_TEXT_ZOOM_MODE, updated.textZoomMode.name)
            .putString(KEY_CROWN_SENSITIVITY, updated.crownSensitivity.name)
            .putBoolean(KEY_INVERT_CROWN, updated.invertCrown)
            .putBoolean(KEY_INVERT_PDF, updated.invertPdfColors)
            .putBoolean(KEY_KEEP_SCREEN_ON, updated.keepScreenOn)
            .putBoolean(KEY_SHOW_HUD_CLOCK, updated.showHudClock)
            .putFloat(KEY_DEFAULT_FONT_SP, updated.defaultFontSizeSp)
            .putString(KEY_DOC_SORT, updated.documentSort.name)
            .apply()
    }

    fun getReadingPosition(docId: String): ReadingPosition? {
        val raw = prefs.getString("$KEY_PROGRESS_PREFIX$docId", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ReadingPosition(
                pageIndex = json.optInt("pageIndex", 0),
                chapterIndex = json.optInt("chapterIndex", 0),
                blockIndex = json.optInt("blockIndex", 0),
                scrollOffsetPx = json.optInt("scrollOffsetPx", 0),
                zoomScale = json.optDouble("zoomScale", 1.0).toFloat().coerceIn(1f, 5f),
                fontSizeSp = json.optDouble("fontSizeSp", _preferences.value.defaultFontSizeSp.toDouble()).toFloat().coerceIn(10f, 28f),
                lastOpenedMs = json.optLong("lastOpenedMs", 0L),
                totalUnits = json.optInt("totalUnits", 1).coerceAtLeast(1),
            )
        }.getOrNull()
    }

    fun saveReadingPosition(docId: String, position: ReadingPosition) {
        val json = JSONObject().apply {
            put("pageIndex", position.pageIndex)
            put("chapterIndex", position.chapterIndex)
            put("blockIndex", position.blockIndex)
            put("scrollOffsetPx", position.scrollOffsetPx)
            put("zoomScale", position.zoomScale.toDouble())
            put("fontSizeSp", position.fontSizeSp.toDouble())
            put("lastOpenedMs", position.lastOpenedMs)
            put("totalUnits", position.totalUnits)
        }
        prefs.edit().putString("$KEY_PROGRESS_PREFIX$docId", json.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "chazdocs_prefs"
        private const val KEY_THEME = "reader_theme"
        private const val KEY_CROWN_READER_MODE = "crown_reader_mode"
        private const val KEY_TEXT_ZOOM_MODE = "text_zoom_mode"
        private const val KEY_CROWN_SENSITIVITY = "crown_sensitivity"
        private const val KEY_INVERT_CROWN = "invert_crown"
        private const val KEY_INVERT_PDF = "invert_pdf"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_SHOW_HUD_CLOCK = "show_hud_clock"
        private const val KEY_DEFAULT_FONT_SP = "default_font_sp"
        private const val KEY_DOC_SORT = "document_sort"
        private const val KEY_PROGRESS_PREFIX = "pos_"

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
