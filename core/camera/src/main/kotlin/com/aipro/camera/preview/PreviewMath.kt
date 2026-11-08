package com.aipro.camera.preview

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Rotation-aware preview geometry, shared by the TextureView transform and the JPEG leveling path.
 *
 * Why this exists: a TextureView stretches its buffer to the view bounds, so the transform must
 * *first* undo that stretch (a non-uniform scale), and only *afterwards* rotate and scale uniformly.
 * Doing it the other way round (rotate first, then undo the stretch) turns the rotation into an
 * anisotropic scale, which is what made the preview look "elongated" for every ratio except 1:1.
 *
 * Roll sign convention (matches [com.aipro.camera.stabilizer.HorizonFilter]): positive roll means the
 * device is rotated clockwise, so the image has to be turned counter-clockwise to level it.
 */
object PreviewMath {

    /**
     * Smallest uniform scale that makes a [contentWidth] x [contentHeight] rectangle, rotated by
     * [angleDegrees], still cover a [viewWidth] x [viewHeight] rectangle.
     *
     * Projecting the *view* onto the rotated content axes (not the other way round) is what makes
     * this exact: the corners of the view are the binding constraint, so
     * `k >= (vw·|cos| + vh·|sin|) / w` and `k >= (vw·|sin| + vh·|cos|) / h`.
     */
    fun coverScale(
        contentWidth: Float,
        contentHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        angleDegrees: Float,
    ): Float {
        if (contentWidth <= 0f || contentHeight <= 0f || viewWidth <= 0f || viewHeight <= 0f) return 1f
        val radians = Math.toRadians(angleDegrees.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        val neededWidth = (viewWidth * c + viewHeight * s) / contentWidth
        val neededHeight = (viewWidth * s + viewHeight * c) / contentHeight
        return maxOf(neededWidth, neededHeight)
    }

    /**
     * Largest [aspect] (width / height) rectangle that fits inside a [width] x [height] rectangle
     * which is itself rotated by [angleDegrees]. Used when leveling a still image so the corners the
     * rotation leaves empty are cropped away instead of showing black wedges.
     */
    fun largestInscribedAspectRect(
        width: Int,
        height: Int,
        aspect: Float,
        angleDegrees: Float,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0 || aspect <= 0f) return width to height
        val radians = Math.toRadians(angleDegrees.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        // Solve W*(c + s/aspect) <= width and W*(s + c/aspect) <= height for the largest W.
        val byWidth = width / (c + s / aspect)
        val byHeight = height / (s + c / aspect)
        val outWidth = minOf(byWidth, byHeight).toInt().coerceAtLeast(1)
        val outHeight = (outWidth / aspect).toInt().coerceAtLeast(1)
        return minOf(outWidth, width) to minOf(outHeight, height)
    }

    /**
     * Preview cover scale used while horizon lock is active. [rollDegrees] is intentionally ignored:
     * rotating the horizon rotates the image inside a fixed framing window but never zooms it.
     */
    fun horizonStableCoverScale(
        contentWidth: Float,
        contentHeight: Float,
        viewWidth: Float,
        viewHeight: Float,
        baseRotationDegrees: Float,
        rollDegrees: Float,
    ): Float = coverScale(
        contentWidth,
        contentHeight,
        viewWidth,
        viewHeight,
        baseRotationDegrees,
    )

    /** Scale needed so a [width] x [height] image rotated by [angleDegrees] still covers the output. */
    fun levelFillScale(
        width: Int,
        height: Int,
        outWidth: Int,
        outHeight: Int,
        angleDegrees: Float,
    ): Float {
        if (width <= 0 || height <= 0 || outWidth <= 0 || outHeight <= 0) return 1f
        val radians = Math.toRadians(angleDegrees.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        // Same projection argument as [coverScale], with the output rectangle as the constraint.
        val byWidth = (outWidth * c + outHeight * s) / width
        val byHeight = (outWidth * s + outHeight * c) / height
        return maxOf(byWidth, byHeight)
    }

    /**
     * Whether a rotation makes the output's width/height axes exchange places.
     * The 45° boundaries keep the output aspect stable around 0°/180° and switch it for
     * portrait-oriented quadrants around 90°/270°.
     */
    fun isAxisSwapped(angleDegrees: Float): Boolean {
        val normalized = ((angleDegrees % 360f) + 360f) % 360f
        return (normalized >= 45f && normalized < 135f) ||
            (normalized >= 225f && normalized < 315f)
    }

    /** Image rotation (degrees) that levels a device roll. Front camera mirroring flips the sign. */
    fun levelingRotation(rollDegrees: Float, mirrored: Boolean): Float =
        if (mirrored) rollDegrees else -rollDegrees

    /**
     * Buffer rotation before horizon leveling: the sensor/display rotation plus the user's offset.
     *
     * The UI setting is an *offset* (default 0 = follow the sensor). Replacing the sensor rotation
     * with the setting instead (the old behaviour) drew a landscape buffer in a portrait window, which
     * looked rotated and stretched sideways.
     */
    fun baseRotation(sensorRotationDegrees: Int, offsetDegrees: Int?): Int =
        (((sensorRotationDegrees + (offsetDegrees ?: 0)) % 360) + 360) % 360

    /** Total rotation applied to the buffer: sensor/display rotation plus horizon leveling. */
    fun totalRotation(rotationDegrees: Int, rollDegrees: Float, mirrored: Boolean): Float =
        rotationDegrees + levelingRotation(rollDegrees, mirrored)
}
