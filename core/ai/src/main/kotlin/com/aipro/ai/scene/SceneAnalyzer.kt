package com.aipro.ai.scene

import android.graphics.Bitmap
import com.aipro.ai.inference.LiteRtInterpreter
import com.aipro.ai.model.ModelManager
import com.aipro.ai.model.ModelTask
import com.aipro.common.AiproLog
import com.aipro.common.ShootingPurpose
import com.aipro.nativebridge.NativeImageOps
import kotlinx.serialization.Serializable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class SceneType { PORTRAIT, LANDSCAPE, URBAN, NIGHT, UNKNOWN }

@Serializable
data class SceneAnalysis(
    val scene: String,
    val night: Boolean,
    val meanLuma: Double,
    val contrast: Double,
    val clippedHighlights: Double,
    val clippedShadows: Double,
    val saturation: Double,
    val method: String,
    val confidence: Float,
)

/**
 * Scene analysis (spec section 7, "Scene Recognition").
 *
 * * If the user installed a scene-classification model it is used (224×224 RGB classifier, first N
 *   logits mapped onto [SceneType]).
 * * Otherwise a deterministic analyser works on the real pixel statistics of the frame
 *   (mean luma, contrast, clipping, saturation). The method is reported, so the UI never claims a
 *   neural network ran when it did not.
 */
class SceneAnalyzer(private val models: ModelManager) {

    private companion object {
        const val TAG = "Scene"
        val CLASS_ORDER = listOf(SceneType.PORTRAIT, SceneType.LANDSCAPE, SceneType.URBAN, SceneType.NIGHT)
        const val NIGHT_LUMA_THRESHOLD = 48.0
    }

    fun analyze(bitmap: Bitmap): SceneAnalysis {
        val stats = NativeImageOps.analyze(bitmap)
        val meanLuma = stats?.meanLuma ?: estimateMeanLuma(bitmap)
        val contrast = stats?.contrast ?: 0.0
        val clippedHigh = stats?.clippedHighlights ?: 0.0
        val clippedLow = stats?.clippedShadows ?: 0.0
        val saturation = stats?.saturationMean ?: 0.0

        val model = models.modelFor(ModelTask.SCENE_RECOGNITION)
        if (model != null) {
            val result = runModel(bitmap, File(models.fileFor(model).path), model.inputShape)
            if (result != null) {
                return SceneAnalysis(
                    scene = result.type.name,
                    night = result.type == SceneType.NIGHT || meanLuma < NIGHT_LUMA_THRESHOLD,
                    meanLuma = meanLuma,
                    contrast = contrast,
                    clippedHighlights = clippedHigh,
                    clippedShadows = clippedLow,
                    saturation = saturation,
                    method = "model:${model.displayName}",
                    confidence = result.confidence,
                )
            }
        }
        val heuristic = heuristicScene(meanLuma, contrast, saturation, clippedHigh, clippedLow)
        return SceneAnalysis(
            scene = heuristic.name,
            night = meanLuma < NIGHT_LUMA_THRESHOLD,
            meanLuma = meanLuma,
            contrast = contrast,
            clippedHighlights = clippedHigh,
            clippedShadows = clippedLow,
            saturation = saturation,
            method = "analysis",
            confidence = 0.55f,
        )
    }

    private fun heuristicScene(
        meanLuma: Double,
        contrast: Double,
        saturation: Double,
        clippedHighlights: Double,
        clippedShadows: Double,
    ): SceneType = when {
        meanLuma < NIGHT_LUMA_THRESHOLD -> SceneType.NIGHT
        // Landscapes: broad tonal range with high chroma (sky/foliage).
        contrast > 55.0 && saturation > 0.22 -> SceneType.LANDSCAPE
        // Urban: hard contrast, strong highlights from signage/reflections.
        clippedHighlights > 0.02 || contrast > 60.0 -> SceneType.URBAN
        // Skin tones: warm midtones, moderate contrast.
        saturation in 0.12..0.28 && meanLuma in 90.0..190.0 -> SceneType.PORTRAIT
        clippedShadows > 0.2 -> SceneType.NIGHT
        else -> SceneType.UNKNOWN
    }

    private fun estimateMeanLuma(bitmap: Bitmap): Double {
        val sample = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        sample.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (sample !== bitmap) sample.recycle()
        var sum = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
        }
        return sum / pixels.size
    }

    private fun runModel(bitmap: Bitmap, file: File, inputShape: List<Int>): ModelResult? = runCatching {
        val bytes = file.inputStream().use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            order(ByteOrder.nativeOrder()); put(bytes); rewind()
        }
        val interpreter: LiteRtInterpreter = com.aipro.ai.inference.InterpreterFactory.create(buffer, threads = 4)
        interpreter.use { engine ->
            val inputWidth = inputShape.getOrNull(1) ?: 224
            val inputHeight = inputShape.getOrNull(2) ?: 224
            val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val pixels = IntArray(inputWidth * inputHeight)
            scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            if (scaled !== bitmap) scaled.recycle()

            val input = ByteBuffer.allocateDirect(inputWidth * inputHeight * 3 * 4).order(ByteOrder.nativeOrder())
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
            input.rewind()
            val logits = FloatArray(CLASS_ORDER.size)
            engine.runRaw(arrayOf<Any>(input), arrayOf<Any>(logits))
            val best = logits.indices.maxByOrNull { logits[it] } ?: 0
            val confidence = logits.getOrElse(best) { 0f }
            ModelResult(CLASS_ORDER.getOrElse(best) { SceneType.UNKNOWN }, confidence)
        }
    }.onFailure { AiproLog.w(TAG, "scene model 추론 실패 → 통계 기반 분석으로 대체", it) }.getOrNull()

    private data class ModelResult(val type: SceneType, val confidence: Float)

    /** Maps a detected scene onto the user's shooting-purpose presets. */
    fun purposeFor(analysis: SceneAnalysis): ShootingPurpose = when (SceneType.valueOf(analysis.scene)) {
        SceneType.PORTRAIT -> ShootingPurpose.PORTRAIT
        SceneType.LANDSCAPE -> ShootingPurpose.LANDSCAPE
        SceneType.URBAN -> ShootingPurpose.URBAN
        SceneType.NIGHT, SceneType.UNKNOWN -> ShootingPurpose.AUTO
    }
}
