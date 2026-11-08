package com.aipro.camera.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.aipro.camera.stabilizer.HorizonFilter
import com.aipro.common.AiproLog
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Encodes one segment of a source video through the GL tone chain:
 *
 * `MediaExtractor -> MediaCodec decoder -> SurfaceTexture -> tone shader -> encoder input surface
 * -> MediaMuxer`
 *
 * A fresh decoder/encoder/muxer per segment is intentional: an MP4 cannot be appended to, so a
 * resumable job must produce independent segment files that a cheap remux joins at the end.
 * Audio is copied from a second extractor without decoding, so the original sound is preserved.
 */
internal class VideoSegmentEncoder(
    private val context: Context,
    private val sourceUri: Uri,
    private val tone: VideoTone,
    private val rotationDegrees: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val horizonRollDegrees: Float = 0f,
    private val horizonSamples: List<HorizonSample> = emptyList(),
    private val frameRate: Int = 30,
) {

    data class SegmentResult(
        val frames: Int,
        val startUs: Long,
        val endUs: Long,
        val usedGl: Boolean,
        val audioCopied: Boolean,
    )

    fun encode(startUs: Long, endUs: Long, output: File): SegmentResult {
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val videoExtractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var outputSurface: DecoderOutputSurface? = null
        var egl: EglCore? = null
        var eglSurface: android.opengl.EGLSurface? = null
        var program: ToneShaderProgram? = null

        return try {
            videoExtractor.setDataSource(context, sourceUri, null)
            val videoTrack = findTrack(videoExtractor, "video/")
                ?: throw IllegalStateException("소스에 비디오 트랙이 없습니다")
            videoExtractor.selectTrack(videoTrack)
            videoExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            decoder = MediaCodec.createDecoderByType(mime)
            val eglCore = EglCore()
            egl = eglCore
            if (!eglCore.init()) throw IllegalStateException("EGL 컨텍스트 생성 실패")

            val textureId = createExternalTexture()
            val outputSurfaceHolder = DecoderOutputSurface(textureId)
            outputSurface = outputSurfaceHolder
            decoder.configure(videoFormat, outputSurfaceHolder.surface, null, 0)
            decoder.start()

            val encoderMime = MediaFormat.MIMETYPE_VIDEO_AVC
            encoder = MediaCodec.createEncoderByType(encoderMime)
            encoder.configure(
                encoderFormat(encoderMime, width, height),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            val encoderSurface = encoder.createInputSurface()
            encoder.start()

            eglSurface = eglCore.createWindowSurface(encoderSurface)
            if (eglSurface == android.opengl.EGL14.EGL_NO_SURFACE) throw IllegalStateException("EGL 서피스 생성 실패")
            if (!eglCore.makeCurrent(eglSurface)) throw IllegalStateException("EGL makeCurrent 실패")

            val shader = ToneShaderProgram()
            program = shader
            if (!shader.compile()) throw IllegalStateException("톤 셰이더 컴파일 실패")

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(((rotationDegrees % 360) + 360) % 360)

            val stats = transcode(
                videoExtractor = videoExtractor,
                decoder = decoder,
                encoder = encoder,
                muxer = muxer,
                egl = eglCore,
                eglSurface = eglSurface,
                shader = shader,
                outputSurface = outputSurfaceHolder,
                width = width,
                height = height,
                startUs = startUs,
                endUs = endUs,
            )

            val audioCopied = copyAudio(startUs, endUs, muxer)
            muxer.stop()
            stats.copy(audioCopied = audioCopied)
        } finally {
            runCatching { muxer?.release() }
            runCatching { egl?.releaseSurface(eglSurface) }
            runCatching { egl?.release() }
            runCatching { program?.release() }
            runCatching { outputSurface?.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { videoExtractor.release() }
        }
    }

    private fun transcode(
        videoExtractor: MediaExtractor,
        decoder: MediaCodec,
        encoder: MediaCodec,
        muxer: MediaMuxer,
        egl: EglCore,
        eglSurface: android.opengl.EGLSurface,
        shader: ToneShaderProgram,
        outputSurface: DecoderOutputSurface,
        width: Int,
        height: Int,
        startUs: Long,
        endUs: Long,
    ): SegmentResult {
        val bufferInfo = MediaCodec.BufferInfo()
        val texMatrix = FloatArray(16)
        Matrix.setIdentityM(texMatrix, 0)
        var videoTrack = -1
        var frames = 0
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        val filter = HorizonFilter()
        val frameAspect = width.toFloat() / height.coerceAtLeast(1)
        val texelX = 1f / width.toFloat()
        val texelY = 1f / height.toFloat()

        while (!encoderDone) {
            if (!inputDone) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer: ByteBuffer = decoder.getInputBuffer(inIndex) ?: ByteBuffer.allocate(0)
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, videoExtractor.sampleTime, 0)
                        videoExtractor.advance()
                    }
                }
            }

            if (!decoderDone) {
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                if (outIndex >= 0) {
                    val eos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val ptsUs = bufferInfo.presentationTimeUs
                    val render = ptsUs >= startUs && ptsUs < endUs && bufferInfo.size > 0
                    decoder.releaseOutputBuffer(outIndex, render)
                    if (render) {
                        if (!outputSurface.awaitFrame()) {
                            AiproLog.e(TAG, "frame $ptsUs 대기 시간 초과, 세그먼트 중단")
                            break
                        }
                        outputSurface.updateTexImage()
                        outputSurface.getTransformMatrix(texMatrix)
                        GLES20.glViewport(0, 0, width, height)
                        GLES20.glClearColor(0f, 0f, 0f, 1f)
                        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                        // Interpolate the phone roll at this frame's presentation timestamp.
                        // The encoder viewport stays width×height for every frame, so only the
                        // inscribed crop changes; the output aspect ratio cannot wobble with tilt.
                        val rollDegrees = rollAt(ptsUs)
                        val rollRadians = Math.toRadians(rollDegrees.toDouble()).toFloat()
                        val fillZoom = filter.fillZoom(rollRadians, frameAspect)
                        shader.draw(
                            textureId = outputSurface.textureId,
                            texMatrix = texMatrix,
                            tone = tone,
                            rollRadians = rollRadians,
                            zoom = fillZoom,
                            texelWidth = texelX,
                            texelHeight = texelY,
                            aspect = frameAspect,
                        )
                        egl.setPresentationTime(eglSurface, (ptsUs - startUs) * 1_000L)
                        if (!egl.swapBuffers(eglSurface)) {
                            AiproLog.e(TAG, "swapBuffers 실패")
                            break
                        }
                        ++frames
                    }
                    if (eos) decoderDone = true
                    if (ptsUs >= endUs && bufferInfo.size > 0) decoderDone = true
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Decoder format change is normal; the GL path does not need it.
                }
            }

            val encIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (encIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoTrack = muxer.addTrack(encoder.outputFormat)
                muxer.start()
            } else if (encIndex >= 0) {
                val encoded: ByteBuffer = encoder.getOutputBuffer(encIndex) ?: ByteBuffer.allocate(0)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size > 0 && videoTrack >= 0) {
                    encoded.position(bufferInfo.offset)
                    encoded.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(videoTrack, encoded, bufferInfo)
                }
                encoder.releaseOutputBuffer(encIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
            }

            if (decoderDone && !encoderDone) {
                val signal = encoder.dequeueInputBuffer(0)
                if (signal >= 0) {
                    encoder.queueInputBuffer(signal, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
        }
        return SegmentResult(frames = frames, startUs = startUs, endUs = endUs, usedGl = true, audioCopied = false)
    }

    /** Copies the audio track sample-for-sample (no decode/encode) for this segment's time window. */
    private fun copyAudio(startUs: Long, endUs: Long, muxer: MediaMuxer): Boolean = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, sourceUri, null)
            val audioTrackIndex = findTrack(extractor, "audio/") ?: return false
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val outTrack = muxer.addTrack(format)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val info = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocate(256 * 1024)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts >= endUs) break
                if (pts >= startUs) {
                    info.set(0, size, (pts - startUs).coerceAtLeast(0L), extractor.sampleFlags)
                    muxer.writeSampleData(outTrack, buffer, info)
                }
                extractor.advance()
            }
            true
        } finally {
            extractor.release()
        }
    }.onFailure { AiproLog.e(TAG, "오디오 복사 실패", it) }.getOrDefault(false)

    private fun encoderFormat(mime: String, width: Int, height: Int): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime, width, height)
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        )
        // ~0.12 bit per pixel per frame is a safe "high quality but not absurd" default for 1080p/4K.
        format.setInteger(MediaFormat.KEY_BIT_RATE, (width * height * frameRate * 0.12).toInt().coerceAtLeast(4_000_000))
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        return format
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        return null
    }

    private fun createExternalTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        return textures[0]
    }


    /** Shortest-path interpolation between sensor samples; falls back to the legacy fixed roll. */
    private fun rollAt(timeUs: Long): Float {
        if (horizonSamples.isEmpty()) return horizonRollDegrees
        val first = horizonSamples.first()
        val last = horizonSamples.last()
        if (timeUs <= first.timeUs) return first.rollDegrees
        if (timeUs >= last.timeUs) return last.rollDegrees
        val rightIndex = horizonSamples.binarySearch { it.timeUs.compareTo(timeUs) }
            .let { if (it >= 0) it else -it - 1 }
        val right = horizonSamples[rightIndex]
        val left = horizonSamples[rightIndex - 1]
        val span = (right.timeUs - left.timeUs).coerceAtLeast(1L)
        val fraction = ((timeUs - left.timeUs).toFloat() / span).coerceIn(0f, 1f)
        var delta = (right.rollDegrees - left.rollDegrees) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return left.rollDegrees + delta * fraction
    }

    companion object {
        private const val TAG = "VideoSegment"

        /** Reads the video geometry/rotation/duration without decoding anything. */
        fun probe(context: Context, uri: Uri): SourceInfo? = runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                var videoTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        videoTrack = i
                        break
                    }
                }
                if (videoTrack < 0) return null
                val format = extractor.getTrackFormat(videoTrack)
                val width = format.getInteger(MediaFormat.KEY_WIDTH)
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else {
                    0
                }
                val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                SourceInfo(width, height, ((rotation % 360) + 360) % 360, duration)
            } finally {
                extractor.release()
            }
        }.onFailure { AiproLog.e(TAG, "소스 분석 실패", it) }.getOrNull()
    }

    data class SourceInfo(val width: Int, val height: Int, val rotationDegrees: Int, val durationUs: Long)
}

/** SurfaceTexture wrapper that lets the transcode loop wait for the next decoded frame. */
internal class DecoderOutputSurface(val textureId: Int) {

    private val texture = android.graphics.SurfaceTexture(textureId)
    val surface: Surface = Surface(texture)
    private val latch = AtomicReference(CountDownLatch(1))

    init {
        texture.setOnFrameAvailableListener {
            latch.get().countDown()
        }
    }

    fun awaitFrame(timeoutMs: Long = 4_000L): Boolean {
        val current = latch.get()
        val ok = current.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (ok) latch.set(CountDownLatch(1))
        return ok
    }

    fun updateTexImage() {
        runCatching { texture.updateTexImage() }
    }

    fun getTransformMatrix(matrix: FloatArray) {
        texture.getTransformMatrix(matrix)
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { texture.release() }
    }
}
