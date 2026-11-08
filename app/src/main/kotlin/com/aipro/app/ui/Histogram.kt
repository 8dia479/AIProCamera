package com.aipro.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp

/** Luma histogram drawn from the real Y-plane bins produced by :core:native. */
@Composable
fun HistogramOverlay(bins: IntArray?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(56.dp)) {
        if (bins == null || bins.isEmpty()) return@Box
        Canvas(modifier = Modifier.fillMaxWidth().height(56.dp)) {
            val max = (bins.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
            val path = Path()
            val width = size.width / bins.size
            bins.forEachIndexed { index, value ->
                val normalised = (value / max).coerceIn(0f, 1f)
                val x = index * width
                val y = size.height * (1f - normalised)
                if (index == 0) path.moveTo(x, size.height) else Unit
                path.lineTo(x, y)
            }
            path.lineTo(size.width, size.height)
            path.close()
            drawPath(path, Color(0x99FFD166))
            drawLine(
                color = Color(0x55FFFFFF),
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1f,
            )
        }
    }
}
