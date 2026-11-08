package com.aipro.ai.model

import kotlinx.serialization.Serializable

/** What a model is used for. One installed model per task; the newest compatible one wins. */
enum class ModelTask(val label: String) {
    SCENE_RECOGNITION("장면 인식"),
    DENOISE("노이즈 제거"),
    SUPER_RESOLUTION("업스케일링"),
    PORTRAIT("인물 처리");

    companion object {
        fun fromName(value: String?): ModelTask? = entries.firstOrNull { it.name == value }
    }
}

/**
 * On-device model descriptor. Models are *installed by the user* (spec section 7) and live in the
 * app's private storage; nothing is fetched from the network and nothing ships inside the APK.
 */
@Serializable
data class ModelManifest(
    val id: String,
    val task: String,
    val displayName: String,
    val version: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val inputShape: List<Int> = emptyList(),
    val outputShape: List<Int> = emptyList(),
    val dataType: String = "unknown",
    val inputScale: Float = 1f,
    val inputZeroPoint: Int = 0,
    val outputScale: Float = 1f,
    val outputZeroPoint: Int = 0,
    val installedAtMs: Long = System.currentTimeMillis(),
    val verified: Boolean = false,
    val note: String? = null,
) {
    val modelTask: ModelTask? get() = ModelTask.fromName(task)
}
