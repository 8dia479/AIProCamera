package com.aipro.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.aipro.camera.engine.MediaSink
import com.aipro.camera.engine.SinkTarget
import com.aipro.common.AiproLog
import com.aipro.common.CaptureRecord
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaStore-backed sink: writes into `Pictures/AIProCamera/...` with IS_PENDING so a half-written
 * file is never visible to the gallery and an interrupted capture cannot corrupt anything.
 */
class MediaStoreSink(private val context: Context) : MediaSink {

    private val sidecar = SidecarStore(context)

    override fun createImageTarget(mimeType: String, relativeDir: String, extension: String): SinkTarget {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(extension))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert 실패 ($relativeDir)")
        return openTarget(uri, collection, relativeDir, mimeType)
    }

    override fun createVideoTarget(mimeType: String, relativeDir: String, extension: String): SinkTarget {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName(extension))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore insert 실패 ($relativeDir)")
        return openTarget(uri, collection, relativeDir, mimeType)
    }

    private fun openTarget(uri: Uri, collection: Uri, relativeDir: String, mimeType: String): SinkTarget {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("출력 스트림을 열 수 없습니다: $uri")
        val stream = FileOutputStream(descriptor.fileDescriptor)
        return SinkTarget(
            uri = uri.toString(),
            displayName = uri.lastPathSegment ?: "",
            relativePath = relativeDir,
            descriptor = descriptor.fileDescriptor,
            stream = stream,
            onClose = {
                runCatching { descriptor.close() }
                publish(uri, mimeType)
            },
        )
    }

    private fun publish(uri: Uri, mimeType: String) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (mimeType.startsWith("image/")) {
                    put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                } else {
                    put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                }
            }
            context.contentResolver.update(uri, values, null, null)
        }.onFailure { AiproLog.w("Storage", "publish failed for $uri", it) }
    }

    override fun writeSidecar(record: CaptureRecord) {
        sidecar.save(record)
    }

    override fun delete(uri: String): Boolean = runCatching {
        context.contentResolver.delete(Uri.parse(uri), null, null) >= 0
    }.getOrDefault(false)

    override fun fileSize(uri: String): Long = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
    }.getOrDefault(0L)

    fun sidecarStore(): SidecarStore = sidecar

    private fun fileName(extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return "AIPRO_$stamp.$extension"
    }

    companion object {
        /** True when the app can write to shared storage without legacy permissions (API 29+). */
        fun sharedStorageReady(): Boolean = Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }
}
