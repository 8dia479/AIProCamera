package com.aipro.camera

import com.aipro.camera.discovery.LensClassifier
import com.aipro.common.Facing
import com.aipro.common.LensType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LensClassifierTest {

    // Plausible Galaxy-class geometries: focal length + sensor physical size.
    @Test
    fun `ultra wide sensor classifies as ultra wide`() {
        // 2.2 mm lens on a 4.6 x 3.5 mm sensor -> ~15-16 mm equivalent.
        val type = LensClassifier.classify(Facing.BACK, 2.2f, 4.6f, 3.5f)
        assertEquals(LensType.ULTRA_WIDE, type)
    }

    @Test
    fun `main sensor classifies as wide`() {
        // 6.7 mm lens on a 9.6 x 7.2 mm sensor -> ~24 mm equivalent.
        val type = LensClassifier.classify(Facing.BACK, 6.7f, 9.6f, 7.2f)
        assertEquals(LensType.WIDE, type)
    }

    @Test
    fun `long focal length classifies as telephoto`() {
        val type = LensClassifier.classify(Facing.BACK, 12f, 9.6f, 7.2f)
        assertEquals(LensType.TELEPHOTO, type)
    }

    @Test
    fun `front camera is always reported as front`() {
        assertEquals(LensType.FRONT, LensClassifier.classify(Facing.FRONT, 3.2f, 4.7f, 3.5f))
    }

    @Test
    fun `degenerate geometry yields unknown instead of a wrong guess`() {
        assertEquals(LensType.UNKNOWN, LensClassifier.classify(Facing.BACK, 0f, 9.6f, 7.2f))
        assertEquals(LensType.UNKNOWN, LensClassifier.classify(Facing.BACK, 6.7f, 0f, 0f))
    }

    @Test
    fun `equivalent focal length matches 35mm diagonal maths`() {
        val equiv = LensClassifier.equivalentFocalLength35mm(6.7f, 9.6f, 7.2f)
        assertEquals(24.2, equiv!!, 0.6)
        assertNull(LensClassifier.equivalentFocalLength35mm(0f, 9.6f, 7.2f))
    }
}
