package com.aipro.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Icons drawn with Canvas instead of a font/vector dependency.
 *
 * The app intentionally avoids material-icons-extended (it blew up the dex step on the 2 GB CI box),
 * so the Galaxy-style chrome uses these primitives. Every glyph is a real, tappable target — no
 * placeholder boxes.
 */
enum class Glyph { GEAR, FLASH_ON, FLASH_OFF, RATIO, GALLERY, SWITCH_CAMERA, AI, INFO, GRID, CLOSE }

@Composable
fun GlyphIcon(
    glyph: Glyph,
    size: Dp = 24.dp,
    tint: Color = Color.White,
    onClick: (() -> Unit)? = null,
) {
    val modifier = if (onClick != null) {
        Modifier.size(size + 16.dp).clickable(onClick = onClick)
    } else {
        Modifier.size(size + 16.dp)
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) { drawGlyph(glyph, tint) }
    }
}

@Composable
fun GlyphButton(
    glyph: Glyph,
    onClick: () -> Unit,
    size: Dp = 24.dp,
    tint: Color = Color.White,
    background: Color = Color(0x33000000),
) {
    Box(
        modifier = Modifier
            .size(size + 20.dp)
            .background(background, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) { drawGlyph(glyph, tint) }
    }
}

private fun DrawScope.drawGlyph(glyph: Glyph, tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
    when (glyph) {
        Glyph.GEAR -> {
            // Ring + eight teeth.
            drawCircle(tint, radius = w * 0.26f, center = center, style = stroke)
            repeat(8) { index ->
                val angle = Math.toRadians(index * 45.0)
                val inner = w * 0.33f
                val outer = w * 0.46f
                drawLine(
                    color = tint,
                    start = Offset(
                        center.x + (inner * kotlin.math.cos(angle)).toFloat(),
                        center.y + (inner * kotlin.math.sin(angle)).toFloat(),
                    ),
                    end = Offset(
                        center.x + (outer * kotlin.math.cos(angle)).toFloat(),
                        center.y + (outer * kotlin.math.sin(angle)).toFloat(),
                    ),
                    strokeWidth = w * 0.12f,
                    cap = StrokeCap.Round,
                )
            }
        }
        Glyph.FLASH_ON -> {
            val path = Path().apply {
                moveTo(w * 0.58f, h * 0.06f)
                lineTo(w * 0.30f, h * 0.55f)
                lineTo(w * 0.48f, h * 0.55f)
                lineTo(w * 0.40f, h * 0.94f)
                lineTo(w * 0.72f, h * 0.44f)
                lineTo(w * 0.53f, h * 0.44f)
                close()
            }
            drawPath(path, tint)
        }
        Glyph.FLASH_OFF -> {
            val path = Path().apply {
                moveTo(w * 0.58f, h * 0.06f)
                lineTo(w * 0.30f, h * 0.55f)
                lineTo(w * 0.48f, h * 0.55f)
                lineTo(w * 0.40f, h * 0.94f)
                lineTo(w * 0.72f, h * 0.44f)
                lineTo(w * 0.53f, h * 0.44f)
                close()
            }
            drawPath(path, tint.copy(alpha = 0.45f))
            drawLine(
                color = tint,
                start = Offset(w * 0.18f, h * 0.86f),
                end = Offset(w * 0.84f, h * 0.14f),
                strokeWidth = w * 0.075f,
                cap = StrokeCap.Round,
            )
        }
        Glyph.RATIO -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.16f, h * 0.24f),
                size = Size(w * 0.68f, h * 0.52f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f),
                style = stroke,
            )
        }
        Glyph.GALLERY -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.12f, h * 0.18f),
                size = Size(w * 0.76f, h * 0.64f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f),
                style = stroke,
            )
            drawCircle(tint, radius = w * 0.07f, center = Offset(w * 0.32f, h * 0.36f))
            val path = Path().apply {
                moveTo(w * 0.16f, h * 0.76f)
                lineTo(w * 0.44f, h * 0.46f)
                lineTo(w * 0.68f, h * 0.76f)
                close()
            }
            drawPath(path, tint.copy(alpha = 0.9f))
        }
        Glyph.SWITCH_CAMERA -> {
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.22f, h * 0.30f),
                size = Size(w * 0.56f, h * 0.40f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.10f),
                style = stroke,
            )
            drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.5f, h * 0.5f))
            drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.5f, h * 0.16f), style = stroke)
        }
        Glyph.AI -> {
            drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.5f, h * 0.22f))
            drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.24f, h * 0.62f))
            drawCircle(tint, radius = w * 0.10f, center = Offset(w * 0.76f, h * 0.62f))
            drawLine(tint, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.24f, h * 0.62f), strokeWidth = w * 0.05f)
            drawLine(tint, Offset(w * 0.5f, h * 0.22f), Offset(w * 0.76f, h * 0.62f), strokeWidth = w * 0.05f)
            drawLine(tint, Offset(w * 0.24f, h * 0.62f), Offset(w * 0.76f, h * 0.62f), strokeWidth = w * 0.05f)
        }
        Glyph.INFO -> {
            drawCircle(tint, radius = w * 0.40f, center = center, style = stroke)
            drawLine(tint, Offset(w * 0.5f, h * 0.42f), Offset(w * 0.5f, h * 0.70f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            drawCircle(tint, radius = w * 0.045f, center = Offset(w * 0.5f, h * 0.30f))
        }
        Glyph.GRID -> {
            repeat(2) { i ->
                val p = w * (0.16f + 0.34f * (i + 1))
                drawLine(tint, Offset(p, h * 0.14f), Offset(p, h * 0.86f), strokeWidth = w * 0.06f)
                drawLine(tint, Offset(w * 0.14f, p), Offset(w * 0.86f, p), strokeWidth = w * 0.06f)
            }
        }
        Glyph.CLOSE -> {
            drawLine(tint, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
            drawLine(tint, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), strokeWidth = w * 0.08f, cap = StrokeCap.Round)
        }
    }
}
