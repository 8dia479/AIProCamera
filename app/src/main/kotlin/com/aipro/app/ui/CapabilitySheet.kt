package com.aipro.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipro.camera.discovery.CameraCapabilities
import com.aipro.common.DeviceProfile

/**
 * Shows exactly what the device reports through Camera2. This screen is the anti-fake-UI contract:
 * if a feature is missing here, the app will not offer it anywhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitySheet(
    caps: CameraCapabilities?,
    device: DeviceProfile,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("카메라 지원 정보 (실측)", style = MaterialTheme.typography.titleMedium)
            Text(device.summary(), style = MaterialTheme.typography.labelSmall)
            if (caps == null) {
                Text("카메라 정보를 읽지 못했습니다", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            RowInfo("카메라 ID", caps.cameraId)
            RowInfo("렌즈", "${caps.lensType.displayName} · ${caps.focalLengthsMm.joinToString()}mm")
            RowInfo("35mm 환산", caps.equivalentFocalLength35mm?.let { String.format("%.1fmm", it) } ?: "-")
            RowInfo("센서", caps.sensorSize?.toString() ?: "-")
            RowInfo("조리개", caps.apertureFNumbers.joinToString().ifEmpty { "-" })
            RowInfo("HAL 레벨", caps.hardwareSupportLevel)
            RowInfo("수동 센서", if (caps.manualSensorSupported) "지원" else "미지원")
            RowInfo("ISO 범위", caps.isoRange?.let { "${it.first}~${it.last}" } ?: "미지원")
            RowInfo(
                "셔터 범위",
                caps.exposureTimeRangeNs?.let { "${ExposureFormatShort(it.first)}~${ExposureFormatShort(it.last)}" } ?: "미지원",
            )
            RowInfo("EV 범위", caps.exposureCompensationRange?.let { "${it.first}~${it.last}" } ?: "미지원")
            RowInfo("수동 초점", if (caps.manualFocusSupported) "지원" else "미지원")
            RowInfo("수동 WB", if (caps.manual.whiteBalance) "지원" else "미지원")
            RowInfo("RAW/DNG", if (caps.rawSupported) "지원 (${caps.rawSizes.firstOrNull()})" else "미지원")
            RowInfo("HEIF", if (caps.heifSupported) "지원" else "미지원")
            RowInfo("Ultra HDR", if (caps.jpegRSupported) "지원" else "미지원")
            RowInfo("최대 JPEG", caps.jpegSizes.maxByOrNull { it.width.toLong() * it.height }?.toString() ?: "-")
            RowInfo("동영상 최대", caps.videoSizes.firstOrNull()?.toString() ?: "-")
            RowInfo("OIS", if (caps.opticalStabilizationSupported) "지원" else "미지원")
            RowInfo("EIS 모드", caps.videoStabilizationModes.joinToString().ifEmpty { "-" })
            RowInfo("플래시", if (caps.flashAvailable) "있음" else "없음")
            RowInfo("논리 멀티카메라", if (caps.logicalMultiCamera) caps.physicalCameraIds.joinToString() else "아니오")
            RowInfo("광각 왜곡 보정", if (caps.lensDistortionCorrectionSupported) "지원" else "미지원")
            RowInfo("제어 가능", caps.manual.describe())
            Button(onClick = onExport, modifier = Modifier.padding(top = 12.dp)) {
                Text("전체 카메라 JSON 저장")
            }
        }
    }
}

@Composable
private fun RowInfo(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
        Text(value, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private fun ExposureFormatShort(ns: Long): String =
    com.aipro.camera.manual.ExposureFormat.exposureTime(ns)
