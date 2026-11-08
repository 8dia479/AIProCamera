package com.aipro.ai.pipeline

import android.graphics.Bitmap
import com.aipro.ai.backend.AccelerationBackend
import com.aipro.ai.performance.PerformanceGovernor
import com.aipro.ai.scene.SceneAnalyzer
import com.aipro.common.AiproLog
import com.aipro.common.CaptureRecord
import com.aipro.common.ProcessingStatus
import com.aipro.common.ShootingPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Decodes the original capture. Implemented by the app layer (MediaStore). */
interface CaptureSource {
    fun loadBitmap(uri: String, maxDimension: Int): Bitmap?
}

/** Persists the enhanced copy and records it. Implemented by the app layer. */
interface EnhancedDestination {
    fun saveEnhanced(bitmap: Bitmap, record: CaptureRecord, result: EnhancementResult): String?
}

/** Durable job list so an interrupted enhancement resumes after a restart (spec sections 12-13). */
interface AiJobStore {
    fun pending(): List<CaptureRecord>
    fun save(record: CaptureRecord)
}

data class QueueItem(
    val id: String,
    val label: String,
    val purpose: String,
    val status: String,
    val attempts: Int = 0,
    val error: String? = null,
)

data class QueueState(
    val items: List<QueueItem> = emptyList(),
    val running: Boolean = false,
    val paused: Boolean = false,
    val currentId: String? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val thermalLabel: String = "-",
    val batteryLabel: String = "-",
)

/**
 * Sequential enhancement queue.
 *
 * * One job at a time (photo quality over throughput).
 * * Pause/resume/cancel, and every state change is written back to [AiJobStore] so nothing is lost.
 * * Thermal/battery aware: heavy work pauses instead of cooking the phone (spec section 13).
 * * The original file is never touched - only `Enhanced/` receives output.
 */
class EnhanceQueue(
    private val enhancer: PhotoEnhancer,
    private val sceneAnalyzer: SceneAnalyzer,
    private val governor: PerformanceGovernor,
    private val store: AiJobStore,
    private val source: CaptureSource,
    private val destination: EnhancedDestination,
    private val scope: CoroutineScope,
    private val maxUpscale: Int = 1,
    private val maxDimension: Int = 4096,
) {

    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var worker: Job? = null
    private var paused = false
    private val jobs = LinkedHashMap<String, CaptureRecord>()

    @Volatile private var upscaleFactor: Int = maxUpscale
    @Volatile private var accelerationBackend: AccelerationBackend = AccelerationBackend.CPU_XNNPACK

    fun setBackend(backend: AccelerationBackend) {
        accelerationBackend = backend
    }

    /** Upscaling is user-controlled and can change while the queue is running. */
    fun setMaxUpscale(factor: Int) {
        upscaleFactor = factor.coerceIn(1, 2)
    }

    /** Loads persisted jobs (resume after process death) and starts the worker. */
    fun start() {
        if (worker?.isActive == true) return
        runCatching { store.pending() }.getOrDefault(emptyList()).forEach { record ->
            if (record.processingStatus == ProcessingStatus.QUEUED.name ||
                record.processingStatus == ProcessingStatus.PROCESSING.name
            ) {
                jobs[record.id] = record.copy(processingStatus = ProcessingStatus.QUEUED.name)
            }
        }
        publish()
        worker = scope.launch { loop() }
    }

    fun enqueue(record: CaptureRecord) {
        val queued = record.copy(processingStatus = ProcessingStatus.QUEUED.name)
        jobs[queued.id] = queued
        runCatching { store.save(queued) }
        publish()
        start()
    }

    fun pause() {
        paused = true
        publish()
    }

    fun resume() {
        paused = false
        publish()
        start()
    }

    fun cancel(id: String) {
        jobs[id]?.let { record ->
            jobs.remove(id)
            runCatching { store.save(record.copy(processingStatus = ProcessingStatus.CANCELLED.name)) }
        }
        publish()
    }

    fun retry(id: String) {
        jobs[id]?.let { record ->
            jobs[id] = record.copy(processingStatus = ProcessingStatus.QUEUED.name)
            publish()
            start()
        }
    }

    fun shutdown() {
        worker?.cancel()
        worker = null
    }

    private fun publish() {
        val governorLabels = runCatching {
            governor.thermalLabel() to governor.batteryState()
        }.getOrDefault("알 수 없음" to PerformanceGovernor.BatteryState(-1, false))
        _state.update { current ->
            current.copy(
                items = jobs.values.map { record ->
                    QueueItem(
                        id = record.id,
                        label = "${record.shootingPurpose} · ${record.width}x${record.height}",
                        purpose = record.shootingPurpose,
                        status = record.processingStatus,
                        error = record.processingError,
                    )
                },
                paused = paused,
                running = worker?.isActive == true && !paused,
                currentId = current.currentId,
                thermalLabel = governorLabels.first,
                batteryLabel = governorLabels.second.let { state ->
                    if (state.percent < 0) "-" else "${state.percent}%" + if (state.charging) " (충전 중)" else ""
                },
            )
        }
    }

    private suspend fun loop() {
        while (scope.isActive) {
            if (paused) {
                delay(500)
                continue
            }
            val level = runCatching { governor.level() }.getOrDefault(PerformanceGovernor.Level.FULL)
            if (level == PerformanceGovernor.Level.PAUSED) {
                _state.update { it.copy(message = "발열/배터리 보호를 위해 AI 처리를 일시 중지했습니다") }
                delay(5_000)
                publish()
                continue
            }
            val next = mutex.withLock {
                jobs.values.firstOrNull { it.processingStatus == ProcessingStatus.QUEUED.name }
            }
            if (next == null) {
                _state.update { it.copy(running = false, currentId = null, progress = 0f) }
                delay(1_500)
                continue
            }
            process(next, level)
        }
    }

    private suspend fun process(record: CaptureRecord, level: PerformanceGovernor.Level) {
        _state.update { it.copy(currentId = record.id, running = true, progress = 0.1f, message = null) }
        updateStatus(record, ProcessingStatus.PROCESSING)
        val started = System.currentTimeMillis()
        try {
            val bitmap = withContext(Dispatchers.IO) {
                source.loadBitmap(record.originalUri, maxDimension)
            } ?: error("원본 이미지를 읽을 수 없습니다")
            _state.update { it.copy(progress = 0.35f) }
            val purpose = runCatching { ShootingPurpose.valueOf(record.shootingPurpose) }
                .getOrDefault(ShootingPurpose.AUTO)
            val result = withContext(Dispatchers.Default) {
                enhancer.enhance(
                    source = bitmap,
                    purpose = purpose,
                    nightEnabled = record.nightMode,
                    maxUpscale = if (level == PerformanceGovernor.Level.REDUCED) 1 else upscaleFactor,
                    threads = governor.recommendedThreads(),
                    backend = accelerationBackend,
                )
            } ?: error("처리 파이프라인이 결과를 만들지 못했습니다")
            _state.update { it.copy(progress = 0.8f) }

            // saveEnhanced reads this bitmap; recycle only after the write completes.
            val enhancedUri = try {
                destination.saveEnhanced(bitmap, record, result)
            } finally {
                bitmap.recycle()
            }
            if (enhancedUri == null) error("보정본 저장에 실패했습니다")
            val updated = record.copy(
                enhancedUri = enhancedUri,
                processingStatus = ProcessingStatus.ENHANCED.name,
                processingError = null,
                modelVersion = if (result.modelIds.isEmpty()) "classical" else result.modelIds.joinToString(","),
            )
            store.save(updated)
            mutex.withLock { jobs.remove(record.id) }
            _state.update {
                it.copy(
                    progress = 1f,
                    currentId = null,
                    message = "보정 완료 (${System.currentTimeMillis() - started}ms) · ${result.steps.firstOrNull() ?: ""}",
                )
            }
            AiproLog.i("Queue", "job ${record.id} enhanced :: ${result.steps.joinToString()}")
        } catch (t: Throwable) {
            AiproLog.e("Queue", "job ${record.id} failed", t)
            val failed = record.copy(
                processingStatus = ProcessingStatus.FAILED.name,
                processingError = t.message ?: t::class.java.simpleName,
            )
            runCatching { store.save(failed) }
            mutex.withLock { jobs[record.id] = failed }
            _state.update { it.copy(currentId = null, progress = 0f, message = "보정 실패: ${t.message}") }
        }
        publish()
    }

    private suspend fun updateStatus(record: CaptureRecord, status: ProcessingStatus) {
        val updated = record.copy(processingStatus = status.name)
        mutex.withLock { jobs[record.id] = updated }
        runCatching { store.save(updated) }
        publish()
    }
}
