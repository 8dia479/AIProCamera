package com.aipro.camera.discovery

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Camera2 ranges are not serializable out of the box; the capability report needs them as readable
 * JSON (`"50..3200"`) so the on-device probe output can be pasted straight into a bug report.
 */
object IntRangeSerializer : KSerializer<IntRange> {
    override val descriptor = PrimitiveSerialDescriptor("IntRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IntRange) = encoder.encodeString("${value.first}..${value.last}")

    override fun deserialize(decoder: Decoder): IntRange {
        val parts = decoder.decodeString().split("..")
        return parts[0].toInt()..parts[1].toInt()
    }
}

object LongRangeSerializer : KSerializer<LongRange> {
    override val descriptor = PrimitiveSerialDescriptor("LongRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LongRange) = encoder.encodeString("${value.first}..${value.last}")

    override fun deserialize(decoder: Decoder): LongRange {
        val parts = decoder.decodeString().split("..")
        return parts[0].toLong()..parts[1].toLong()
    }
}
