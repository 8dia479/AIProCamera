package com.aipro.camera.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import com.aipro.common.AiproLog

/**
 * Minimal EGL14 helper: an offscreen/window GLES2 context plus window surfaces.
 *
 * Used by both the live preview chain and the video post-processing transcode. Sizes and surfaces are
 * provided by the caller because the encoder defines the transcode size and the view defines the
 * preview size. Everything is released in [release]; a failure to create a context is reported to the
 * caller (which then degrades to the non-GL path) instead of being swallowed.
 */
class EglCore {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null

    val isReady: Boolean get() = display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT

    fun init(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return fail("eglGetDisplay")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return fail("eglInitialize")

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            return fail("eglChooseConfig")
        }
        config = configs[0]
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttributes, 0)
        return if (context == EGL14.EGL_NO_CONTEXT) fail("eglCreateContext") else true
    }

    /** Window surface over an encoder input surface or a [Surface] backed by a view/SurfaceTexture. */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val attributes = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, attributes, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            AiproLog.e(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
        }
        return eglSurface
    }

    fun makeCurrent(eglSurface: EGLSurface): Boolean =
        EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

    fun swapBuffers(eglSurface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, eglSurface)

    /** Stamps the frame time so the encoder muxes correct timestamps. */
    fun setPresentationTime(eglSurface: EGLSurface, ptsNanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNanos)
    }

    fun releaseSurface(eglSurface: EGLSurface?) {
        if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, eglSurface)
        }
    }

    fun makeNothingCurrent() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        }
    }

    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            makeNothingCurrent()
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
    }

    private fun fail(where: String): Boolean {
        AiproLog.e(TAG, "$where failed: ${EGL14.eglGetError()}")
        release()
        return false
    }

    private companion object {
        const val TAG = "EglCore"
    }
}
