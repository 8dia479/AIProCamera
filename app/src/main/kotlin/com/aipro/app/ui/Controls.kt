package com.aipro.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipro.app.CameraUiState
import com.aipro.common.CaptureMode
import com.aipro.common.ShootingPurpose
import com.aipro.common.LensType

fun purposeLabel(purpose: ShootingPurpose): String = when (purpose) {
    ShootingPurpose.AUTO -> "자동"
    ShootingPurpose.PORTRAIT -> "인물"
    ShootingPurpose.LANDSCAPE -> "풍경"
    ShootingPurpose.URBAN -> "도시"
}

@Composable
fun PurposeChips(
    selected: ShootingPurpose,
    onSelect: (ShootingPurpose) -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShootingPurpose.entries.forEach { purpose ->
            FilterChip(
                selected = selected == purpose,
                onClick = { onSelect(purpose) },
                label = { Text(purposeLabel(purpose), fontSize = if (compact) 10.sp else 12.sp) },
            )
        }
    }
}

@Composable
fun NightChip(active: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = active,
        onClick = onToggle,
        label = { Text("야간", fontSize = 11.sp) },
    )
}

@Composable
fun LensChips(
    state: CameraUiState,
    onSelect: (String) -> Unit,
    compact: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.cameras.forEach { camera ->
            FilterChip(
                selected = camera.cameraId == state.selectedCameraId,
                onClick = { onSelect(camera.cameraId) },
                label = {
                    Text(
                        camera.displayName,
                        fontSize = if (compact) 9.sp else 10.sp,
                    )
                },
            )
        }
    }
}

@Composable
fun ModeChips(mode: CaptureMode, onMode: (CaptureMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ModePill("사진", mode == CaptureMode.PHOTO) { onMode(CaptureMode.PHOTO) }
        ModePill("동영상", mode == CaptureMode.VIDEO) { onMode(CaptureMode.VIDEO) }
    }
}

@Composable
private fun ModePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        modifier = Modifier.border(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else Color(0x55FFFFFF),
            shape = RoundedCornerShape(50),
        ),
    ) {
        Text(label, fontSize = 11.sp, color = if (selected) MaterialTheme.colorScheme.primary else Color.White)
    }
}

/** Digital-zoom shortcuts. Values above 1x are crop-based; the label states that honestly. */
@Composable
fun ZoomChips(
    currentZoom: Float,
    maxZoom: Float,
    onZoom: (Float) -> Unit,
) {
    val steps = listOf(1f, 2f, 3f, 5f).filter { it <= maxZoom }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEach { zoom ->
            FilterChip(
                selected = kotlin.math.abs(currentZoom - zoom) < 0.05f,
                onClick = { onZoom(zoom) },
                label = { Text("${zoom.toInt()}x", fontSize = 10.sp) },
            )
        }
    }
}

@Composable
fun ShutterButton(
    recording: Boolean,
    videoMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Int = 72,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(size.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (recording) 28.dp else if (videoMode) 48.dp else 56.dp)
                .background(
                    if (recording) Color(0xFFFF5252) else MaterialTheme.colorScheme.primary,
                    if (videoMode) RoundedCornerShape(8.dp) else CircleShape,
                ),
        )
    }
}

@Composable
fun MessageBar(message: String?) {
    if (message == null) return
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun TechnicalReadout(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    Row(modifier = modifier.width(120.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(38.dp),
        )
        Text(
            value,
            color = if (highlight) Color(0xFFFFD166) else Color(0xFFE0E0E0),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

fun lensLabel(lens: LensType): String = lens.displayName
