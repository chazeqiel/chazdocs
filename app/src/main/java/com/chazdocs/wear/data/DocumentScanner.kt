package com.chazdocs.wear.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.chazdocs.wear.engine.DocFormat
import com.chazdocs.wear.engine.DocumentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class ScanResult(
    val documents: List<DocumentItem>,
    val scannedPath: String,
    val hasAllFilesAccess: Boolean,
)

class DocumentScanner(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun getDocumentsDirectoryPath(): String {
        val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return docsDir?.absolutePath ?: "/sdcard/Documents"
    }

    suspend fun scanDocuments(sort: DocumentSort = DocumentSort.DATE): ScanResult = withContext(Dispatchers.IO) {
        val hasAllFiles = hasAllFilesAccess()
        val itemsByKey = LinkedHashMap<String, DocumentItem>()

        val candidateDirs = buildList {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let { add(it) }
            add(File("/sdcard/Documents"))
            add(File(Environment.getExternalStorageDirectory(), "Documents"))
        }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

        val primaryPath = candidateDirs.firstOrNull()?.absolutePath ?: "/sdcard/Documents"

        for (dir in candidateDirs) {
            if (dir.exists() && dir.isDirectory && dir.canRead()) {
                scanDirectoryRecursive(dir, dir, itemsByKey)
            }
        }

        scanMediaStoreDocuments(itemsByKey)

        ScanResult(
            documents = sortDocuments(itemsByKey.values.toList(), sort),
            scannedPath = primaryPath,
            hasAllFilesAccess = hasAllFiles,
        )
    }

    private fun scanDirectoryRecursive(
        rootDir: File,
        currentDir: File,
        out: MutableMap<String, DocumentItem>,
        depth: Int = 0,
    ) {
        if (depth > 6) return
        val children = currentDir.listFiles() ?: return
        for (file in children) {
            if (file.name.startsWith(".")) continue
            if (file.isDirectory) {
                scanDirectoryRecursive(rootDir, file, out, depth + 1)
            } else if (file.isFile) {
                val ext = file.extension.lowercase(Locale.getDefault())
                val format = DocFormat.fromExtension(ext) ?: continue
                val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                val id = canonical.hashCode().toUInt().toString(16) + "_" + file.name
                val savedPos = settingsRepository.getReadingPosition(id)
                val relParent = file.parentFile?.let { parent ->
                    runCatching { parent.relativeTo(rootDir).path }.getOrDefault("")
                }.orEmpty()
                val sizeText = formatBytes(file.length())
                val subtitle = if (relParent.isNotEmpty()) "$relParent • $sizeText" else sizeText

                out[canonical] = DocumentItem(
                    id = id,
                    title = file.nameWithoutExtension.ifBlank { file.name },
                    fileName = file.name,
                    path = canonical,
                    contentUriString = null,
                    format = format,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    lastOpenedMs = savedPos?.lastOpenedMs ?: 0L,
                    progressFraction = savedPos?.progressFraction ?: 0f,
                    subtitle = subtitle,
                )
            }
        }
    }

    private fun scanMediaStoreDocuments(out: MutableMap<String, DocumentItem>) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val projection = arrayListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Files.FileColumns.RELATIVE_PATH)
            }
            @Suppress("DEPRECATION")
            add(MediaStore.Files.FileColumns.DATA)
        }.toTypedArray()

        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?"
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Files.FileColumns.DATA} LIKE ?"
        }
        val selectionArgs = arrayOf("%Documents%")

        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val modIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                @Suppress("DEPRECATION")
                val dataIdx = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
                    val format = DocFormat.fromExtension(ext) ?: continue

                    val rowId = cursor.getLong(idIdx)
                    val uri: Uri = ContentUris.withAppendedId(collection, rowId)
                    val filePath = if (dataIdx >= 0) cursor.getString(dataIdx) else null
                    val key = filePath?.let { runCatching { File(it).canonicalPath }.getOrDefault(it) }
                        ?: uri.toString()

                    if (out.containsKey(key)) continue

                    val size = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                    val modMs = cursor.getLong(modIdx) * 1000L
                    val docId = key.hashCode().toUInt().toString(16) + "_" + name
                    val savedPos = settingsRepository.getReadingPosition(docId)

                    out[key] = DocumentItem(
                        id = docId,
                        title = name.substringBeforeLast('.', name),
                        fileName = name,
                        path = filePath?.takeIf { File(it).canRead() },
                        contentUriString = uri.toString(),
                        format = format,
                        sizeBytes = size,
                        lastModifiedMs = modMs,
                        lastOpenedMs = savedPos?.lastOpenedMs ?: 0L,
                        progressFraction = savedPos?.progressFraction ?: 0f,
                        subtitle = formatBytes(size),
                    )
                }
            }
        }
    }

    companion object {
        fun sortDocuments(docs: List<DocumentItem>, sort: DocumentSort): List<DocumentItem> {
            return when (sort) {
                DocumentSort.DATE -> docs.sortedWith(
                    compareByDescending<DocumentItem> { maxOf(it.lastOpenedMs, it.lastModifiedMs) }
                        .thenBy { it.title.lowercase(Locale.getDefault()) }
                )
                DocumentSort.NAME -> docs.sortedBy { it.title.lowercase(Locale.getDefault()) }
                DocumentSort.SIZE -> docs.sortedWith(
                    compareByDescending<DocumentItem> { it.sizeBytes }
                        .thenBy { it.title.lowercase(Locale.getDefault()) }
                )
            }
        }

        fun requestStorageAccess(
            activity: ComponentActivity,
            legacyLauncher: ActivityResultLauncher<String>,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    val manageIntent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${activity.packageName}"),
                    )
                    val fallbackIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    runCatching { activity.startActivity(manageIntent) }
                        .onFailure { runCatching { activity.startActivity(fallbackIntent) } }
                }
            } else {
                val granted = ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
