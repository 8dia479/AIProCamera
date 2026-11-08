package com.aipro.camera.manual

import com.aipro.camera.discovery.ManualControlSupport
import com.aipro.nativebridge.NativeMath

/**
 * Pro-mode capture settings. `null` always means "let the camera decide" (auto), which mirrors the
 * Camera2 contract where leaving a key unset keeps 3A in charge.
 */
data class ManualSettings(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    /** 0 = closest focus, 1 = infinity. `null` = autofocus. */
    val focusSlider01: Float? = null,
    /** Kelvin value for manual white balance. `null` = auto white balance. */
    val whiteBalanceKelvin: Int? = null,
    val exposureCompensationEv: Float = 0f,
    val zoomRatio: Float = 1f,
    /** AE/AWB lock, only applied when the device reports support. */
    val aeLock: Boolean = false,
    val awbLock: Boolean = false,
) {
    val isManualFocus: Boolean get() = focusSlider01 != null
    val isManualWhiteBalance: Boolean get() = whiteBalanceKelvin != null
    val isManualIso: Boolean get() = iso != null
    val isManualShutter: Boolean get() = exposureTimeNs != null

    companion object {
        const val MIN_KELVIN = 2000
        const val MAX_KELVIN = 10000

        /**
         * Drops/clamps every value the hardware cannot honour so a request is never built from an
         * unsupported setting (spec section 4: unsupported controls must not reach the pipeline).
         */
        fun normalize(
            requested: ManualSettings,
            support: ManualControlSupport,
            isoRange: IntRange?,
            exposureRangeNs: LongRange?,
            evRange: IntRange?,
            evStep: Float,
            maxZoom: Float,
            minimumFocusDiopters: Float,
        ): ManualSettings {
            val iso = requested.iso?.let { value ->
                if (!support.iso || isoRange == null) null
                else NativeMath.clampIso(value, isoRange.first, isoRange.last)
            }
            val exposure = requested.exposureTimeNs?.let { value ->
                if (!support.shutterSpeed || exposureRangeNs == null) null
                else NativeMath.clampExposureNs(value, exposureRangeNs.first, exposureRangeNs.last)
            }
            val focus = requested.focusSlider01?.let { value ->
                if (!support.focus) null else value.coerceIn(0f, 1f)
            }
            val kelvin = requested.whiteBalanceKelvin?.let { value ->
                if (!support.whiteBalance) null else value.coerceIn(MIN_KELVIN, MAX_KELVIN)
            }
            val ev = if (!support.exposureCompensation || evRange == null) {
                0f
            } else {
                // CONTROL_AE_COMPENSATION_RANGE is expressed in *steps*, not in EV.
                val step = if (evStep > 0f) evStep else 1f
                val stepped = (requested.exposureCompensationEv / step).toInt() * step
                val minEv = evRange.first * step
                val maxEv = evRange.last * step
                stepped.coerceIn(minEv, maxEv)
            }
            val zoom = if (!support.zoom) 1f else requested.zoomRatio.coerceIn(1f, maxZoom)
            return requested.copy(
                iso = iso,
                exposureTimeNs = exposure,
                focusSlider01 = focus,
                whiteBalanceKelvin = kelvin,
                exposureCompensationEv = ev,
                zoomRatio = zoom,
                aeLock = requested.aeLock,
                awbLock = requested.awbLock && support.whiteBalance,
            )
        }

        /** Auto-everything defaults matching the hardware, used when switching lenses. */
        fun auto(maxZoom: Float): ManualSettings = ManualSettings(zoomRatio = 1f.coerceAtMost(maxZoom))
    }

    /** Dioptre value handed to LENS_FOCUS_DISTANCE for the current slider position. */
    fun focusDiopters(minimumFocusDiopters: Float): Float? =
        focusSlider01?.let { NativeMath.sliderToDiopters(it, minimumFocusDiopters) }
}

/** Human-readable exposure values for the pro-mode readouts. */
object ExposureFormat {

    fun exposureTime(ns: Long): String {
        if (ns <= 0L) return "auto"
        if (ns >= 1_000_000_000L) {
            val seconds = ns / 1_000_000_000.0
            return if (seconds >= 10) "${seconds.toInt()}s" else String.format("%.1fs", seconds)
        }
        val denominator = (1_000_000_000.0 / ns).toInt().coerceAtLeast(1)
        return "1/$denominator"
    }

    fun iso(value: Int?): String = value?.toString() ?: "auto"

    fun ev(value: Float): String = when {
        value == 0f -> "0"
        value > 0f -> String.format("+%.1f", value)
        else -> String.format("%.1f", value)
    }

    fun kelvin(value: Int?): String = value?.let { "${it}K" } ?: "auto"
}
