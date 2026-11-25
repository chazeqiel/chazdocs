package com.chazdocs.wear.ui.components

import com.chazdocs.wear.data.CrownSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMathTest {

    @Test
    fun `multi tap tracker distinguishes single, double, and immediate triple tap`() {
        val tracker = MultiTapTracker(multiTapTimeoutMs = 270L, maxTapDistancePx = 60f)

        assertEquals(MultiTapTracker.Action.WAIT_FOR_MORE, tracker.registerTapUp(100f, 100f, 1000L))
        assertEquals(MultiTapTracker.Action.WAIT_FOR_MORE, tracker.registerTapUp(105f, 98f, 1150L))
        assertEquals(MultiTapTracker.Action.TRIPLE_TAP_NOW, tracker.registerTapUp(102f, 101f, 1280L))

        // After triple tap fires, next tap starts fresh
        assertEquals(MultiTapTracker.Action.WAIT_FOR_MORE, tracker.registerTapUp(100f, 100f, 1400L))
        assertEquals(1, tracker.consumePendingCount())
    }

    @Test
    fun `multi tap tracker resets when tap moves too far or times out`() {
        val tracker = MultiTapTracker(multiTapTimeoutMs = 250L, maxTapDistancePx = 50f)

        tracker.registerTapUp(10f, 10f, 1000L)
        // Too far away -> restarts at count 1
        tracker.registerTapUp(200f, 200f, 1100L)
        assertEquals(1, tracker.consumePendingCount())

        tracker.registerTapUp(10f, 10f, 2000L)
        // Timed out -> restarts at count 1
        tracker.registerTapUp(12f, 12f, 2400L)
        assertEquals(1, tracker.consumePendingCount())
    }

    @Test
    fun `rotary zoom math clamps bounds and respects invert flag`() {
        val zoomedIn = RotaryZoomMath.computeNewCanvasScale(
            currentScale = 1.0f,
            verticalScrollPixels = 100f,
            sensitivity = CrownSensitivity.MEDIUM,
            invertCrown = false,
        )
        assertTrue(zoomedIn > 1.0f)

        val invertedOut = RotaryZoomMath.computeNewCanvasScale(
            currentScale = 2.0f,
            verticalScrollPixels = 100f,
            sensitivity = CrownSensitivity.MEDIUM,
            invertCrown = true,
        )
        assertTrue(invertedOut < 2.0f)

        val clampedMax = RotaryZoomMath.computeNewFontSizeSp(
            currentSp = 27f,
            verticalScrollPixels = 500f,
            sensitivity = CrownSensitivity.HIGH,
            invertCrown = false,
        )
        assertEquals(28f, clampedMax, 0.001f)

        val scrollDown = RotaryZoomMath.computeScrollDeltaPx(
            verticalScrollPixels = 60f,
            sensitivity = CrownSensitivity.MEDIUM,
            invertCrown = false,
        )
        assertTrue(scrollDown > 0f)
        val scrollInv = RotaryZoomMath.computeScrollDeltaPx(
            verticalScrollPixels = 60f,
            sensitivity = CrownSensitivity.MEDIUM,
            invertCrown = true,
        )
        assertEquals(-scrollDown, scrollInv, 0.001f)
    }

    @Test
    fun `gpu zoom keeps screen center and pinch centroid locked on frame 1`() {
        val initial = RotaryZoomMath.GpuZoomState(scale = 1.0f, tx = 0f, ty = 0f)

        // Crown zoom at center (233, 233) from 1.0x -> 2.0x
        val centerZoom = RotaryZoomMath.computeGpuZoom(
            current = initial,
            newScale = 2.0f,
            anchorX = 233f,
            anchorY = 233f,
            viewportWidth = 466f,
            viewportHeight = 466f,
        )
        assertEquals(2.0f, centerZoom.scale, 0.001f)
        assertEquals(-233f, centerZoom.tx, 0.01f)
        assertEquals(-233f, centerZoom.ty, 0.01f)
        // Verify point (233, 233) maps to (233, 233)
        assertEquals(233f, 233f * centerZoom.scale + centerZoom.tx, 0.01f)
        assertEquals(233f, 233f * centerZoom.scale + centerZoom.ty, 0.01f)

        // Pinch zoom near bottom-right (350, 400) from 1.0x -> 2.0x
        val pinchZoom = RotaryZoomMath.computeGpuZoom(
            current = initial,
            newScale = 2.0f,
            anchorX = 350f,
            anchorY = 400f,
            viewportWidth = 466f,
            viewportHeight = 466f,
        )
        assertEquals(350f, 350f * pinchZoom.scale + pinchZoom.tx, 0.01f)
        assertEquals(400f, 400f * pinchZoom.scale + pinchZoom.ty, 0.01f)

        // Pan overflow scrolls LazyColumn down cleanly when hitting bottom clamp
        val panOverflow = RotaryZoomMath.computeGpuPan(
            current = RotaryZoomMath.GpuZoomState(scale = 2.0f, tx = -233f, ty = -466f),
            dragX = 0f,
            dragY = -40f,
            viewportWidth = 466f,
            viewportHeight = 466f,
        )
        assertEquals(-466f, panOverflow.state.ty, 0.01f)
        assertEquals(20f, panOverflow.verticalScrollOverflowPx, 0.01f)
    }
}
