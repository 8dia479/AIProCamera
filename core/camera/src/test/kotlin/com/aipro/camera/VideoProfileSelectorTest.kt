package com.aipro.camera

import com.aipro.camera.discovery.SizeInfo
import com.aipro.camera.video.VideoProfileSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProfileSelectorTest {

    private val sizes = listOf(
        SizeInfo(3840, 2160), SizeInfo(1920, 1080), SizeInfo(1280, 720),
        SizeInfo(3840, 1644), SizeInfo(2560, 1440),
    )

    @Test
    fun `prefers the largest 16 by 9 profile up to 4K`() {
        val selection = VideoProfileSelector.select(sizes)!!
        assertEquals(3840, selection.size.width)
        assertEquals(2160, selection.size.height)
        assertEquals(30, selection.fps)
    }

    @Test
    fun `ignores ultra wide aspect ratios when a 16 by 9 exists`() {
        val selection = VideoProfileSelector.select(listOf(SizeInfo(3840, 1644), SizeInfo(1920, 1080)))!!
        assertEquals(1920, selection.size.width)
    }

    @Test
    fun `caps selection when the device only offers oversized modes`() {
        val selection = VideoProfileSelector.select(listOf(SizeInfo(7680, 4320)), preferredMaxWidth = 3840)
        // 7680x4320 exceeds the cap, yet it is the only candidate and is still returned.
        assertEquals(7680, selection!!.size.width)
    }

    @Test
    fun `empty capability list reports no profile instead of a fake default`() {
        assertNull(VideoProfileSelector.select(emptyList()))
    }

    @Test
    fun `bitrate stays inside sane encoder bounds`() {
        val bitrate = VideoProfileSelector.estimateBitrate(SizeInfo(3840, 2160), 30)
        assertTrue(bitrate in 20_000_000..40_000_000)
        assertTrue(VideoProfileSelector.estimateBitrate(SizeInfo(320, 240), 30) >= 2_000_000)
    }
}
