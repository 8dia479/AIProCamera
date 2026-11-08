package com.aipro.camera.engine

import com.aipro.common.CaptureRecord
import java.io.Closeable
import java.io.FileDescriptor
import java.io.OutputStream

/**
 * A camera output destination. :core:camera never talks to MediaStore directly; :core:storage
 * implements this interface so the engine stays unit-testable and can also write to a temp file
 * during development.
 */
interface MediaSink {

    fun createImageTarget(mimeType: String, relativeDir: String, extension: String): SinkTarget

    fun createVideoTarget(mimeType: String, relativeDir: String, extension: String): SinkTarget

    fun writeSidecar(record: CaptureRecord)

    fun delete(uri: String): Boolean

    /** Size of the finished file in bytes (0 when unknown). */
    fun fileSize(uri: String): Long
}

/** An open output stream plus the metadata needed to report the file back to the UI. */
class SinkTarget(
    val uri: String,
    val displayName: String,
    val relativePath: String,
    private val descriptor: java.io.FileDescriptor,
    private val stream: OutputStream,
    private val onClose: () -> Unit = {},
) : Closeable {

    val fileDescriptor: FileDescriptor get() = descriptor
    val outputStream: OutputStream get() = stream

    override fun close() {
        runCatching { stream.close() }
        onClose()
    }
}
