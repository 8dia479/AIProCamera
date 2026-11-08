package com.aipro.ai.inference

import android.graphics.Bitmap
import com.aipro.common.AiproLog
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import org.tensorflow.lite.Delegate
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The single place in the app that touches the LiteRT interpreter API.
 *
 * Keeping it isolated means delegate/API changes (NPU delegates appearing, LiteRT 2.x CompiledModel)
 * only affect this file, and every model load goes through the same verification path.
 */
class LiteRtInterpreter internal constructor(
    private val interpreter: Interpreter,
    private val delegate: AutoCloseable?,
    val inputShape: List<Int>,
    val outputShape: List<Int>,
    val inputDataType: String,
    private val inputIsQuantized: Boolean,
    private val outputIsQuantized: Boolean,
) : Closeable {

    val inputHeight: Int get() = inputShape.getOrNull(1) ?: 0
    val inputWidth: Int get() = inputShape.getOrNull(2) ?: 0
    val inputChannels: Int get() = inputShape.getOrNull(3) ?: 3

    /**
     * Runs the network on an ARGB bitmap. The input is resized to the tensor's spatial size and the
     * output is mapped back to the original resolution, so callers always get a full-size frame.
     */
    fun run(bitmap: Bitmap): Bitmap? {
        if (inputWidth <= 0 || inputHeight <= 0) return null
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (resized !== bitmap) resized.recycle()

        val elements = inputWidth * inputHeight * inputChannels
        val output: Array<Any>
        val input: Array<Any>
        if (inputIsQuantized) {
            val quantized = ByteArray(elements)
            var index = 0
            for (pixel in pixels) {
                for (channel in 0 until inputChannels) {
                    if (index >= quantized.size) break
                    quantized[index++] = channelOf(pixel, channel).toByte()
                }
            }
            input = arrayOf<Any>(quantized)
        } else {
            val buffer = ByteBuffer.allocateDirect(elements * 4).order(ByteOrder.nativeOrder())
            for (pixel in pixels) {
                for (channel in 0 until inputChannels) buffer.putFloat(channelOf(pixel, channel) / 255f)
            }
            buffer.rewind()
            input = arrayOf<Any>(buffer)
        }

        val outH = outputShape.getOrNull(1) ?: inputHeight
        val outW = outputShape.getOrNull(2) ?: inputWidth
        val channels = outputShape.getOrNull(3) ?: 3
        if (outW <= 0 || outH <= 0) return null
        val outElements = outW * outH * channels
        output = if (outputIsQuantized) {
            arrayOf<Any>(ByteArray(outElements))
        } else {
            arrayOf<Any>(FloatArray(outElements))
        }

        interpreter.run(input, output)

        val outPixels = IntArray(outW * outH)
        if (outputIsQuantized) {
            val data = output[0] as ByteArray
            for (i in 0 until outW * outH) {
                val base = i * channels
                if (base + 2 >= data.size) break
                val r = data[base].toInt() and 0xFF
                val g = data[base + 1].toInt() and 0xFF
                val b = data[base + 2].toInt() and 0xFF
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else {
            val data = output[0] as FloatArray
            for (i in 0 until outW * outH) {
                val base = i * channels
                if (base + 2 >= data.size) break
                val r = toByte(data[base])
                val g = toByte(data[base + 1])
                val b = toByte(data[base + 2])
                outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val produced = Bitmap.createBitmap(outPixels, outW, outH, Bitmap.Config.ARGB_8888)
        return if (outW == bitmap.width && outH == bitmap.height) produced
        else Bitmap.createScaledBitmap(produced, bitmap.width, bitmap.height, true).also { produced.recycle() }
    }

    /** Escape hatch for callers that manage their own tensors (e.g. classification heads). */
    fun runRaw(input: Array<Any>, output: Array<Any>) {
        interpreter.run(input, output)
    }

    private fun channelOf(pixel: Int, channel: Int): Int = when (channel) {
        0 -> (pixel shr 16) and 0xFF
        1 -> (pixel shr 8) and 0xFF
        else -> pixel and 0xFF
    }

    private fun toByte(value: Float): Int =
        if (value <= 1.5f) (value * 255f).toInt().coerceIn(0, 255) else value.toInt().coerceIn(0, 255)

    override fun close() {
        runCatching { interpreter.close() }
        runCatching { delegate?.close() }
    }
}

object InterpreterFactory {

    private const val TAG = "LiteRT"

    /**
     * Creates a CPU (XNNPACK) interpreter.
     *
     * LiteRT 2.3.2 has **no** `org.tensorflow.lite.gpu.GpuDelegate` / `NnapiDelegate` class: the GPU
     * and NPU accelerators are only reachable through `com.google.ai.edge.litert.CompiledModel`, so
     * `useGpu` cannot be honoured here. Callers that want acceleration must use
     * [com.aipro.ai.inference.CompiledRunner].
     */
    fun create(
        modelBuffer: ByteBuffer,
        threads: Int = 4,
        useNpu: Boolean = false,
        nativeLibraryDir: String? = null,
    ): LiteRtInterpreter {
        val options = Interpreter.Options().apply {
            setNumThreads(threads.coerceIn(1, 8))
        }
        var delegate: Delegate? = null
        var interpreter: Interpreter? = null
        try {
            if (useNpu) {
                delegate = QualcommNpuDelegate.create(
                    nativeLibraryDir ?: error("QNN native library 경로가 없습니다"),
                )
                options.addDelegate(delegate)
            }
            interpreter = Interpreter(modelBuffer, options)
            val inputTensor: Tensor = interpreter.getInputTensor(0)
            val outputTensor: Tensor = interpreter.getOutputTensor(0)
            return LiteRtInterpreter(
                interpreter = interpreter,
                delegate = delegate,
                inputShape = inputTensor.shape().toList(),
                outputShape = outputTensor.shape().toList(),
                inputDataType = inputTensor.dataType().toString(),
                inputIsQuantized = inputTensor.dataType() == org.tensorflow.lite.DataType.UINT8 ||
                    inputTensor.dataType() == org.tensorflow.lite.DataType.INT8,
                outputIsQuantized = outputTensor.dataType() == org.tensorflow.lite.DataType.UINT8 ||
                    outputTensor.dataType() == org.tensorflow.lite.DataType.INT8,
            )
        } catch (error: Throwable) {
            runCatching { interpreter?.close() }
            runCatching { delegate?.close() }
            throw error
        }
    }
}
