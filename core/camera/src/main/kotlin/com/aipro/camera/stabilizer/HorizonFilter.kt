package com.aipro.camera.stabilizer

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Gyro + accelerometer roll estimator for the horizon lock.
 *
 * Design notes (this is what makes the lock keep up with a fast turn):
 *  * the gyroscope is the *fast* signal: its z rate is integrated using the sensor timestamps, so a
 *    90° twist is followed within one sample,
 *  * the accelerometer is the *absolute* reference: it drifts not at all but is noisy and lags during
 *    motion. It is therefore only allowed to pull the estimate at a limited rate
 *    ([maxCorrectionDegPerSecond]) instead of blending per sample — a per-sample blend with a large
 *    weight is what made the preview visibly lag behind the phone,
 *  * the published roll is [predictionSeconds] ahead of the integrated value, compensating the pipeline
 *    latency between the sensor event and the frame that is on screen,
 *  * angles are unwrapped, so a full 360° rotation is followed instead of snapping at ±180°.
 *
 * Device axes follow Android: +x right, +y up, +z out of the screen. Positive roll = clockwise from
 * the viewer's point of view. Pure Kotlin, no Android imports: [HorizonFilterTest] drives it with
 * synthetic sensor data.
 */
class HorizonFilter(
    private val accelTrust: Float = 0.06f,
    private val maxRollDegrees: Float = 180f,
    private val maxCorrectionDegPerSecond: Float = 45f,
    private val predictionSeconds: Float = 0.035f,
) {

    private var rollDegrees = 0f
    private var lastGyroTimestampNanos = 0L
    private var lastAccelTimestampNanos = 0L
    private var initialised = false
    private var accelRollDegrees = 0f
    private var gyroDeltaDegrees = 0f
    private var gyroRateDegPerSecond = 0f

    /** Predicted, display-ready roll (degrees). */
    val roll: Float get() = clamp(rollDegrees + gyroRateDegPerSecond * predictionSeconds)

    /** Un-predicted integrated roll, used by tests and by the video post job. */
    val integratedRoll: Float get() = rollDegrees

    /** Absolute roll from gravity, only meaningful while the device is not being accelerated. */
    val accelReference: Float get() = accelRollDegrees

    /** |gyro roll − accel roll|: large values mean real motion, so the lock should not be trusted. */
    val disagreement: Float get() = abs(shortestDelta(accelRollDegrees, rollDegrees))

    fun reset() {
        rollDegrees = 0f
        accelRollDegrees = 0f
        gyroDeltaDegrees = 0f
        gyroRateDegPerSecond = 0f
        lastGyroTimestampNanos = 0L
        lastAccelTimestampNanos = 0L
        initialised = false
    }

    /** Gyroscope sample in rad/s. Returns the predicted roll in degrees. */
    fun onGyroscope(radiansPerSecondZ: Float, timestampNanos: Long): Float {
        if (!initialised || timestampNanos <= lastGyroTimestampNanos) {
            lastGyroTimestampNanos = timestampNanos
            return roll
        }
        val dt = ((timestampNanos - lastGyroTimestampNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
        lastGyroTimestampNanos = timestampNanos
        gyroRateDegPerSecond = Math.toDegrees(radiansPerSecondZ.toDouble()).toFloat()
        gyroDeltaDegrees = gyroRateDegPerSecond * dt
        rollDegrees = clamp(rollDegrees + gyroDeltaDegrees)
        initialised = true
        return roll
    }

    /** Accelerometer sample in m/s². Returns the predicted roll in degrees. */
    fun onAccelerometer(x: Float, y: Float, z: Float, timestampNanos: Long): Float {
        val magnitude = sqrt(x * x + y * y + z * z)
        // Outside 4..12 m/s² the device is being shaken/handled; gravity is not a valid reference.
        if (magnitude < 4f || magnitude > 12f) return roll
        if (abs(x) < 0.001f && abs(y) < 0.001f) return roll
        accelRollDegrees = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
        if (!initialised) {
            rollDegrees = clamp(accelRollDegrees)
            lastGyroTimestampNanos = timestampNanos
            lastAccelTimestampNanos = timestampNanos
            initialised = true
            return roll
        }
        val dt = if (lastAccelTimestampNanos == 0L) {
            0.02f
        } else {
            ((timestampNanos - lastAccelTimestampNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.2f)
        }
        lastAccelTimestampNanos = timestampNanos
        // A stale gyro rate must not keep predicting movement: the accelerometer only lands when the
        // sensor hub is delivering samples, so decay the rate here (fresh gyro samples overwrite it).
        gyroRateDegPerSecond *= 0.5f
        if (dt <= 0f) return roll
        // Shortest angular path, so a 360° turn is not "corrected" back to 0. The sign matters: this
        // is "how far the estimate has to move to reach gravity" (accel − roll); using the opposite
        // sign makes the correction run away instead of settling.
        val error = shortestDelta(rollDegrees, accelRollDegrees)
        val maxStep = maxCorrectionDegPerSecond * dt
        val blended = error * accelTrust * 4f
        rollDegrees = clamp(rollDegrees + blended.coerceIn(-maxStep, maxStep))
        return roll
    }

    /** Roll to hand to the renderer: 0 when the phone is upright, radians elsewhere. */
    fun rollRadians(): Float = Math.toRadians(roll.toDouble()).toFloat()

    /**
     * Zoom needed so rotating by [rollRadians] does not expose empty corners. For a rotation of θ the
     * inscribed axis-aligned rectangle shrinks by |cosθ| + |sinθ| in the worst case.
     */
    fun fillZoom(rollRadians: Float = rollRadians(), sourceAspect: Float = 1f): Float {
        val c = abs(cos(rollRadians))
        val s = abs(kotlin.math.sin(rollRadians))
        val aspect = sourceAspect.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.1f, 10f) ?: 1f
        // Project rotated output corners onto the source axes; c+s alone assumes square pixels.
        return maxOf(c + s / aspect, c + s * aspect).coerceIn(1f, 2.5f)
    }

    /** Signed difference a→b inside (-180, 180]. */
    private fun shortestDelta(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun clamp(value: Float): Float = value.coerceIn(-maxRollDegrees, maxRollDegrees)
}
