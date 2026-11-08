package com.aipro.camera.video

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.aipro.common.AiproLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-time preview processing (spec section 8): camera frames go into an OES texture, through the
 * same [ToneShaderProgram] used by the post job, and are drawn onto the view surface. Because this
 * chain only ever touches the preview path, enabling or disabling it can never stop or corrupt the
 * original recording — the MediaRecorder keeps its own surface from the camera session.
 *
 * `setRollRadians` is fed by the stage-6 horizon lock, so what the user sees is what the still capture
 * and the post job will reproduce.
 */
class LivePreviewProcessor(
    private val viewSurface: Surface,
    private val viewWidth: Int,
    private val viewHeight: Int,
    /** Camera preview buffer size; the texture must not be resized to the view or the aspect breaks. */
    private val bufferWidth: Int = viewWidth,
    private val bufferHeight: Int = viewHeight,
    initialTone: VideoTone = VideoTone(),
) {

    private val running = AtomicBoolean(false)
    private val tone = AtomicReference(initialTone)
    private val roll = AtomicReference(0f)
    private val zoom = AtomicReference(1f)
    private val frameAvailable = AtomicBoolean(false)
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var egl: EglCore? = null
    private var eglSurface: EGLSurface? = null
    private var program: ToneShaderProgram? = null
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    /** Surface the camera session should target. Available after [start] returned true. */
    var cameraInputSurface: Surface? = null
        private set

    private val texMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    fun setTone(next: VideoTone) = tone.set(next)

    fun setRollRadians(radians: Float, fillZoom: Float = 1f) {
        roll.set(radians)
        zoom.set(fillZoom)
    }

    fun start(): Boolean {
        if (running.get()) return true
        val eglCore = EglCore()
        if (!eglCore.init()) return false
        val window = eglCore.createWindowSurface(viewSurface)
        if (window == EGL14.EGL_NO_SURFACE) {
            eglCore.release()
            return false
        }
        if (!eglCore.makeCurrent(window)) {
            eglCore.releaseSurface(window)
            eglCore.release()
            return false
        }
        val shader = ToneShaderProgram()
        if (!shader.compile()) {
            eglCore.releaseSurface(window)
            eglCore.release()
            return false
        }
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val texture = SurfaceTexture(textures[0])
        texture.setDefaultBufferSize(bufferWidth.coerceAtLeast(1), bufferHeight.coerceAtLeast(1))
        texture.setOnFrameAvailableListener { frameAvailable.set(true) }
        surfaceTexture = texture
        cameraInputSurface = Surface(texture)

        egl = eglCore
        eglSurface = window
        program = shader
        textureId = textures[0]

        // The render thread owns the GL context, so the context is re-made current on it before drawing.
        val thread = HandlerThread("live-preview-gl").apply { start() }
        this.thread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        running.set(true)
        eglCore.makeNothingCurrent()
        handler.post { renderLoop() }
        AiproLog.i(TAG, "live preview processor 시작 ${viewWidth}x$viewHeight")
        return true
    }

    private fun renderLoop() {
        val eglCore = egl ?: return
        val window = eglSurface ?: return
        val shader = program ?: return
        if (!eglCore.makeCurrent(window)) return
        while (running.get()) {
            if (!frameAvailable.getAndSet(false)) {
                Thread.sleep(4)
                continue
            }
            val texture = surfaceTexture ?: break
            runCatching { texture.updateTexImage() }
            texture.getTransformMatrix(texMatrix)
            GLES20.glViewport(0, 0, viewWidth, viewHeight)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            shader.draw(
                textureId = textureId,
                texMatrix = texMatrix,
                tone = tone.get(),
                rollRadians = roll.get(),
                zoom = zoom.get(),
                texelWidth = 1f / viewWidth.coerceAtLeast(1),
                texelHeight = 1f / viewHeight.coerceAtLeast(1),
            )
            eglCore.swapBuffers(window)
        }
        eglCore.makeNothingCurrent()
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        handler?.post {
            val eglCore = egl
            if (eglCore != null) {
                eglCore.makeCurrent(eglSurface ?: EGL14.EGL_NO_SURFACE)
                program?.release()
                GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
                eglCore.releaseSurface(eglSurface)
                eglCore.release()
            }
            thread?.quitSafely()
        }
        runCatching { cameraInputSurface?.release() }
        cameraInputSurface = null
        surfaceTexture = null
        program = null
        egl = null
        eglSurface = null
    }

    private companion object {
        const val TAG = "LivePreview"
    }
}
