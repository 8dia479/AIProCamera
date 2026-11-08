package com.aipro.camera.video

import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.aipro.common.AiproLog
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Colour/tone knobs of the video path. Serializable so a post job can be resumed later. */
@Serializable
data class VideoTone(
    val stretchLow: Int = 4,
    val stretchHigh: Int = 250,
    val contrast: Float = 1.06f,
    val saturation: Float = 1.0f,
    val sharpen: Float = 0.30f,
    val denoise: Float = 0.0f,
)

/**
 * GLES2 program that draws an external (camera/decoder) texture into a surface while applying the
 * tone chain used by both the live preview and the post-processing transcode.
 *
 * The same fragment code runs in both paths, so what the user sees live is the same math that is
 * baked into the saved enhanced video; only the inputs differ (surface size, presentation time).
 */
class ToneShaderProgram {

    private var program = 0
    private var texMatrixLocation = 0
    private var rollLocation = 0
    private var zoomLocation = 0
    private var stretchLocation = 0
    private var contrastLocation = 0
    private var saturationLocation = 0
    private var sharpenLocation = 0
    private var texelLocation = 0
    private var aPosition = 0
    private var aTexCoord = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(FULL_RECT_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(FULL_RECT_COORDS); position(0) }

    val isCompiled: Boolean get() = program != 0

    fun compile(): Boolean {
        val vertex = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        if (vertex == 0 || fragment == 0) return false
        val created = GLES20.glCreateProgram()
        GLES20.glAttachShader(created, vertex)
        GLES20.glAttachShader(created, fragment)
        GLES20.glLinkProgram(created)
        val status = IntArray(1)
        GLES20.glGetProgramiv(created, GLES20.GL_LINK_STATUS, status, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (status[0] != GLES20.GL_TRUE) {
            AiproLog.e(TAG, "link failed: ${GLES20.glGetProgramInfoLog(created)}")
            GLES20.glDeleteProgram(created)
            return false
        }
        program = created
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        rollLocation = GLES20.glGetUniformLocation(program, "uRollRadians")
        zoomLocation = GLES20.glGetUniformLocation(program, "uZoom")
        stretchLocation = GLES20.glGetUniformLocation(program, "uStretch")
        contrastLocation = GLES20.glGetUniformLocation(program, "uContrast")
        saturationLocation = GLES20.glGetUniformLocation(program, "uSaturation")
        sharpenLocation = GLES20.glGetUniformLocation(program, "uSharpen")
        texelLocation = GLES20.glGetUniformLocation(program, "uTexelSize")
        return true
    }

    /** Draws one frame. [texMatrix] comes from SurfaceTexture.getTransformMatrix. */
    fun draw(
        textureId: Int,
        texMatrix: FloatArray,
        tone: VideoTone,
        rollRadians: Float = 0f,
        zoom: Float = 1f,
        texelWidth: Float = 0f,
        texelHeight: Float = 0f,
    ) {
        if (program == 0) return
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(texMatrixLocation, 1, false, texMatrix, 0)
        GLES20.glUniform1f(rollLocation, rollRadians)
        GLES20.glUniform1f(zoomLocation, zoom.coerceIn(1f, 1.5f))
        GLES20.glUniform2f(
            stretchLocation,
            tone.stretchLow.coerceIn(0, 254).toFloat() / 255f,
            tone.stretchHigh.coerceIn(1, 255).toFloat() / 255f,
        )
        GLES20.glUniform1f(contrastLocation, tone.contrast.coerceIn(0.5f, 1.8f))
        GLES20.glUniform1f(saturationLocation, tone.saturation.coerceIn(0f, 2f))
        GLES20.glUniform1f(sharpenLocation, tone.sharpen.coerceIn(0f, 1.5f))
        GLES20.glUniform2f(texelLocation, texelWidth, texelHeight)

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            AiproLog.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private companion object {
        const val TAG = "ToneShader"

        /** x,y, u,v for two triangles covering the viewport (triangle strip). */
        val FULL_RECT_COORDS = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )

        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform float uRollRadians;
            uniform float uZoom;
            varying vec2 vTexCoord;
            void main() {
                // Horizon-lock roll: zoom in just enough to keep the frame filled, then rotate the
                // sampling coordinates around the frame centre.
                vec2 uv = (aTexCoord.xy - vec2(0.5, 0.5)) / uZoom;
                float c = cos(uRollRadians);
                float s = sin(uRollRadians);
                uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c) + vec2(0.5, 0.5);
                vTexCoord = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
                gl_Position = aPosition;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uStretch;
            uniform float uContrast;
            uniform float uSaturation;
            uniform float uSharpen;
            uniform vec2 uTexelSize;
            varying vec2 vTexCoord;

            vec3 rgbAt(vec2 uv) { return texture2D(uTexture, uv).rgb; }

            void main() {
                vec3 c = rgbAt(vTexCoord);

                // Contrast stretch: remap the [low, high] window onto [0, 1].
                float low = uStretch.x;
                float high = max(uStretch.y, low + 0.004);
                c = clamp((c - low) / (high - low), 0.0, 1.0);

                // Unsharp mask on the centre pixel; tap size comes from the real frame size.
                if (uSharpen > 0.001) {
                    vec3 blur = (
                        rgbAt(vTexCoord + vec2(uTexelSize.x, 0.0)) +
                        rgbAt(vTexCoord - vec2(uTexelSize.x, 0.0)) +
                        rgbAt(vTexCoord + vec2(0.0, uTexelSize.y)) +
                        rgbAt(vTexCoord - vec2(0.0, uTexelSize.y))
                    ) * 0.25;
                    c = clamp(c + uSharpen * (c - blur), 0.0, 1.0);
                }

                // Contrast around mid grey.
                c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);

                // Saturation.
                float luma = dot(c, vec3(0.299, 0.587, 0.114));
                c = clamp(mix(vec3(luma), c, uSaturation), 0.0, 1.0);

                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
