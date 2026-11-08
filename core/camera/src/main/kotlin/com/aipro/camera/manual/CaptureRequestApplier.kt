package com.aipro.camera.manual

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import com.aipro.camera.capture.CropPlanner
import com.aipro.common.AiproLog
import com.aipro.nativebridge.NativeMath

/**
 * Writes [ManualSettings] into a CaptureRequest.Builder. The key order matters: disabling 3A comes
 * first, then the concrete values, exactly as the Camera2 documentation requires.
 */
object CaptureRequestApplier {

    private const val TAG = "ManualApply"

    /**
     * @param cropAspect aspect ratio (width / height) of the session's output streams. The sensor crop
     *   must use exactly this ratio, otherwise Camera2 stretches the cropped image into the stream
     *   buffers (the "가로로 늘어남" bug). `null` leaves `SCALER_CROP_REGION` untouched.
     */
    fun apply(
        builder: CaptureRequest.Builder,
        settings: ManualSettings,
        characteristics: CameraCharacteristics,
        flashMode: FlashMode = FlashMode.OFF,
        cropAspect: Float? = null,
    ) {
        applyZoom(builder, settings.zoomRatio, characteristics, cropAspect)
        applyExposure(builder, settings)
        applyFocus(builder, settings, characteristics)
        applyWhiteBalance(builder, settings)
        applyEv(builder, settings, characteristics)
        applyLocks(builder, settings)
        applyFlash(builder, flashMode, settings)
    }

    /**
     * Flash is expressed through CONTROL_AE_MODE, and only while AE is not overridden by manual
     * ISO/shutter — Camera2 ignores FLASH_MODE when AE is OFF, so the manual path keeps its
     * behaviour and the UI says so instead of pretending the flash will fire.
     */
    private fun applyFlash(
        builder: CaptureRequest.Builder,
        flashMode: FlashMode,
        settings: ManualSettings,
    ) {
        if (flashMode == FlashMode.OFF) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF)
            return
        }
        if (settings.isManualIso || settings.isManualShutter) {
            AiproLog.i(TAG, "수동 노출 중에는 플래시를 사용할 수 없습니다 (AE_MODE=OFF)")
            return
        }
        builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
        builder.set(
            CaptureRequest.CONTROL_AE_MODE,
            if (flashMode == FlashMode.AUTO) CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            else CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
        )
    }

    private fun applyZoom(
        builder: CaptureRequest.Builder,
        zoom: Float,
        characteristics: CameraCharacteristics,
        cropAspect: Float?,
    ) {
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        if (cropAspect == null || cropAspect <= 0f) return
        val ratio = zoom.coerceIn(1f, maxZoom)
        // No zoom and the streams already match the sensor aspect: leave the key out entirely so the
        // driver crops each stream itself (this is what a stock camera app relies on).
        if (CropPlanner.isFullFrame(active.width(), active.height(), cropAspect, ratio)) return
        val window = CropPlanner.centered(active.width(), active.height(), cropAspect, ratio)
        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            android.graphics.Rect(
                active.left + window.left,
                active.top + window.top,
                active.left + window.left + window.width,
                active.top + window.top + window.height,
            ),
        )
    }

    private fun applyExposure(builder: CaptureRequest.Builder, settings: ManualSettings) {
        val iso = settings.iso
        val exposureNs = settings.exposureTimeNs
        if (iso == null && exposureNs == null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        } else {
            // Manual exposure requires AE off, otherwise the framework overwrites SENSOR_* keys.
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            if (iso != null) builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            if (exposureNs != null) builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
            // Frame duration must be at least the exposure time, otherwise the HAL rejects the request.
            exposureNs?.let {
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, (it * 1.2).toLong().coerceAtLeast(it))
            }
        }
    }

    private fun applyFocus(
        builder: CaptureRequest.Builder,
        settings: ManualSettings,
        characteristics: CameraCharacteristics,
    ) {
        val slider = settings.focusSlider01
        if (slider == null) {
            val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return
            val preferred = when {
                modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                    CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                modes.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) -> CameraMetadata.CONTROL_AF_MODE_AUTO
                else -> CameraMetadata.CONTROL_AF_MODE_OFF
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, preferred)
        } else {
            val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            if (minFocus <= 0f) {
                AiproLog.w(TAG, "manual focus requested but lens is fixed-focus - ignored")
                return
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                NativeMath.sliderToDiopters(slider, minFocus),
            )
        }
    }

    private fun applyWhiteBalance(builder: CaptureRequest.Builder, settings: ManualSettings) {
        val kelvin = settings.whiteBalanceKelvin
        if (kelvin == null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
            return
        }
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
        val gains = NativeMath.kelvinToRggbGains(kelvin)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_MODE,
            CameraMetadata.COLOR_CORRECTION_MODE_HIGH_QUALITY,
        )
        builder.set(
            CaptureRequest.COLOR_CORRECTION_GAINS,
            RggbChannelVector(gains[0], gains[1], gains[2], gains[3]),
        )
    }

    private fun applyEv(
        builder: CaptureRequest.Builder,
        settings: ManualSettings,
        characteristics: CameraCharacteristics,
    ) {
        val range: Range<Int> = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return
        val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val stepValue = if (step == null || step.denominator == 0) 0f else step.numerator.toFloat() / step.denominator
        val target = if (stepValue > 0f) (settings.exposureCompensationEv / stepValue).toInt() else settings.exposureCompensationEv.toInt()
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, target.coerceIn(range.lower, range.upper))
    }

    private fun applyLocks(builder: CaptureRequest.Builder, settings: ManualSettings) {
        builder.set(CaptureRequest.CONTROL_AE_LOCK, settings.aeLock)
        builder.set(CaptureRequest.CONTROL_AWB_LOCK, settings.awbLock && !settings.isManualWhiteBalance)
    }
}
