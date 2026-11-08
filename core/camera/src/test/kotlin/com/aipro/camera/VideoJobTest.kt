package com.aipro.camera

import com.aipro.camera.video.VideoJob
import com.aipro.camera.video.VideoJobStatus
import com.aipro.camera.video.VideoPostProcessor
import com.aipro.camera.video.VideoTone
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage-5 job bookkeeping and tone mapping. No Android classes are touched, so these run on the JVM. */
class VideoJobTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun job(
        segmentCount: Int = 4,
        completed: List<String> = emptyList(),
        status: VideoJobStatus = VideoJobStatus.PENDING,
        attempts: Int = 0,
    ) = VideoJob(
        id = "job-1",
        createdAtEpochMs = 1_000L,
        sourceUri = "content://media/external/video/media/42",
        shootingPurpose = "AUTO",
        status = status,
        segmentCount = segmentCount,
        completedSegments = completed,
        attempts = attempts,
    )

    @Test
    fun `progress tracks completed segments`() {
        assertEquals(0f, job(segmentCount = 4).progress, 0.0001f)
        assertEquals(0.5f, job(segmentCount = 4, completed = listOf("part_000.mp4", "part_001.mp4")).progress, 0.0001f)
        assertEquals(1f, job(segmentCount = 2, completed = listOf("part_000.mp4", "part_001.mp4")).progress, 0.0001f)
    }

    @Test
    fun `progress is safe when the segment count is unknown`() {
        assertEquals(0f, job(segmentCount = 0).progress, 0.0001f)
        assertTrue(job(segmentCount = 0).resumeLabel.contains("0/0"))
    }

    @Test
    fun `resume label reports segments frames and percent`() {
        val label = job(segmentCount = 4, completed = listOf("part_000.mp4")).copy(framesDone = 57).resumeLabel
        assertTrue(label, label.contains("1/4"))
        assertTrue(label, label.contains("25%"))
        assertTrue(label, label.contains("57"))
    }

    @Test
    fun `job survives a serialization round trip so a restart can resume it`() {
        val original = job(segmentCount = 3, completed = listOf("part_000.mp4"), status = VideoJobStatus.PAUSED)
            .copy(framesDone = 31, lastError = "발열 보호")
        val restored = json.decodeFromString(VideoJob.serializer(), json.encodeToString(VideoJob.serializer(), original))
        assertEquals(original, restored)
        assertEquals(1, restored.segmentsDone)
    }

    @Test
    fun `unknown keys from a newer version do not break an older job file`() {
        val text = """
            {"id":"job-1","createdAtEpochMs":1000,"sourceUri":"content://x","shootingPurpose":"AUTO",
             "segmentCount":2,"completedSegments":["part_000.mp4"],"futureField":123}
        """.trimIndent()
        val restored = json.decodeFromString(VideoJob.serializer(), text)
        assertEquals(1, restored.segmentsDone)
        assertEquals(VideoJobStatus.PENDING, restored.status)
    }

    @Test
    fun `tone chain differentiates shooting purposes`() {
        val auto = VideoPostProcessor.toneFor("AUTO", nightMode = false)
        val portrait = VideoPostProcessor.toneFor("PORTRAIT", nightMode = false)
        val landscape = VideoPostProcessor.toneFor("LANDSCAPE", nightMode = false)
        val urban = VideoPostProcessor.toneFor("URBAN", nightMode = false)
        assertEquals(VideoTone(), auto)
        assertTrue(portrait.sharpen < landscape.sharpen)
        assertTrue(urban.contrast > landscape.contrast)
        assertTrue(urban.saturation > portrait.saturation)
    }

    @Test
    fun `night mode always wins over the purpose and stays gentle`() {
        val night = VideoPostProcessor.toneFor("URBAN", nightMode = true)
        assertNotEquals(VideoPostProcessor.toneFor("URBAN", nightMode = false), night)
        assertTrue(night.sharpen < 0.2f)
        assertTrue(night.contrast <= 1.05f)
    }
}
