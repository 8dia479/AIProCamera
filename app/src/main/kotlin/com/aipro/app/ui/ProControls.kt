package com.aipro.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipro.camera.discovery.CameraCapabilities
import com.aipro.camera.manual.ExposureFormat
import com.aipro.camera.manual.ManualSettings
import com.aipro.camera.video.VideoProfileSelector
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Pro panel. A control is only composed when the camera actually supports it: the gating comes from
 * [CameraCapabilities.manual], which is probed from CameraCharacteristics at runtime.
 */
@Composable
fun ProPanel(
    caps: CameraCapabilities?,
    manual: ManualSettings,
    onUpdate: ((ManualSettings) -> ManualSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val support = caps?.manual ?: return
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("PRO", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        Text(
            text = support.describe(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        val isoRange = caps.isoRange
        if (support.iso && isoRange != null) {
            val range = isoRange
            val current = manual.iso
            SliderRow(
                label = "ISO",
                valueText = ExposureFormat.iso(current),
                auto = current == null,
                fraction = if (current == null) 0f else logFraction(range.first, range.last, current),
                onAutoToggle = { onUpdate { it.copy(iso = if (current == null) range.first.coerceAtLeast(100) else null) } },
            ) { fraction ->
                val iso = logScale(range.first, range.last, fraction)
                onUpdate { it.copy(iso = iso) }
            }
        }
        val exposureRange = caps.exposureTimeRangeNs
        if (support.shutterSpeed && exposureRange != null) {
            val stops = exposureStops(exposureRange)
            val index = manual.exposureTimeNs?.let { ns -> stops.indexOfFirst { it >= ns }.coerceAtLeast(0) }
            SliderRow(
                label = "SHUTTER",
                valueText = ExposureFormat.exposureTime(manual.exposureTimeNs ?: 0L),
                auto = manual.exposureTimeNs == null,
                fraction = when {
                    manual.exposureTimeNs == null || stops.size < 2 -> 0f
                    else -> ((index ?: 0).toFloat() / (stops.size - 1)).coerceIn(0f, 1f)
                },
                onAutoToggle = { onUpdate { it.copy(exposureTimeNs = if (manual.exposureTimeNs == null) stops[stops.size / 2] else null) } },
            ) { fraction ->
                val i = (fraction * (stops.size - 1)).roundToInt().coerceIn(0, stops.size - 1)
                onUpdate { it.copy(exposureTimeNs = stops[i]) }
            }
            if (index != null) Unit
        }
        val evRange = caps.exposureCompensationRange
        if (support.exposureCompensation && evRange != null) {
            val range = evRange
            SliderRow(
                label = "EV",
                valueText = ExposureFormat.ev(manual.exposureCompensationEv),
                auto = manual.exposureCompensationEv == 0f,
                fraction = if (range.last == range.first) 0f
                else ((manual.exposureCompensationEv - range.first) / (range.last - range.first)).coerceIn(0f, 1f),
                onAutoToggle = { onUpdate { it.copy(exposureCompensationEv = 0f) } },
            ) { fraction ->
                val ev = range.first + fraction * (range.last - range.first)
                onUpdate { it.copy(exposureCompensationEv = ev) }
            }
        }
        if (support.focus) {
            SliderRow(
                label = "FOCUS",
                valueText = manual.focusSlider01?.let { f -> "MF ${(f * 100).roundToInt()}%" } ?: "AF",
                auto = manual.focusSlider01 == null,
                fraction = manual.focusSlider01 ?: 0.5f,
                onAutoToggle = { onUpdate { it.copy(focusSlider01 = if (manual.focusSlider01 == null) 1f else null) } },
            ) { fraction ->
                onUpdate { it.copy(focusSlider01 = fraction) }
            }
        }
        if (support.whiteBalance) {
            SliderRow(
                label = "WB",
                valueText = ExposureFormat.kelvin(manual.whiteBalanceKelvin),
                auto = manual.whiteBalanceKelvin == null,
                fraction = manual.whiteBalanceKelvin?.let { k ->
                    ((k - ManualSettings.MIN_KELVIN).toFloat() / (ManualSettings.MAX_KELVIN - ManualSettings.MIN_KELVIN)).coerceIn(0f, 1f)
                } ?: 0.5f,
                onAutoToggle = { onUpdate { it.copy(whiteBalanceKelvin = if (manual.whiteBalanceKelvin == null) 5500 else null) } },
            ) { fraction ->
                val kelvin = ManualSettings.MIN_KELVIN +
                    (fraction * (ManualSettings.MAX_KELVIN - ManualSettings.MIN_KELVIN)).roundToInt()
                onUpdate { it.copy(whiteBalanceKelvin = kelvin) }
            }
        }
        if (support.zoom) {
            SliderRow(
                label = "ZOOM",
                valueText = String.format("%.1fx", manual.zoomRatio),
                auto = manual.zoomRatio == 1f,
                fraction = if (caps.maxDigitalZoom <= 1f) 0f
                else ((manual.zoomRatio - 1f) / (caps.maxDigitalZoom - 1f)).coerceIn(0f, 1f),
                onAutoToggle = { onUpdate { it.copy(zoomRatio = 1f) } },
            ) { fraction ->
                onUpdate { it.copy(zoomRatio = 1f + fraction * (caps.maxDigitalZoom - 1f)) }
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    auto: Boolean,
    fraction: Float,
    onAutoToggle: () -> Unit,
    onFraction: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.width(72.dp))
            FilterChip(selected = auto, onClick = onAutoToggle, label = { Text(if (auto) "AUTO" else "MANUAL", fontSize = 10.sp) })
            Text(valueText, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        Slider(value = fraction.coerceIn(0f, 1f), onValueChange = onFraction, valueRange = 0f..1f)
    }
}

private fun logFraction(min: Int, max: Int, value: Int): Float {
    if (min <= 0 || max <= min) return 0f
    val fraction = (ln(value.toDouble()) - ln(min.toDouble())) / (ln(max.toDouble()) - ln(min.toDouble()))
    return fraction.toFloat().coerceIn(0f, 1f)
}

private fun logScale(min: Int, max: Int, fraction: Float): Int {
    if (min <= 0 || max <= min) return min
    val value = exp(ln(min.toDouble()) + fraction.coerceIn(0f, 1f) * (ln(max.toDouble()) - ln(min.toDouble())))
    return value.roundToInt().coerceIn(min, max)
}

/** Standard photographic shutter stops clipped to the range the hardware reports. */
internal fun exposureStops(range: LongRange): LongArray {
    val candidates = longArrayOf(
        1_000_000L / 8000, 1_000_000L / 4000, 1_000_000L / 2000, 1_000_000L / 1000,
        1_000_000L / 500, 1_000_000L / 250, 1_000_000L / 125, 1_000_000L / 60,
        1_000_000L / 30, 1_000_000L / 15, 1_000_000L / 8, 1_000_000L / 4,
        1_000_000L / 2, 1_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L,
        15_000_000_000L, 30_000_000_000L,
    )
    val filtered = candidates.filter { it in range }.toLongArray()
    return if (filtered.isNotEmpty()) filtered else longArrayOf(range.first, range.last)
}

/** Suggested recording profile label shown in the top bar. */
internal fun videoProfileLabel(caps: CameraCapabilities?): String {
    val caps = caps ?: return "-"
    val selection = VideoProfileSelector.select(caps.videoSizes) ?: return "지원 안 됨"
    return "${selection.size.width}×${selection.size.height}@${selection.fps}"
}
