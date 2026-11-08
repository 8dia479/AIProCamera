package com.aipro.nativebridge

import java.nio.ByteBuffer

/**
 * JNI facade over video_ops.cpp: luma-plane work for the stage-5 video post job.
 *
 * Every call takes a **direct** ByteBuffer holding the first `width * height` bytes of the plane and
 * returns true when the native library handled it. A false return means the caller must fall back to
 * the CPU path (the pipeline reports that in the job record instead of pretending it ran).
 */
object NativeVideoOps {

    val isAvailable: Boolean get() = NativeMath.isAvailable

    fun applyToneLut(luma: ByteBuffer, width: Int, height: Int, lut: ByteArray): Boolean =
        isAvailable && lut.size == 256 && nativeApplyToneLutY(luma, width, height, lut) == 0

    fun sharpen(luma: ByteBuffer, width: Int, height: Int, amount: Float): Boolean =
        isAvailable && amount > 0f && nativeSharpenY(luma, width, height, amount) == 0

    fun denoise(luma: ByteBuffer, width: Int, height: Int, sigmaRange: Float): Boolean =
        isAvailable && nativeDenoiseY(luma, width, height, sigmaRange) == 0

    fun mirror(luma: ByteBuffer, width: Int, height: Int): Boolean =
        isAvailable && nativeMirrorY(luma, width, height) == 0

    fun stretch(luma: ByteBuffer, width: Int, height: Int, low: Int, high: Int): Boolean =
        isAvailable && nativeStretchY(luma, width, height, low, high) == 0

    private external fun nativeApplyToneLutY(
        luma: ByteBuffer,
        width: Int,
        height: Int,
        lut: ByteArray,
    ): Int

    private external fun nativeSharpenY(luma: ByteBuffer, width: Int, height: Int, amount: Float): Int

    private external fun nativeDenoiseY(luma: ByteBuffer, width: Int, height: Int, sigmaRange: Float): Int

    private external fun nativeMirrorY(luma: ByteBuffer, width: Int, height: Int): Int

    private external fun nativeStretchY(luma: ByteBuffer, width: Int, height: Int, low: Int, high: Int): Int
}
