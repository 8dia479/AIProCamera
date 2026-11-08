package com.aipro.common

import kotlinx.serialization.Serializable

/**
 * Sidecar metadata stored next to every capture (spec section 12). Written by :core:storage and
 * consumed later by the AI pipeline so a queued enhancement job can resume after a restart.
 */
@Serializable
data class CaptureRecord(
    val id: String,
    val createdAtEpochMs: Long,
    val mode: String,
    val shootingPurpose: String,
    val nightMode: Boolean,
    val lens: String,
    val facing: String,
    val cameraId: String,
    val originalUri: String,
    val originalPath: String?,
    val enhancedUri: String? = null,
    val rawUri: String? = null,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val iso: Int?,
    val exposureTimeNs: Long?,
    val focalLengthMm: Float?,
    val fNumber: Float?,
    val whiteBalanceMode: String?,
    val whiteBalanceKelvin: Int? = null,
    val exposureCompensationEv: Float?,
    val focusDistanceDiopters: Float?,
    val isManualIso: Boolean = false,
    val isManualShutter: Boolean = false,
    val isManualFocus: Boolean = false,
    val isManualWhiteBalance: Boolean = false,
    val modelVersion: String? = null,
    // Multi-frame (night / HDR) diagnostics, null for single-frame captures.
    val multiFrameMode: String? = null,
    val framesUsed: Int? = null,
    val motionRatio: Float? = null,
    val noiseRatio: Float? = null,
    val alignmentShiftPx: Float? = null,
    // Experimental horizon-lock roll at the moment of capture (degrees); the original is never rotated.
    val horizonRollDegrees: Float? = null,
    /** Framing ratio requested at capture time (e.g. "4:3") and whether the frame was cropped. */
    val aspectRatio: String? = null,
    val croppedToRatio: Boolean? = null,
    val processingStatus: String = ProcessingStatus.ORIGINAL_ONLY.name,
    val processingError: String? = null,
)
