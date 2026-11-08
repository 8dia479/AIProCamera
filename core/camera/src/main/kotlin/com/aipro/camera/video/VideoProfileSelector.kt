package com.aipro.camera.video

import com.aipro.camera.capture.AspectRatioOption
import com.aipro.camera.discovery.SizeInfo

/**
 * Picks a video recording size from the sizes the device actually reports for MediaRecorder output.
 * Pure logic (no Android types) so it can be unit-tested; preference order favours image quality
 * (spec: Quality First) while staying inside 16:9 and the requested maximum width.
 */
object VideoProfileSelector {

    data class Selection(val size: SizeInfo, val fps: Int)

    fun select(
        available: List<SizeInfo>,
        preferredMaxWidth: Int = 3840,
        targetFps: Int = 30,
        /** Framing ratio requested in the UI; only sizes the device really reports are used. */
        aspect: AspectRatioOption? = null,
    ): Selection? {
        if (available.isEmpty()) return null
        val withinWidth = available.filter { it.width <= preferredMaxWidth }
        val target = aspect?.let { option ->
            withinWidth.filter { size ->
                kotlin.math.abs(size.width.toDouble() / size.height - option.ratio) /
                    option.ratio <= 0.02
            }.maxByOrNull { it.width.toLong() * it.height }
        }
        if (target != null) return Selection(target, targetFps)
        val sixteenByNine = withinWidth.filter { is16x9(it) }
        val pool = sixteenByNine.ifEmpty { withinWidth.ifEmpty { available } }
        val best = pool.maxByOrNull { it.width.toLong() * it.height } ?: return null
        return Selection(best, targetFps)
    }

    fun is16x9(size: SizeInfo): Boolean {
        val ratio = size.width.toDouble() / size.height
        return kotlin.math.abs(ratio - 16.0 / 9.0) < 0.02
    }

    /** Rough bitrate target (bits/s) for MediaRecorder: ~0.12 bits per pixel per frame. */
    fun estimateBitrate(size: SizeInfo, fps: Int): Int {
        val perFrame = size.width.toLong() * size.height * 12L / 100L
        return (perFrame * fps).coerceIn(2_000_000L, 120_000_000L).toInt()
    }
}
