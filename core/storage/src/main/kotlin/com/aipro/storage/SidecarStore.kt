package com.aipro.storage

import android.content.Context
import com.aipro.common.AiproLog
import com.aipro.common.CaptureRecord
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Append-only metadata index for every capture (spec section 12).
 *
 * Files live in the app-private external directory (no permissions, no network) as
 * `AIProCamera/metadata/<id>.json` plus a line-delimited `index.jsonl` for fast listing. Because the
 * index is on disk, a queued AI job survives process death and can be resumed (stage 3/5).
 */
class SidecarStore(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val root: File
        get() = (context.getExternalFilesDir(null) ?: context.filesDir).resolve("AIProCamera/metadata").apply { mkdirs() }

    fun save(record: CaptureRecord): Boolean = runCatching {
        root.resolve("${record.id}.json").writeText(json.encodeToString(CaptureRecord.serializer(), record))
        root.resolve("index.jsonl").appendText(
            json.encodeToString(CaptureRecord.serializer(), record).replace("\n", " ") + "\n",
        )
        true
    }.onFailure { AiproLog.e("Sidecar", "save failed for ${record.id}", it) }.getOrDefault(false)

    fun load(id: String): CaptureRecord? = runCatching {
        val file = root.resolve("$id.json")
        if (!file.exists()) null else json.decodeFromString(CaptureRecord.serializer(), file.readText())
    }.getOrNull()

    fun list(limit: Int = 50): List<CaptureRecord> = runCatching {
        root.listFiles { file -> file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(limit)
            ?.mapNotNull { file ->
                runCatching { json.decodeFromString(CaptureRecord.serializer(), file.readText()) }.getOrNull()
            } ?: emptyList()
    }.getOrDefault(emptyList())

    fun pending(): List<CaptureRecord> = list(limit = 500).filter {
        it.processingStatus != com.aipro.common.ProcessingStatus.ORIGINAL_ONLY.name &&
            it.processingStatus != com.aipro.common.ProcessingStatus.ENHANCED.name
    }

    /** Writes any text artefact (e.g. the capability report) next to the metadata for inspection. */
    fun writeTextArtifact(name: String, content: String): String? = runCatching {
        val target = root.resolve(name)
        target.writeText(content)
        target.absolutePath
    }.getOrNull()
}
