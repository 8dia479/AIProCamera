package com.aipro.camera

import com.aipro.camera.discovery.ManualControlSupport
import com.aipro.camera.manual.ExposureFormat
import com.aipro.camera.manual.ManualSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ManualSettingsTest {

    private val fullSupport = ManualControlSupport(
        iso = true, shutterSpeed = true, focus = true, whiteBalance = true,
        exposureCompensation = true, raw = true, heif = true, zoom = true, histogram = true,
    )
    private val noSupport = fullSupport.copy(
        iso = false, shutterSpeed = false, focus = false, whiteBalance = false,
        exposureCompensation = false, zoom = false,
    )

    private fun normalize(settings: ManualSettings, support: ManualControlSupport = fullSupport) =
        ManualSettings.normalize(
            requested = settings,
            support = support,
            isoRange = 50..3200,
            exposureRangeNs = 100_000L..30_000_000_000L,
            evRange = -24..24,
            evStep = 0.1f,
            maxZoom = 10f,
            minimumFocusDiopters = 10f,
        )

    @Test
    fun `unsupported controls are dropped instead of faked`() {
        val requested = ManualSettings(iso = 400, exposureTimeNs = 1_000_000, focusSlider01 = 0.5f, whiteBalanceKelvin = 5000, zoomRatio = 2f)
        val result = normalize(requested, noSupport)
        assertNull(result.iso)
        assertNull(result.exposureTimeNs)
        assertNull(result.focusSlider01)
        assertNull(result.whiteBalanceKelvin)
        assertEquals(1f, result.zoomRatio, 0f)
        assertEquals(0f, result.exposureCompensationEv, 0f)
    }

    @Test
    fun `iso and shutters are clamped to the reported hardware ranges`() {
        val result = normalize(ManualSettings(iso = 999_999, exposureTimeNs = 60_000_000_000L))
        assertEquals(3200, result.iso)
        assertEquals(30_000_000_000L, result.exposureTimeNs)
        val low = normalize(ManualSettings(iso = 1, exposureTimeNs = 1L))
        assertEquals(50, low.iso)
        assertEquals(100_000L, low.exposureTimeNs)
    }

    @Test
    fun `exposure compensation snaps to the reported step and range`() {
        val result = normalize(ManualSettings(exposureCompensationEv = 1.26f))
        assertEquals(1.2f, result.exposureCompensationEv, 0.001f)
        val clamped = normalize(ManualSettings(exposureCompensationEv = 5f))
        assertEquals(2.4f, clamped.exposureCompensationEv, 0.001f)
    }

    @Test
    fun `zoom, focus and kelvin are kept inside legal bounds`() {
        val result = normalize(ManualSettings(zoomRatio = 42f, focusSlider01 = 1.7f, whiteBalanceKelvin = 900))
        assertEquals(10f, result.zoomRatio, 0f)
        assertEquals(1f, result.focusSlider01!!, 0f)
        assertEquals(ManualSettings.MIN_KELVIN, result.whiteBalanceKelvin)
    }

    @Test
    fun `autofocus stays auto unless the user moves the slider`() {
        val auto = normalize(ManualSettings())
        assertNull(auto.focusSlider01)
        val manual = normalize(ManualSettings(focusSlider01 = 0f))
        assertNotNull(manual.focusSlider01)
    }

    @Test
    fun `exposure readouts use photographic notation`() {
        assertEquals("1/125", ExposureFormat.exposureTime(8_000_000L))
        assertEquals("1/1000", ExposureFormat.exposureTime(1_000_000L))
        assertEquals("2.0s", ExposureFormat.exposureTime(2_000_000_000L))
        assertEquals("auto", ExposureFormat.iso(null))
        assertEquals("+1.0", ExposureFormat.ev(1f))
        assertEquals("-0.5", ExposureFormat.ev(-0.5f))
        assertEquals("5500K", ExposureFormat.kelvin(5500))
        assertEquals("auto", ExposureFormat.kelvin(null))
    }
}
