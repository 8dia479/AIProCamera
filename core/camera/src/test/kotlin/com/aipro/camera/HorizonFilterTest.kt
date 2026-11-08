package com.aipro.camera

import com.aipro.camera.stabilizer.HorizonFilter
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage-6 horizon-lock maths, driven by synthetic sensor samples (no Android needed). */
class HorizonFilterTest {

    private val g = 9.80665f

    @Test
    fun `upright device reports zero roll`() {
        val filter = HorizonFilter()
        filter.onAccelerometer(0f, g, 0f, 1_000_000_000L)
        assertEquals(0f, filter.roll, 0.01f)
    }

    @Test
    fun `accelerometer tilt sets the absolute roll`() {
        val filter = HorizonFilter()
        // 20 degrees clockwise: gravity leaks into +x.
        val tilt = Math.toRadians(20.0).toFloat()
        filter.onAccelerometer(g * kotlin.math.sin(tilt), g * kotlin.math.cos(tilt), 0f, 1_000_000_000L)
        assertEquals(20f, filter.accelReference, 0.5f)
        assertEquals(20f, filter.roll, 0.5f)
    }

    @Test
    fun `a tilt beyond the correction range is clamped but still measured`() {
        val filter = HorizonFilter(maxRollDegrees = 25f)
        val tilt = Math.toRadians(40.0).toFloat()
        filter.onAccelerometer(g * kotlin.math.sin(tilt), g * kotlin.math.cos(tilt), 0f, 1_000_000_000L)
        assertEquals(40f, filter.accelReference, 0.5f)
        assertEquals(25f, filter.roll, 0.5f)
    }

    @Test
    fun `gyro rate integrates using the sensor timestamps`() {
        val filter = HorizonFilter()
        filter.onAccelerometer(0f, g, 0f, 0L)
        val rate = Math.toRadians(10.0).toFloat() // 10 deg/s
        var roll = 0f
        for (i in 1..20) {
            roll = filter.onGyroscope(rate, i * 50_000_000L) // 50 ms steps → 1 s total
        }
        assertEquals(10f, roll, 1.5f)
    }

    @Test
    fun `timestamp going backwards is ignored instead of producing a jump`() {
        val filter = HorizonFilter()
        filter.onAccelerometer(0f, g, 0f, 0L)
        filter.onGyroscope(1f, 500_000_000L)
        val before = filter.roll
        filter.onGyroscope(1f, 100_000_000L)
        assertEquals(before, filter.roll, 0.0001f)
    }

    @Test
    fun `shaking the device does not move the absolute reference`() {
        val filter = HorizonFilter()
        filter.onAccelerometer(0f, g, 0f, 0L)
        // 25 m/s² total: outside the trusted gravity window.
        filter.onAccelerometer(20f, 15f, 3f, 100_000_000L)
        assertEquals(0f, filter.accelReference, 0.0001f)
    }

    @Test
    fun `roll is clamped so a wild gyro spike cannot flip the preview`() {
        val filter = HorizonFilter(maxRollDegrees = 25f)
        filter.onAccelerometer(0f, g, 0f, 0L)
        filter.onGyroscope(50f, 50_000_000L)
        assertTrue(filter.roll <= 25.0001f)
        assertTrue(abs(filter.roll) <= 25.0001f)
    }

    @Test
    fun `fill zoom is one when level and grows with roll`() {
        val filter = HorizonFilter()
        assertEquals(1f, filter.fillZoom(0f), 0.0001f)
        val tilted = filter.fillZoom(Math.toRadians(20.0).toFloat())
        assertTrue("zoom=$tilted", tilted > 1.2f)
        assertTrue(tilted <= 1.45f)
    }

    @Test
    fun `disagreement grows while the gyro moves and settles when it stops`() {
        val filter = HorizonFilter()
        filter.onAccelerometer(0f, g, 0f, 0L)
        filter.onGyroscope(Math.toRadians(60.0).toFloat(), 50_000_000L)
        val moving = filter.disagreement
        assertTrue("disagreement=$moving", moving > 1f)
        // Let the accelerometer pull the estimate back to gravity over many samples.
        var ts = 100_000_000L
        repeat(300) {
            ts += 20_000_000L
            filter.onAccelerometer(0f, g, 0f, ts)
        }
        assertTrue("settled=${filter.disagreement}", filter.disagreement < moving)
    }

    @Test
    fun `a fast twist is followed within one sample`() {
        val filter = HorizonFilter()
        filter.onAccelerometer(0f, g, 0f, 0L)
        // 360 deg/s for 100 ms -> 36 degrees, one sample.
        val rate = Math.toRadians(360.0).toFloat()
        filter.onGyroscope(rate, 100_000_000L)
        assertEquals(36f, filter.integratedRoll, 0.5f)
        // The published value leads the integrated one to hide the pipeline latency.
        assertTrue(filter.roll >= filter.integratedRoll)
    }

    @Test
    fun `accelerometer cannot pull a fast rotation back`() {
        val filter = HorizonFilter()
        filter.onAccelerometer(0f, g, 0f, 0L)
        filter.onGyroscope(Math.toRadians(360.0).toFloat(), 100_000_000L) // 36 degrees
        // Stale gravity reading from before the twist arrives late: it must only nudge, not snap back.
        filter.onAccelerometer(0f, g, 0f, 120_000_000L)
        assertTrue(filter.integratedRoll > 30f)
    }

    @Test
    fun `a full turn keeps accumulating instead of wrapping`() {
        val filter = HorizonFilter(maxRollDegrees = 400f)
        filter.onAccelerometer(0f, g, 0f, 0L)
        var timestamp = 0L
        // 90 deg/s for 5 s = 450 degrees of rotation, i.e. more than a full turn.
        repeat(100) {
            timestamp += 50_000_000L
            filter.onGyroscope(Math.toRadians(90.0).toFloat(), timestamp)
        }
        assertTrue("roll=${filter.integratedRoll}", filter.integratedRoll > 300f)
    }
}
