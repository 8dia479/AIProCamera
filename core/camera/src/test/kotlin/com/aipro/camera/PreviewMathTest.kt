package com.aipro.camera

import com.aipro.camera.preview.PreviewMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewMathTest {

    @Test
    fun `no rotation keeps the plain cover scale`() {
        // 4:3 buffer in a square view: the height term (3000 -> 1000) is the binding one.
        assertEquals(1f / 3f, PreviewMath.coverScale(4000f, 3000f, 1000f, 1000f, 0f), 1e-4f)
    }

    @Test
    fun `quarter turn uses the rotated bounding box`() {
        // 4:3 buffer in a 3:4 view: after a 90 degree turn the bounding box is 3000x4000
        val scale = PreviewMath.coverScale(4000f, 3000f, 900f, 1200f, 90f)
        assertEquals(0.3f, scale, 1e-4f)
    }

    @Test
    fun `lever scale is continuous around a quarter turn`() {
        val before = PreviewMath.coverScale(4000f, 3000f, 900f, 1200f, 89.999f)
        val after = PreviewMath.coverScale(4000f, 3000f, 900f, 1200f, 90.001f)
        assertEquals(before, after, 1e-2f)
    }

    @Test
    fun `rotation never returns a scale below the unrotated cover`() {
        val base = PreviewMath.coverScale(4000f, 3000f, 1080f, 1440f, 0f)
        for (angle in listOf(-25f, -10f, -1f, 0f, 1f, 10f, 25f)) {
            assertTrue(PreviewMath.coverScale(4000f, 3000f, 1080f, 1440f, angle) >= base - 1e-4f)
        }
    }

    @Test
    fun `inscribed rect keeps the requested aspect`() {
        val (w, h) = PreviewMath.largestInscribedAspectRect(4000, 3000, 16f / 9f, 0f)
        assertEquals(16f / 9f, w.toFloat() / h.toFloat(), 0.01f)
        assertTrue(w <= 4000 && h <= 3000)
    }

    @Test
    fun `inscribed rect shrinks when the image is tilted`() {
        val (flatW, _) = PreviewMath.largestInscribedAspectRect(4000, 3000, 4f / 3f, 0f)
        val (tiltedW, _) = PreviewMath.largestInscribedAspectRect(4000, 3000, 4f / 3f, 10f)
        assertTrue(tiltedW < flatW)
    }

    @Test
    fun `leveling rotation counter-rotates the device roll`() {
        assertEquals(-5f, PreviewMath.levelingRotation(5f, mirrored = false), 1e-4f)
        assertEquals(5f, PreviewMath.levelingRotation(5f, mirrored = true), 1e-4f)
        assertEquals(90f - 5f, PreviewMath.totalRotation(90, 5f, mirrored = false), 1e-4f)
    }

    @Test
    fun `level fill scale grows with the tilt`() {
        val flat = PreviewMath.levelFillScale(4000, 3000, 4000, 3000, 0f)
        val tilted = PreviewMath.levelFillScale(4000, 3000, 4000, 3000, 8f)
        assertEquals(1f, flat, 1e-3f)
        assertTrue(tilted > flat)
    }
}

/** Regression: the preview rotation setting is an offset, it must never replace the sensor rotation. */
class PreviewRotationOffsetTest {

    @Test
    fun `default offset keeps the sensor rotation`() {
        assertEquals(90, PreviewMath.baseRotation(90, null))
        assertEquals(90, PreviewMath.baseRotation(90, 0))
    }

    @Test
    fun `offset is added on top of the sensor rotation`() {
        assertEquals(180, PreviewMath.baseRotation(90, 90))
        assertEquals(270, PreviewMath.baseRotation(90, 180))
        assertEquals(0, PreviewMath.baseRotation(270, 90))
        assertEquals(90, PreviewMath.baseRotation(270, 180))
    }

    /**
     * The exact case reported on the Flip 6: a 1280x720 landscape buffer in a 1080x1920 portrait
     * window. With the sensor rotation applied the cover scale is uniform (1.5x), so the image is not
     * stretched; with rotation 0 it needed 2.667x, which is what looked "옆으로 늘어난" on device.
     */
    @Test
    fun `portrait window with landscape buffer needs uniform cover scale`() {
        val rotation = PreviewMath.baseRotation(90, 0).toFloat()
        assertEquals(1.5f, PreviewMath.coverScale(1280f, 720f, 1080f, 1920f, rotation), 0.001f)
        assertEquals(2.667f, PreviewMath.coverScale(1280f, 720f, 1080f, 1920f, 0f), 0.01f)
    }
}

/**
 * The rebuild sizes the TextureView to the buffer aspect and only applies a *uniform* scale, so the
 * preview can never be stretched. These numbers are what the device logs after the fix.
 */
class PreviewFillScaleTest {

    @Test
    fun `720p buffer in a 9 by 16 box is a 1x50 upscale`() {
        assertEquals(1.5f, PreviewMath.coverScale(1280f, 720f, 1080f, 1920f, 90f), 0.001f)
    }

    @Test
    fun `matching preview stream needs no upscale at all`() {
        // 1920x1080 stream, 16:9 box rotated to portrait.
        assertEquals(1.0f, PreviewMath.coverScale(1920f, 1080f, 1080f, 1920f, 90f), 0.001f)
        // 1440x1080 stream, 4:3 box rotated to portrait.
        assertEquals(1.0f, PreviewMath.coverScale(1440f, 1080f, 1080f, 1440f, 90f), 0.001f)
    }

    @Test
    fun `horizon roll only ever adds a uniform scale`() {
        val level = PreviewMath.coverScale(1920f, 1080f, 1080f, 1920f, 90f)
        val rolled = PreviewMath.coverScale(1920f, 1080f, 1080f, 1920f, 93f)
        assertTrue(rolled > level)
        assertTrue(rolled < level * 1.1f)
    }
}
