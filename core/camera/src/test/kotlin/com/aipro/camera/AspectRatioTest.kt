package com.aipro.camera

import com.aipro.camera.capture.AspectRatioOption
import com.aipro.camera.capture.RatioSizing
import com.aipro.camera.discovery.SizeInfo
import com.aipro.camera.video.VideoProfileSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Framing-ratio selection: native sizes win, otherwise a centred crop is described exactly. */
class AspectRatioTest {

    private val sensor43 = listOf(SizeInfo(4000, 3000), SizeInfo(2000, 1500), SizeInfo(640, 480))
    private val withWides = sensor43 + listOf(SizeInfo(3840, 2160), SizeInfo(1920, 1080))

    @Test
    fun `4 by 3 uses the largest native size without cropping`() {
        val plan = RatioSizing.plan(withWides, AspectRatioOption.RATIO_4_3)!!
        assertEquals(4000, plan.streamSize.width)
        assertNull(plan.cropRect)
        assertEquals(4000, plan.outputWidth)
        assertEquals(3000, plan.outputHeight)
    }

    @Test
    fun `16 by 9 prefers the native 3840x2160 over cropping 4 by 3`() {
        val plan = RatioSizing.plan(withWides, AspectRatioOption.RATIO_16_9)!!
        assertEquals(3840, plan.streamSize.width)
        assertEquals(2160, plan.streamSize.height)
        assertNull(plan.cropRect)
    }

    @Test
    fun `16 by 9 crops height when only 4 by 3 exists`() {
        val plan = RatioSizing.plan(sensor43, AspectRatioOption.RATIO_16_9)!!
        assertEquals(4000, plan.streamSize.width)
        val crop = plan.cropRect!!
        assertEquals(4000, crop.width)
        assertEquals(2250, crop.height)
        assertEquals((3000 - 2250) / 2, crop.top)
        assertEquals(0, crop.left)
        assertEquals(4000, plan.outputWidth)
        assertEquals(2250, plan.outputHeight)
    }

    @Test
    fun `22 by 9 crops height and keeps a usable resolution`() {
        val plan = RatioSizing.plan(sensor43, AspectRatioOption.RATIO_22_9)!!
        assertTrue(plan.cropped)
        val crop = plan.cropRect!!
        assertEquals(4000, crop.width)
        assertEquals((4000 / (22f / 9f)).toInt().let { kotlin.math.abs(it - crop.height) <= 1 }, true)
        // output aspect must equal the requested ratio within a pixel
        val aspect = plan.outputWidth.toFloat() / plan.outputHeight
        assertTrue(kotlin.math.abs(aspect - 22f / 9f) < 0.01f)
    }

    @Test
    fun `1 by 1 crops the sides of a 4 by 3 frame`() {
        val plan = RatioSizing.plan(sensor43, AspectRatioOption.RATIO_1_1)!!
        val crop = plan.cropRect!!
        assertEquals(3000, crop.width)
        assertEquals(3000, crop.height)
        assertEquals((4000 - 3000) / 2, crop.left)
        assertEquals(0, crop.top)
    }

    @Test
    fun `tiny thumbnails never win over the real capture sizes`() {
        val sizes = sensor43 + listOf(SizeInfo(160, 90))
        val plan = RatioSizing.plan(sizes, AspectRatioOption.RATIO_16_9)!!
        assertEquals(4000, plan.streamSize.width) // 160x90 is ignored, 4:3 is cropped instead
    }

    @Test
    fun `empty size list yields no plan`() {
        assertNull(RatioSizing.plan(emptyList(), AspectRatioOption.RATIO_4_3))
    }

    @Test
    fun `native detection drives which video ratios are offered`() {
        val video = listOf(SizeInfo(3840, 2160), SizeInfo(1920, 1080), SizeInfo(1440, 1080))
        val supported = RatioSizing.nativelySupported(video, AspectRatioOption.entries)
        assertTrue(AspectRatioOption.RATIO_16_9 in supported)
        assertTrue(AspectRatioOption.RATIO_4_3 in supported)
        assertTrue(AspectRatioOption.RATIO_22_9 !in supported)
        assertTrue(AspectRatioOption.RATIO_1_1 !in supported)
    }

    @Test
    fun `record size follows the selected ratio when the device supports it`() {
        val video = listOf(SizeInfo(3840, 2160), SizeInfo(1920, 1080), SizeInfo(1440, 1080))
        val sixteen = VideoProfileSelector.select(video, aspect = AspectRatioOption.RATIO_16_9)!!
        assertEquals(3840, sixteen.size.width)
        val fourThree = VideoProfileSelector.select(video, aspect = AspectRatioOption.RATIO_4_3)!!
        assertEquals(1440, fourThree.size.width)
        // Unsupported ratio falls back to the usual 16:9 preference instead of failing.
        val twentyTwo = VideoProfileSelector.select(video, aspect = AspectRatioOption.RATIO_22_9)!!
        assertEquals(3840, twentyTwo.size.width)
    }

    @Test
    fun `display ratio flips in portrait`() {
        assertEquals(4f / 3f, AspectRatioOption.RATIO_4_3.displayRatio(portrait = false), 0.0001f)
        assertEquals(3f / 4f, AspectRatioOption.RATIO_4_3.displayRatio(portrait = true), 0.0001f)
        assertNotNull(RatioSizing.plan(sensor43, AspectRatioOption.RATIO_4_3))
    }
}
