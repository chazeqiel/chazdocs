package com.chazdocs.wear.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.chazdocs.wear.data.AppPreferences
import com.chazdocs.wear.data.CrownReaderMode
import com.chazdocs.wear.data.ReaderTheme
import com.chazdocs.wear.data.ReadingPosition
import com.chazdocs.wear.data.SettingsRepository
import com.chazdocs.wear.data.TextZoomMode
import com.chazdocs.wear.engine.BlockStyle
import com.chazdocs.wear.engine.DocFormat
import com.chazdocs.wear.engine.DocumentItem
import com.chazdocs.wear.engine.EpubDocumentEngine
import com.chazdocs.wear.engine.OfficeDocumentEngine
import com.chazdocs.wear.engine.PdfDocumentEngine
import com.chazdocs.wear.engine.ReflowBlock
import com.chazdocs.wear.engine.TextChapter
import com.chazdocs.wear.engine.TextDocumentEngine
import com.chazdocs.wear.engine.TocEntry
import com.chazdocs.wear.ui.components.ChazIcons
import com.chazdocs.wear.ui.components.RotaryZoomMath
import com.chazdocs.wear.ui.components.detectDocumentMultiTap
import com.chazdocs.wear.ui.components.detectPdfPinchAndPan
import com.chazdocs.wear.ui.theme.LocalReaderPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun DocumentReaderScreen(
    document: DocumentItem,
    preferences: AppPreferences,
    settingsRepository: SettingsRepository,
    onUpdatePreferences: ((AppPreferences) -> AppPreferences) -> Unit,
    onOverlayStateChanged: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onExitToHome: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val palette = LocalReaderPalette.current
    val scope = rememberCoroutineScope()

    val savedPos = remember(document.id) { settingsRepository.getReadingPosition(document.id) }

    var isLoading by remember(document.id) { mutableStateOf(true) }
    var errorMessage by remember(document.id) { mutableStateOf<String?>(null) }

    var tocEntries by remember(document.id) { mutableStateOf<List<TocEntry>>(emptyList()) }
    var showToc by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showHud by remember { mutableStateOf(false) }
    var showZoomBadge by remember { mutableStateOf(false) }
    var zoomBadgeEpoch by remember { mutableIntStateOf(0) }
    var activeSearchQuery by remember { mutableStateOf("") }

    val anyOverlayOpen = showToc || showMenu || showSearch
    LaunchedEffect(anyOverlayOpen) {
        onOverlayStateChanged(anyOverlayOpen)
    }

    // PDF continuous state
    var pdfEngine by remember(document.id) { mutableStateOf<PdfDocumentEngine?>(null) }
    var pdfPageCount by remember(document.id) { mutableIntStateOf(0) }
    val pdfListState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPos?.pageIndex ?: 0,
        initialFirstVisibleItemScrollOffset = savedPos?.scrollOffsetPx ?: 0,
    )

    // Chaptered (EPUB / PPTX) and Reflowable (DOCX / TXT / MD) state
    var chapters by remember(document.id) { mutableStateOf<List<TextChapter>>(emptyList()) }
    var textBlocks by remember(document.id) { mutableStateOf<List<ReflowBlock>>(emptyList()) }
    var currentChapter by remember(document.id) { mutableIntStateOf(savedPos?.chapterIndex ?: 0) }
    val reflowListState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedPos?.blockIndex ?: 0,
        initialFirstVisibleItemScrollOffset = savedPos?.scrollOffsetPx ?: 0,
    )

    // Zoom & font state
    var viewportSize by remember { mutableStateOf(IntSize(450, 450)) }
    var gpuZoom by remember(document.id) {
        val initScale = savedPos?.zoomScale?.coerceIn(1f, 4.5f) ?: 1f
        mutableStateOf(
            RotaryZoomMath.GpuZoomState(
                scale = initScale,
                tx = -225f * (initScale - 1f),
                ty = -225f * (initScale - 1f),
            )
        )
    }
    var canvasScale by remember(document.id) {
        mutableFloatStateOf(savedPos?.zoomScale?.coerceIn(1f, 4.5f) ?: 1f)
    }
    var settledPdfScale by remember(document.id) {
        mutableFloatStateOf(savedPos?.zoomScale?.coerceIn(1f, 4.5f) ?: 1f)
    }
    var fontSizeSp by remember(document.id) {
        mutableFloatStateOf(savedPos?.fontSizeSp?.coerceIn(10f, 28f) ?: preferences.defaultFontSizeSp)
    }
    var panOffset by remember(document.id) { mutableStateOf(Offset.Zero) }

    val activeScale = if (document.format == DocFormat.PDF) gpuZoom.scale else canvasScale

    val currentPdfPage by remember {
        derivedStateOf {
            val layoutInfo = pdfListState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                pdfListState.firstVisibleItemIndex
            } else {
                val centerLazyY = ((viewportSize.height / 2f) - gpuZoom.ty) / gpuZoom.scale.coerceAtLeast(1f)
                visible.minByOrNull { abs((it.offset + it.size / 2f) - centerLazyY) }?.index
                    ?: pdfListState.firstVisibleItemIndex
            }
        }
    }

    val readerFocusRequester = remember { FocusRequester() }
    var lastHapticTimeMs by remember { mutableStateOf(0L) }
    var pdfFlingJob by remember { mutableStateOf<Job?>(null) }

    val isChaptered = document.format == DocFormat.EPUB || document.format == DocFormat.PPTX

    fun applyPdfGpuZoom(
        newScale: Float,
        anchorX: Float,
        anchorY: Float,
        panX: Float = 0f,
        panY: Float = 0f,
    ) {
        pdfFlingJob?.cancel()
        gpuZoom = RotaryZoomMath.computeGpuZoom(
            current = gpuZoom,
            newScale = newScale,
            anchorX = anchorX,
            anchorY = anchorY,
            panX = panX,
            panY = panY,
            viewportWidth = viewportSize.width.coerceAtLeast(1).toFloat(),
            viewportHeight = viewportSize.height.coerceAtLeast(1).toFloat(),
        )
        canvasScale = gpuZoom.scale
    }

    fun applyPdfGpuPan(dx: Float, dy: Float) {
        val result = RotaryZoomMath.computeGpuPan(
            current = gpuZoom,
            dragX = dx,
            dragY = dy,
            viewportWidth = viewportSize.width.coerceAtLeast(1).toFloat(),
            viewportHeight = viewportSize.height.coerceAtLeast(1).toFloat(),
        )
        gpuZoom = result.state
        if (abs(result.verticalScrollOverflowPx) > 0.01f) {
            pdfListState.dispatchRawDelta(result.verticalScrollOverflowPx)
        }
    }

    fun startPdfFling(vx: Float, vy: Float) {
        pdfFlingJob?.cancel()
        pdfFlingJob = scope.launch {
            var prev = Offset.Zero
            AnimationState(
                typeConverter = Offset.VectorConverter,
                initialValue = Offset.Zero,
                initialVelocity = Offset(vx, vy),
            ).animateDecay(
                animationSpec = exponentialDecay(frictionMultiplier = 1.25f),
            ) {
                val delta = value - prev
                prev = value
                applyPdfGpuPan(delta.x, delta.y)
            }
        }
    }

    // Inside the document (no overlay open), consume Back so swipe-back cannot close the document.
    BackHandler(enabled = !anyOverlayOpen) {
        if (showHud) showHud = false
    }

    fun persistProgress() {
        val totalUnits = when {
            document.format == DocFormat.PDF -> pdfPageCount.coerceAtLeast(1)
            isChaptered -> chapters.size.coerceAtLeast(1)
            else -> textBlocks.size.coerceAtLeast(1)
        }
        val pageIdx = if (document.format == DocFormat.PDF) {
            currentPdfPage.coerceIn(0, (pdfPageCount - 1).coerceAtLeast(0))
        } else {
            0
        }
        val blockIdx = if (document.format == DocFormat.PDF) 0 else reflowListState.firstVisibleItemIndex
        val scrollOff = if (document.format == DocFormat.PDF) {
            pdfListState.firstVisibleItemScrollOffset
        } else {
            reflowListState.firstVisibleItemScrollOffset
        }
        val pos = ReadingPosition(
            pageIndex = pageIdx,
            chapterIndex = currentChapter,
            blockIndex = blockIdx,
            scrollOffsetPx = scrollOff,
            zoomScale = canvasScale,
            fontSizeSp = fontSizeSp,
            lastOpenedMs = System.currentTimeMillis(),
            totalUnits = totalUnits,
        )
        settingsRepository.saveReadingPosition(document.id, pos)
    }

    // Load document content
    DisposableEffect(document.id) {
        val job = scope.launch {
            isLoading = true
            errorMessage = null
            when (document.format) {
                DocFormat.PDF -> {
                    val engine = PdfDocumentEngine(context, document)
                    val res = engine.open()
                    res.onSuccess {
                        pdfEngine = engine
                        pdfPageCount = engine.pageCount
                        tocEntries = engine.tocEntries
                        isLoading = false
                    }.onFailure { err ->
                        engine.close()
                        errorMessage = err.message ?: "Failed to open PDF"
                        isLoading = false
                    }
                }
                DocFormat.EPUB -> {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            openDocStream(context, document).use { stream ->
                                EpubDocumentEngine.parseFromStream(stream, document.title)
                            }
                        }
                    }
                    res.onSuccess { book ->
                        chapters = book.chapters
                        currentChapter = currentChapter.coerceIn(0, (book.chapters.size - 1).coerceAtLeast(0))
                        textBlocks = book.chapters.getOrNull(currentChapter)?.blocks ?: emptyList()
                        tocEntries = book.tocEntries
                        isLoading = false
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Failed to parse EPUB"
                        isLoading = false
                    }
                }
                DocFormat.PPTX -> {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            openDocStream(context, document).use { stream ->
                                OfficeDocumentEngine.parsePptxFromStream(stream)
                            }
                        }
                    }
                    res.onSuccess { deck ->
                        chapters = deck.slides
                        currentChapter = currentChapter.coerceIn(0, (deck.slides.size - 1).coerceAtLeast(0))
                        textBlocks = deck.slides.getOrNull(currentChapter)?.blocks ?: emptyList()
                        tocEntries = deck.tocEntries
                        isLoading = false
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Failed to parse PPTX"
                        isLoading = false
                    }
                }
                DocFormat.DOCX -> {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            openDocStream(context, document).use { stream ->
                                OfficeDocumentEngine.parseDocxFromStream(stream)
                            }
                        }
                    }
                    res.onSuccess { parsed ->
                        textBlocks = parsed.blocks
                        tocEntries = parsed.tocEntries
                        isLoading = false
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Failed to parse DOCX"
                        isLoading = false
                    }
                }
                DocFormat.MARKDOWN, DocFormat.TEXT -> {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            openDocStream(context, document).use { stream ->
                                TextDocumentEngine.parseFromStream(
                                    inputStream = stream,
                                    isMarkdown = document.format == DocFormat.MARKDOWN,
                                )
                            }
                        }
                    }
                    res.onSuccess { parsed ->
                        textBlocks = parsed.blocks
                        tocEntries = parsed.tocEntries
                        isLoading = false
                    }.onFailure { err ->
                        errorMessage = err.message ?: "Failed to read file"
                        isLoading = false
                    }
                }
            }
        }
        onDispose {
            job.cancel()
            persistProgress()
            pdfEngine?.close()
        }
    }

    // Debounce crisp PDF re-render scale while zooming
    LaunchedEffect(canvasScale) {
        delay(150L)
        settledPdfScale = canvasScale
    }

    // Update chapter blocks when EPUB/PPTX chapter changes
    LaunchedEffect(chapters, currentChapter) {
        if (isChaptered) {
            textBlocks = chapters.getOrNull(currentChapter)?.blocks ?: emptyList()
        }
    }

    // Auto-save progress periodically
    LaunchedEffect(
        currentPdfPage,
        currentChapter,
        reflowListState.firstVisibleItemIndex,
        canvasScale,
        fontSizeSp,
    ) {
        delay(600L)
        if (!isLoading && errorMessage == null) {
            persistProgress()
        }
    }

    // Request rotary focus ONLY when NO overlay is open; when an overlay opens,
    // the reader Box drops its focusable() modifier so the overlay's TransformingLazyColumn
    // gets uncontested rotary crown scrolling.
    LaunchedEffect(lifecycleOwner, anyOverlayOpen, isLoading) {
        if (!anyOverlayOpen && !isLoading) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                delay(40L)
                runCatching { readerFocusRequester.requestFocus() }
            }
        }
    }

    LaunchedEffect(showHud) {
        if (showHud) {
            delay(2800L)
            showHud = false
        }
    }
    LaunchedEffect(zoomBadgeEpoch) {
        if (zoomBadgeEpoch > 0) {
            showZoomBadge = true
            delay(1400L)
            showZoomBadge = false
        }
    }

    val isCanvasZoomMode = document.format == DocFormat.PDF ||
        preferences.textZoomMode == TextZoomMode.CANVAS_ZOOM

    val zoomLabel = if (isCanvasZoomMode) {
        "${((activeScale * 10f).roundToInt() / 10f)}x"
    } else {
        "${fontSizeSp.roundToInt()}sp"
    }

    val positionLabel = when {
        document.format == DocFormat.PDF -> "${currentPdfPage + 1}/${pdfPageCount.coerceAtLeast(1)}"
        document.format == DocFormat.PPTX -> "S.${currentChapter + 1}/${chapters.size.coerceAtLeast(1)}"
        document.format == DocFormat.EPUB -> "Ch.${currentChapter + 1}/${chapters.size.coerceAtLeast(1)}"
        else -> {
            val total = textBlocks.size.coerceAtLeast(1)
            val pct = ((reflowListState.firstVisibleItemIndex.toFloat() / (total - 1).coerceAtLeast(1)) * 100f)
                .roundToInt()
                .coerceIn(0, 100)
            "$pct%"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Document viewport (only captures rotary focus when NO overlay is open)
        val readerRotaryModifier = if (!anyOverlayOpen) {
            Modifier
                .onRotaryScrollEvent { event ->
                    if (preferences.crownReaderMode == CrownReaderMode.SCROLL) {
                        val deltaPx = RotaryZoomMath.computeScrollDeltaPx(
                            verticalScrollPixels = event.verticalScrollPixels,
                            sensitivity = preferences.crownSensitivity,
                            invertCrown = preferences.invertCrown,
                        )
                        if (document.format == DocFormat.PDF) {
                            if (gpuZoom.scale > 1.02f) {
                                applyPdfGpuPan(dx = 0f, dy = -deltaPx)
                            } else {
                                pdfListState.dispatchRawDelta(deltaPx)
                            }
                        } else {
                            reflowListState.dispatchRawDelta(deltaPx)
                        }
                    } else {
                        if (isCanvasZoomMode) {
                            val updated = RotaryZoomMath.computeNewCanvasScale(
                                currentScale = activeScale,
                                verticalScrollPixels = event.verticalScrollPixels,
                                sensitivity = preferences.crownSensitivity,
                                invertCrown = preferences.invertCrown,
                            )
                            if (updated != activeScale) {
                                if (document.format == DocFormat.PDF) {
                                    applyPdfGpuZoom(
                                        newScale = updated,
                                        anchorX = viewportSize.width / 2f,
                                        anchorY = viewportSize.height / 2f,
                                    )
                                } else {
                                    canvasScale = updated
                                    panOffset = clampPan(panOffset, updated, viewportSize)
                                }
                            }
                        } else {
                            fontSizeSp = RotaryZoomMath.computeNewFontSizeSp(
                                currentSp = fontSizeSp,
                                verticalScrollPixels = event.verticalScrollPixels,
                                sensitivity = preferences.crownSensitivity,
                                invertCrown = preferences.invertCrown,
                            )
                        }
                        zoomBadgeEpoch++
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastHapticTimeMs > 90L) {
                        lastHapticTimeMs = now
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    true
                }
                .focusRequester(readerFocusRequester)
                .focusable()
        } else {
            Modifier
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (document.format == DocFormat.PDF && !preferences.invertPdfColors) Color(0xFF101216) else palette.background)
                .onSizeChanged { viewportSize = it }
                .then(readerRotaryModifier)
                .detectDocumentMultiTap(
                    enabled = !anyOverlayOpen && !isLoading && errorMessage == null,
                    onSingleTap = { showHud = !showHud },
                    onDoubleTap = {
                        showHud = false
                        showToc = true
                    },
                    onTripleTap = {
                        showHud = false
                        showMenu = true
                    },
                ),
        ) {
            when {
                isLoading -> {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Opening ${document.format.label}…",
                            color = palette.muted,
                            fontSize = 13.sp,
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                    ) {
                        Text(
                            text = errorMessage ?: "Error",
                            color = Color(0xFFFF7A7A),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onExitToHome,
                            label = { Text("Main Menu", fontSize = 12.sp) },
                        )
                    }
                }
                document.format == DocFormat.PDF -> {
                    PdfContinuousViewport(
                        pdfEngine = pdfEngine,
                        pageCount = pdfPageCount,
                        listState = pdfListState,
                        gpuZoom = gpuZoom,
                        settledScale = settledPdfScale,
                        invertColors = preferences.invertPdfColors,
                        viewportSize = viewportSize,
                        onPointerDown = { pdfFlingJob?.cancel() },
                        onPinchZoom = { zoomChange, centroid, pan ->
                            applyPdfGpuZoom(
                                newScale = gpuZoom.scale * zoomChange,
                                anchorX = centroid.x,
                                anchorY = centroid.y,
                                panX = pan.x,
                                panY = pan.y,
                            )
                            zoomBadgeEpoch++
                        },
                        onZoomedPan = { dx, dy ->
                            applyPdfGpuPan(dx, dy)
                        },
                        onZoomedFling = { vx, vy ->
                            startPdfFling(vx, vy)
                        },
                    )
                }
                else -> {
                    ReflowTextViewport(
                        blocks = textBlocks,
                        fontSizeSp = fontSizeSp,
                        canvasScale = if (preferences.textZoomMode == TextZoomMode.CANVAS_ZOOM) canvasScale else 1f,
                        panOffset = if (preferences.textZoomMode == TextZoomMode.CANVAS_ZOOM) panOffset else Offset.Zero,
                        listState = reflowListState,
                        highlightQuery = activeSearchQuery,
                        hasNextChapter = isChaptered && currentChapter + 1 < chapters.size,
                        hasPrevChapter = isChaptered && currentChapter > 0,
                        nextButtonLabel = if (document.format == DocFormat.PPTX) "Next Slide" else "Next Ch",
                        onPrevChapter = {
                            if (currentChapter > 0) {
                                currentChapter--
                                scope.launch { reflowListState.scrollToItem(0) }
                            }
                        },
                        onNextChapter = {
                            val maxCh = chapters.size - 1
                            if (currentChapter < maxCh) {
                                currentChapter++
                                scope.launch { reflowListState.scrollToItem(0) }
                            }
                        },
                        onTransformPanZoom = { zoomChange, panChange ->
                            if (preferences.textZoomMode == TextZoomMode.CANVAS_ZOOM) {
                                val newScale = (canvasScale * zoomChange).coerceIn(1f, 3.5f)
                                canvasScale = newScale
                                panOffset = clampPan(panOffset + panChange, newScale, viewportSize)
                                if (zoomChange != 1f) zoomBadgeEpoch++
                            }
                        },
                    )
                }
            }

            // Always-visible compact bottom page indicator for PDFs (and subtle chapter indicator for chaptered docs)
            if (!isLoading && errorMessage == null && !anyOverlayOpen && document.format == DocFormat.PDF) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 9.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = positionLabel,
                        color = Color(0xFFD8DEE9),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            // Transient Zoom Badge when turning crown in Zoom mode
            AnimatedVisibility(
                visible = showZoomBadge && !anyOverlayOpen,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.84f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (isCanvasZoomMode) "Zoom $zoomLabel" else "Size $zoomLabel",
                        color = Color(0xFF7CC4FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // Single-Tap Minimal HUD
            AnimatedVisibility(
                visible = showHud && !anyOverlayOpen && !isLoading,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.84f))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        val clockPrefix = if (preferences.showHudClock) {
                            val timeStr = remember(showHud) {
                                DateFormat.getTimeFormat(context).format(Date())
                            }
                            "$timeStr • "
                        } else {
                            ""
                        }
                        val hudPos = if (document.format == DocFormat.PDF) "" else "$positionLabel • "
                        Text(
                            text = "$clockPrefix$hudPos$zoomLabel • ${preferences.crownReaderMode.label}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    if (isChaptered) {
                        val canPrev = currentChapter > 0
                        val canNext = currentChapter + 1 < chapters.size
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 14.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.84f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            IconButton(
                                onClick = {
                                    if (currentChapter > 0) {
                                        currentChapter--
                                        scope.launch { reflowListState.scrollToItem(0) }
                                    }
                                },
                                enabled = canPrev,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = ChazIcons.ChevronLeft,
                                    contentDescription = "Previous",
                                    modifier = Modifier.size(18.dp),
                                )
                            }

                            Text(
                                text = positionLabel,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )

                            IconButton(
                                onClick = {
                                    if (currentChapter + 1 < chapters.size) {
                                        currentChapter++
                                        scope.launch { reflowListState.scrollToItem(0) }
                                    }
                                },
                                enabled = canNext,
                                modifier = Modifier.size(34.dp),
                            ) {
                                Icon(
                                    imageVector = ChazIcons.ChevronRight,
                                    contentDescription = "Next",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Sibling Overlays (outside reader's focusable Box so TransformingLazyColumn gets rotary focus cleanly!)
        if (showToc) {
            TocOverlay(
                entries = tocEntries,
                format = document.format,
                currentPage = currentPdfPage,
                currentChapter = currentChapter,
                onSelectEntry = { entry ->
                    when {
                        document.format == DocFormat.PDF -> {
                            val target = entry.targetPage.coerceIn(0, (pdfPageCount - 1).coerceAtLeast(0))
                            scope.launch { pdfListState.scrollToItem(target) }
                        }
                        isChaptered -> {
                            val maxCh = (chapters.size - 1).coerceAtLeast(0)
                            currentChapter = entry.targetChapter.coerceIn(0, maxCh)
                            scope.launch { reflowListState.scrollToItem(0) }
                        }
                        else -> {
                            val maxIdx = (textBlocks.size - 1).coerceAtLeast(0)
                            val target = entry.targetBlockIndex.coerceIn(0, maxIdx)
                            scope.launch { reflowListState.scrollToItem(target) }
                        }
                    }
                    showToc = false
                },
                onDismiss = { showToc = false },
            )
        }

        if (showMenu) {
            ReaderMenuOverlay(
                documentTitle = document.title,
                format = document.format,
                positionSummary = positionLabel,
                zoomSummary = zoomLabel,
                preferences = preferences,
                onMainMenu = {
                    persistProgress()
                    showMenu = false
                    onExitToHome()
                },
                onOpenSearch = {
                    showMenu = false
                    showSearch = true
                },
                onOpenSettings = {
                    persistProgress()
                    showMenu = false
                    onOpenSettings()
                },
                onOpenToc = {
                    showMenu = false
                    showToc = true
                },
                onToggleCrownMode = {
                    onUpdatePreferences { prefs ->
                        val next = if (prefs.crownReaderMode == CrownReaderMode.ZOOM) {
                            CrownReaderMode.SCROLL
                        } else {
                            CrownReaderMode.ZOOM
                        }
                        prefs.copy(crownReaderMode = next)
                    }
                },
                onResetZoom = {
                    gpuZoom = RotaryZoomMath.GpuZoomState()
                    canvasScale = 1f
                    settledPdfScale = 1f
                    panOffset = Offset.Zero
                    fontSizeSp = preferences.defaultFontSizeSp
                    showMenu = false
                },
                onCycleTheme = {
                    onUpdatePreferences { prefs ->
                        val next = ReaderTheme.entries[(prefs.readerTheme.ordinal + 1) % ReaderTheme.entries.size]
                        prefs.copy(readerTheme = next)
                    }
                },
                onToggleInvertPdf = { checked ->
                    onUpdatePreferences { it.copy(invertPdfColors = checked) }
                },
                onDismiss = { showMenu = false },
            )
        }

        if (showSearch) {
            DocumentSearchOverlay(
                format = document.format,
                pdfEngine = pdfEngine,
                chapters = chapters,
                blocks = textBlocks,
                initialQuery = activeSearchQuery,
                onSelectHit = { hit, query ->
                    activeSearchQuery = query
                    when {
                        document.format == DocFormat.PDF -> {
                            val target = hit.targetPage.coerceIn(0, (pdfPageCount - 1).coerceAtLeast(0))
                            scope.launch { pdfListState.scrollToItem(target) }
                        }
                        isChaptered -> {
                            val maxCh = (chapters.size - 1).coerceAtLeast(0)
                            currentChapter = hit.targetChapter.coerceIn(0, maxCh)
                            scope.launch {
                                delay(30L)
                                reflowListState.scrollToItem(hit.targetBlockIndex.coerceAtLeast(0))
                            }
                        }
                        else -> {
                            val maxIdx = (textBlocks.size - 1).coerceAtLeast(0)
                            scope.launch {
                                reflowListState.scrollToItem(hit.targetBlockIndex.coerceIn(0, maxIdx))
                            }
                        }
                    }
                    showSearch = false
                },
                onDismiss = { showSearch = false },
            )
        }
    }
}

@Composable
private fun PdfContinuousViewport(
    pdfEngine: PdfDocumentEngine?,
    pageCount: Int,
    listState: LazyListState,
    gpuZoom: RotaryZoomMath.GpuZoomState,
    settledScale: Float,
    invertColors: Boolean,
    viewportSize: IntSize,
    onPointerDown: () -> Unit,
    onPinchZoom: (Float, Offset, Offset) -> Unit,
    onZoomedPan: (Float, Float) -> Unit,
    onZoomedFling: (Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val baseWidthDp = with(density) { viewportSize.width.coerceAtLeast(320).toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .detectPdfPinchAndPan(
                isZoomed = gpuZoom.scale > 1.02f,
                onPointerDown = onPointerDown,
                onPinch = onPinchZoom,
                onZoomedPan = onZoomedPan,
                onZoomedFling = onZoomedFling,
            ),
    ) {
        LazyColumn(
            state = listState,
            userScrollEnabled = gpuZoom.scale <= 1.02f,
            contentPadding = PaddingValues(top = 6.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = gpuZoom.scale
                    scaleY = gpuZoom.scale
                    translationX = gpuZoom.tx
                    translationY = gpuZoom.ty
                },
        ) {
            items(
                count = pageCount,
                key = { pageIndex -> "pdf_page_$pageIndex" },
            ) { pageIndex ->
                PdfLazyPageItem(
                    pdfEngine = pdfEngine,
                    pageIndex = pageIndex,
                    pageWidthDp = baseWidthDp,
                    viewportWidthPx = viewportSize.width,
                    settledScale = settledScale,
                    invertColors = invertColors,
                )
            }
        }
    }
}

@Composable
private fun PdfLazyPageItem(
    pdfEngine: PdfDocumentEngine?,
    pageIndex: Int,
    pageWidthDp: androidx.compose.ui.unit.Dp,
    viewportWidthPx: Int,
    settledScale: Float,
    invertColors: Boolean,
) {
    var aspectRatio by remember(pageIndex) { mutableFloatStateOf(1.414f) }
    var bitmap by remember(pageIndex, invertColors) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(pdfEngine, pageIndex) {
        val engine = pdfEngine ?: return@LaunchedEffect
        aspectRatio = engine.getPageAspectRatio(pageIndex)
    }

    LaunchedEffect(pdfEngine, pageIndex, viewportWidthPx, settledScale, invertColors) {
        val engine = pdfEngine ?: return@LaunchedEffect
        if (viewportWidthPx <= 0) return@LaunchedEffect
        bitmap = engine.renderPage(
            pageIndex = pageIndex,
            viewportWidthPx = viewportWidthPx,
            zoomFactor = settledScale,
            invertColors = invertColors,
        )
    }

    val pageHeightDp = pageWidthDp * aspectRatio
    val pageBg = if (invertColors) Color(0xFF16181D) else Color.White

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(pageWidthDp)
            .height(pageHeightDp)
            .background(pageBg),
    ) {
        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "Page ${pageIndex + 1}",
                color = Color(0xFF7A8494),
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ReflowTextViewport(
    blocks: List<ReflowBlock>,
    fontSizeSp: Float,
    canvasScale: Float,
    panOffset: Offset,
    listState: LazyListState,
    highlightQuery: String,
    hasNextChapter: Boolean,
    hasPrevChapter: Boolean,
    nextButtonLabel: String,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onTransformPanZoom: (zoomChange: Float, panChange: Offset) -> Unit,
) {
    val palette = LocalReaderPalette.current
    val canvasZoomModifier = if (canvasScale > 1.01f) {
        Modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransformPanZoom(zoom, pan)
                }
            }
            .graphicsLayer(
                scaleX = canvasScale,
                scaleY = canvasScale,
                translationX = panOffset.x,
                translationY = panOffset.y,
            )
    } else {
        Modifier
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 42.dp,
            bottom = 52.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxSize()
            .then(canvasZoomModifier),
    ) {
        items(blocks) { block ->
            val annotatedText = remember(block.text, highlightQuery, palette.accent) {
                buildHighlightedText(block.text, highlightQuery, palette.accent)
            }
            when (block.style) {
                BlockStyle.H1 -> Text(
                    text = annotatedText,
                    color = palette.accent,
                    fontSize = (fontSizeSp + 4f).sp,
                    lineHeight = (fontSizeSp * 1.35f + 5f).sp,
                    fontWeight = FontWeight.Bold,
                )
                BlockStyle.H2 -> Text(
                    text = annotatedText,
                    color = palette.accent,
                    fontSize = (fontSizeSp + 2.5f).sp,
                    lineHeight = (fontSizeSp * 1.32f + 3f).sp,
                    fontWeight = FontWeight.SemiBold,
                )
                BlockStyle.H3 -> Text(
                    text = annotatedText,
                    color = palette.onBackground,
                    fontSize = (fontSizeSp + 1f).sp,
                    lineHeight = (fontSizeSp * 1.32f + 2f).sp,
                    fontWeight = FontWeight.SemiBold,
                )
                BlockStyle.QUOTE -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surface)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = annotatedText,
                        color = palette.muted,
                        fontSize = (fontSizeSp - 0.5f).coerceAtLeast(9f).sp,
                        lineHeight = (fontSizeSp * 1.35f).sp,
                        fontStyle = FontStyle.Italic,
                    )
                }
                BlockStyle.CODE -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surface)
                        .padding(8.dp),
                ) {
                    Text(
                        text = annotatedText,
                        color = palette.onBackground,
                        fontSize = (fontSizeSp - 2f).coerceAtLeast(8.5f).sp,
                        lineHeight = (fontSizeSp * 1.25f).sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                BlockStyle.DIVIDER -> Text(
                    text = "• • •",
                    color = palette.muted,
                    fontSize = fontSizeSp.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                BlockStyle.BODY -> Text(
                    text = annotatedText,
                    color = palette.onBackground,
                    fontSize = fontSizeSp.sp,
                    lineHeight = (fontSizeSp * 1.42f).sp,
                )
            }
        }

        if (hasPrevChapter || hasNextChapter) {
            item(key = "chapter_nav_footer") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                ) {
                    if (hasPrevChapter) {
                        Button(
                            onClick = onPrevChapter,
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            modifier = Modifier.height(34.dp),
                            label = { Text("Prev", fontSize = 11.sp) },
                        )
                    }
                    if (hasNextChapter) {
                        Button(
                            onClick = onNextChapter,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.height(34.dp),
                            label = { Text(nextButtonLabel, fontSize = 11.sp) },
                        )
                    }
                }
            }
        }
    }
}

private fun buildHighlightedText(
    text: String,
    query: String,
    accent: Color,
) = buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        var idx = text.indexOf(query, ignoreCase = true)
        while (idx >= 0) {
            addStyle(
                style = SpanStyle(
                    background = accent.copy(alpha = 0.35f),
                    fontWeight = FontWeight.Bold,
                ),
                start = idx,
                end = idx + query.length,
            )
            idx = text.indexOf(query, startIndex = idx + query.length, ignoreCase = true)
        }
    }
}

private fun clampPan(offset: Offset, scale: Float, viewport: IntSize): Offset {
    if (scale <= 1.01f) {
        val maxY = viewport.height * 0.35f
        return Offset(0f, offset.y.coerceIn(-maxY, maxY))
    }
    val maxX = viewport.width * (scale - 1f) * 0.6f
    val maxY = viewport.height * (scale - 1f) * 0.75f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

private fun openDocStream(context: android.content.Context, document: DocumentItem) =
    if (document.path != null && File(document.path).canRead()) {
        File(document.path).inputStream()
    } else {
        val uri = Uri.parse(
            document.contentUriString
                ?: throw IllegalStateException("No readable source for ${document.fileName}")
        )
        context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Cannot open document stream")
    }
