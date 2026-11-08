package com.aipro.camera.engine

import com.aipro.common.CaptureMode
import com.aipro.common.Facing
import com.aipro.common.LensType
import com.aipro.common.ShootingPurpose
import com.aipro.camera.discovery.CameraCapabilities
import com.aipro.camera.discovery.CameraDescriptor
import com.aipro.camera.discovery.SizeInfo
import com.aipro.camera.manual.ManualSettings

/** Lifecycle of the camera engine as observed by the UI. */
sealed interface EngineState {
    data object Idle : EngineState
    data class Opening(val cameraId: String) : EngineState
    data class Previewing(val cameraId: String) : EngineState
    data class Capturing(val cameraId: String) : EngineState
    data class Recording(val cameraId: String, val startedAtMs: Long) : EngineState
    data class Error(val message: String, val cause: Throwable? = null) : EngineState
    data object Closed : EngineState
}

/** Live readout of the frame the camera just produced (real CaptureResult / ImageReader data). */
data class FrameInfo(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val awbMode: Int? = null,
    val afState: Int? = null,
    val aeState: Int? = null,
    val zoomRatio: Float = 1f,
    val meanLuma: Double = 0.0,
    val histogram: IntArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FrameInfo) return false
        return iso == other.iso && exposureTimeNs == other.exposureTimeNs &&
            focusDistanceDiopters == other.focusDistanceDiopters && awbMode == other.awbMode &&
            afState == other.afState && aeState == other.aeState && zoomRatio == other.zoomRatio &&
            meanLuma == other.meanLuma && (histogram?.contentEquals(other.histogram) ?: (other.histogram == null))
    }

    override fun hashCode(): Int {
        var result = iso ?: 0
        result = 31 * result + (exposureTimeNs?.hashCode() ?: 0)
        result = 31 * result + (afState ?: 0)
        result = 31 * result + (histogram?.contentHashCode() ?: 0)
        return result
    }
}

/** What the user asked the engine to shoot. */
data class StillCaptureRequest(
    val purpose: ShootingPurpose = ShootingPurpose.AUTO,
    val nightMode: Boolean = false,
    val format: StillFormat = StillFormat.JPEG,
    val includeRaw: Boolean = false,
    val burstSize: Int = 1,
)

enum class StillFormat { JPEG, HEIF, JPEG_R }

data class CaptureOutcome(
    val primaryUri: String,
    val primaryPath: String,
    val rawUri: String?,
    val width: Int,
    val height: Int,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val processingMillis: Long,
    val format: String,
    val lens: LensType,
    val facing: Facing,
    val capturedFrames: Int,
)

data class VideoOutcome(
    val uri: String,
    val path: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val codec: String,
)

/** Session-wide description of the active camera, handed to the UI. */
data class ActiveCamera(
    val descriptor: CameraDescriptor,
    val capabilities: CameraCapabilities,
    val previewSize: SizeInfo,
    val stillSize: SizeInfo,
    val mode: CaptureMode,
    val manual: ManualSettings,
)
