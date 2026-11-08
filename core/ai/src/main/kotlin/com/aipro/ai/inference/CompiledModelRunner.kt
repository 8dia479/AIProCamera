package com.aipro.ai.inference

import android.content.Context
import android.graphics.Bitmap
import com.aipro.ai.model.ModelManifest
import com.aipro.common.AiproLog
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import com.google.ai.edge.litert.TensorBuffer
import java.io.File

/**
 * Executes image-to-image LiteRT models using the standalone CompiledModel API.
 * Each model is created, run, and closed synchronously on the same background worker thread.
 */
internal object CompiledModelRunner {

    data class Result(val bitmap: Bitmap, val accelerator: Accelerator)

    @Volatile
    private var sharedEnvironment: Environment? = null

    @Synchronized
    private fun environment(context: Context, useNpu: Boolean): Environment {
        sharedEnvironment?.let { return it }
        val app = context.applicationContext
        val env = if (useNpu && BuiltinNpuAcceleratorProvider(app).isDeviceSupported()) {
            Environment.create(app, BuiltinNpuAcceleratorProvider(app))
        } else {
            Environment.create(app)
        }
        sharedEnvironment = env
        return env
    }

    fun availableAccelerators(context: Context, useNpu: Boolean): Set<String> {
        val env = environment(context, useNpu)
        val method = env.javaClass.getMethod("getAvailableAccelerators")
        val values = method.invoke(env) as? Collection<*> ?: return emptySet()
        return values.mapNotNull { (it as? Enum<*>)?.name }.toSet()
    }

    fun run(
        context: Context,
        file: File,
        manifest: ModelManifest,
        bitmap: Bitmap,
        preferNpu: Boolean,
        preferGpu: Boolean,
        threads: Int,
    ): Result? {
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
        require(inputWidth > 0 && inputHeight > 0 && inputChannels in 1..4) { "지원하지 않는 입력 형상" }
        require(outputWidth > 0 && outputHeight > 0 && outputChannels in 1..4) { "지원하지 않는 출력 형상" }

        val supportsNpu = runCatching { BuiltinNpuAcceleratorProvider(context).isDeviceSupported() }
            .getOrDefault(false)
        val env = environment(context, useNpu = preferNpu && supportsNpu)
        val available = runCatching { availableAccelerators(context, useNpu = preferNpu && supportsNpu) }
            .getOrDefault(emptySet())

        val attempts = buildList {
            if (preferNpu && supportsNpu && available.contains("NPU")) {
                add(Accelerator.NPU to CompiledModel.Options(Accelerator.NPU))
            }
            if (preferGpu && available.contains("GPU")) {
                add(Accelerator.GPU to CompiledModel.Options(Accelerator.GPU, Accelerator.CPU))
            }
            add(
                Accelerator.CPU to CompiledModel.Options(Accelerator.CPU).apply {
                    cpuOptions = CompiledModel.CpuOptions(numThreads = threads.coerceIn(1, 8))
                },
            )
        }

        var model: CompiledModel? = null
        var selected = Accelerator.CPU
        for ((accelerator, options) in attempts) {
            try {
                model = CompiledModel.create(file.absolutePath, options, env)
                selected = accelerator
                break
            } catch (error: LiteRtException) {
                AiproLog.w("CompiledRunner", "$accelerator 컴파일 실패 → 다음 가속기", error)
            } catch (error: Throwable) {
                AiproLog.w("CompiledRunner", "$accelerator 초기화 실패 → 다음 가속기", error)
            }
        }
        val compiled = model ?: return null
        val inputs = mutableListOf<TensorBuffer>()
        val outputs = mutableListOf<TensorBuffer>()
        try {
            inputs += compiled.createInputBuffers()
            outputs += compiled.createOutputBuffers()
            require(inputs.isNotEmpty() && outputs.isNotEmpty()) { "모델 텐서 생성 실패" }

            val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val pixels = IntArray(inputWidth * inputHeight)
            try {
                resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            } finally {
                if (resized !== bitmap) resized.recycle()
            }

            val inputType = manifest.dataType.uppercase()
            val elements = inputWidth * inputHeight * inputChannels
            when {
                inputType.contains("FLOAT") -> {
                    val values = FloatArray(elements)
                    var index = 0
                    for (pixel in pixels) {
                        for (channel in 0 until inputChannels) {
                            values[index++] = channelOf(pixel, channel) / 255f
                        }
                    }
                    inputs[0].writeFloat(values)
                }
                inputType.contains("INT8") || inputType.contains("UINT8") -> {
                    val values = ByteArray(elements)
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

            compiled.run(inputs, outputs)

            val outputPixels = IntArray(outputWidth * outputHeight)
            val outputType = manifest.dataType.uppercase()
            if (outputType.contains("FLOAT")) {
                val values = outputs[0].readFloat()
                for (index in outputPixels.indices) {
                    val base = index * outputChannels
                    if (base + 2 >= values.size) break
                    outputPixels[index] = argb(toByte(values[base]), toByte(values[base + 1]), toByte(values[base + 2]))
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

            val produced = Bitmap.createBitmap(outputPixels, outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
            val output = if (outputWidth == bitmap.width && outputHeight == bitmap.height) {
                produced
            } else {
                Bitmap.createScaledBitmap(produced, bitmap.width, bitmap.height, true).also { produced.recycle() }
            }
            return Result(output, selected)
        } catch (error: Throwable) {
            AiproLog.w("CompiledRunner", "$selected 추론 실패", error)
            return null
        } finally {
            inputs.forEach { runCatching { it.close() } }
            outputs.forEach { runCatching { it.close() } }
            runCatching { compiled.close() }
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
