package com.aipro.camera.capture

/**
 * Sensor crop windows for `SCALER_CROP_REGION`.
 *
 * Why this exists: Camera2 applies **one** crop region to every output stream of a request and then
 * scales the cropped image to each stream's buffer. If the crop's aspect ratio differs from a stream's
 * aspect ratio the driver **stretches** the image to fill it. The old code set the crop to the full
 * active array (4:3 on the Flip 6) while the preview stream was 16:9 - the preview was therefore
 * squeezed horizontally, which read as "가로로 늘어남".
 *
 * Rule: the crop aspect must equal the stream aspect, or the key must be left unset (then the driver
 * crops each stream correctly by itself).
 */
data class SensorWindow(val left: Int, val top: Int, val width: Int, val height: Int)

object CropPlanner {

    /** Largest centred window with [aspect] (width / height) inside the active array, then /[zoom]. */
    fun centered(activeWidth: Int, activeHeight: Int, aspect: Float, zoom: Float = 1f): SensorWindow {
        if (activeWidth <= 0 || activeHeight <= 0) return SensorWindow(0, 0, activeWidth, activeHeight)
        val target = if (aspect > 0f) aspect else activeWidth.toFloat() / activeHeight
        val areaWidth: Int
        val areaHeight: Int
        val activeAspect = activeWidth.toFloat() / activeHeight
        if (activeAspect > target) {
            // Active area is wider than the target: trim the sides, keep full height.
            areaHeight = activeHeight
            areaWidth = (activeHeight * target).toInt().coerceIn(1, activeWidth)
        } else {
            areaWidth = activeWidth
            areaHeight = (activeWidth / target).toInt().coerceIn(1, activeHeight)
        }
        val crop = zoom.coerceAtLeast(1f)
        val width = (areaWidth / crop).toInt().coerceAtLeast(1)
        val height = (areaHeight / crop).toInt().coerceAtLeast(1)
        return SensorWindow(
            left = (activeWidth - width) / 2,
            top = (activeHeight - height) / 2,
            width = width,
            height = height,
        )
    }

    /** True when the crop is only "the whole sensor" and the key can be left out of the request. */
    fun isFullFrame(activeWidth: Int, activeHeight: Int, aspect: Float, zoom: Float): Boolean {
        if (zoom > 1.001f) return false
        val active = activeWidth.toFloat() / activeHeight
        return kotlin.math.abs(active - aspect) / active <= 0.01f
    }
}
