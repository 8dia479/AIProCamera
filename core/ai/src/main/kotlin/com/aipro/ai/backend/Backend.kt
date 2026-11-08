package com.aipro.ai.backend

import android.content.Context
import android.os.Build
import com.aipro.common.AiproLog

/**
 * Execution environments, in preference order. Nothing here is assumed: each one is probed on the
 * device and, if the probe fails, the next environment is used (spec section 2).
 */
enum class AccelerationBackend(val label: String) {
    NPU_QNN("NPU (Qualcomm QNN)"),
    GPU_OPENCL("GPU (LiteRT GPU / OpenCL)"),
    CPU_XNNPACK("CPU (XNNPACK)"),
}

data class BackendAvailability(
    val backend: AccelerationBackend,
    val available: Boolean,
    val detail: String,
)

data class BackendReport(
    val selected: AccelerationBackend,
    val probed: List<BackendAvailability>,
    val socManufacturer: String = Build.SOC_MANUFACTURER,
    val socModel: String = Build.SOC_MODEL,
    val deviceModel: String = Build.MODEL,
) {
    fun summary(): String = buildString {
        append("가속: ").append(selected.label)
        val unavailable = probed.filter { !it.available }
        if (unavailable.isNotEmpty()) {
            append(" · 미사용: ")
            append(unavailable.joinToString(", ") { "${it.backend.label}(${it.detail})" })
        }
    }

    companion object {
        /** Used before probing finishes so the UI never claims NPU support it has not verified. */
        val UNKNOWN = BackendReport(
            selected = AccelerationBackend.CPU_XNNPACK,
            probed = emptyList(),
        )
    }
}

/**
 * Probes the delegates that LiteRT can use on this specific device.
 *
 * * NPU: Qualcomm's QNN delegate needs vendor libraries (`libQnnTFLiteDelegate.so` + `libQnnHtp.so`)
 *   that are not part of the public LiteRT AAR. We try to load them; on most retail firmware the
 *   load fails, which is reported honestly instead of pretending NPU acceleration exists.
 * * GPU: the LiteRT GPU delegate (OpenCL) is instantiated once to prove it works on this driver.
 * * CPU: always available (XNNPACK is bundled with LiteRT).
 */
object BackendProbe {

    private const val TAG = "Backend"

    fun probe(context: Context, preferred: List<AccelerationBackend> = AccelerationBackend.entries): BackendReport {
        val prober = DelegateProber(context)
        val results = preferred.map { backend ->
            when (backend) {
                AccelerationBackend.NPU_QNN -> prober.probeNpu()
                AccelerationBackend.GPU_OPENCL -> prober.probeGpu()
                AccelerationBackend.CPU_XNNPACK -> BackendAvailability(backend, true, "내장")
            }
        }
        val selected = results.firstOrNull { it.available }?.backend ?: AccelerationBackend.CPU_XNNPACK
        AiproLog.i(TAG, "selected=${selected.label} :: ${results.joinToString { "${it.backend.name}=${it.available}" }}")
        return BackendReport(selected = selected, probed = results)
    }
}
