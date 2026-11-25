package com.chazdocs.wear

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import com.chazdocs.wear.data.SettingsRepository
import com.chazdocs.wear.engine.DocFormat
import com.chazdocs.wear.engine.DocumentItem
import com.chazdocs.wear.ui.screens.DocumentReaderScreen
import com.chazdocs.wear.ui.theme.ChazDocsTheme

class ReaderActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private var insideDocumentActive by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository.getInstance(applicationContext)

        val document = parseDocumentFromIntent(intent)
        if (document == null) {
            finish()
            return
        }

        setContent {
            val preferences by settingsRepository.preferences.collectAsState()
            var anyOverlayOpen by remember { mutableStateOf(false) }

            LaunchedEffect(preferences.keepScreenOn) {
                if (preferences.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            LaunchedEffect(anyOverlayOpen) {
                insideDocumentActive = !anyOverlayOpen
                updateSystemGestureExclusion(!anyOverlayOpen)
            }

            ChazDocsTheme(readerTheme = preferences.readerTheme) {
                // Hide global AppScaffold TimeText inside the document so it never overlaps the zoom pill
                AppScaffold(
                    timeText = { if (anyOverlayOpen) androidx.wear.compose.material3.TimeText() },
                ) {
                    DocumentReaderScreen(
                        document = document,
                        preferences = preferences,
                        settingsRepository = settingsRepository,
                        onUpdatePreferences = settingsRepository::updatePreferences,
                        onOverlayStateChanged = { overlayOpen -> anyOverlayOpen = overlayOpen },
                        onOpenSettings = {
                            startActivity(Intent(this@ReaderActivity, SettingsActivity::class.java))
                        },
                        onExitToHome = { finish() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateSystemGestureExclusion(insideDocumentActive)
    }

    private fun updateSystemGestureExclusion(blockBackSwipe: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.decorView.post {
                if (blockBackSwipe) {
                    val w = window.decorView.width
                    val h = window.decorView.height
                    if (w > 0 && h > 0) {
                        window.decorView.systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
                    }
                } else {
                    window.decorView.systemGestureExclusionRects = emptyList()
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "doc_id"
        private const val EXTRA_TITLE = "doc_title"
        private const val EXTRA_FILE_NAME = "doc_file_name"
        private const val EXTRA_PATH = "doc_path"
        private const val EXTRA_URI = "doc_uri"
        private const val EXTRA_FORMAT = "doc_format"
        private const val EXTRA_SIZE = "doc_size"
        private const val EXTRA_MODIFIED = "doc_modified"

        fun createIntent(context: Context, doc: DocumentItem): Intent {
            return Intent(context, ReaderActivity::class.java).apply {
                putExtra(EXTRA_ID, doc.id)
                putExtra(EXTRA_TITLE, doc.title)
                putExtra(EXTRA_FILE_NAME, doc.fileName)
                putExtra(EXTRA_PATH, doc.path)
                putExtra(EXTRA_URI, doc.contentUriString)
                putExtra(EXTRA_FORMAT, doc.format.name)
                putExtra(EXTRA_SIZE, doc.sizeBytes)
                putExtra(EXTRA_MODIFIED, doc.lastModifiedMs)
            }
        }

        private fun parseDocumentFromIntent(intent: Intent?): DocumentItem? {
            intent ?: return null
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Document"
            val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: title
            val formatName = intent.getStringExtra(EXTRA_FORMAT) ?: return null
            val format = DocFormat.entries.firstOrNull { it.name == formatName } ?: return null
            return DocumentItem(
                id = id,
                title = title,
                fileName = fileName,
                path = intent.getStringExtra(EXTRA_PATH),
                contentUriString = intent.getStringExtra(EXTRA_URI),
                format = format,
                sizeBytes = intent.getLongExtra(EXTRA_SIZE, 0L),
                lastModifiedMs = intent.getLongExtra(EXTRA_MODIFIED, 0L),
            )
        }
    }
}
