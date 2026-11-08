package com.aipro.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipro.app.CameraUiState
import com.aipro.app.CameraViewModel
import com.aipro.camera.engine.EngineState
import com.aipro.camera.manual.ExposureFormat
import com.aipro.common.CaptureMode

/**
 * Shared building blocks for both camera designs. Keeping the preview and the capture controls in
 * one place guarantees the two styles cannot drift apart functionally.
 */
@Composable
fun PreviewArea(
    state: CameraUiState,
    vm: CameraViewModel,
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val portrait = androidx.compose.ui.platform.LocalConfiguration.current.let {
        it.screenHeightDp >= it.screenWidthDp
    }
    // fillMaxSize (not fillMaxWidth): inside a weighted Column this box otherwise wraps its content
    // and a fillMaxSize preview inside it collapses to zero height -- the "weird camera screen".
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            geometry = state.geometry,
            displayAspect = state.geometry?.aspectRatio?.takeIf { it > 0f }
                ?: if (portrait) 1f / state.aspectRatio.ratio else state.aspectRatio.ratio,
            onTextureAvailable = vm::attachPreview,
            onTextureDestroyed = vm::detachPreview,
            rollDegrees = if (state.horizonLock) vm.horizonRollFlow else null,
            rotationOffset = state.previewRotationMode.toIntOrNull(),
        )
        if (state.showGrid) GridOverlay()
        overlay()
    }
}

@Composable
fun CameraTopBar(
    state: CameraUiState,
    vm: CameraViewModel,
    compact: Boolean,
    onSettings: () -> Unit,
    onInfo: () -> Unit,
    onAi: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xE6000000)).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSettings, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("설정", color = Color.White, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.width(4.dp))
            PurposeChips(
                selected = state.purpose,
                onSelect = vm::setPurpose,
                compact = compact,
                modifier = Modifier.weight(1f),
            )
            AiStatusChip(state = state, onClick = onAi)
            TextButton(onClick = onInfo, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("지원정보", color = Color(0xFFB0BEC5), fontSize = 10.sp)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NightChip(active = state.nightMode, onToggle = vm::toggleNightMode)
            Text(
                text = cameraSummary(state),
                color = Color(0xFFBDBDBD),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (state.thermalLevel != "FULL") {
                Text(
                    "발열 ${state.thermalLabel}",
                    color = Color(0xFFFF8A65),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (state.mode == CaptureMode.VIDEO) {
                Text(videoProfileLabel(state.capabilities), color = Color(0xFFFFD166), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Compact, honest AI status: which backend really runs, and how many jobs are queued. */
@Composable
private fun AiStatusChip(state: CameraUiState, onClick: () -> Unit) {
    val backend = when (state.aiBackend.selected.name) {
        "NPU_QNN" -> "NPU"
        "GPU_OPENCL" -> "GPU"
        else -> "CPU"
    }
    val queued = state.aiQueue.items.count { it.status == "QUEUED" || it.status == "PROCESSING" }
    val label = buildString {
        append("AI ").append(backend)
        if (queued > 0) append(" ·").append(queued)
        if (state.aiQueue.paused) append(" ⏸")
    }
    FilterChip(
        selected = state.aiAutoEnhance,
        onClick = onClick,
        label = { Text(label, fontSize = 9.sp, fontFamily = FontFamily.Monospace) },
    )
}

@Composable
fun CameraBottomBar(
    state: CameraUiState,
    vm: CameraViewModel,
    compact: Boolean,
    onPro: () -> Unit,
) {
    val recording = state.recordingStartedAt != null
    val busy = state.engineState is EngineState.Opening || state.engineState is EngineState.Capturing
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xF2000000)).padding(vertical = 6.dp)) {
        LensChips(state = state, onSelect = vm::selectCamera, compact = compact)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeChips(mode = state.mode, onMode = vm::setMode)
            ShutterButton(
                recording = recording,
                videoMode = state.mode == CaptureMode.VIDEO,
                enabled = !busy,
                onClick = { if (state.mode == CaptureMode.VIDEO) vm.toggleRecording() else vm.capture() },
                size = if (compact) 62 else 72,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.mode == CaptureMode.PHOTO) {
                    FilterChip(
                        selected = state.hdrEnabled,
                        onClick = vm::toggleHdr,
                        label = { Text("HDR", fontSize = 10.sp) },
                    )
                }
                if (state.mode == CaptureMode.VIDEO) {
                    FilterChip(
                        selected = state.realtimeProcessing,
                        onClick = vm::toggleRealtimeProcessing,
                        label = { Text("실시간", fontSize = 10.sp) },
                    )
                }
                // Horizon lock only exists when the device really reports gyro + accelerometer sensors.
                if (state.horizonSupported) {
                    FilterChip(
                        selected = state.horizonLock,
                        onClick = vm::toggleHorizonLock,
                        label = {
                            Text(
                                if (state.horizonLock) "수평 ${"%.0f".format(state.horizonRollDegrees)}°" else "수평잠금",
                                fontSize = 10.sp,
                            )
                        },
                    )
                }
                if (state.capabilities?.manual?.raw == true && state.mode == CaptureMode.PHOTO) {
                    FilterChip(
                        selected = state.captureRaw,
                        onClick = vm::toggleRaw,
                        label = { Text("RAW", fontSize = 10.sp) },
                    )
                }
                TextButton(onClick = onPro, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(if (state.showProPanel) "PRO▲" else "PRO▼", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { nextCamera(state)?.let(vm::selectCamera) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("전환", color = Color.White, fontSize = 10.sp)
                }
            }
        }
        ZoomRow(state, vm)
    }
}

@Composable
private fun ZoomRow(state: CameraUiState, vm: CameraViewModel) {
    val caps = state.capabilities ?: return
    if (!caps.manual.zoom) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        ZoomChips(
            currentZoom = state.manual.zoomRatio,
            maxZoom = caps.maxDigitalZoom,
            onZoom = { zoom -> vm.updateManual { it.copy(zoomRatio = zoom) } },
        )
    }
}

/**
 * Technical HUD used by the pro design: only values the camera actually reported are displayed.
 */
@Composable
fun ProHud(state: CameraUiState, modifier: Modifier = Modifier) {
    val frame = state.frame
    Column(modifier = modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(6.dp)) {
        TechnicalReadout("ISO", ExposureFormat.iso(state.manual.iso ?: frame.iso), highlight = state.manual.iso != null)
        TechnicalReadout("SS", ExposureFormat.exposureTime(state.manual.exposureTimeNs ?: frame.exposureTimeNs ?: 0L), highlight = state.manual.exposureTimeNs != null)
        TechnicalReadout("WB", ExposureFormat.kelvin(state.manual.whiteBalanceKelvin), highlight = state.manual.whiteBalanceKelvin != null)
        TechnicalReadout("EV", ExposureFormat.ev(state.manual.exposureCompensationEv))
        TechnicalReadout(
            "MF",
            state.manual.focusSlider01?.let { f -> "%.0f%%".format(f * 100) } ?: "AF",
            highlight = state.manual.focusSlider01 != null,
        )
        TechnicalReadout("ZOOM", "%.1fx".format(state.manual.zoomRatio))
        TechnicalReadout("LUMA", "%.0f".format(frame.meanLuma))
    }
}

@Composable
fun ProControlStack(
    state: CameraUiState,
    vm: CameraViewModel,
    modifier: Modifier = Modifier,
    maxHeight: Int = 320,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.dp)
            .background(Color(0xF2000000))
            .verticalScroll(rememberScrollState()),
    ) {
        ProPanel(
            caps = state.capabilities,
            manual = state.manual,
            onUpdate = vm::updateManual,
        )
    }
}

/** Shown while the device is half-opened, so the user knows the hinge angle is real sensor data. */
@Composable
fun HingeBar(degrees: Int?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(Color(0xFF111114)).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (degrees != null) "Flex Mode · 힌지 ${degrees}°" else "Flex Mode · 힌지 각도 미지원",
            color = Color(0xFF8AC6D1),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
fun SettingsDialogBody(
    current: CameraUiStyle,
    showGrid: Boolean,
    onStyle: (CameraUiStyle) -> Unit,
    onGrid: (Boolean) -> Unit,
    rotationMode: String = "0",
    onRotationMode: (String) -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("프리뷰 회전", style = MaterialTheme.typography.titleSmall)
        Text(
            "0°(기본)는 센서 방향을 그대로 따릅니다. 값은 여기에 더해지는 보정 각도입니다.",
            color = Color(0xFF9E9E9E),
            fontSize = 10.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("0" to "0°", "90" to "90°", "180" to "180°", "270" to "270°", "AUTO" to "자동").forEach { (mode, label) ->
                FilterChip(
                    selected = rotationMode == mode,
                    onClick = { onRotationMode(mode) },
                    label = { Text(label, fontSize = 11.sp) },
                )
            }
        }
        Text("카메라 UI 스타일", style = MaterialTheme.typography.titleSmall)
        CameraUiStyle.entries.forEach { style ->
            FilterChip(
                selected = current == style,
                onClick = { onStyle(style) },
                label = {
                    Column {
                        Text(style.label, fontSize = 12.sp)
                        Text(style.description, fontSize = 10.sp, color = Color(0xFF9E9E9E))
                    }
                },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        FilterChip(
            selected = showGrid,
            onClick = { onGrid(!showGrid) },
            label = { Text("3분할 격자", fontSize = 12.sp) },
        )
    }
}

@Composable
fun DiagnosticButton(onExport: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onExport, modifier = modifier) { Text("카메라 지원 정보 JSON 저장", fontSize = 11.sp) }
}

private fun nextCamera(state: CameraUiState): String? {
    if (state.cameras.isEmpty()) return null
    val index = state.cameras.indexOfFirst { it.cameraId == state.selectedCameraId }
    return state.cameras[(index + 1) % state.cameras.size].cameraId
}

internal fun cameraSummary(state: CameraUiState): String {
    val caps = state.capabilities ?: return "카메라 준비 중"
    val engine = when (state.engineState) {
        is EngineState.Previewing -> "PREVIEW"
        is EngineState.Opening -> "OPENING"
        is EngineState.Capturing -> "CAPTURE"
        is EngineState.Recording -> "REC"
        is EngineState.Error -> "ERROR"
        EngineState.Idle -> "IDLE"
        EngineState.Closed -> "CLOSED"
    }
    return "${caps.lensType.displayName} ${caps.hardwareSupportLevel} · $engine"
}
