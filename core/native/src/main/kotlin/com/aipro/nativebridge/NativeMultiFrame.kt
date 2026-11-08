package com.aipro.nativebridge

/**
 * JNI facade over multiframe.cpp (stage 4): burst alignment, night stacking, HDR exposure fusion and
 * YUV_420_888 → NV21 conversion for JPEG encoding.
 */
object NativeMultiFrame {

    val isAvailable: Boolean get() = NativeMath.isAvailable

    data class Shift(val dx: Int, val dy: Int, val confidence: Float)

    data class MergeStats(
        val framesUsed: Int,
        val meanShiftPx: Float,
        val motionRatio: Float,
        /** merged noise / single-frame noise (lower is better; < 1 means the stack helped). */
        val noiseRatio: Float,
    )

    enum class Mode { NIGHT_STACK, EXPOSURE_FUSION }

    fun estimateShift(reference: ByteArray, alternative: ByteArray, width: Int, height: Int, stride: Int): Shift? {
        if (!isAvailable) return null
        val values = nativeEstimateShift(reference, alternative, width, height, stride, 12) ?: return null
        if (values.size < 3) return null
        return Shift(values[0], values[1], values[2] / 1000f)
    }

    /** Merges [frames] (luma planes, same size/stride) into [out]. Returns null when unavailable. */
    fun mergeFrames(
        frames: Array<ByteArray>,
        width: Int,
        height: Int,
        stride: Int,
        out: ByteArray,
        mode: Mode,
        motionThreshold: Float = 18f,
        maxFrames: Int = 9,
    ): MergeStats? {
        if (!isAvailable || frames.isEmpty()) return null
        val values = nativeMergeFrames(
            frames, width, height, stride, out,
            if (mode == Mode.EXPOSURE_FUSION) 1 else 0, motionThreshold, maxFrames,
        ) ?: return null
        if (values.size < 4) return null
        return MergeStats(values[0].toInt(), values[1], values[2], values[3])
    }

    /** Converts planar YUV to NV21 in place into [out] (size = width*height*3/2). */
    fun yuv420ToNv21(
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
        width: Int,
        height: Int,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        out: ByteArray,
    ): Boolean {
        if (!isAvailable) return false
        return nativeYuv420ToNv21(
            y, u, v, width, height, yStride, uStride, vStride, uPixelStride, vPixelStride, out,
        ) == 0
    }

    private external fun nativeEstimateShift(
        reference: ByteArray, alternative: ByteArray, width: Int, height: Int, stride: Int,
        searchRadius: Int,
    ): IntArray?

    private external fun nativeMergeFrames(
        frames: Array<ByteArray>, width: Int, height: Int, stride: Int, out: ByteArray, mode: Int,
        motionThreshold: Float, maxFrames: Int,
    ): FloatArray?

    private external fun nativeYuv420ToNv21(
        y: ByteArray, u: ByteArray, v: ByteArray, width: Int, height: Int, yStride: Int,
        uStride: Int, vStride: Int, uPixelStride: Int, vPixelStride: Int, out: ByteArray,
    ): Int
}
