package com.aipro.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipro.app.CameraUiState
import com.aipro.app.CameraViewModel
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontFamily as TextFontFamily
import com.aipro.app.BuildConfig
import com.aipro.camera.engine.EngineState
import com.aipro.camera.manual.ExposureFormat
import com.aipro.common.CaptureMode

/**
 * Entry point of the camera UI.
 *
 * Three things decide the layout:
 *  1. the user's chosen design ([CameraUiStyle]): Galaxy-style or pro-camera style,
 *  2. the fold posture (Flex Mode when the device is half opened),
 *  3. the available width (the 3.4" cover display is ~360 dp wide when the app is launched there).
 */
@Composable
fun CameraScreen(vm: CameraViewModel, state: CameraUiState) {
    var showCapabilities by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val fold = rememberFoldInfo()

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 420.dp
            if (fold.posture == FoldPosture.HORIZONTAL_HALF_OPENED) {
                // Flex mode now uses the same Galaxy controls as the normal layout (the old pro-only
                // flex screen was the reason the new UI "did not apply" in Flex Mode).
                GalaxyFlexLayout(
                    state = state,
                    vm = vm,
                    hingeDegrees = fold.hingeDegrees,
                    compact = compact,
                    onSettings = { showSettings = true },
                    onInfo = { showCapabilities = true },
                    onAi = { vm.setAiSheetVisible(true) },
                )
            } else {
                when (state.uiStyle) {
                    CameraUiStyle.GALAXY -> GalaxyShell(
                        state = state,
                        vm = vm,
                        compact = compact,
                        onInfo = { showCapabilities = true },
                        onSettings = { showSettings = true },
                        onAi = { vm.setAiSheetVisible(true) },
                    )
                    CameraUiStyle.PRO -> ProShell(
                        state = state,
                        vm = vm,
                        compact = compact,
                        onInfo = { showCapabilities = true },
                        onSettings = { showSettings = true },
                        onAi = { vm.setAiSheetVisible(true) },
                    )
                }
            }
            BuildBadge(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 30.dp),
            )
        }
    }

    if (showCapabilities) {
        CapabilitySheet(
            caps = state.capabilities,
            device = state.deviceProfile,
            onDismiss = { showCapabilities = false },
            onExport = { vm.exportCapabilityReport(); showCapabilities = false },
        )
    }
    if (state.showAiSheet) {
        AiSheet(state = state, vm = vm, onDismiss = { vm.setAiSheetVisible(false) })
    }
    if (state.showRatioSheet) {
        RatioSheet(state = state, vm = vm, onDismiss = { vm.setRatioSheetVisible(false) })
    }
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            confirmButton = { TextButton(onClick = { showSettings = false }) { Text("닫기") } },
            title = { Text("카메라 설정") },
            text = {
                SettingsDialogBody(
                    current = state.uiStyle,
                    showGrid = state.showGrid,
                    onStyle = vm::setUiStyle,
                    onGrid = vm::setShowGrid,
                    rotationMode = state.previewRotationMode,
                    onRotationMode = vm::setPreviewRotationMode,
                )
            },
        )
    }
}

// ---------------------------------------------------------------- Galaxy style

@Composable
private fun GalaxyShell(
    state: CameraUiState,
    vm: CameraViewModel,
    compact: Boolean,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
    onAi: () -> Unit,
) {
    // Samsung-like chrome: preview first, controls floating on top of it.
    GalaxyCameraLayout(
        state = state,
        vm = vm,
        compact = compact,
        onSettings = onSettings,
        onInfo = onInfo,
        onAi = onAi,
    )
}

// ------------------------------------------------------------------- Pro style

@Composable
private fun ProShell(
    state: CameraUiState,
    vm: CameraViewModel,
    compact: Boolean,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
    onAi: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        CameraTopBar(state, vm, compact, onSettings, onInfo, onAi)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PreviewArea(state, vm) {
                ProHud(state, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                HistogramOverlay(
                    bins = state.frame.histogram,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
                Column(modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                    TechnicalReadout("AF", afStateLabel(state))
                    TechnicalReadout("MODE", state.engineState.label())
                }
            }
        }
        MessageBar(state.message)
        if (state.showProPanel) {
            ProControlStack(state, vm, maxHeight = if (compact) 190 else 260)
        }
        CameraBottomBar(state, vm, compact, onPro = vm::toggleProPanel)
    }
}

// --------------------------------------------------------------------- Flex Mode

@Composable
private fun FlexModeShell(
    state: CameraUiState,
    vm: CameraViewModel,
    fold: FoldInfo,
    compact: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(0.5f).fillMaxWidth()) {
            PreviewArea(state, vm) {
                ProHud(state, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
                HistogramOverlay(
                    bins = state.frame.histogram,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                )
            }
        }
        HingeBar(fold.hingeDegrees)
        Column(
            modifier = Modifier.weight(0.5f).fillMaxWidth().background(Color(0xFF0B0B0D))
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PurposeChips(state.purpose, vm::setPurpose, compact = true, modifier = Modifier.weight(1f))
                NightChip(state.nightMode, vm::toggleNightMode)
            }
            ProPanel(caps = state.capabilities, manual = state.manual, onUpdate = vm::updateManual)
            MessageBar(state.message)
            CameraBottomBar(state, vm, compact = true, onPro = vm::toggleProPanel)
        }
    }
}

private fun EngineState.label(): String = when (this) {
    is EngineState.Previewing -> "PREVIEW"
    is EngineState.Opening -> "OPEN"
    is EngineState.Capturing -> "CAPTURE"
    is EngineState.Recording -> "REC"
    is EngineState.Error -> "ERROR"
    EngineState.Idle -> "IDLE"
    EngineState.Closed -> "CLOSED"
}

private fun afStateLabel(state: CameraUiState): String = when (state.frame.afState) {
    null -> "-"
    0 -> "INACTIVE"
    1 -> "PASSIVE_SCAN"
    2 -> "PASSIVE_FOCUSED"
    3 -> "ACTIVE_SCAN"
    4 -> "FOCUSED"
    5 -> "NOT_FOCUSED"
    6 -> "LOCKED"
    else -> "?"
}

/** Exposed for the readout in the pro HUD. */
@Composable
internal fun ExposureLine(state: CameraUiState) {
    val frame = state.frame
    Text(
        "ISO ${ExposureFormat.iso(frame.iso)} · ${ExposureFormat.exposureTime(frame.exposureTimeNs ?: 0L)}",
        color = Color(0xFFFFD166),
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelSmall,
    )
}

internal fun modeLabel(mode: CaptureMode): String = if (mode == CaptureMode.PHOTO) "PHOTO" else "VIDEO"

/**
 * Tiny always-on readout: version, build stamp and what the preview transform actually applied.
 * It exists so a stale install can never be mistaken for a broken fix.
 */
@Composable
private fun BuildBadge(modifier: Modifier = Modifier) {
    val preview by PreviewDiag.text.collectAsState()
    Text(
        text = "v${BuildConfig.VERSION_NAME}·b${BuildConfig.VERSION_CODE} (${BuildConfig.BUILD_TIME}) · $preview",
        color = Color(0x99FFFFFF),
        fontSize = 9.sp,
        fontFamily = TextFontFamily.Monospace,
        modifier = modifier.background(Color(0x33000000)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
