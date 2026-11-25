package com.chazdocs.wear

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material3.AppScaffold
import com.chazdocs.wear.data.DocumentScanner
import com.chazdocs.wear.data.ScanResult
import com.chazdocs.wear.data.SettingsRepository
import com.chazdocs.wear.engine.DocumentItem
import com.chazdocs.wear.ui.screens.HomeScreen
import com.chazdocs.wear.ui.theme.ChazDocsTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var documentScanner: DocumentScanner

    private var scanResultState by mutableStateOf<ScanResult?>(null)
    private var isScanningState by mutableStateOf(false)

    private val legacyPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshLibrary() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository.getInstance(applicationContext)
        documentScanner = DocumentScanner(applicationContext, settingsRepository)
        scanResultState = ScanResult(
            documents = emptyList(),
            scannedPath = documentScanner.getDocumentsDirectoryPath(),
            hasAllFilesAccess = documentScanner.hasAllFilesAccess(),
        )

        setContent {
            val preferences by settingsRepository.preferences.collectAsState()
            val scanResult = scanResultState ?: ScanResult(
                documents = emptyList(),
                scannedPath = documentScanner.getDocumentsDirectoryPath(),
                hasAllFilesAccess = documentScanner.hasAllFilesAccess(),
            )

            ChazDocsTheme(readerTheme = preferences.readerTheme) {
                AppScaffold {
                    HomeScreen(
                        scanResult = scanResult,
                        currentSort = preferences.documentSort,
                        isScanning = isScanningState,
                        onRefresh = ::refreshLibrary,
                        onChangeSort = { newSort ->
                            settingsRepository.updatePreferences { it.copy(documentSort = newSort) }
                        },
                        onOpenDocument = ::openReader,
                        onOpenSettings = {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        },
                        onRequestStoragePermission = {
                            DocumentScanner.requestStorageAccess(
                                activity = this@MainActivity,
                                legacyLauncher = legacyPermissionLauncher,
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLibrary()
    }

    private fun refreshLibrary() {
        lifecycleScope.launch {
            isScanningState = true
            scanResultState = documentScanner.scanDocuments(settingsRepository.preferences.value.documentSort)
            isScanningState = false
        }
    }

    private fun openReader(doc: DocumentItem) {
        startActivity(ReaderActivity.createIntent(this, doc))
    }
}
