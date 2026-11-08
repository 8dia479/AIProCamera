package com.aipro.nativebridge

import java.nio.ByteBuffer

/**
 * JNI facade over :core:native (C++17). Every function here is backed by host-tested pure C++
 * (see core/native/src/testHost). Loading is lazy so a missing ABI never breaks the JVM modules.
 */
object NativeMath {

    private val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("aipro_native") }.isSuccess
    }

    /** False when the shared library could not be loaded; callers must fall back to Kotlin maths. */
    val isAvailable: Boolean get() = loaded

    fun apiVersion(): Int = if (loaded) nativeApiVersion() else -1

    fun evToExposureMultiplier(ev: Double): Double =
        if (loaded) nativeEvToExposureMultiplier(ev) else Math.pow(2.0, ev)

    fun clampIso(iso: Int, minIso: Int, maxIso: Int): Int =
        if (loaded) nativeClampIso(iso, minIso, maxIso) else iso.coerceIn(minIso, maxIso)

    fun clampExposureNs(ns: Long, minNs: Long, maxNs: Long): Long =
        if (loaded) nativeClampExposureNs(ns, minNs, maxNs) else ns.coerceIn(minNs, maxNs)

    fun sliderToDiopters(slider01: Float, minimumFocusDistance: Float): Float =
        if (loaded) nativeSliderToDiopters(slider01, minimumFocusDistance)
        else minimumFocusDistance * (1f - slider01.coerceIn(0f, 1f))

    /** Fills [out] with RGGB gains for the given Kelvin value (green normalised to 1). */
    fun kelvinToRggbGains(kelvin: Int, out: FloatArray = FloatArray(4)): FloatArray {
        require(out.size >= 4) { "gains array must hold 4 floats" }
        if (loaded) {
            nativeKelvinToRggbGains(kelvin, out)
        } else {
            out[0] = 1f; out[1] = 1f; out[2] = 1f; out[3] = 1f
        }
        return out
    }

    /** Luma histogram (256 bins) of a YUV_420_888 Y plane. Returns null if the plane is inaccessible. */
    fun lumaHistogram(
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        out: IntArray = IntArray(256),
    ): IntArray? {
        if (!loaded || !yPlane.isDirect) return null
        val code = nativeLumaHistogram(yPlane, width, height, rowStride, pixelStride, out)
        return if (code == 0) out else null
    }

    fun meanLuma(
        yPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): Double =
        if (loaded && yPlane.isDirect) nativeMeanLuma(yPlane, width, height, rowStride, pixelStride)
        else 0.0

    private external fun nativeApiVersion(): Int
    private external fun nativeEvToExposureMultiplier(ev: Double): Double
    private external fun nativeClampIso(iso: Int, minIso: Int, maxIso: Int): Int
    private external fun nativeClampExposureNs(ns: Long, minNs: Long, maxNs: Long): Long
    private external fun nativeSliderToDiopters(slider01: Float, minimumFocusDistance: Float): Float
    private external fun nativeKelvinToRggbGains(kelvin: Int, out: FloatArray): Int
    private external fun nativeLumaHistogram(
        yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int, out: IntArray,
    ): Int
    private external fun nativeMeanLuma(
        yPlane: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
    ): Double
}
