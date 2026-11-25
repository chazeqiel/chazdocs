package com.chazdocs.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.compose.material3.AppScaffold
import com.chazdocs.wear.data.DocumentScanner
import com.chazdocs.wear.data.SettingsRepository
import com.chazdocs.wear.ui.screens.SettingsScreen
import com.chazdocs.wear.ui.theme.ChazDocsTheme

class SettingsActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var documentScanner: DocumentScanner
    private var hasAllFilesAccess by mutableStateOf(true)

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasAllFilesAccess = documentScanner.hasAllFilesAccess() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        documentScanner = DocumentScanner(applicationContext, settingsRepository)
        hasAllFilesAccess = documentScanner.hasAllFilesAccess()

        setContent {
            val preferences by settingsRepository.preferences.collectAsState()
            ChazDocsTheme(readerTheme = preferences.readerTheme) {
                AppScaffold {
                    SettingsScreen(
                        preferences = preferences,
                        hasAllFilesAccess = hasAllFilesAccess,
                        documentsPath = documentScanner.getDocumentsDirectoryPath(),
                        onUpdatePreferences = settingsRepository::updatePreferences,
                        onRequestStoragePermission = {
                            DocumentScanner.requestStorageAccess(
                                activity = this@SettingsActivity,
                                legacyLauncher = legacyPermissionLauncher,
                            )
                        },
                        onBack = { finish() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasAllFilesAccess = documentScanner.hasAllFilesAccess()
    }
}
