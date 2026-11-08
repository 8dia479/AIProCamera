package com.aipro.ai.model

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.aipro.common.AiproLog
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Installs, verifies and removes models from app-private storage.
 *
 * Install pipeline: copy → flatbuffer magic check → size/ABI sanity → optional interpreter load →
 * SHA-256 → manifest.json. A model that fails any step is rejected and never offered to the pipeline.
 */
class ModelManager(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val root: File get() = File(context.filesDir, "ai_models").apply { mkdirs() }

    fun installed(): List<ModelManifest> = runCatching {
        root.listFiles { file -> file.isDirectory }
            ?.mapNotNull { dir -> readManifest(dir) }
            ?.sortedBy { it.task }
            ?: emptyList()
    }.getOrDefault(emptyList())

    fun modelFor(task: ModelTask): ModelManifest? =
        installed().filter { it.task == task.name && it.verified }.maxByOrNull { it.installedAtMs }

    fun fileFor(manifest: ModelManifest): File = File(root, manifest.id).resolve(manifest.fileName)

    fun applicationContext(): Context = context.applicationContext

    fun install(uri: Uri, task: ModelTask, displayName: String): Result<ModelManifest> = runCatching {
        val resolver = context.contentResolver
        val id = "m_${System.currentTimeMillis()}"
        val dir = File(root, id).apply { mkdirs() }
        val target = File(dir, "model.tflite")
        resolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("모델 파일을 열 수 없습니다")

        if (target.length() <= 0L) error("빈 파일입니다")
        if (target.length() > MAX_MODEL_BYTES) error("모델이 너무 큽니다 (최대 ${MAX_MODEL_BYTES / 1024 / 1024}MB)")
        verifyFlatBuffer(target)

        val probe = probeShapes(target)
        val manifest = ModelManifest(
            id = id,
            task = task.name,
            displayName = displayName,
            version = probe.detail ?: "unverified",
            fileName = target.name,
            sha256 = sha256(target),
            sizeBytes = target.length(),
            inputShape = probe.inputShape,
            outputShape = probe.outputShape,
            dataType = probe.dataType,
            inputScale = probe.inputScale,
            inputZeroPoint = probe.inputZeroPoint,
            outputScale = probe.outputScale,
            outputZeroPoint = probe.outputZeroPoint,
            verified = probe.loaded,
            note = probe.detail,
        )
        File(dir, MANIFEST).writeText(json.encodeToString(ModelManifest.serializer(), manifest))
        AiproLog.i("ModelManager", "installed ${manifest.displayName} (${manifest.task}) verified=${manifest.verified}")
        manifest
    }.onFailure { AiproLog.e("ModelManager", "install failed", it) }

    fun remove(modelId: String): Boolean = runCatching {
        File(root, modelId).deleteRecursively()
    }.getOrDefault(false)

    fun clear() {
        root.deleteRecursively()
        root.mkdirs()
    }

    private fun readManifest(dir: File): ModelManifest? = runCatching {
        val file = File(dir, MANIFEST)
        if (!file.exists()) return@runCatching null
        json.decodeFromString(ModelManifest.serializer(), file.readText())
    }.getOrNull()

    private fun verifyFlatBuffer(file: File) {
        val header = ByteArray(8)
        file.inputStream().use { it.read(header) }
        // LiteRT models are FlatBuffers whose identifier "TFL3" sits at byte offset 4.
        val magic = header.copyOfRange(4, 8).toString(Charsets.US_ASCII)
        require(magic == "TFL3") { "LiteRT(.tflite) 파일이 아닙니다 (magic=$magic)" }
    }

    internal data class ShapeProbe(
        val loaded: Boolean,
        val inputShape: List<Int>,
        val outputShape: List<Int>,
        val dataType: String,
        val inputScale: Float,
        val inputZeroPoint: Int,
        val outputScale: Float,
        val outputZeroPoint: Int,
        val detail: String?,
    )

    /** Loads the model in a throwaway interpreter to record shapes and confirm it is valid. */
    private fun probeShapes(file: File): ShapeProbe = runCatching {
        val bytes = file.inputStream().use { it.readBytes() }
        val buffer = java.nio.ByteBuffer.allocateDirect(bytes.size).apply {
            order(java.nio.ByteOrder.nativeOrder())
            put(bytes)
            rewind()
        }
        com.aipro.ai.inference.InterpreterFactory.create(buffer, threads = 1).use { engine ->
            ShapeProbe(
                loaded = true,
                inputShape = engine.inputShape,
                outputShape = engine.outputShape,
                dataType = engine.inputDataType,
                inputScale = 0f,
                inputZeroPoint = 0,
                outputScale = 0f,
                outputZeroPoint = 0,
                detail = null,
            )
        }
    }.getOrElse { error ->
        AiproLog.w("ModelManager", "model could not be loaded", error)
        ShapeProbe(false, emptyList(), emptyList(), "unknown", 0f, 0, 0f, 0, error.message)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val MAX_MODEL_BYTES = 400L * 1024 * 1024
    }
}
