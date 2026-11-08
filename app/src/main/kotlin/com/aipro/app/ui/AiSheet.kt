package com.aipro.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aipro.ai.model.ModelTask
import com.aipro.app.CameraUiState
import com.aipro.app.CameraViewModel

/**
 * AI status + control surface. Everything shown here is measured: the delegate probe result, the
 * installed-model list on disk, and the persisted job queue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSheet(state: CameraUiState, vm: CameraViewModel, onDismiss: () -> Unit) {
    var pendingTask by remember { mutableStateOf(ModelTask.DENOISE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.installModel(uri, pendingTask, "${pendingTask.label} 모델")
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("AI 처리", style = MaterialTheme.typography.titleMedium)
            Text(
                state.aiBackend.summary(),
                style = MaterialTheme.typography.labelMedium,
                fontSize = 11.sp,
            )
            Text(
                "${state.aiBackend.socManufacturer} ${state.aiBackend.socModel} · ${state.aiBackend.deviceModel}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            )
            state.aiBackend.probed.forEach { availability ->
                Text(
                    "· ${availability.backend.label}: ${if (availability.available) "사용 가능" else "불가 (${availability.detail})"}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text("동영상 후처리 (원본 보존 · 재개 가능)", style = MaterialTheme.typography.titleSmall, fontSize = 12.sp)
            if (state.videoJobs.isEmpty()) {
                Text("대기 중인 동영상 작업이 없습니다", fontSize = 10.sp)
            } else {
                state.videoJobs.take(6).forEach { job ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            "${job.status} · ${job.resumeLabel}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "목적 ${job.shootingPurpose} · 야간 ${if (job.nightMode) "예" else "아니오"} · " +
                                "수평 ${"%.1f".format(job.horizonRollDegrees)}° · ${job.width}×${job.height}",
                            fontSize = 9.sp,
                        )
                        job.lastError?.let { Text("오류: $it", fontSize = 9.sp) }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { vm.retryVideoJob(job.id) }) { Text("재시도", fontSize = 10.sp) }
                            TextButton(onClick = { vm.cancelVideoJob(job.id) }) { Text("보류", fontSize = 10.sp) }
                        }
                    }
                }
                TextButton(onClick = { vm.resumeVideoJobs() }) { Text("대기 작업 모두 재개", fontSize = 10.sp) }
            }
            Text(
                "동영상 후처리는 원본(MediaRecorder) 종료 후 별도 워커에서 실행됩니다. " +
                    "발열/배터리 보호 시 세그먼트 단위로 멈추고 이어서 처리합니다.",
                fontSize = 9.sp,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.aiAutoEnhance,
                    onClick = vm::toggleAutoEnhance,
                    label = { Text("촬영 후 자동 보정", fontSize = 11.sp) },
                )
                FilterChip(
                    selected = state.aiMaxUpscale > 1,
                    onClick = { vm.setMaxUpscale(if (state.aiMaxUpscale > 1) 1 else 2) },
                    label = { Text(if (state.aiMaxUpscale > 1) "업스케일 2x" else "업스케일 끔", fontSize = 11.sp) },
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text("설치된 모델 (${state.installedModels.size})", style = MaterialTheme.typography.labelLarge)
            if (state.installedModels.isEmpty()) {
                Text(
                    "모델이 없습니다. 아래에서 .tflite 파일을 선택해 설치하세요. 모델 없이도 고전(네이티브) 경로로 보정이 동작합니다.",
                    fontSize = 10.sp,
                )
            }
            state.installedModels.forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("${model.displayName} · ${model.task}", fontSize = 11.sp)
                        Text(
                            "${model.inputShape} → ${model.outputShape} · ${model.dataType} · 검증 ${if (model.verified) "OK" else "실패"}",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    OutlinedButton(onClick = { vm.removeModel(model.id) }) { Text("삭제", fontSize = 10.sp) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModelTask.entries.forEach { task ->
                    FilterChip(
                        selected = pendingTask == task,
                        onClick = { pendingTask = task },
                        label = { Text(task.label, fontSize = 10.sp) },
                    )
                }
            }
            Button(onClick = { picker.launch(arrayOf("application/octet-stream", "application/x-tflite", "*/*")) }) {
                Text("${pendingTask.label} 모델 설치 (.tflite)")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text("처리 큐 (${state.aiQueue.items.size})", style = MaterialTheme.typography.labelLarge)
            Text(
                "상태: ${if (state.aiQueue.paused) "일시정지" else if (state.aiQueue.running) "처리 중" else "대기"} · 발열 ${state.aiQueue.thermalLabel} · 배터리 ${state.aiQueue.batteryLabel}",
                fontSize = 10.sp,
            )
            state.aiQueue.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${item.label} · ${item.status}", fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.status == "FAILED") {
                            OutlinedButton(onClick = { vm.retryAiJob(item.id) }) { Text("재시도", fontSize = 9.sp) }
                        }
                        OutlinedButton(onClick = { vm.cancelAiJob(item.id) }) { Text("취소", fontSize = 9.sp) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::pauseAi) { Text("일시정지", fontSize = 11.sp) }
                Button(onClick = vm::resumeAi) { Text("재개", fontSize = 11.sp) }
            }
            state.aiQueue.message?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.primary) }
            Button(onClick = vm::refreshAiReport, modifier = Modifier.padding(top = 4.dp)) {
                Text("가속기 재탐지", fontSize = 11.sp)
            }
        }
    }
}
