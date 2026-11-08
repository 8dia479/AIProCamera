package com.aipro.camera.capture

import com.aipro.camera.discovery.SizeInfo
import kotlin.math.abs

/**
 * Photo/video framing ratios (spec: 화질/비율 설정).
 *
 * The ratio is expressed **in the sensor frame (landscape basis)**: 4:3 means the sensor buffer is
 * 4:3, and when the phone is held upright the picture is displayed 3:4 because the JPEG carries an
 * EXIF rotation. That is how Galaxy Camera behaves, and it keeps one option list valid for both
 * orientations instead of duplicating "3:4" and "4:3".
 */
enum class AspectRatioOption(val landscapeW: Float, val landscapeH: Float, val label: String) {
    RATIO_1_1(1f, 1f, "1:1"),
    RATIO_4_3(4f, 3f, "4:3"),
    RATIO_16_9(16f, 9f, "16:9"),
    RATIO_22_9(22f, 9f, "22:9"),
    ;

    /** width / height in the sensor frame. */
    val ratio: Float get() = landscapeW / landscapeH

    /** Same ratio expressed for the current display orientation (portrait → height/width). */
    fun displayRatio(portrait: Boolean): Float = if (portrait) landscapeH / landscapeW else ratio
}

/** Rect in stream pixel coordinates, centred crop. */
data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * How a requested ratio maps onto the sizes a device really reports.
 *
 * Two honest outcomes only:
 *  * a native stream size already has the ratio (within [TOLERANCE]) → no crop, full sensor output;
 *  * no native size exists (typical for 22:9 and sometimes 1:1) → use the best real size and crop it
 *    centrally to the exact ratio. The crop is recorded in the sidecar metadata so nothing is hidden.
 */
object RatioSizing {

    const val TOLERANCE = 0.02f

    data class Plan(
        val streamSize: SizeInfo,
        val cropRect: CropRect?,
        val outputWidth: Int,
        val outputHeight: Int,
    ) {
        /** True when the frame is produced by cropping rather than by a native stream size. */
        val cropped: Boolean get() = cropRect != null

        val summary: String
            get() = buildString {
                append(streamSize.width).append('×').append(streamSize.height)
                append(" → ").append(outputWidth).append('×').append(outputHeight)
                if (cropped) append(" (크롭)") else append(" (네이티브)")
            }
    }

    fun aspect(width: Int, height: Int): Float =
        if (height <= 0) 0f else width.toFloat() / height.toFloat()

    fun matches(width: Int, height: Int, ratio: Float): Boolean {
        val a = aspect(width, height)
        if (a <= 0f || ratio <= 0f) return false
        return abs(a - ratio) / ratio <= TOLERANCE
    }

    fun matches(width: Int, height: Int, option: AspectRatioOption): Boolean {
        val a = aspect(width, height)
        if (a <= 0f) return false
        return abs(a - option.ratio) / option.ratio <= TOLERANCE
    }

    /**
     * Chooses the stream size and crop for [option].
     *
     * Preference order: an exact-ratio size (largest), then the size whose aspect is >= the target
     * (crop the width, which keeps every row of the sensor) and finally any larger-aspect size.
     * Sizes smaller than a quarter of the biggest available one are ignored so a tiny thumbnail size
     * can never win just because its aspect happens to match.
     */
    /** Plan for an arbitrary ratio (used when the preview stream dictates the session aspect). */
    fun planRatio(sizes: List<SizeInfo>, ratio: Float, maxPixels: Long = 0L): Plan? =
        planWithRatio(sizes, ratio, maxPixels)

    fun plan(
        sizes: List<SizeInfo>,
        option: AspectRatioOption,
        /** Highest pixel count a single still may use; 0 / negative means "no cap". */
        maxPixels: Long = 0L,
    ): Plan? = planWithRatio(sizes, option.ratio, maxPixels)

    private fun planWithRatio(sizes: List<SizeInfo>, ratio: Float, maxPixels: Long): Plan? {
        val usable0 = sizes.filter { it.width > 0 && it.height > 0 }
        if (usable0.isEmpty()) return null
        val capped = if (maxPixels > 0L) usable0.filter { it.width.toLong() * it.height <= maxPixels } else usable0
        // Keep the cap only when at least one sensor size respects it (otherwise use every size).
        val usable = capped.ifEmpty { usable0 }
        val largestArea = usable.maxOf { it.width.toLong() * it.height }.toDouble()
        val pool = usable.filter { it.width.toLong() * it.height >= largestArea * 0.25 }

        val native = pool.filter { matches(it.width, it.height, ratio) }
            .maxByOrNull { it.width.toLong() * it.height }
        if (native != null) {
            return Plan(native, cropRect = null, outputWidth = native.width, outputHeight = native.height)
        }

        val source = pool
            .filter { aspect(it.width, it.height) >= ratio }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: pool.maxByOrNull { it.width.toLong() * it.height }
            ?: return null

        val crop = centeredCrop(source, ratio) ?: return null
        return Plan(source, crop, crop.width, crop.height)
    }

    /** Largest centred rect with [target] aspect inside [size]. */
    fun centeredCrop(size: SizeInfo, target: Float): CropRect? {
        if (size.width <= 0 || size.height <= 0 || target <= 0f) return null
        val current = aspect(size.width, size.height)
        if (abs(current - target) / target <= TOLERANCE) return null
        return if (current > target) {
            // Too wide: keep the full height and trim the sides.
            val width = (size.height * target).toInt().coerceAtMost(size.width).coerceAtLeast(1)
            CropRect(left = (size.width - width) / 2, top = 0, width = width, height = size.height)
        } else {
            // Too tall: keep the full width and trim top/bottom.
            val height = (size.width / target).toInt().coerceAtMost(size.height).coerceAtLeast(1)
            CropRect(left = 0, top = (size.height - height) / 2, width = size.width, height = height)
        }
    }

    /** Ratios that a given size list can serve *natively* (used to hide impossible video ratios). */
    fun nativelySupported(sizes: List<SizeInfo>, options: List<AspectRatioOption>): List<AspectRatioOption> =
        options.filter { option -> sizes.any { matches(it.width, it.height, option) } }
}
