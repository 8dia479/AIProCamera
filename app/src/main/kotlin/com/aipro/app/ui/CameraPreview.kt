package com.aipro.app.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aipro.camera.preview.PreviewGeometry
import com.aipro.camera.preview.PreviewMath
import kotlinx.coroutines.flow.StateFlow

/**
 * Camera2 preview in a ratio-correct viewport.
 *
 * TextureView compensates for the physical camera-sensor orientation itself. The app must reverse
 * TextureView's default non-uniform fit, apply a uniform cover scale, and compensate for display
 * rotation. Resizing and rotating the View itself caused the portrait/landscape distortion.
 */
@Composable
fun CameraPreview(
    geometry: PreviewGeometry?,
    displayAspect: Float,
    displayRotationDegrees: Int,
    onTextureAvailable: (SurfaceTexture, Int, Int) -> Unit,
    onTextureDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
    /** Live device roll used by horizon lock. */
    rollDegrees: StateFlow<Float>? = null,
    /** Additional manual sensor correction. Zero means no extra correction. */
    rotationOffset: Int? = null,
) {
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val applier = remember { TransformApplier() }
    val latestGeometry by rememberUpdatedState(geometry)
    val latestOffset by rememberUpdatedState(rotationOffset)

    val correction = rotationOffset ?: 0
    val normalizedCorrection = ((correction % 360) + 360) % 360
    // A manual quarter-turn changes the selected frame's axes. Sensor orientation is handled by
    // TextureView and must not be added to this user-facing correction.
    val effectiveDisplayAspect =
        if (normalizedCorrection == 90 || normalizedCorrection == 270) 1f / displayAspect
        else displayAspect

    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val viewport = fitInside(maxWidth, maxHeight, effectiveDisplayAspect)

        applier.transform = { view ->
            applyViewTransform(
                view = view,
                geometry = latestGeometry,
                displayRotationDegrees = displayRotationDegrees,
                offset = latestOffset,
                mirrored = latestGeometry?.mirrored == true,
            )
        }

        Box(
            modifier = Modifier.size(viewport.first, viewport.second).clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, w: Int, h: Int) {
                                onTextureAvailable(texture, w, h)
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, w: Int, h: Int) {
                                onTextureAvailable(texture, w, h)
                            }

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                onTextureDestroyed()
                                return true
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                            textureView = view as TextureView
                            applier.transform?.invoke(view)
                        }
                    }
                },
                update = { view ->
                    textureView = view
                    applier.transform?.invoke(view)
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Base framing is fixed. Only the live leveling angle changes, preventing horizon lock
            // from continuously changing the scale/zoom as the phone is rotated.
            LaunchedEffect(
                textureView,
                viewport.first,
                viewport.second,
                geometry,
                displayRotationDegrees,
                rotationOffset,
                rollDegrees,
            ) {
                val view = textureView ?: return@LaunchedEffect
                applier.transform?.invoke(view)
                val applyRoll: (Float) -> Unit = { roll ->
                    view.rotation = PreviewMath.levelingRotation(roll, geometry?.mirrored == true)
                }
                if (rollDegrees == null) {
                    applyRoll(0f)
                } else {
                    rollDegrees.collect(applyRoll)
                }
            }
        }
    }
}

/** Holds the latest transform lambda so layout callbacks use current camera geometry. */
internal class TransformApplier {
    var transform: ((TextureView) -> Unit)? = null
}

/**
 * Reverses TextureView's default independent X/Y scaling before applying one uniform cover scale.
 * The matrix uses the Camera2 relative rotation only to decide whether the preview axes are swapped.
 * It does not rotate by SENSOR_ORIENTATION a second time: TextureView already handles that.
 */
internal fun applyViewTransform(
    view: TextureView,
    geometry: PreviewGeometry?,
    displayRotationDegrees: Int,
    offset: Int? = null,
    mirrored: Boolean = false,
) {
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    val bufferWidth = geometry?.bufferWidth?.toFloat() ?: 0f
    val bufferHeight = geometry?.bufferHeight?.toFloat() ?: 0f
    if (
        viewWidth <= 0f || viewHeight <= 0f ||
        bufferWidth <= 0f || bufferHeight <= 0f ||
        geometry == null
    ) return

    val correction = offset ?: 0
    val relativeRotation = ((geometry.rotationDegrees + correction) % 360 + 360) % 360
    val isRotationRequired = relativeRotation % 180 != 0
    val sensorOrientation = ((geometry.sensorOrientationDegrees % 360) + 360) % 360

    // These factors undo the non-uniform fit TextureView applied when mapping the camera buffer
    // into the viewport. This is the Android Camera2 resizable-preview scaling algorithm.
    val scaleX: Float
    val scaleY: Float
    if (sensorOrientation == 0) {
        scaleX = if (!isRotationRequired) viewWidth / bufferHeight else viewWidth / bufferWidth
        scaleY = if (!isRotationRequired) viewHeight / bufferWidth else viewHeight / bufferHeight
    } else {
        scaleX = if (isRotationRequired) viewWidth / bufferHeight else viewWidth / bufferWidth
        scaleY = if (isRotationRequired) viewHeight / bufferWidth else viewHeight / bufferHeight
    }
    if (
        scaleX <= 0f || scaleY <= 0f ||
        !scaleX.isFinite() || !scaleY.isFinite()
    ) return

    val uniformCover = maxOf(scaleX, scaleY)
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f
    val matrix = Matrix()
    if (isRotationRequired) {
        matrix.setScale(
            (1f / scaleX) * uniformCover,
            (1f / scaleY) * uniformCover,
            centerX,
            centerY,
        )
    } else {
        matrix.setScale(
            (viewHeight / viewWidth / scaleY) * uniformCover,
            (viewWidth / viewHeight / scaleX) * uniformCover,
            centerX,
            centerY,
        )
    }
    // Display#getRotation is counter-clockwise. Manual correction is an extra clockwise turn.
    matrix.postRotate((-displayRotationDegrees + correction).toFloat(), centerX, centerY)
    view.setTransform(matrix)

    // Keep the base sensor/display transform inside the texture matrix. View properties are reserved
    // for front-camera mirroring and the live horizon-lock roll.
    view.rotation = 0f
    view.scaleX = if (mirrored) -1f else 1f
    view.scaleY = 1f

    val signature = "buf=${bufferWidth.toInt()}x${bufferHeight.toInt()} " +
        "view=${viewWidth.toInt()}x${viewHeight.toInt()} sensor=${sensorOrientation} " +
        "correction=${correction} display=${displayRotationDegrees} relative=${relativeRotation} " +
        "cover=${"%.2f".format(uniformCover)}"
    if (signature != lastLoggedSignature) {
        lastLoggedSignature = signature
        android.util.Log.i("AIProPreview", signature)
        PreviewDiag.publish(
            "센서 보정 ${correction}° · 표시 회전 ${displayRotationDegrees}° · 내부 방향 ${relativeRotation}°",
        )
    }
}

private var lastLoggedSignature: String? = null

/** Biggest box with [aspect] (width / height) that fits in [maxWidth] x [maxHeight]. */
private fun fitInside(maxWidth: Dp, maxHeight: Dp, aspect: Float): Pair<Dp, Dp> {
    if (aspect <= 0f) return maxWidth to maxHeight
    val widthByHeight = maxHeight * aspect
    return if (widthByHeight <= maxWidth) widthByHeight to maxHeight else maxWidth to (maxWidth / aspect)
}

/** Rule-of-thirds grid (spec section 4). */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val stroke = Stroke(width = 1f)
        val color = Color(0x40FFFFFF)
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke.width)
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke.width)
        }
    }
}

/** Thin level indicator drawn while the horizon lock is active. */
@Composable
fun FrameGuide(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val inset = size.minDimension * 0.08f
        drawRect(
            color = Color(0x33FFFFFF),
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
            style = Stroke(width = 2f),
        )
    }
}

/** Runs [onLeave] when the preview leaves the composition (stops the camera cleanly). */
@Composable
fun PreviewLifecycle(onLeave: () -> Unit) {
    DisposableEffect(Unit) { onDispose { onLeave() } }
}
