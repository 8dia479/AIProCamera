package com.aipro.camera.discovery

import com.aipro.common.Facing
import com.aipro.common.LensType
import kotlinx.serialization.Serializable

@Serializable
data class SizeInfo(val width: Int, val height: Int) {
    override fun toString(): String = "${width}x$height"
}

/**
 * Everything Camera2 actually reports for one camera. Nothing in the UI may offer a control that is
 * not backed by these values - see [ManualControlSupport].
 */
@Serializable
data class CameraCapabilities(
    val cameraId: String,
    val facing: Facing,
    val lensType: LensType,
    val hardwareSupportLevel: String,
    val sensorOrientation: Int,
    val sensorSize: SizeInfo?,
    val sensorPhysicalWidthMm: Float,
    val sensorPhysicalHeightMm: Float,
    val focalLengthsMm: List<Float>,
    val equivalentFocalLength35mm: Double?,
    val apertureFNumbers: List<Float>,
    val minimumFocusDistanceDiopters: Float,
    // --- manual control support -------------------------------------------------
    val manualSensorSupported: Boolean,
    val manualFocusSupported: Boolean,
    val autoFocusModes: List<Int>,
    val autoWhiteBalanceModes: List<Int>,
    val autoWhiteBalanceLockSupported: Boolean,
    @Serializable(with = IntRangeSerializer::class)
    val isoRange: IntRange?,
    @Serializable(with = LongRangeSerializer::class)
    val exposureTimeRangeNs: LongRange?,
    @Serializable(with = IntRangeSerializer::class)
    val exposureCompensationRange: IntRange?,
    val exposureCompensationStepEv: Float,
    val maxDigitalZoom: Float,
    // --- outputs -----------------------------------------------------------------
    val outputFormats: List<String>,
    val rawSupported: Boolean,
    val rawSizes: List<SizeInfo>,
    val jpegSizes: List<SizeInfo>,
    val heifSupported: Boolean,
    val jpegRSupported: Boolean,
    val yuvSizes: List<SizeInfo>,
    val previewSizes: List<SizeInfo>,
    val videoSizes: List<SizeInfo>,
    // --- video -------------------------------------------------------------------
    val videoStabilizationModes: List<Int>,
    val opticalStabilizationSupported: Boolean,
    val maxVideoFps: Int,
    val highSpeedVideoSizes: List<SizeInfo>,
    // --- misc ---------------------------------------------------------------------
    val flashAvailable: Boolean,
    val logicalMultiCamera: Boolean,
    val physicalCameraIds: List<String>,
    val lensDistortionCorrectionSupported: Boolean,
) {
    val isLogicalOnly: Boolean get() = logicalMultiCamera && physicalCameraIds.isNotEmpty()

    /** Per-control gating used by the pro-mode UI (spec section 4: never show unsupported controls). */
    val manual: ManualControlSupport
        get() = ManualControlSupport(
            iso = manualSensorSupported && isoRange != null,
            shutterSpeed = manualSensorSupported && exposureTimeRangeNs != null,
            focus = manualFocusSupported && minimumFocusDistanceDiopters > 0f,
            whiteBalance = autoWhiteBalanceModes.contains(android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_OFF),
            exposureCompensation = exposureCompensationRange != null,
            raw = rawSupported,
            heif = heifSupported,
            zoom = maxDigitalZoom > 1.0f,
            histogram = yuvSizes.isNotEmpty() || previewSizes.isNotEmpty(),
        )
}

@Serializable
data class ManualControlSupport(
    val iso: Boolean,
    val shutterSpeed: Boolean,
    val focus: Boolean,
    val whiteBalance: Boolean,
    val exposureCompensation: Boolean,
    val raw: Boolean,
    val heif: Boolean,
    val zoom: Boolean,
    val histogram: Boolean,
) {
    fun describe(): String = buildList {
        if (iso) add("ISO")
        if (shutterSpeed) add("셔터")
        if (focus) add("MF")
        if (whiteBalance) add("WB")
        if (exposureCompensation) add("EV")
        if (raw) add("RAW")
        if (heif) add("HEIF")
        if (zoom) add("줌")
    }.joinToString(" · ").ifEmpty { "수동 제어 없음" }
}

/** A camera as offered to the user in the lens switcher. */
@Serializable
data class CameraDescriptor(
    val cameraId: String,
    val facing: Facing,
    val lensType: LensType,
    val focalLengthMm: Float,
    val equivalentFocalLength35mm: Double?,
    val displayName: String,
    val physicalCameraIds: List<String> = emptyList(),
)
