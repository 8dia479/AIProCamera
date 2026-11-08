package com.aipro.app.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipro.app.CameraUiState
import com.aipro.app.CameraViewModel
import com.aipro.camera.capture.AspectRatioOption
import com.aipro.camera.engine.EngineState
import com.aipro.camera.manual.FlashMode
import com.aipro.common.CaptureMode
import com.aipro.common.Facing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val GalaxyYellow = Color(0xFFFFD166)
private val GalaxyScrim = Brush.verticalGradient(listOf(Color(0xB3000000), Color(0x00000000)))

/**
 * Galaxy-style camera UI, rebuilt to follow Samsung's Camera conventions:
 *
 *  * the preview is the screen; chrome floats on top of it (no black header/footer bands),
 *  * the visible frame is letterboxed to the selected ratio (1:1 · 4:3 · 16:9 · 22:9),
 *  * top-left settings, top-right flash / ratio / AI, zoom pills above the shutter,
 *  * bottom mode row (사진 · 동영상 · 야간 · HDR) and a shutter row with gallery + switch camera.
 *
 * Every control is backed by a real capability: flash only appears when the camera reports a flash
 * unit, zoom pills only for lenses the device exposes, and unavailable ratios are disabled in the
 * ratio sheet instead of silently doing nothing.
 */
@Composable
fun GalaxyCameraLayout(
    state: CameraUiState,
    vm: CameraViewModel,
    compact: Boolean,
    onSettings: () -> Unit,
    onInfo: () -> Unit,
    onAi: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        GalaxyPreview(state, vm)
        GalaxyTopBar(state = state, vm = vm, onSettings = onSettings, onAi = onAi, modifier = Modifier.align(Alignment.TopCenter))
        GalaxyBottomControls(state = state, vm = vm, compact = compact, modifier = Modifier.align(Alignment.BottomCenter))
        if (state.recordingStartedAt != null) {
            Text(
                "● REC",
                color = Color(0xFFFF5252),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 12.dp),
            )
        }
    }
}

/**
 * Flex mode (반쯤 접은 자세): the preview sits in the top half with the controls in the bottom half,
 * exactly like Samsung's camera, and it uses the *same* controls as the normal Galaxy layout so the
 * two never drift apart.
 */
@Composable
fun GalaxyFlexLayout(
    state: CameraUiState,
    vm: CameraViewModel,
    hingeDegrees: Int?,
    compact: Boolean,
    onSettings: () -> Unit,
    onInfo: () -> Unit,
    onAi: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(0.52f).fillMaxWidth()) {
            GalaxyPreview(state, vm)
            GalaxyTopBar(state = state, vm = vm, onSettings = onSettings, onAi = onAi, modifier = Modifier.align(Alignment.TopCenter))
        }
        HingeBar(hingeDegrees)
        Box(modifier = Modifier.weight(0.48f).fillMaxWidth().background(Color.Black)) {
            GalaxyBottomControls(state = state, vm = vm, compact = compact, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Preview + grid + horizon level, framed to the selected ratio. */
@Composable
private fun GalaxyPreview(state: CameraUiState, vm: CameraViewModel) {
    val configuration = LocalConfiguration.current
    val portrait = configuration.screenHeightDp >= configuration.screenWidthDp
    // The clip window follows what the sensor+display rotation actually produce, so a landscape
    // window, Flex Mode or a rotated display can never end up with a sideways / over-cropped preview.
    // (The nominal ratio is only the fallback before the engine publishes its geometry.)
    val ratioAspect = if (portrait) 1f / state.aspectRatio.ratio else state.aspectRatio.ratio
    val displayAspect = state.geometry?.aspectRatio?.takeIf { it > 0f } ?: ratioAspect
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            geometry = state.geometry,
            displayAspect = displayAspect,
            onTextureAvailable = vm::attachPreview,
            onTextureDestroyed = vm::detachPreview,
            modifier = Modifier.fillMaxSize(),
            // Only while the lock is on: a null flow costs nothing and keeps the transform static.
            rollDegrees = if (state.horizonLock) vm.horizonRollFlow else null,
            rotationOffset = state.previewRotationMode.toIntOrNull(),
        )
        if (state.showGrid) GridOverlay()
        if (state.horizonLock) HorizonLevel(state.horizonRollDegrees)
    }
}

@Composable
fun GalaxyTopBar(
    state: CameraUiState,
    vm: CameraViewModel,
    onSettings: () -> Unit,
    onAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(GalaxyScrim)
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(glyph = Glyph.GEAR, onClick = onSettings, tint = Color.White)
        // 프로 모드: the manual (ISO/shutter/EV/WB/focus) menu stays hidden until this is switched on,
        // so the everyday screen looks like Samsung's default camera.
        Text(
            "프로 모드",
            color = if (state.showProPanel) GalaxyYellow else Color(0xCCFFFFFF),
            fontSize = 12.sp,
            fontWeight = if (state.showProPanel) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier
                .padding(start = 4.dp)
                .clickable(onClick = vm::toggleProPanel)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        if (state.thermalLevel != "FULL") {
            Text(
                "발열 ${state.thermalLabel}",
                color = Color(0xFFFF8A65),
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 6.dp),
            )
        }
        if (state.flashAvailable) {
            GlyphButton(
                glyph = if (state.flashMode == FlashMode.OFF) Glyph.FLASH_OFF else Glyph.FLASH_ON,
                onClick = vm::cycleFlash,
                tint = if (state.flashMode == FlashMode.OFF) Color(0xCCFFFFFF) else GalaxyYellow,
            )
        }
        RatioButton(state.aspectRatio.label) { vm.setRatioSheetVisible(true) }
        GlyphButton(
            glyph = Glyph.AI,
            onClick = onAi,
            tint = if (state.aiAutoEnhance) GalaxyYellow else Color(0xCCFFFFFF),
        )
    }
}

@Composable
fun GalaxyBottomControls(
    state: CameraUiState,
    vm: CameraViewModel,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0x00000000), Color(0xC0000000))))
            .navigationBarsPadding()
            .padding(bottom = 6.dp),
    ) {
        state.message?.let { message ->
            Text(
                message,
                color = Color(0xE6FFFFFF),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        ZoomPills(state = state, vm = vm)
        if (state.showProPanel) {
            ProControlStack(state, vm, maxHeight = if (compact) 180 else 230)
        }
        ModeRow(state = state, vm = vm)
        ShutterRow(state = state, vm = vm, compact = compact)
    }
}

/** Thin artificial horizon used by the experimental horizon lock. */
@Composable
private fun HorizonLevel(rollDegrees: Float) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val half = size.width * 0.22f
        rotate(degrees = rollDegrees, pivot = Offset(centerX, centerY)) {
            drawLine(
                color = if (kotlin.math.abs(rollDegrees) < 0.8f) GalaxyYellow else Color(0x99FFFFFF),
                start = Offset(centerX - half, centerY),
                end = Offset(centerX + half, centerY),
                strokeWidth = 2f,
            )
            drawLine(
                color = Color(0x99FFFFFF),
                start = Offset(centerX, centerY - 10f),
                end = Offset(centerX, centerY + 10f),
                strokeWidth = 2f,
            )
        }
    }
}

@Composable
private fun RatioButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph = Glyph.RATIO, size = 18.dp, tint = Color(0xCCFFFFFF))
        Text(label, color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
private fun ZoomPills(state: CameraUiState, vm: CameraViewModel) {
    val pills = remember(state.cameras, state.selectedCameraId) { vm.zoomPills() }
    if (pills.size <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pills.forEach { (cameraId, label) ->
            val selected = cameraId == state.selectedCameraId
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (selected) 30.dp else 26.dp)
                    .background(
                        if (selected) Color(0x33FFFFFF) else Color.Transparent,
                        CircleShape,
                    )
                    .clickable { vm.selectCamera(cameraId) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) GalaxyYellow else Color(0xCCFFFFFF),
                    fontSize = if (selected) 12.sp else 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun ModeRow(state: CameraUiState, vm: CameraViewModel) {
    val recording = state.recordingStartedAt != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeItem("사진", state.mode == CaptureMode.PHOTO) { if (!recording) vm.setMode(CaptureMode.PHOTO) }
        ModeItem("동영상", state.mode == CaptureMode.VIDEO) { if (!recording) vm.setMode(CaptureMode.VIDEO) }
        if (state.mode == CaptureMode.PHOTO) {
            ModeItem("야간", state.nightMode) { vm.toggleNightMode() }
            ModeItem("HDR", state.hdrEnabled) { vm.toggleHdr() }
        } else {
            ModeItem("액션", state.actionMode) { vm.toggleActionMode() }
            ModeItem("실시간", state.realtimeProcessing) { vm.toggleRealtimeProcessing() }
        }
        // Available in both modes: the preview and stills are leveled, video gets a leveled copy.
        ModeItem("수평잠금", state.horizonLock) { vm.toggleHorizonLock() }
    }
}

@Composable
private fun ModeItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) GalaxyYellow else Color(0x99FFFFFF),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun ShutterRow(state: CameraUiState, vm: CameraViewModel, compact: Boolean) {
    val busy = state.engineState is EngineState.Opening || state.engineState is EngineState.Capturing
    val recording = state.recordingStartedAt != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        GalleryThumb(state.lastPhotoUri)
        ShutterButton(
            recording = recording,
            videoMode = state.mode == CaptureMode.VIDEO,
            enabled = !busy,
            onClick = { if (state.mode == CaptureMode.VIDEO) vm.toggleRecording() else vm.capture() },
            size = if (compact) 62 else 72,
        )
        GlyphButton(glyph = Glyph.SWITCH_CAMERA, onClick = vm::switchCamera, size = 26.dp)
    }
}

/** Last-shot thumbnail; decoded off the main thread, no image-loading dependency needed. */
@Composable
private fun GalleryThumb(uri: String?) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            val parsed = uri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return@withContext null
            runCatching {
                context.contentResolver.openInputStream(parsed)?.use { stream ->
                    val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                    BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color(0x22FFFFFF), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "최근 촬영",
                modifier = Modifier.size(44.dp).background(Color.Black, RoundedCornerShape(10.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            GlyphIcon(glyph = Glyph.GALLERY, size = 20.dp, tint = Color(0x99FFFFFF))
        }
    }
}

/** Ratio picker (화질/비율). Ratios the current mode cannot serve are shown disabled with a reason. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RatioSheet(state: CameraUiState, vm: CameraViewModel, onDismiss: () -> Unit) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text("화질 / 비율", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "비율은 가로 기준입니다. 세로로 들면 가로·세로가 바뀝니다 (4:3 → 3:4).",
                fontSize = 10.sp,
                color = Color(0xFF9E9E9E),
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            AspectRatioOption.entries.forEach { option ->
                val enabled = option in state.supportedRatios
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { vm.setAspectRatio(option) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        option.label,
                        color = when {
                            !enabled -> Color(0x55FFFFFF)
                            option == state.aspectRatio -> GalaxyYellow
                            else -> Color.White
                        },
                        fontSize = 14.sp,
                        fontWeight = if (option == state.aspectRatio) FontWeight.Bold else FontWeight.Normal,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        if (enabled) {
                            val portraitLabel = option.label.split(":").reversed().joinToString(":")
                            "세로 $portraitLabel"
                        } else {
                            if (state.mode == CaptureMode.VIDEO) "이 기기 동영상 미지원" else "이 카메라 미지원"
                        },
                        color = Color(0xFF9E9E9E),
                        fontSize = 11.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (option == state.aspectRatio) {
                        Text("선택됨", color = GalaxyYellow, fontSize = 10.sp)
                    }
                }
            }
            Text(
                "센서가 해당 비율을 직접 지원하지 않으면 가장 가까운 실제 해상도로 촬영한 뒤 중앙 크롭하며, " +
                    "크롭 여부는 메타데이터에 기록됩니다.",
                fontSize = 9.sp,
                color = Color(0xFF757575),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
