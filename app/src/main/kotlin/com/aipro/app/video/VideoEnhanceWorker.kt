package com.aipro.app.video

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aipro.ai.performance.PerformanceGovernor
import com.aipro.camera.video.VideoJobStatus
import com.aipro.camera.video.VideoPostProcessor
import com.aipro.common.AiproLog
import com.aipro.storage.MediaStoreSink
import com.aipro.storage.VideoJobStore

/**
 * Runs a resumable video post-processing job off the UI thread (spec section 8).
 *
 * The job never touches the original recording: it reads the finished file from `Video/Original` and
 * writes `Video/Enhanced`. Under thermal/battery pressure the worker stops cooperatively and asks
 * WorkManager for a retry — the checkpoint in [VideoJobStore] means no frame is recomputed twice.
 */
class VideoEnhanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val store = VideoJobStore(applicationContext)
        val job = store.load(jobId) ?: return Result.failure()
        if (job.status == VideoJobStatus.DONE) return Result.success()

        val governor = PerformanceGovernor(applicationContext)
        if (governor.level() == PerformanceGovernor.Level.PAUSED) {
            store.save(job.copy(status = VideoJobStatus.PAUSED, lastError = "발열/배터리 보호로 보류"))
            return Result.retry()
        }

        val sink = MediaStoreSink(applicationContext)
        val processor = VideoPostProcessor(applicationContext)
        setProgress(workDataOf(KEY_PROGRESS to job.progress))

        val result = processor.process(
            job = job,
            sink = sink,
            onCheckpoint = { updated ->
                store.save(updated)
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to updated.progress,
                        KEY_SEGMENTS to updated.segmentsDone,
                        KEY_FRAMES to updated.framesDone,
                    ),
                )
            },
            shouldContinue = {
                !isStopped && governor.level() != PerformanceGovernor.Level.PAUSED
            },
        )

        val finished = store.load(jobId)
        AiproLog.i(TAG, "worker 종료: ${finished?.status} ${finished?.lastError ?: ""}")
        return when {
            result.isFailure -> Result.failure(workDataOf(KEY_ERROR to (result.exceptionOrNull()?.message ?: "unknown")))
            finished?.status == VideoJobStatus.PAUSED -> Result.retry()
            else -> Result.success(workDataOf(KEY_OUTPUT to (finished?.outputUri ?: "")))
        }
    }

    companion object {
        private const val TAG = "VideoEnhanceWorker"
        const val KEY_JOB_ID = "job_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_SEGMENTS = "segments"
        const val KEY_FRAMES = "frames"
        const val KEY_OUTPUT = "output_uri"
        const val KEY_ERROR = "error"
    }
}
