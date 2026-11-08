package com.aipro.camera

import com.aipro.camera.preview.PreviewTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewTransformTest {

    @Test
    fun `sensor rotation is applied relative to the display`() {
        val land = PreviewTransform.compute(4000, 3000, 1080, 2400, 90, 0, facingFront = false)
        assertEquals(90, land.rotationDegrees)
        assertEquals(3000, land.rotatedWidth)
        assertEquals(4000, land.rotatedHeight)
        assertEquals(90, land.sensorOrientationDegrees)

        // Display#getRotation() is counter-clockwise. A rear-facing camera uses
        // sensorOrientation - displayRotation * (-1), so a 90° display turn yields 180°.
        val landscape = PreviewTransform.compute(4000, 3000, 2400, 1080, 90, 90, facingFront = false)
        assertEquals(180, landscape.rotationDegrees)
        assertEquals(4000, landscape.rotatedWidth)

        // The front-camera sign is the opposite.
        val front = PreviewTransform.compute(4000, 3000, 2400, 1080, 270, 90, facingFront = true)
        assertEquals(180, front.rotationDegrees)
    }

    @Test
    fun `negative rotation wraps into 0-359`() {
        val geometry = PreviewTransform.compute(4000, 3000, 1080, 2400, 0, 270, facingFront = true)
        assertEquals(90, geometry.rotationDegrees)
    }

    @Test
    fun `fill scale never letterboxes`() {
        val geometry = PreviewTransform.compute(1920, 1080, 1080, 2400, 90, 0, facingFront = false)
        assertEquals(true, geometry.fillScale >= 1f)
    }

    @Test
    fun `front camera is mirrored`() {
        assertEquals(true, PreviewTransform.compute(1000, 1000, 1000, 1000, 0, 0, facingFront = true).mirrored)
    }

    @Test
    fun `jpeg orientation follows the camera convention`() {
        assertEquals(90, PreviewTransform.jpegOrientation(90, 0, facingFront = false))
        assertEquals(0, PreviewTransform.jpegOrientation(90, 90, facingFront = false))
        assertEquals(180, PreviewTransform.jpegOrientation(90, 90, facingFront = true))
        assertEquals(270, PreviewTransform.jpegOrientation(90, 180, facingFront = true))
    }
}
