package com.aipro.ai.pipeline

import com.aipro.ai.scene.SceneAnalysis
import com.aipro.common.ShootingPurpose

/**
 * Concrete processing decisions for one photo (spec section 8 steps 3-7).
 * Every value is derived from the measured scene analysis plus the user's shooting purpose.
 */
data class EnhancementPlan(
    val purpose: ShootingPurpose,
    val nightMode: Boolean,
    val denoiseRadius: Int,
    val denoiseSigma: Float,
    val portraitSmoothing: Float,
    val sharpenAmount: Float,
    val saturation: Float,
    val temperature: Float,
    val upscaleFactor: Int,
    val contrastBoost: Double,
    val rationale: String,
) {
    companion object {

        /**
         * @param nightEnabled user toggle: night mode is independent of the shooting purpose.
         * @param maxUpscale largest integer factor the user allowed (0/1 = no upscaling).
         */
        fun build(
            analysis: SceneAnalysis,
            purpose: ShootingPurpose,
            nightEnabled: Boolean,
            maxUpscale: Int,
        ): EnhancementPlan {
            val effectiveNight = nightEnabled || analysis.night
            val noiseLevel = when {
                analysis.meanLuma < 40 -> 3
                analysis.meanLuma < 80 -> 2
                else -> 1
            }
            // Portrait never over-sharpens skin; landscape/urban recover far detail.
            val sharpen = when (purpose) {
                ShootingPurpose.PORTRAIT -> 0.35f
                ShootingPurpose.LANDSCAPE -> 0.75f
                ShootingPurpose.URBAN -> 0.85f
                ShootingPurpose.AUTO -> 0.55f
            }
            val denoiseSigma = when {
                effectiveNight -> 34f + noiseLevel * 4f
                noiseLevel >= 2 -> 26f
                else -> 18f
            }
            val upscale = when {
                maxUpscale <= 1 -> 1
                analysis.meanLuma < 40 -> 1 // do not amplify night noise
                else -> maxUpscale
            }
            val contrast = when (purpose) {
                ShootingPurpose.PORTRAIT -> 0.95
                ShootingPurpose.LANDSCAPE -> 1.18
                ShootingPurpose.URBAN -> 1.12
                ShootingPurpose.AUTO -> 1.02
            } * if (effectiveNight) 1.06 else 1.0
            val saturation = when (purpose) {
                ShootingPurpose.PORTRAIT -> 1.02f
                ShootingPurpose.LANDSCAPE -> 1.12f
                ShootingPurpose.URBAN -> 1.06f
                ShootingPurpose.AUTO -> 1.03f
            }
            val temperature = when (purpose) {
                ShootingPurpose.PORTRAIT -> 0.06f
                ShootingPurpose.LANDSCAPE -> -0.02f
                ShootingPurpose.URBAN -> 0.04f
                ShootingPurpose.AUTO -> 0.0f
            }
            return EnhancementPlan(
                purpose = purpose,
                nightMode = effectiveNight,
                denoiseRadius = if (effectiveNight) 3 else if (noiseLevel >= 2) 2 else 1,
                denoiseSigma = denoiseSigma,
                portraitSmoothing = if (purpose == ShootingPurpose.PORTRAIT) 0.45f else 0.0f,
                sharpenAmount = if (effectiveNight) sharpen * 0.7f else sharpen,
                saturation = saturation,
                temperature = if (analysis.meanLuma < 40) temperature * 0.5f else temperature,
                upscaleFactor = upscale,
                contrastBoost = contrast,
                rationale = buildString {
                    append("장면=").append(analysis.scene)
                    append(", 분석=").append(analysis.method)
                    append(", 평균밝기=").append(String.format("%.0f", analysis.meanLuma))
                    append(", 노이즈수준=").append(noiseLevel)
                    if (effectiveNight) append(" · 야간 보정 적용")
                    if (upscale > 1) append(" · ").append(upscale).append("배 업스케일")
                },
            )
        }
    }
}

/** Result of a pipeline run; [steps] records exactly what happened for the metadata sidecar. */
data class EnhancementResult(
    val width: Int,
    val height: Int,
    val steps: List<String>,
    val modelIds: List<String>,
    val elapsedMs: Long,
    val plan: EnhancementPlan,
    val scene: SceneAnalysis,
)
