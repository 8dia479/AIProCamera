package com.aipro.ai

import com.aipro.ai.pipeline.EnhancementPlan
import com.aipro.ai.scene.SceneAnalysis
import com.aipro.common.ShootingPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan is pure logic, so the processing decisions can be verified without a camera or a model.
 */
class EnhancementPlanTest {

    private fun analysis(
        scene: String = "LANDSCAPE",
        meanLuma: Double = 120.0,
        night: Boolean = false,
        method: String = "analysis",
    ) = SceneAnalysis(
        scene = scene,
        night = night,
        meanLuma = meanLuma,
        contrast = 55.0,
        clippedHighlights = 0.01,
        clippedShadows = 0.02,
        saturation = 0.3,
        method = method,
        confidence = 0.5f,
    )

    @Test
    fun `night mode is independent of the shooting purpose`() {
        val plan = EnhancementPlan.build(analysis(), ShootingPurpose.PORTRAIT, nightEnabled = true, maxUpscale = 1)
        assertEquals(ShootingPurpose.PORTRAIT, plan.purpose)
        assertTrue(plan.nightMode)
        assertTrue(plan.denoiseSigma >= 34f)
    }

    @Test
    fun `dark frames enable night treatment even without the toggle`() {
        val plan = EnhancementPlan.build(analysis(meanLuma = 25.0, night = true), ShootingPurpose.AUTO, nightEnabled = false, maxUpscale = 1)
        assertTrue(plan.nightMode)
        assertEquals("업스케일은 야간 노이즈 증폭을 피해야 한다", 1, plan.upscaleFactor)
    }

    @Test
    fun `portrait keeps skin soft while urban sharpens structure`() {
        val portrait = EnhancementPlan.build(analysis(), ShootingPurpose.PORTRAIT, false, 1)
        val urban = EnhancementPlan.build(analysis(), ShootingPurpose.URBAN, false, 1)
        assertTrue(portrait.portraitSmoothing > 0f)
        assertEquals(0f, urban.portraitSmoothing, 0f)
        assertTrue(urban.sharpenAmount > portrait.sharpenAmount)
    }

    @Test
    fun `upscale factor is honoured but blocked for night shots`() {
        assertEquals(2, EnhancementPlan.build(analysis(), ShootingPurpose.LANDSCAPE, false, 2).upscaleFactor)
        assertEquals(1, EnhancementPlan.build(analysis(meanLuma = 30.0), ShootingPurpose.LANDSCAPE, false, 2).upscaleFactor)
        assertEquals(1, EnhancementPlan.build(analysis(), ShootingPurpose.LANDSCAPE, false, 0).upscaleFactor)
    }

    @Test
    fun `noisier frames get stronger denoise`() {
        val bright = EnhancementPlan.build(analysis(meanLuma = 160.0), ShootingPurpose.AUTO, false, 1)
        val dark = EnhancementPlan.build(analysis(meanLuma = 50.0), ShootingPurpose.AUTO, false, 1)
        assertTrue(dark.denoiseSigma > bright.denoiseSigma)
        assertTrue(dark.denoiseRadius >= bright.denoiseRadius)
    }

    @Test
    fun `rationale records what drove the decision`() {
        val plan = EnhancementPlan.build(analysis(scene = "URBAN", method = "model:scene_v2"), ShootingPurpose.URBAN, false, 1)
        assertTrue(plan.rationale.contains("URBAN"))
        assertTrue(plan.rationale.contains("model:scene_v2"))
    }
}
