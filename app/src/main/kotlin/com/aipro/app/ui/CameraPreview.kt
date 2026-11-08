package com.aipro.app.ui

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aipro.camera.preview.PreviewGeometry
import com.aipro.camera.preview.PreviewMath
import kotlinx.coroutines.flow.StateFlow

/**
 * Camera preview.
 *
 * The image is never transformed by a hand-built `TextureView.setTransform` matrix any more. That
 * matrix lived in an ambiguous coordinate space (view space vs texture space) and, as soon as the
 * buffer was rotated, it turned into an anisotropic scale - the "옆으로 늘어난" preview.
 *
 * Instead:
 *  1. the ratio box (1:1 / 4:3 / 16:9 / 22:9 as seen in portrait) is the clip window,
 *  2. the `TextureView` is sized so that **its own aspect ratio equals the camera buffer's**, which
 *     makes the TextureView's implicit "stretch buffer to view bounds" a *uniform* scale,
 *  3. rotation and the (uniform) cover scale are applied as plain [android.view.View] properties,
 *     which can only scale uniformly - a stretched image is impossible by construction.
 *
 * When the preview stream matches the selected ratio (which the engine now picks on purpose) the
 * cover scale is exactly 1.0, so there is no zoom-in either.
 */
@Composable
fun CameraPreview(
    geometry: PreviewGeometry?,
    displayAspect: Float,
    onTextureAvailable: (SurfaceTexture, Int, Int) -> Unit,
    onTextureDestroyed: () -> Unit,
    modifier: Modifier = Modifier,
    /** Live device roll (degrees) used by the horizon lock; collected off the recomposition path. */
    rollDegrees: StateFlow<Float>? = null,
    rotationOffset: Int? = null,
) {
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    // Re-applied from the layout listener as well: on the first frames the view has no size yet and
    // `update` is not called again just because layout changed.
    // Plain holder (not a MutableState): writing it must not schedule a recomposition.
    val applier = remember { TransformApplier() }
    val latestGeometry by rememberUpdatedState(geometry)
    val latestRoll by rememberUpdatedState(rollDegrees)
    val latestOffset by rememberUpdatedState(rotationOffset)
    val rotationDegrees = geometry?.rotationDegrees ?: 0
    val effectiveRotation = PreviewMath.baseRotation(rotationDegrees, rotationOffset)
    // A quarter-turn offset swaps the visible frame's axes as well as the image.
    val baseSwapsAxes = rotationDegrees == 90 || rotationDegrees == 270
    val effectiveSwapsAxes = effectiveRotation == 90 || effectiveRotation == 270
    val effectiveDisplayAspect = if (baseSwapsAxes != effectiveSwapsAxes && displayAspect > 0f) {
        1f / displayAspect
    } else {
        displayAspect
    }
    val bufferAspect = bufferAspectOf(geometry, effectiveDisplayAspect)

    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // The visible frame follows the *effective* sensor + user offset rotation.
        val box: Pair<Dp, Dp> = fitInside(maxWidth, maxHeight, effectiveDisplayAspect)
        val quarterTurn = effectiveSwapsAxes
        // Size for the *rotated* image, then undo the quarter turn for the view itself, so the
        // unrotated view is larger than the box and only gets clipped - never upscaled.
        val shown = fitInside(box.first, box.second, if (quarterTurn) 1f / bufferAspect else bufferAspect)
        val viewSize = if (quarterTurn) shown.second to shown.first else shown
        val boxWidthPx = with(density) { box.first.toPx() }
        val boxHeightPx = with(density) { box.second.toPx() }
        applier.transform = { view ->
            applyViewTransform(
                view = view,
                rotationDegrees = rotationDegrees,
                boxWidthPx = boxWidthPx,
                boxHeightPx = boxHeightPx,
                roll = latestRoll?.value ?: 0f,
                offset = latestOffset,
                mirrored = geometry?.mirrored == true,
            )
        }

        Box(
            modifier = Modifier.size(box.first, box.second).clipToBounds(),
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
                // requiredSize: the view is deliberately *larger* than the ratio box (it is clipped),
                // so the parent's constraints must not shrink it back.
                modifier = Modifier.requiredSize(viewSize.first, viewSize.second),
            )

            // Roll is a 60 Hz signal: it is applied straight to the View, not through recomposition.
            LaunchedEffect(
                textureView,
                boxWidthPx,
                boxHeightPx,
                effectiveRotation,
                geometry?.mirrored,
                rollDegrees,
                rotationOffset,
            ) {
                val view = textureView ?: return@LaunchedEffect
                val apply = { roll: Float ->
                    applyViewTransform(
                        view,
                        rotationDegrees,
                        boxWidthPx,
                        boxHeightPx,
                        roll,
                        rotationOffset,
                        geometry?.mirrored == true,
                    )
                }
                if (rollDegrees == null) apply(0f) else rollDegrees.collect(apply)
            }
        }
    }
}

/** Holds the latest transform lambda so the layout listener always applies current values. */
internal class TransformApplier {
    var transform: ((TextureView) -> Unit)? = null
}

private fun bufferAspectOf(geometry: PreviewGeometry?, displayAspect: Float): Float {
    val width = geometry?.bufferWidth ?: 0
    val height = geometry?.bufferHeight ?: 0
    if (width <= 0 || height <= 0) return displayAspect
    return width.toFloat() / height.toFloat()
}

/**
 * Sets rotation and a **uniform** cover scale on [view]. Because both scale factors are the same, no
 * amount of rotation can stretch the image; the only trade-off is cropping, which is intended (the
 * clip window is the selected ratio).
 */
internal fun applyViewTransform(
    view: TextureView,
    rotationDegrees: Int,
    boxWidthPx: Float,
    boxHeightPx: Float,
    roll: Float = 0f,
    offset: Int? = null,
    mirrored: Boolean = false,
) {
    val viewWidth = view.width.toFloat()
    val viewHeight = view.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f || boxWidthPx <= 0f || boxHeightPx <= 0f) return

    val baseRotation = PreviewMath.baseRotation(rotationDegrees, offset)
    val rotation = PreviewMath.totalRotation(baseRotation, roll, mirrored)

    // Do not recalculate cover scale from the live horizon angle. That made the camera zoom in
    // continuously as the phone rolled. Keep the framing fixed; black corner wedges are preferable
    // to a changing focal length/FOV while horizon lock is active.
    val scale = PreviewMath.horizonStableCoverScale(
        contentWidth = viewWidth,
        contentHeight = viewHeight,
        viewWidth = boxWidthPx,
        viewHeight = boxHeightPx,
        baseRotationDegrees = baseRotation.toFloat(),
        rollDegrees = roll,
    ).coerceIn(0.5f, 4f)

    view.rotation = rotation
    view.scaleX = if (mirrored) -scale else scale
    view.scaleY = scale

    val userOffset = offset ?: 0
    val signature = "buf=${viewWidth.toInt()}x${viewHeight.toInt()} box=${boxWidthPx.toInt()}x${boxHeightPx.toInt()} " +
        "sensorFix=${rotationDegrees} offset=${userOffset} scale=${"%.2f".format(scale)} roll=${"%.1f".format(roll)}"
    if (signature != lastLoggedSignature) {
        lastLoggedSignature = signature
        android.util.Log.i("AIProPreview", signature)
        PreviewDiag.publish(
            "센서 보정 ${rotationDegrees}° · 사용자 보정 ${userOffset}° · 수평 ${"%.1f".format(roll)}°",
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
