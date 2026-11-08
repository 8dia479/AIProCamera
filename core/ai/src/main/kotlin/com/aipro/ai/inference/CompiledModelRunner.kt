package com.aipro.ai.inference

import android.graphics.Bitmap
import com.aipro.ai.model.ModelManifest
import com.aipro.common.AiproLog
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File

/**
 * GPU execution using LiteRT's CompiledModel API.
 *
 * It requests GPU only, rather than GPU+CPU, so an unavailable GPU cannot be silently reported as
 * GPU while all operations run on CPU. Callers explicitly fall back to the CPU interpreter.
 */
internal object CompiledModelRunner {

    fun runOnGpu(file: File, manifest: ModelManifest, bitmap: Bitmap): Bitmap? {
        require(file.isFile) { "모델 파일이 없습니다" }
        require(manifest.inputShape.size >= 4 && manifest.outputShape.size >= 4) {
            "이미지 모델의 NHWC 입력/출력 형상이 필요합니다"
        }

        val inputHeight = manifest.inputShape[1]
        val inputWidth = manifest.inputShape[2]
        val inputChannels = manifest.inputShape[3]
        val outputHeight = manifest.outputShape[1]
        val outputWidth = manifest.outputShape[2]
        val outputChannels = manifest.outputShape[3]
        require(inputWidth > 0 && inputHeight > 0 && inputChannels in 1..4) { "지원하지 않는 모델 입력 형상" }
        require(outputWidth > 0 && outputHeight > 0 && outputChannels in 1..4) { "지원하지 않는 모델 출력 형상" }

        val model = CompiledModel.create(
            file.absolutePath,
            CompiledModel.Options(Accelerator.GPU),
        )
        val inputs = mutableListOf<com.google.ai.edge.litert.TensorBuffer>()
        val outputs = mutableListOf<com.google.ai.edge.litert.TensorBuffer>()
        try {
            inputs += model.createInputBuffers()
            outputs += model.createOutputBuffers()
            require(inputs.isNotEmpty() && outputs.isNotEmpty()) { "모델 입출력 텐서를 만들지 못했습니다" }

            val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val pixels = IntArray(inputWidth * inputHeight)
            try {
                resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            } finally {
                if (resized !== bitmap) resized.recycle()
            }

            val inputType = manifest.dataType.uppercase()
            val elementCount = inputWidth * inputHeight * inputChannels
            when {
                inputType.contains("FLOAT") -> {
                    val values = FloatArray(elementCount)
                    var index = 0
                    for (pixel in pixels) {
                        for (channel in 0 until inputChannels) {
                            values[index++] = channelOf(pixel, channel) / 255f
                        }
                    }
                    inputs[0].writeFloat(values)
                }
                inputType.contains("INT8") || inputType.contains("UINT8") -> {
                    val values = ByteArray(elementCount)
                    var index = 0
                    for (pixel in pixels) {
                        for (channel in 0 until inputChannels) {
                            values[index++] = channelOf(pixel, channel).toByte()
                        }
                    }
                    inputs[0].writeInt8(values)
                }
                else -> error("지원하지 않는 입력 자료형: $inputType")
            }

            model.run(inputs, outputs)

            val outputType = if (manifest.dataType.contains("INT8", ignoreCase = true) ||
                manifest.dataType.contains("UINT8", ignoreCase = true)
            ) manifest.dataType.uppercase() else "FLOAT32"
            val outputPixels = IntArray(outputWidth * outputHeight)
            if (outputType.contains("FLOAT")) {
                val values = outputs[0].readFloat()
                for (index in outputPixels.indices) {
                    val base = index * outputChannels
                    if (base + 2 >= values.size) break
                    outputPixels[index] = argb(
                        toByte(values[base]),
                        toByte(values[base + 1]),
                        toByte(values[base + 2]),
                    )
                }
            } else {
                val values = outputs[0].readInt8()
                for (index in outputPixels.indices) {
                    val base = index * outputChannels
                    if (base + 2 >= values.size) break
                    outputPixels[index] = argb(
                        values[base].toInt() and 0xFF,
                        values[base + 1].toInt() and 0xFF,
                        values[base + 2].toInt() and 0xFF,
                    )
                }
            }

            val produced = Bitmap.createBitmap(
                outputPixels,
                outputWidth,
                outputHeight,
                Bitmap.Config.ARGB_8888,
            )
            return if (outputWidth == bitmap.width && outputHeight == bitmap.height) {
                produced
            } else {
                Bitmap.createScaledBitmap(produced, bitmap.width, bitmap.height, true)
                    .also { produced.recycle() }
            }
        } catch (error: Throwable) {
            AiproLog.w("CompiledRunner", "GPU 추론 실패: ${error.message}", error)
            return null
        } finally {
            inputs.forEach { runCatching { it.close() } }
            outputs.forEach { runCatching { it.close() } }
            runCatching { model.close() }
        }
    }

    private fun channelOf(pixel: Int, channel: Int): Int = when (channel) {
        0 -> (pixel shr 16) and 0xFF
        1 -> (pixel shr 8) and 0xFF
        else -> pixel and 0xFF
    }

    private fun toByte(value: Float): Int =
        if (value <= 1.5f) (value * 255f).toInt().coerceIn(0, 255)
        else value.toInt().coerceIn(0, 255)

    private fun argb(red: Int, green: Int, blue: Int): Int =
        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}
