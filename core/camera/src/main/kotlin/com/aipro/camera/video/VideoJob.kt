package com.aipro.camera.video

import kotlinx.serialization.Serializable

/** Lifecycle of a post-processing job. PAUSED means "resumable": nothing is thrown away. */
@Serializable
enum class VideoJobStatus { PENDING, RUNNING, PAUSED, DONE, FAILED }

/**
 * A resumable video post-processing job (spec section 8).
 *
 * Real-time processing never writes into this job: the original recording always finishes on the
 * MediaRecorder surface, and this job reads that finished file afterwards. Because every field is
 * serializable the job survives process death; [completedSegments] is the checkpoint that makes a
 * resumed run continue where it stopped instead of restarting the file.
 */
@Serializable
data class HorizonSample(
    /** Microseconds from the first recorded frame. */
    val timeUs: Long,
    val rollDegrees: Float,
)

@Serializable
data class VideoJob(
    val id: String,
    val createdAtEpochMs: Long,
    val sourceUri: String,
    val shootingPurpose: String,
    val nightMode: Boolean = false,
    val status: VideoJobStatus = VideoJobStatus.PENDING,
    val tone: VideoTone = VideoTone(),
    // Experimental horizon-lock roll captured when the recording was made (degrees, 0 = off).
    val horizonRollDegrees: Float = 0f,
    /** Time-varying horizon correction captured during recording. */
    val horizonSamples: List<HorizonSample> = emptyList(),
    val width: Int = 0,
    val height: Int = 0,
    val rotationDegrees: Int = 0,
    val durationUs: Long = 0L,
    val segmentCount: Int = 0,
    val completedSegments: List<String> = emptyList(),
    val framesDone: Int = 0,
    val attempts: Int = 0,
    val outputUri: String? = null,
    val usedGl: Boolean = true,
    val lastError: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val segmentsDone: Int get() = completedSegments.size
    val progress: Float
        get() = if (segmentCount <= 0) 0f else (segmentsDone.toFloat() / segmentCount).coerceIn(0f, 1f)
    val resumeLabel: String
        get() = "세그먼트 ${segmentsDone}/${segmentCount} · ${(progress * 100).toInt()}% · ${framesDone}프레임"
}
