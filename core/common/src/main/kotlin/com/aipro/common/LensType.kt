package com.aipro.common

/** Physical lens classification derived from hardware focal length + sensor geometry. */
enum class LensType {
    ULTRA_WIDE,
    WIDE,
    TELEPHOTO,
    FRONT,
    UNKNOWN;

    val displayName: String
        get() = when (this) {
            ULTRA_WIDE -> "초광각"
            WIDE -> "광각"
            TELEPHOTO -> "망원"
            FRONT -> "전면"
            UNKNOWN -> "알 수 없음"
        }
}

/** Logical camera facing, mirrors CameraCharacteristics.LENS_FACING without the Android dependency. */
enum class Facing { BACK, FRONT, EXTERNAL }

enum class CaptureMode { PHOTO, VIDEO }

/**
 * Shooting-purpose presets (spec section 5). Stage 1 only stores the selection; the AI processing
 * that consumes it lands in stage 3.
 */
enum class ShootingPurpose { AUTO, PORTRAIT, LANDSCAPE, URBAN }

/** Processing state for the original/copy pairs kept in the gallery. */
enum class ProcessingStatus { ORIGINAL_ONLY, QUEUED, PROCESSING, ENHANCED, FAILED, CANCELLED }

/** Output formats we can actually request from Camera2 on a given device. */
enum class PhotoFormat { JPEG, HEIF, JPEG_R, RAW_DNG }
