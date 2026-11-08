package com.aipro.nativebridge

import android.graphics.Bitmap

/**
 * JNI facade over image_ops.cpp. Every routine is host-tested (core/native/src/testHost).
 *
 * Bitmaps are handled as packed ARGB IntArray so the JNI layer only moves primitive arrays.
 * When the shared library is unavailable each call degrades to a no-op and reports false, which the
 * pipeline surfaces as "고전 처리 경로 사용 불가" instead of silently producing wrong pixels.
 */
object NativeImageOps {

    val isAvailable: Boolean get() = NativeMath.isAvailable

    fun denoise(bitmap: Bitmap, radius: Int = 2, sigmaRange: Float = 24f, sigmaSpatial: Float = 1.6f): Boolean =
        mutate(bitmap) { pixels, w, h -> nativeBilateral(pixels, w, h, radius, sigmaRange, sigmaSpatial) }

    fun sharpen(bitmap: Bitmap, amount: Float = 0.6f, radius: Int = 2): Boolean =
        mutate(bitmap) { pixels, w, h -> nativeUnsharp(pixels, w, h, amount, radius) }

    fun smoothPortrait(bitmap: Bitmap, strength: Float = 0.55f): Boolean =
        mutate(bitmap) { pixels, w, h -> nativePortraitSmooth(pixels, w, h, strength) }

    fun applyToneAndColor(
        bitmap: Bitmap,
        lut: ByteArray?,
        saturation: Float,
        temperature: Float,
    ): Boolean = mutate(bitmap) { pixels, w, h ->
        nativeToneAndColor(pixels, w, h, lut, saturation, temperature)
    }

    /** Upscales [source] by [factor] using bicubic resampling (fallback when no SR model exists). */
    fun upscale(source: Bitmap, factor: Int): Bitmap? {
        if (!isAvailable || factor < 2) return null
        val targetWidth = source.width * factor
        val targetHeight = source.height * factor
        if (targetWidth.toLong() * targetHeight > 200_000_000L) return null
        val src = IntArray(source.width * source.height)
        source.getPixels(src, 0, source.width, 0, 0, source.width, source.height)
        val dst = IntArray(targetWidth * targetHeight)
        val code = nativeResizeBicubic(src, source.width, source.height, dst, targetWidth, targetHeight)
        if (code != 0) return null
        return Bitmap.createBitmap(dst, targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    }

    fun analyze(bitmap: Bitmap): ImageStats? {
        if (!isAvailable) return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val values = nativeAnalyze(pixels, bitmap.width, bitmap.height) ?: return null
        if (values.size < 5) return null
        return ImageStats(
            meanLuma = values[0],
            contrast = values[1],
            clippedHighlights = values[2],
            clippedShadows = values[3],
            saturationMean = values[4],
        )
    }

    fun purposeLut(purpose: Int, contrast: Double): ByteArray? =
        if (!isAvailable) null else nativeBuildPurposeLut(purpose, contrast)

    private inline fun mutate(bitmap: Bitmap, block: (IntArray, Int, Int) -> Int): Boolean {
        if (!isAvailable || bitmap.isRecycled) return false
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val code = block(pixels, bitmap.width, bitmap.height)
        if (code != 0) return false
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return true
    }

    data class ImageStats(
        val meanLuma: Double,
        val contrast: Double,
        val clippedHighlights: Double,
        val clippedShadows: Double,
        val saturationMean: Double,
    )

    private external fun nativeBilateral(
        pixels: IntArray, width: Int, height: Int, radius: Int, sigmaRange: Float, sigmaSpatial: Float,
    ): Int
    private external fun nativeUnsharp(pixels: IntArray, width: Int, height: Int, amount: Float, radius: Int): Int
    private external fun nativePortraitSmooth(pixels: IntArray, width: Int, height: Int, strength: Float): Int
    private external fun nativeResizeBicubic(
        src: IntArray, srcWidth: Int, srcHeight: Int, dst: IntArray, dstWidth: Int, dstHeight: Int,
    ): Int
    private external fun nativeToneAndColor(
        pixels: IntArray, width: Int, height: Int, lut: ByteArray?, saturation: Float, temperature: Float,
    ): Int
    private external fun nativeAnalyze(pixels: IntArray, width: Int, height: Int): DoubleArray?
    private external fun nativeBuildPurposeLut(purpose: Int, contrast: Double): ByteArray?
}
