package com.aipro.camera.engine

/** Which merge strategy a burst uses (spec section 6: night mode is independent of shooting purpose). */
enum class MultiFrameMode { NIGHT_STACK, HDR_FUSION }

/**
 * Result of a multi-frame capture. The JPEG is produced in-engine because merging happens on YUV
 * planes: the chroma is averaged, then NV21 is encoded with Android's JPEG encoder.
 */
data class MultiFrameCapture(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val framesRequested: Int,
    val framesUsed: Int,
    val mode: MultiFrameMode,
    val motionRatio: Float,
    val noiseRatio: Float,
    val meanShiftPx: Float,
    val mergeMillis: Long,
    val orientationDegrees: Int,
) {
    val summary: String
        get() = "${mode.name} ${framesUsed}/${framesRequested}프레임 · 노이즈 ${"%.2f".format(noiseRatio)}배 · " +
            "움직임 ${(motionRatio * 100).toInt()}% · 정렬 ${"%.1f".format(meanShiftPx)}px"
}
