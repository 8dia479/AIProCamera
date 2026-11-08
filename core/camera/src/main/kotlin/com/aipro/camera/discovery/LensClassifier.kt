package com.aipro.camera.discovery

import com.aipro.common.Facing
import com.aipro.common.LensType
import kotlin.math.sqrt

/**
 * Classifies a physical camera as ultra-wide / wide / telephoto from the hardware focal length and
 * the sensor's physical size, using the 35 mm-equivalent focal length as the yardstick.
 *
 * Pure Kotlin with no Android types so it is unit-testable on the JVM. Values come from
 * CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS and SENSOR_INFO_PHYSICAL_SIZE.
 */
object LensClassifier {

    /** 35 mm film frame diagonal in millimetres. */
    const val FULL_FRAME_DIAGONAL_MM = 43.266615

    /** Thresholds on the 35 mm-equivalent focal length. */
    const val ULTRA_WIDE_MAX_MM = 19.0
    const val WIDE_MAX_MM = 40.0

    fun equivalentFocalLength35mm(focalLengthMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Double? {
        if (focalLengthMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return null
        val diagonal = sqrt(sensorWidthMm.toDouble() * sensorWidthMm + sensorHeightMm.toDouble() * sensorHeightMm)
        if (diagonal <= 0.0) return null
        return focalLengthMm * (FULL_FRAME_DIAGONAL_MM / diagonal)
    }

    fun classify(
        facing: Facing,
        focalLengthMm: Float,
        sensorWidthMm: Float,
        sensorHeightMm: Float,
    ): LensType {
        if (facing == Facing.FRONT) return LensType.FRONT
        val equiv = equivalentFocalLength35mm(focalLengthMm, sensorWidthMm, sensorHeightMm)
            ?: return LensType.UNKNOWN
        return when {
            equiv < ULTRA_WIDE_MAX_MM -> LensType.ULTRA_WIDE
            equiv <= WIDE_MAX_MM -> LensType.WIDE
            else -> LensType.TELEPHOTO
        }
    }
}
