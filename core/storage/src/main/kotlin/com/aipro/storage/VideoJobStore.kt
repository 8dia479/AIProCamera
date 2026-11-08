package com.aipro.storage

import android.content.Context
import com.aipro.camera.video.VideoJob
import com.aipro.camera.video.VideoJobStatus
import com.aipro.common.AiproLog
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable store for resumable video post-processing jobs (spec section 8).
 *
 * One JSON file per job in the app-private external directory, so a job survives process death,
 * thermal pauses and reboots without needing WorkManager's own database to be the source of truth.
 * WorkManager only schedules; this store decides what still has to be done.
 */
class VideoJobStore(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val root: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir)
            .resolve("AIProCamera/video_jobs")
            .apply { mkdirs() }

    fun save(job: VideoJob): Boolean = runCatching {
        root.resolve("${job.id}.json")
            .writeText(json.encodeToString(VideoJob.serializer(), job.copy(updatedAtEpochMs = System.currentTimeMillis())))
        true
    }.onFailure { AiproLog.e(TAG, "job 저장 실패 ${job.id}", it) }.getOrDefault(false)

    fun load(id: String): VideoJob? = runCatching {
        val file = root.resolve("$id.json")
        if (!file.exists()) null else json.decodeFromString(VideoJob.serializer(), file.readText())
    }.onFailure { AiproLog.e(TAG, "job 읽기 실패 $id", it) }.getOrNull()

    fun list(): List<VideoJob> = runCatching {
        root.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { json.decodeFromString(VideoJob.serializer(), file.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAtEpochMs }
            ?: emptyList()
    }.getOrDefault(emptyList())

    /** Jobs that still need work: queued, resumed, or paused mid-way. */
    fun outstanding(maxAttempts: Int = 3): List<VideoJob> = list().filter { job ->
        when (job.status) {
            VideoJobStatus.PENDING, VideoJobStatus.PAUSED -> true
            VideoJobStatus.RUNNING -> true // a crash during RUNNING must not strand the job
            VideoJobStatus.FAILED -> job.attempts < maxAttempts
            VideoJobStatus.DONE -> false
        }
    }.sortedBy { it.createdAtEpochMs }

    fun markAbandoned(id: String, reason: String): VideoJob? {
        val job = load(id) ?: return null
        val failed = job.copy(status = VideoJobStatus.FAILED, lastError = reason)
        save(failed)
        return failed
    }

    fun delete(id: String): Boolean = runCatching {
        root.resolve("$id.json").delete()
    }.getOrDefault(false)

    private companion object {
        const val TAG = "VideoJobStore"
    }
}
