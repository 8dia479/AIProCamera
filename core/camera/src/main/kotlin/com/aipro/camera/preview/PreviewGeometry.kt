package com.aipro.camera.preview

/**
 * Rotation/scale values needed to show a camera buffer correctly in a UI view.
 *
 * Kept as plain numbers (instead of an android.graphics.Matrix) so the maths can be unit-tested and
 * applied by Compose through Modifier.graphicsLayer + ContentScale.
 */
data class PreviewGeometry(
    /** Relative sensor-to-display rotation; used for axis scaling, not applied twice to TextureView. */
    val rotationDegrees: Int,
    /** Size of the buffer as the camera delivers it (sensor orientation, before rotation). */
    val bufferWidth: Int = 0,
    val bufferHeight: Int = 0,
    /** Horizontal mirroring, needed for the front camera. */
    val mirrored: Boolean,
    /** Extra scale (>= 1) so the rotated buffer fills the view without letterboxing. */
    val fillScale: Float,
    /** Size of the buffer after rotation is applied. */
    val rotatedWidth: Int,
    val rotatedHeight: Int,
    /** Physical CameraCharacteristics.SENSOR_ORIENTATION; TextureView handles this part. */
    val sensorOrientationDegrees: Int = 0,
) {
    val aspectRatio: Float
        get() = if (rotatedHeight == 0) 0f else rotatedWidth.toFloat() / rotatedHeight
}

object PreviewTransform {

    /**
     * @param bufferWidth  width of the camera buffer (sensor coordinate space)
     * @param bufferHeight height of the camera buffer
     * @param viewWidth    width of the UI area
     * @param viewHeight   height of the UI area
     * @param sensorOrientation CameraCharacteristics.SENSOR_ORIENTATION (0/90/180/270)
     * @param displayRotationDegrees Surface.ROTATION_x converted to degrees
     * @param facingFront  true for the front camera (mirrored preview)
     */
    fun compute(
        bufferWidth: Int,
        bufferHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        facingFront: Boolean,
    ): PreviewGeometry {
        // Android Camera2 relative rotation:
        // rotation = sensorOrientation - displayRotation * sign
        // sign = +1 for front cameras and -1 for rear cameras. Display#getRotation() already
        // reports counter-clockwise degrees, so use it directly here.
        val sign = if (facingFront) 1 else -1
        val rotation = ((sensorOrientation - displayRotationDegrees * sign) % 360 + 360) % 360
        val swapped = rotation == 90 || rotation == 270
        val rotatedWidth = if (swapped) bufferHeight else bufferWidth
        val rotatedHeight = if (swapped) bufferWidth else bufferHeight

        val fillScale = if (rotatedWidth <= 0 || rotatedHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            1f
        } else {
            maxOf(
                viewWidth.toFloat() / rotatedWidth,
                viewHeight.toFloat() / rotatedHeight,
            ).coerceAtLeast(1f)
        }
        return PreviewGeometry(
            rotationDegrees = rotation,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            mirrored = facingFront,
            fillScale = fillScale,
            rotatedWidth = rotatedWidth,
            rotatedHeight = rotatedHeight,
            sensorOrientationDegrees = ((sensorOrientation % 360) + 360) % 360,
        )
    }

    /** JPEG orientation to embed so the saved file is upright regardless of how the phone was held. */
    fun jpegOrientation(sensorOrientation: Int, displayRotationDegrees: Int, facingFront: Boolean): Int {
        return if (facingFront) {
            (sensorOrientation + displayRotationDegrees) % 360
        } else {
            (sensorOrientation - displayRotationDegrees + 360) % 360
        }
    }
}
