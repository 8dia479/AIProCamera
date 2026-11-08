package com.aipro.camera

import com.aipro.camera.capture.CropPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the "가로로 늘어남" preview: the sensor crop must never change the aspect ratio of
 * the image that reaches the output streams.
 */
class CropPlannerTest {

    private fun aspectOf(w: Int, h: Int) = w.toFloat() / h

    @Test
    fun `crop keeps the requested aspect inside a 4 by 3 sensor`() {
        val window = CropPlanner.centered(4080, 3060, 16f / 9f)
        assertEquals(16f / 9f, aspectOf(window.width, window.height), 0.01f)
        assertTrue(window.width <= 4080)
        assertTrue(window.height <= 3060)
        // centred: 16:9 is wider than the 4:3 sensor, so the top/bottom are trimmed.
        assertEquals(0, window.left)
        assertEquals((3060 - window.height) / 2, window.top)
    }

    @Test
    fun `zoom crops without changing the aspect`() {
        val base = CropPlanner.centered(4080, 3060, 16f / 9f)
        val zoomed = CropPlanner.centered(4080, 3060, 16f / 9f, zoom = 2f)
        assertEquals(16f / 9f, aspectOf(zoomed.width, zoomed.height), 0.02f)
        assertTrue(zoomed.width < base.width)
        assertTrue(zoomed.height < base.height)
    }

    @Test
    fun `full frame is detected only when aspect and zoom already match`() {
        assertTrue(CropPlanner.isFullFrame(4080, 3060, 4f / 3f, 1f))
        assertFalse(CropPlanner.isFullFrame(4080, 3060, 16f / 9f, 1f))
        assertFalse(CropPlanner.isFullFrame(4080, 3060, 4f / 3f, 2f))
    }

    @Test
    fun `a 1 by 1 window stays centred and square`() {
        val window = CropPlanner.centered(4080, 3060, 1f)
        assertEquals(window.width, window.height)
        assertEquals((4080 - window.width) / 2, window.left)
    }
}
