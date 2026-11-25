package com.chazdocs.wear.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.chazdocs.wear.data.CrownSensitivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure state tracker for single / double / triple tap sequences so timing & slop
 * behavior can be unit-tested cleanly without Compose UI harness.
 */
class MultiTapTracker(
    private val multiTapTimeoutMs: Long = 270L,
    private val maxTapDistancePx: Float = 72f,
) {
    private var tapCount = 0
    private var lastUpTimeMs = 0L
    private var lastUpX = 0f
    private var lastUpY = 0f

    enum class Action {
        WAIT_FOR_MORE,
        TRIPLE_TAP_NOW,
    }

    fun registerTapUp(x: Float, y: Float, nowMs: Long): Action {
        val withinTime = (nowMs - lastUpTimeMs) <= multiTapTimeoutMs
        val withinDist = hypot(x - lastUpX, y - lastUpY) <= maxTapDistancePx

        tapCount = if (tapCount > 0 && withinTime && withinDist) tapCount + 1 else 1
        lastUpTimeMs = nowMs
        lastUpX = x
        lastUpY = y

        return if (tapCount >= 3) {
            tapCount = 0
            Action.TRIPLE_TAP_NOW
        } else {
            Action.WAIT_FOR_MORE
        }
    }

    fun consumePendingCount(): Int {
        val count = tapCount
        tapCount = 0
        return count
    }

    fun reset() {
        tapCount = 0
    }
}

object RotaryZoomMath {
    fun computeNewCanvasScale(
        currentScale: Float,
        verticalScrollPixels: Float,
        sensitivity: CrownSensitivity,
        invertCrown: Boolean,
        minScale: Float = 1.0f,
        maxScale: Float = 4.5f,
    ): Float {
        val signed = if (invertCrown) -verticalScrollPixels else verticalScrollPixels
        val delta = signed * 0.0045f * sensitivity.multiplier
        return (currentScale + delta).coerceIn(minScale, maxScale)
    }

    fun computeNewFontSizeSp(
        currentSp: Float,
        verticalScrollPixels: Float,
        sensitivity: CrownSensitivity,
        invertCrown: Boolean,
        minSp: Float = 10f,
        maxSp: Float = 28f,
    ): Float {
        val signed = if (invertCrown) -verticalScrollPixels else verticalScrollPixels
        val delta = signed * 0.032f * sensitivity.multiplier
        return (currentSp + delta).coerceIn(minSp, maxSp)
    }

    fun computeScrollDeltaPx(
        verticalScrollPixels: Float,
        sensitivity: CrownSensitivity,
        invertCrown: Boolean,
    ): Float {
        val signed = if (invertCrown) -verticalScrollPixels else verticalScrollPixels
        return signed * 1.45f * sensitivity.multiplier
    }

    data class GpuZoomState(
        val scale: Float = 1f,
        val tx: Float = 0f,
        val ty: Float = 0f,
    )

    data class GpuPanResult(
        val state: GpuZoomState,
        val verticalScrollOverflowPx: Float,
    )

    fun computeGpuZoom(
        current: GpuZoomState,
        newScale: Float,
        anchorX: Float,
        anchorY: Float,
        panX: Float = 0f,
        panY: Float = 0f,
        viewportWidth: Float,
        viewportHeight: Float,
    ): GpuZoomState {
        val clampedScale = newScale.coerceIn(1f, 4.5f)
        val safeOldScale = current.scale.coerceAtLeast(0.1f)
        val ratio = clampedScale / safeOldScale

        val minTx = -viewportWidth.coerceAtLeast(1f) * (clampedScale - 1f)
        val minTy = -viewportHeight.coerceAtLeast(1f) * (clampedScale - 1f)

        val rawTx = anchorX - (anchorX - current.tx) * ratio + panX
        val rawTy = anchorY - (anchorY - current.ty) * ratio + panY

        return GpuZoomState(
            scale = clampedScale,
            tx = rawTx.coerceIn(minTx, 0f),
            ty = rawTy.coerceIn(minTy, 0f),
        )
    }

    fun computeGpuPan(
        current: GpuZoomState,
        dragX: Float,
        dragY: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): GpuPanResult {
        val minTx = -viewportWidth.coerceAtLeast(1f) * (current.scale - 1f)
        val minTy = -viewportHeight.coerceAtLeast(1f) * (current.scale - 1f)

        val newTx = (current.tx + dragX).coerceIn(minTx, 0f)
        val unclampedTy = current.ty + dragY
        val newTy = unclampedTy.coerceIn(minTy, 0f)

        // Positive overflow scrolls LazyColumn down (when dragging finger up past minTy).
        val overflowY = -(unclampedTy - newTy) / current.scale.coerceAtLeast(1f)

        return GpuPanResult(
            state = current.copy(tx = newTx, ty = newTy),
            verticalScrollOverflowPx = overflowY,
        )
    }
}

/**
 * Handles 2-finger pinch zoom (anchored at pinch centroid) and 1-finger 2D pan + momentum fling
 * when zoomed, while leaving 1-finger drags unconsumed at 1.0x so LazyColumn scrolls natively.
 * Clean taps within touchSlop are never consumed so multi-tap detection always fires.
 */
fun Modifier.detectPdfPinchAndPan(
    isZoomed: Boolean,
    onPointerDown: () -> Unit,
    onPinch: (zoom: Float, centroid: Offset, pan: Offset) -> Unit,
    onZoomedPan: (dx: Float, dy: Float) -> Unit,
    onZoomedFling: (vx: Float, vy: Float) -> Unit,
): Modifier = composed {
    val currentIsZoomed by rememberUpdatedState(isZoomed)
    val currentOnPointerDown by rememberUpdatedState(onPointerDown)
    val currentOnPinch by rememberUpdatedState(onPinch)
    val currentOnZoomedPan by rememberUpdatedState(onZoomedPan)
    val currentOnZoomedFling by rememberUpdatedState(onZoomedFling)

    pointerInput(Unit) {
        val touchSlop = viewConfiguration.touchSlop
        val maxFlingVelocity = viewConfiguration.maximumFlingVelocity
        awaitEachGesture {
            val firstDown = awaitFirstDown(requireUnconsumed = false)
            currentOnPointerDown()
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(firstDown.uptimeMillis, firstDown.position)

            var panStarted = false
            var usedPinch = false
            var totalDragX = 0f
            var totalDragY = 0f

            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                val zoomedNow = currentIsZoomed
                if (pressed.size >= 2) {
                    usedPinch = true
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid != Offset.Unspecified && (abs(zoom - 1f) > 0.001f || pan != Offset.Zero)) {
                        currentOnPinch(zoom, centroid, pan)
                    }
                    event.changes.forEach { it.consume() }
                    panStarted = true
                } else if (zoomedNow && !usedPinch && pressed.size == 1) {
                    val change = pressed[0]
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val delta = change.position - change.previousPosition
                    if (!panStarted) {
                        totalDragX += delta.x
                        totalDragY += delta.y
                        if (hypot(totalDragX, totalDragY) > touchSlop) {
                            panStarted = true
                            currentOnZoomedPan(totalDragX, totalDragY)
                            change.consume()
                        }
                    } else if (delta != Offset.Zero) {
                        currentOnZoomedPan(delta.x, delta.y)
                        change.consume()
                    }
                } else if (pressed.size == 1 && panStarted && (!zoomedNow || usedPinch)) {
                    val change = pressed[0]
                    if (change.id == firstDown.id) {
                        change.consume()
                    }
                }
            } while (pressed.isNotEmpty())

            if (currentIsZoomed && panStarted && !usedPinch) {
                val v = velocityTracker.calculateVelocity()
                val vx = v.x.coerceIn(-maxFlingVelocity, maxFlingVelocity)
                val vy = v.y.coerceIn(-maxFlingVelocity, maxFlingVelocity)
                if (hypot(vx, vy) > 90f) {
                    currentOnZoomedFling(vx, vy)
                }
            }
        }
    }
}

/**
 * Detects single, double, and triple taps anywhere on the target composable
 * WITHOUT blocking underlying scroll, drag, or pinch gestures.
 */
fun Modifier.detectDocumentMultiTap(
    enabled: Boolean = true,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onTripleTap: () -> Unit,
): Modifier {
    if (!enabled) return this
    return this.pointerInput(enabled, onSingleTap, onDoubleTap, onTripleTap) {
        val touchSlop = viewConfiguration.touchSlop
        val tracker = MultiTapTracker(
            multiTapTimeoutMs = 265L,
            maxTapDistancePx = (touchSlop * 4.5f).coerceAtLeast(64f),
        )
        var pendingJob: Job? = null

        coroutineScope {
            val tapScope = this
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                val downPos: Offset = down.position
                val downTime = down.uptimeMillis
                var cancelledByDragOrMultiTouch = false
                var upPos = downPos
                var upTime = downTime

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val active = event.changes.filter { it.pressed }
                    if (event.changes.size > 1) {
                        cancelledByDragOrMultiTouch = true
                    }
                    val first = event.changes.firstOrNull { it.id == down.id }
                    if (first != null) {
                        upPos = first.position
                        upTime = first.uptimeMillis
                        val dist = (first.position - downPos).getDistance()
                        if (dist > touchSlop) {
                            cancelledByDragOrMultiTouch = true
                        }
                    }
                    if (active.isEmpty()) break
                }

                val tapDuration = upTime - downTime
                if (cancelledByDragOrMultiTouch || tapDuration > 320L) {
                    pendingJob?.cancel()
                    tracker.reset()
                    return@awaitEachGesture
                }

                pendingJob?.cancel()
                when (tracker.registerTapUp(upPos.x, upPos.y, upTime)) {
                    MultiTapTracker.Action.TRIPLE_TAP_NOW -> {
                        onTripleTap()
                    }
                    MultiTapTracker.Action.WAIT_FOR_MORE -> {
                        pendingJob = tapScope.launch {
                            delay(265L)
                            when (tracker.consumePendingCount()) {
                                1 -> onSingleTap()
                                2 -> onDoubleTap()
                            }
                        }
                    }
                }
            }
        }
    }
}
