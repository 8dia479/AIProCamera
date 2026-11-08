package com.aipro.ai.backend

import android.content.Context
import com.aipro.common.AiproLog
import com.aipro.ai.inference.CompiledModelRunner

/**
 * Asks the LiteRT runtime itself which accelerators this device can actually use.
 *
 * The previous probe looked for `org.tensorflow.lite.gpu.GpuDelegate`, which **does not exist** in
 * LiteRT 2.x: `com.google.ai.edge.litert:litert-gpu:2.3.2` ships only
 * `libLiteRtClGlAccelerator.so` and an empty classes.jar, and GPU/NPU are selected through
 * `CompiledModel.Options(accelerators = ...)` instead of a Java delegate. That is why the old probe
 * always failed with ClassNotFoundException and reported "GPU 사용 불가".
 */
internal class DelegateProber(private val context: Context) {

    /**
     * Enumerates the accelerators the LiteRT runtime actually reports for this device.
     *
     * Called through reflection on purpose: `litert-api:2.3.2` is built with a newer Kotlin compiler
     * than this project (the module even needs `-Xskip-metadata-version-check`), so its Kotlin
     * properties are not resolvable from source here while the JVM signatures are perfectly usable.
     */
    private fun availableAccelerators(): Set<String>? = runCatching {
        val envClass = Class.forName("com.google.ai.edge.litert.Environment")
        val env = envClass.getMethod("create", Context::class.java).invoke(null, context) ?: return null
        val names = LinkedHashSet<String>()
        val accelerators = envClass.getMethod("getAvailableAccelerators").invoke(env) as? Collection<*>
        accelerators?.forEach { accelerator ->
            if (accelerator is Enum<*>) names.add(accelerator.name)
        }
        runCatching { (env as? AutoCloseable)?.close() }
        names
    }.getOrElse { error ->
        AiproLog.w("Prober", "LiteRT 가속기 목록 조회 실패", error)
        null
    }

    fun probeGpu(): BackendAvailability {
        val accelerators = availableAccelerators()
            ?: return BackendAvailability(AccelerationBackend.GPU_OPENCL, false, "LiteRT 런타임 초기화 실패")
        val available = accelerators.contains("GPU")
        val detail = if (available) {
            "LiteRT 런타임이 GPU 가속기를 보고합니다 (OpenCL/GL 플러그인 로드 확인)"
        } else {
            "LiteRT 런타임이 GPU 가속기를 보고하지 않습니다 (가능: " +
                (accelerators.joinToString("·").ifEmpty { "없음" }) + ")"
        }
        return BackendAvailability(AccelerationBackend.GPU_OPENCL, available, detail)
    }

    /**
     * NPU goes through the same accelerator enumeration. Qualcomm HTP still needs vendor QNN
     * libraries (`libQnnHtp.so` …) that the app cannot ship, so this stays honest: if the runtime does
     * not list NPU we say so, and we never pretend the CPU result came from the NPU.
     */
    fun probeNpu(): BackendAvailability {
        val soc = "${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}".trim()
        val supported = runCatching {
            com.google.ai.edge.litert.NpuCompatibilityChecker.Qualcomm.isDeviceSupported()
        }.getOrDefault(false)
        if (!supported) {
            return BackendAvailability(
                AccelerationBackend.NPU_QNN,
                false,
                "LiteRT NPU provider가 이 기기를 지원하지 않습니다 ($soc)",
            )
        }
        return runCatching {
            val available = CompiledModelRunner.availableAccelerators(context, useNpu = true)
            val ready = available.contains("NPU")
            BackendAvailability(
                AccelerationBackend.NPU_QNN,
                ready,
                if (ready) "LiteRT NPU provider 준비됨 ($soc) · 모델 연산자에 따라 CPU 보완 가능"
                else "기기는 지원되지만 런타임 가속기 목록에 NPU가 없습니다 ($soc)",
            )
        }.getOrElse { error ->
            BackendAvailability(
                AccelerationBackend.NPU_QNN,
                false,
                "LiteRT NPU 초기화 실패 ($soc): ${error.message ?: error.javaClass.simpleName}",
            )
        }
    }
}
