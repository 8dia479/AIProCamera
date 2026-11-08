package com.aipro.camera.discovery

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Range
import android.util.Size
import com.aipro.common.AiproLog
import com.aipro.common.Facing
import com.aipro.common.LensType
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Translates CameraCharacteristics into [CameraCapabilities]. Every flag is read from the framework;
 * no device-specific assumptions are baked in (spec section 16 items 2-3).
 */
object CapabilityProbe {

    private const val TAG = "Capability"

    private val reportJsonFormat = kotlinx.serialization.json.Json { prettyPrint = true }

    fun probe(cameraId: String, characteristics: CameraCharacteristics): CameraCapabilities {
        val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraMetadata.LENS_FACING_FRONT -> Facing.FRONT
            CameraMetadata.LENS_FACING_BACK -> Facing.BACK
            else -> Facing.EXTERNAL
        }
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList()
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorWidthMm = physicalSize?.width ?: 0f
        val sensorHeightMm = physicalSize?.height ?: 0f
        val primaryFocal = focalLengths.firstOrNull() ?: 0f
        val lensType = LensClassifier.classify(facing, primaryFocal, sensorWidthMm, sensorHeightMm)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()

        val manualSensorSupported = capabilities.contains(
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR,
        )
        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: emptyList()
        val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.toIntRange()
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.toLongRange()
        val evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.toIntRange()
        val evStep = rationalToFloat(characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP), 0f)

        val rawSizes = map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR)?.map { it.toInfo() } ?: emptyList()
        val jpegSizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.map { it.toInfo() } ?: emptyList()
        val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.map { it.toInfo() } ?: emptyList()
        val videoSizes = map?.getOutputSizes(android.media.MediaRecorder::class.java)?.map { it.toInfo() } ?: emptyList()

        // HEIF stills are exposed through the HEIC image format constant (API 33+).
        val heifSupported = runCatching {
            !map?.getOutputSizes(android.graphics.ImageFormat.HEIC).isNullOrEmpty()
        }.getOrDefault(false)
        // Ultra HDR (JPEG_R) is API 34+; querying it on older devices simply returns no sizes.
        val jpegRSupported = runCatching {
            map?.getOutputSizes(android.graphics.ImageFormat.JPEG_R).isNullOrEmpty() == false
        }.getOrDefault(false)

        val stabilizationModes = characteristics.get(
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES,
        )?.toList() ?: emptyList()

        val previewSizes = (map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.map { it.toInfo() } ?: emptyList())
            .sortedByDescending { it.width.toLong() * it.height }

        val maxFps = map?.getOutputMinFrameDuration(android.media.MediaRecorder::class.java, videoSizes.maxByOrNull { it.width * it.height }?.toSize() ?: Size(1920, 1080))
            ?.let { if (it > 0) (1_000_000_000.0 / it).roundToInt() else 60 } ?: 30

        val caps = CameraCapabilities(
            cameraId = cameraId,
            facing = facing,
            lensType = lensType,
            hardwareSupportLevel = hardwareLevelName(characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
            sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.toInfo(),
            sensorPhysicalWidthMm = sensorWidthMm,
            sensorPhysicalHeightMm = sensorHeightMm,
            focalLengthsMm = focalLengths,
            equivalentFocalLength35mm = LensClassifier.equivalentFocalLength35mm(primaryFocal, sensorWidthMm, sensorHeightMm),
            apertureFNumbers = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.toList() ?: emptyList(),
            minimumFocusDistanceDiopters = minFocus,
            manualSensorSupported = manualSensorSupported,
            manualFocusSupported = afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF),
            autoFocusModes = afModes,
            autoWhiteBalanceModes = awbModes,
            autoWhiteBalanceLockSupported = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) ?: false,
            isoRange = isoRange,
            exposureTimeRangeNs = exposureRange,
            exposureCompensationRange = evRange,
            exposureCompensationStepEv = evStep,
            maxDigitalZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f,
            outputFormats = capabilities.map { capabilityName(it) },
            rawSupported = rawSizes.isNotEmpty() && capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            rawSizes = rawSizes,
            jpegSizes = jpegSizes,
            heifSupported = heifSupported,
            jpegRSupported = jpegRSupported,
            yuvSizes = yuvSizes,
            previewSizes = previewSizes.take(12),
            videoSizes = videoSizes.sortedByDescending { it.width.toLong() * it.height },
            videoStabilizationModes = stabilizationModes,
            opticalStabilizationSupported = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
            maxVideoFps = maxFps,
            highSpeedVideoSizes = map?.getHighSpeedVideoSizes()?.map { it.toInfo() } ?: emptyList(),
            flashAvailable = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false,
            logicalMultiCamera = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
            physicalCameraIds = characteristics.physicalCameraIds.toList(),
            lensDistortionCorrectionSupported = characteristics.get(
                CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES,
            )?.let { modes -> modes.any { it != CameraMetadata.DISTORTION_CORRECTION_MODE_OFF } } ?: false,
        )
        AiproLog.i(TAG, "camera $cameraId → ${caps.lensType} ${caps.hardwareSupportLevel} raw=${caps.rawSupported} ${caps.manual.describe()}")
        return caps
    }

    fun descriptor(caps: CameraCapabilities): CameraDescriptor {
        val equiv = caps.equivalentFocalLength35mm?.roundToInt()
        val name = buildString {
            append(caps.lensType.displayName)
            if (caps.facing == Facing.FRONT) append("(전면)")
            if (equiv != null) append(" ").append(equiv).append("mm")
        }
        return CameraDescriptor(
            cameraId = caps.cameraId,
            facing = caps.facing,
            lensType = caps.lensType,
            focalLengthMm = caps.focalLengthsMm.firstOrNull() ?: 0f,
            equivalentFocalLength35mm = caps.equivalentFocalLength35mm,
            displayName = name.trim(),
            physicalCameraIds = caps.physicalCameraIds,
        )
    }

    /** Renders the capability report as stable JSON for on-device inspection / bug reports. */
    fun reportJson(capabilities: List<CameraCapabilities>): String =
        reportJsonFormat
            .encodeToString(kotlinx.serialization.builtins.ListSerializer(CameraCapabilities.serializer()), capabilities)

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($level)"
    }

    private fun capabilityName(capability: Int): String = when (capability) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        else -> "CAP_0x${Integer.toHexString(capability)}"
    }

    private fun rationalToFloat(rational: android.util.Rational?, fallback: Float): Float =
        if (rational == null || rational.denominator == 0) fallback
        else rational.numerator.toFloat() / rational.denominator

    private fun Range<Int>.toIntRange(): IntRange = lower..upper

    private fun Range<Long>.toLongRange(): LongRange = lower..upper

    private fun Size.toInfo(): SizeInfo = SizeInfo(width, height)

    private fun SizeInfo.toSize(): Size = Size(width, height)

    /** Frame-rate sanity check for the UI (frames per second at the given size). */
    fun fpsForSize(map: StreamConfigurationMap?, size: Size): Int =
        map?.getOutputMinFrameDuration(android.media.MediaRecorder::class.java, size)
            ?.let { if (it > 0) (1_000_000_000.0 / it).roundToInt() else 30 } ?: 30

    /** Diagonal of the sensor in millimetres, used only for reporting. */
    fun sensorDiagonalMm(caps: CameraCapabilities): Double =
        sqrt((caps.sensorPhysicalWidthMm * caps.sensorPhysicalWidthMm + caps.sensorPhysicalHeightMm * caps.sensorPhysicalHeightMm).toDouble())
}
