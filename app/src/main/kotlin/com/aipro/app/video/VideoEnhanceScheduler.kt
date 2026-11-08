package com.aipro.app.video

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aipro.camera.video.VideoJob
import com.aipro.camera.video.VideoJobStatus
import com.aipro.storage.VideoJobStore
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Queues video post-processing jobs. Uses unique work keyed by job id so re-enqueueing the same job
 * (for example after the app restarts) never runs it twice in parallel.
 */
class VideoEnhanceScheduler(private val context: Context) {

    private val store = VideoJobStore(context)

    /** Creates and persists a job for a finished recording, then schedules it. */
    fun enqueue(
        sourceUri: String,
        purpose: String,
        nightMode: Boolean,
        tone: com.aipro.camera.video.VideoTone,
        horizonRollDegrees: Float = 0f,
    ): VideoJob {
        val job = VideoJob(
            id = UUID.randomUUID().toString(),
            createdAtEpochMs = System.currentTimeMillis(),
            sourceUri = sourceUri,
            shootingPurpose = purpose,
            nightMode = nightMode,
            tone = tone,
            horizonRollDegrees = horizonRollDegrees,
            status = VideoJobStatus.PENDING,
        )
        store.save(job)
        schedule(job.id)
        return job
    }

    fun schedule(jobId: String) {
        val request = OneTimeWorkRequestBuilder<VideoEnhanceWorker>()
            .setInputData(workDataOf(VideoEnhanceWorker.KEY_JOB_ID to jobId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("video-enhance-$jobId", ExistingWorkPolicy.KEEP, request)
    }

    /** Re-schedules every job that still has work (called from the app start and the AI sheet). */
    fun resumeOutstanding(): Int {
        val outstanding = store.outstanding()
        outstanding.forEach { schedule(it.id) }
        return outstanding.size
    }

    fun jobs(): List<VideoJob> = store.list()

    fun cancel(jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("video-enhance-$jobId")
        store.save(
            store.load(jobId)?.copy(status = VideoJobStatus.PAUSED, lastError = "사용자 취소")
                ?: return,
        )
    }

    fun retry(jobId: String) {
        val job = store.load(jobId) ?: return
        store.save(job.copy(status = VideoJobStatus.PENDING, lastError = null))
        schedule(jobId)
    }

    private companion object {
        const val TAG = "video-enhance"
    }
}
