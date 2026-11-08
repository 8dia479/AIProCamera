package com.aipro.camera.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.aipro.camera.engine.CameraController
import com.aipro.camera.engine.MediaSink
import com.aipro.common.AiproLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Offline video post-processing (spec section 8): the recording is already finished and safe in
 * `Video/Original` before this runs, so nothing here can interrupt the original.
 *
 * Resumability model: the source is split into fixed 2 s segments and each segment is encoded into
 * its own MP4 part through the GL tone chain. The finished part names are checkpointed in the job, so
 * a killed / thermally paused job resumes at the first missing part instead of starting over. A final
 * remux (no re-encode) joins the parts into one file in `Video/Enhanced`.
 */
class VideoPostProcessor(private val context: Context) {

    data class Output(
        val uri: String,
        val segments: Int,
        val frames: Int,
        val audioCopied: Boolean,
        val elapsedMs: Long,
    )

    /** Encodes every missing segment, checkpointing after each one. Returns the updated job. */
    suspend fun process(
        job: VideoJob,
        sink: MediaSink,
        onCheckpoint: suspend (VideoJob) -> Unit,
        shouldContinue: () -> Boolean = { true },
    ): Result<VideoJob> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        var current = job
        try {
            val info = VideoSegmentEncoder.probe(context, Uri.parse(job.sourceUri))
                ?: return@withContext Result.failure(IllegalStateException("비디오 트랙을 찾을 수 없습니다"))
            if (info.durationUs <= 0L) {
                return@withContext Result.failure(IllegalStateException("길이를 알 수 없는 비디오입니다"))
            }
            val segmentCount = ((info.durationUs + SEGMENT_US - 1) / SEGMENT_US).toInt().coerceAtLeast(1)
            current = current.copy(
                width = info.width,
                height = info.height,
                rotationDegrees = info.rotationDegrees,
                durationUs = info.durationUs,
                segmentCount = segmentCount,
                status = VideoJobStatus.RUNNING,
                attempts = current.attempts + 1,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            onCheckpoint(current)

            val workDir = File(context.cacheDir, "video_jobs/${job.id}").apply { mkdirs() }
            val encoder = VideoSegmentEncoder(
                context = context,
                sourceUri = Uri.parse(job.sourceUri),
                tone = job.tone,
                rotationDegrees = info.rotationDegrees,
                sourceWidth = info.width,
                sourceHeight = info.height,
                horizonRollDegrees = job.horizonRollDegrees,
                horizonSamples = job.horizonSamples,
            )

            val parts = current.completedSegments.toMutableList()
            for (index in parts.size until segmentCount) {
                if (!shouldContinue()) {
                    val paused = current.copy(
                        completedSegments = parts.toList(),
                        status = VideoJobStatus.PAUSED,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                    onCheckpoint(paused)
                    return@withContext Result.success(paused)
                }
                coroutineContext.ensureActive()
                val startUs = index * SEGMENT_US
                val endUs = minOf(startUs + SEGMENT_US, info.durationUs)
                val partName = "part_%03d.mp4".format(index)
                val partResult = encoder.encode(startUs, endUs, File(workDir, partName))
                if (partResult.frames <= 0) {
                    throw IllegalStateException("세그먼트 $index 에서 프레임을 얻지 못했습니다")
                }
                parts += partName
                current = current.copy(
                    completedSegments = parts.toList(),
                    framesDone = current.framesDone + partResult.frames,
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                onCheckpoint(current)
            }

            // All parts exist: join them into Video/Enhanced with a remux (no second generation loss).
            val target = sink.createVideoTarget("video/mp4", CameraController.ENHANCED_VIDEO_DIR, "mp4")
            val joined = runCatching { remux(workDir, parts, target.fileDescriptor, info.rotationDegrees) }
            target.close()
            if (joined.isFailure) {
                throw joined.exceptionOrNull() ?: IllegalStateException("병합 실패")
            }
            // The caller owns the original; only our temp parts are deleted here.
            workDir.deleteRecursively()

            val done = current.copy(
                status = VideoJobStatus.DONE,
                outputUri = target.uri,
                lastError = null,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            AiproLog.i(
                TAG,
                "video enhanced: ${parts.size}개 세그먼트, ${done.framesDone}프레임, " +
                    "${System.currentTimeMillis() - started}ms",
            )
            onCheckpoint(done)
            Result.success(done)
        } catch (t: Throwable) {
            AiproLog.e(TAG, "video post-processing 실패", t)
            val failed = current.copy(
                status = VideoJobStatus.FAILED,
                lastError = t.message ?: t::class.java.simpleName,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            runCatching { onCheckpoint(failed) }
            if (t is kotlinx.coroutines.CancellationException) throw t
            Result.failure(t)
        }
    }

    /** Concatenates the segment MP4 parts into one MP4 on [descriptor] without re-encoding. */
    private fun remux(dir: File, parts: List<String>, descriptor: java.io.FileDescriptor, rotation: Int) {
        val muxer = MediaMuxer(descriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(((rotation % 360) + 360) % 360)
        // MediaMuxer needs every track before start(); the first part defines the track layout.
        val trackMap = mutableMapOf<Int, Int>()
        var started = false
        val bufferInfo = MediaCodec.BufferInfo()
        var timeOffsetUs = 0L
        try {
            for (part in parts) {
                val file = File(dir, part)
                if (!file.exists()) continue
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.absolutePath)
                    val partTracks = mutableListOf<Pair<Int, Int>>()
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                        val outTrack = if (!started && !trackMap.containsKey(i)) {
                            val index = muxer.addTrack(format)
                            trackMap[i] = index
                            index
                        } else {
                            trackMap[i] ?: continue
                        }
                        partTracks += i to outTrack
                    }
                    if (!started) {
                        muxer.start()
                        started = true
                    }
                    var partEndUs = timeOffsetUs
                    for ((inTrack, outTrack) in partTracks) {
                        extractor.selectTrack(inTrack)
                    }
                    // Read all tracks of this part in timestamp order.
                    val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
                    while (true) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) break
                        val inTrack = extractor.sampleTrackIndex
                        val outTrack = partTracks.firstOrNull { it.first == inTrack }?.second
                        if (outTrack != null) {
                            bufferInfo.set(
                                0,
                                size,
                                extractor.sampleTime + timeOffsetUs,
                                extractor.sampleFlags,
                            )
                            muxer.writeSampleData(outTrack, buffer, bufferInfo)
                        }
                        partEndUs = maxOf(partEndUs, extractor.sampleTime + timeOffsetUs)
                        extractor.advance()
                    }
                    timeOffsetUs = partEndUs + 1_000L
                } finally {
                    extractor.release()
                }
            }
            if (!started) throw IllegalStateException("병합할 세그먼트가 없습니다")
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
        }
    }

    companion object {
        private const val TAG = "VideoPost"

        /** 2 s segments: short enough that a pause loses little work, long enough to amortise codec setup. */
        const val SEGMENT_US = 2_000_000L

        /** Default tone chain per shooting purpose; Urban is punchier, Portrait softer. */
        fun toneFor(purpose: String, nightMode: Boolean): VideoTone = when {
            nightMode -> VideoTone(contrast = 1.02f, saturation = 0.98f, sharpen = 0.18f)
            purpose == "PORTRAIT" -> VideoTone(contrast = 1.03f, saturation = 1.0f, sharpen = 0.20f)
            purpose == "LANDSCAPE" -> VideoTone(contrast = 1.08f, saturation = 1.08f, sharpen = 0.38f)
            purpose == "URBAN" -> VideoTone(contrast = 1.14f, saturation = 1.12f, sharpen = 0.45f)
            else -> VideoTone()
        }
    }
}
