package com.aipro.ai.pipeline

import android.graphics.Bitmap
import com.aipro.ai.inference.InterpreterFactory
import com.aipro.ai.model.ModelManager
import com.aipro.ai.model.ModelTask
import com.aipro.ai.scene.SceneAnalyzer
import com.aipro.common.AiproLog
import com.aipro.common.ShootingPurpose
import com.aipro.nativebridge.NativeImageOps
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The photo enhancement pipeline described in spec section 8.
 *
 * Order: scene analysis → plan → denoise → (portrait) → optional upscale → tone/colour → detail.
 * Each stage prefers an installed AI model and falls back to the host-tested native implementation,
 * recording in [EnhancementResult.steps] which path actually ran. Nothing here touches the original
 * file; the caller writes the result to `Enhanced/`.
 */
class PhotoEnhancer(
    private val models: ModelManager,
    private val sceneAnalyzer: SceneAnalyzer,
) {

    private companion object {
        const val TAG = "Enhancer"
    }

    fun enhance(
        source: Bitmap,
        purpose: ShootingPurpose,
        nightEnabled: Boolean,
        maxUpscale: Int = 1,
        threads: Int = 4,
        useGpu: Boolean = false,
    ): EnhancementResult? {
        val startedAt = System.currentTimeMillis()
        val working = if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) source
        else source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val analysis = sceneAnalyzer.analyze(working)
        val plan = EnhancementPlan.build(analysis, purpose, nightEnabled, maxUpscale)
        val steps = mutableListOf<String>()
        val usedModels = mutableListOf<String>()
        var current = working
        var width = current.width
        var height = current.height

        // 1) Noise removal -------------------------------------------------------
        val denoiseModel = models.modelFor(ModelTask.DENOISE)
        val denoiseModelResult = denoiseModel?.let { runModel(ModelTask.DENOISE, current, threads, useGpu) }
        if (denoiseModelResult != null) {
            current.recycle()
            current = denoiseModelResult
            usedModels += denoiseModel!!.id
            steps += "denoise(model:${denoiseModel.displayName})"
        } else if (NativeImageOps.denoise(current, plan.denoiseRadius, plan.denoiseSigma)) {
            steps += "denoise(classical r=${plan.denoiseRadius} σ=${plan.denoiseSigma.toInt()})"
        } else {
            steps += "denoise(skipped)"
        }

        // 2) Portrait treatment --------------------------------------------------
        if (plan.portraitSmoothing > 0f) {
            val portraitModel = models.modelFor(ModelTask.PORTRAIT)
            val portraitResult = portraitModel?.let { runModel(ModelTask.PORTRAIT, current, threads, useGpu) }
            if (portraitResult != null) {
                current.recycle()
                current = portraitResult
                usedModels += portraitModel!!.id
                steps += "portrait(model:${portraitModel.displayName})"
            } else if (NativeImageOps.smoothPortrait(current, plan.portraitSmoothing)) {
                steps += "portrait(edge-aware ${(plan.portraitSmoothing * 100).toInt()}%)"
            }
        }

        // 3) Optional upscaling --------------------------------------------------
        if (plan.upscaleFactor > 1) {
            val srModel = models.modelFor(ModelTask.SUPER_RESOLUTION)
            val srResult = srModel?.let { runModel(ModelTask.SUPER_RESOLUTION, current, threads, useGpu) }
            if (srResult != null && srResult.width > current.width) {
                current.recycle()
                current = srResult
                usedModels += srModel!!.id
                steps += "upscale(model:${srModel.displayName} ${srResult.width}x${srResult.height})"
            } else {
                val upscaled = NativeImageOps.upscale(current, plan.upscaleFactor)
                if (upscaled != null) {
                    current.recycle()
                    current = upscaled
                    steps += "upscale(bicubic x${plan.upscaleFactor} ${upscaled.width}x${upscaled.height})"
                } else {
                    steps += "upscale(skipped: 메모리/모델 없음)"
                }
            }
        }

        // 4) Tone curve + colour -------------------------------------------------
        val lut = NativeImageOps.purposeLut(plan.purpose.ordinal, plan.contrastBoost)
        val toneApplied = NativeImageOps.applyToneAndColor(
            current,
            lut,
            plan.saturation,
            if (plan.nightMode) plan.temperature * 0.5f else plan.temperature,
        )
        steps += if (toneApplied) {
            "tone(lut=${plan.purpose.name.lowercase()}, sat=${plan.saturation}, temp=${plan.temperature})"
        } else {
            "tone(skipped)"
        }

        // 5) Detail recovery -----------------------------------------------------
        if (NativeImageOps.sharpen(current, plan.sharpenAmount)) {
            steps += "detail(unsharp ${plan.sharpenAmount})"
        }

        width = current.width
        height = current.height
        AiproLog.i(TAG, "enhanced ${width}x$height in ${System.currentTimeMillis() - startedAt}ms :: ${steps.joinToString()}")

        return EnhancementResult(
            width = width,
            height = height,
            steps = steps,
            modelIds = usedModels,
            elapsedMs = System.currentTimeMillis() - startedAt,
            plan = plan,
            scene = analysis,
        )
    }

    /** Runs an installed model; returns null when the model is missing or fails, so callers fall back. */
    private fun runModel(task: ModelTask, bitmap: Bitmap, threads: Int, useGpu: Boolean): Bitmap? {
        val manifest = models.modelFor(task) ?: return null
        val file = File(models.fileFor(manifest).path)
        if (!file.exists()) return null
        return runCatching {
            val bytes = file.inputStream().use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder()); put(bytes); rewind()
            }
            InterpreterFactory.create(buffer, threads, useGpu).use { engine -> engine.run(bitmap) }
        }.onFailure { AiproLog.w(TAG, "model ${manifest.displayName} 추론 실패 → 고전 경로 사용", it) }.getOrNull()
    }
}
