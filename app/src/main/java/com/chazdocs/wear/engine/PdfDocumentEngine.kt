package com.chazdocs.wear.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

private data class PageCacheKey(
    val pageIndex: Int,
    val renderWidthPx: Int,
    val inverted: Boolean,
)

class PdfDocumentEngine(
    private val context: Context,
    private val document: DocumentItem,
) : Closeable {

    private val renderMutex = Mutex()
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var pageAspectCache: FloatArray = FloatArray(0)

    private val cacheBytes = (Runtime.getRuntime().maxMemory() / 10L)
        .coerceIn(10L * 1024L * 1024L, 28L * 1024L * 1024L)
        .toInt()

    private val bitmapCache = object : LruCache<PageCacheKey, Bitmap>(cacheBytes) {
        override fun sizeOf(key: PageCacheKey, value: Bitmap): Int = value.allocationByteCount
    }

    var pageCount: Int = 0
        private set

    var tocEntries: List<TocEntry> = emptyList()
        private set

    suspend fun open(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            renderMutex.withLock {
                if (pdfRenderer != null) return@withLock
                val pfd = openFileDescriptor()
                fileDescriptor = pfd
                val renderer = PdfRenderer(pfd)
                pdfRenderer = renderer
                pageCount = renderer.pageCount
                pageAspectCache = FloatArray(pageCount) { -1f }
            }

            val parsedOutline = extractOutlineSafely()
            tocEntries = if (parsedOutline.isNotEmpty()) {
                parsedOutline
            } else {
                buildFallbackPageToc(pageCount)
            }
        }
    }

    private fun openFileDescriptor(): ParcelFileDescriptor {
        val path = document.path
        if (path != null) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val uriStr = document.contentUriString
            ?: throw IllegalStateException("No readable file path or URI for ${document.fileName}")
        return context.contentResolver.openFileDescriptor(Uri.parse(uriStr), "r")
            ?: throw IllegalStateException("Unable to open PDF descriptor")
    }

    fun openByteSource(): PdfByteSource? {
        val path = document.path
        if (path != null) {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return FilePdfByteSource(file)
            }
        }
        val uriStr = document.contentUriString ?: return null
        return context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
            InMemoryPdfByteSource(stream.readBytes())
        }
    }

    private fun extractOutlineSafely(): List<TocEntry> {
        return openByteSource()?.use { PdfOutlineParser.extractOutlines(it) } ?: emptyList()
    }

    fun getCachedAspectRatio(pageIndex: Int): Float {
        return pageAspectCache.getOrNull(pageIndex)?.takeIf { it > 0f } ?: 1.414f
    }

    suspend fun getPageAspectRatio(pageIndex: Int): Float = withContext(Dispatchers.IO) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext 1.414f
        val cached = pageAspectCache.getOrNull(pageIndex) ?: -1f
        if (cached > 0f) return@withContext cached

        renderMutex.withLock {
            val rechecked = pageAspectCache.getOrNull(pageIndex) ?: -1f
            if (rechecked > 0f) return@withLock rechecked
            val renderer = pdfRenderer ?: return@withLock 1.414f
            renderer.openPage(pageIndex).use { page ->
                val aspect = if (page.width > 0) {
                    (page.height.toFloat() / page.width.toFloat()).coerceIn(0.4f, 3.0f)
                } else {
                    1.414f
                }
                if (pageIndex < pageAspectCache.size) {
                    pageAspectCache[pageIndex] = aspect
                }
                aspect
            }
        }
    }

    suspend fun renderPage(
        pageIndex: Int,
        viewportWidthPx: Int,
        zoomFactor: Float,
        invertColors: Boolean,
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null

        val bucketZoom = when {
            zoomFactor <= 1.25f -> 1.0f
            zoomFactor <= 2.1f -> 1.75f
            else -> 2.5f
        }
        val targetWidth = (viewportWidthPx.coerceAtLeast(320) * bucketZoom)
            .toInt()
            .coerceAtMost(1350)

        val key = PageCacheKey(pageIndex, targetWidth, invertColors)
        bitmapCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        renderMutex.withLock {
            bitmapCache.get(key)?.let { cached ->
                if (!cached.isRecycled) return@withLock cached
            }
            val renderer = pdfRenderer ?: return@withLock null
            renderer.openPage(pageIndex).use { page ->
                val aspect = if (page.width > 0) page.height.toFloat() / page.width.toFloat() else 1.414f
                if (pageIndex < pageAspectCache.size) {
                    pageAspectCache[pageIndex] = aspect.coerceIn(0.4f, 3.0f)
                }
                val targetHeight = (targetWidth * aspect).toInt().coerceIn(200, 2200)

                val bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val finalBitmap = if (invertColors) {
                    applyColorInversion(bmp).also {
                        if (it !== bmp) bmp.recycle()
                    }
                } else {
                    bmp
                }
                bitmapCache.put(key, finalBitmap)
                finalBitmap
            }
        }
    }

    private fun applyColorInversion(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val invertMatrix = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(invertMatrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    override fun close() {
        bitmapCache.evictAll()
        runCatching { pdfRenderer?.close() }
        runCatching { fileDescriptor?.close() }
        pdfRenderer = null
        fileDescriptor = null
    }

    companion object {
        fun buildFallbackPageToc(totalPages: Int): List<TocEntry> {
            if (totalPages <= 0) return emptyList()
            val step = when {
                totalPages <= 30 -> 1
                totalPages <= 120 -> 5
                totalPages <= 400 -> 10
                else -> 25
            }
            val list = ArrayList<TocEntry>()
            var p = 0
            while (p < totalPages) {
                list.add(TocEntry(title = "Page ${p + 1}", level = 0, targetPage = p))
                p += step
            }
            if (list.lastOrNull()?.targetPage != totalPages - 1) {
                list.add(TocEntry(title = "Page $totalPages (End)", level = 0, targetPage = totalPages - 1))
            }
            return list
        }
    }
}
