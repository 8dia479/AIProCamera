package com.aipro.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.aipro.ai.pipeline.CaptureSource
import com.aipro.ai.pipeline.AiJobStore
import com.aipro.ai.pipeline.EnhancedDestination
import com.aipro.ai.pipeline.EnhancementResult
import com.aipro.camera.engine.CameraController
import com.aipro.common.AiproLog
import com.aipro.common.CaptureRecord
import com.aipro.common.ProcessingStatus
import com.aipro.storage.MediaStoreSink
import com.aipro.storage.SidecarStore
import kotlinx.serialization.json.Json

/**
 * Glue between :core:ai (which knows nothing about Android storage) and this app's MediaStore sink.
 * Decoding is bounds-aware so a 50 MP original never produces an unbounded allocation.
 */
class MediaStoreCaptureSource(private val context: Context) : CaptureSource {

    override fun loadBitmap(uri: String, maxDimension: Int): Bitmap? = runCatching {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) sampleSize *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = context.contentResolver.openInputStream(parsed)
            ?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        if (maxOf(decoded.width, decoded.height) <= maxDimension) return decoded
        val scale = maxDimension.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        scaled
    }.onFailure { AiproLog.e("CaptureSource", "decode failed for $uri", it) }.getOrNull()
}

class MediaStoreEnhancedDestination(
    private val sink: MediaStoreSink,
) : EnhancedDestination {

    override fun saveEnhanced(bitmap: Bitmap, record: CaptureRecord, result: EnhancementResult): String? = runCatching {
        val target = sink.createImageTarget("image/jpeg", CameraController.ENHANCED_IMAGE_DIR, "jpg")
        target.outputStream.use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream)) error("JPEG 압축 실패")
        }
        target.close()
        AiproLog.i("Destination", "enhanced saved: ${target.uri} (${result.steps.size} steps)")
        target.uri
    }.onFailure { AiproLog.e("Destination", "enhanced save failed", it) }.getOrNull()
}

/** Job persistence backed by the existing sidecar index, so nothing new has to be invented. */
class SidecarJobStore(private val sidecar: SidecarStore) : AiJobStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun pending(): List<CaptureRecord> = sidecar.list(limit = 500).filter {
        it.processingStatus == ProcessingStatus.QUEUED.name ||
            it.processingStatus == ProcessingStatus.PROCESSING.name ||
            it.processingStatus == ProcessingStatus.FAILED.name
    }

    override fun save(record: CaptureRecord) {
        sidecar.save(record)
    }
}
