package com.aipro.app

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Build
import com.aipro.camera.discovery.CameraCapabilities
import com.aipro.camera.discovery.CameraDescriptor
import com.aipro.camera.discovery.CapabilityProbe
import com.aipro.camera.engine.CameraController
import com.aipro.camera.engine.CaptureOutcome
import com.aipro.camera.engine.EngineState
import com.aipro.camera.engine.FrameInfo
import com.aipro.camera.capture.AspectRatioOption
import com.aipro.camera.engine.MultiFrameCapture
import com.aipro.camera.engine.MultiFrameMode
import com.aipro.camera.engine.StillCaptureRequest
import com.aipro.camera.engine.StillFormat
import com.aipro.camera.engine.VideoOutcome
import com.aipro.camera.video.LivePreviewProcessor
import com.aipro.camera.video.VideoJob
import com.aipro.camera.video.HorizonSample
import com.aipro.camera.video.VideoPostProcessor
import com.aipro.camera.stabilizer.HorizonLockController
import com.aipro.app.video.VideoEnhanceScheduler
import android.view.Surface
import com.aipro.camera.manual.FlashMode
import com.aipro.camera.manual.ManualSettings
import com.aipro.camera.preview.PreviewGeometry
import com.aipro.camera.preview.PreviewTransform
import com.aipro.common.AiproLog
import com.aipro.common.CaptureMode
import com.aipro.common.Facing
import com.aipro.common.LensType
import com.aipro.common.CaptureRecord
import com.aipro.common.DeviceProfile
import com.aipro.common.ProcessingStatus
import com.aipro.common.ShootingPurpose
import com.aipro.ai.backend.BackendReport
import com.aipro.ai.backend.BackendProbe
import com.aipro.ai.model.ModelManager
import com.aipro.ai.model.ModelManifest
import com.aipro.ai.model.ModelTask
import com.aipro.ai.performance.PerformanceGovernor
import com.aipro.ai.pipeline.EnhanceQueue
import com.aipro.ai.pipeline.PhotoEnhancer
import com.aipro.ai.pipeline.QueueState
import com.aipro.ai.scene.SceneAnalyzer
import com.aipro.app.ai.MediaStoreCaptureSource
import com.aipro.app.ai.MediaStoreEnhancedDestination
import com.aipro.app.ai.SidecarJobStore
import com.aipro.app.ui.CameraUiStyle
import com.aipro.app.ui.SettingsStore
import com.aipro.storage.MediaStoreSink
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID

data class CameraUiState(
    val cameras: List<CameraDescriptor> = emptyList(),
    val selectedCameraId: String? = null,
    val capabilities: CameraCapabilities? = null,
    val engineState: EngineState = EngineState.Idle,
    val frame: FrameInfo = FrameInfo(),
    val geometry: PreviewGeometry? = null,
    val mode: CaptureMode = CaptureMode.PHOTO,
    val purpose: ShootingPurpose = ShootingPurpose.AUTO,
    val nightMode: Boolean = false,
    val manual: ManualSettings = ManualSettings(),
    val captureRaw: Boolean = false,
    val showProPanel: Boolean = false,
    val message: String? = null,
    val lastPhotoUri: String? = null,
    val lastVideo: VideoOutcome? = null,
    val capabilityJson: String? = null,
    val recordingStartedAt: Long? = null,
    val deviceProfile: DeviceProfile = DeviceProfile(),
    val uiStyle: CameraUiStyle = CameraUiStyle.GALAXY,
    val showGrid: Boolean = true,
    // --- AI (stage 3) ---
    val aiBackend: BackendReport = BackendReport.UNKNOWN,
    val aiQueue: QueueState = QueueState(),
    val installedModels: List<ModelManifest> = emptyList(),
    val aiAutoEnhance: Boolean = false,
    val hdrEnabled: Boolean = false,
    val multiFrameSummary: String? = null,
    val realtimeProcessing: Boolean = false,
    val videoJobs: List<VideoJob> = emptyList(),
    val horizonLock: Boolean = false,
    val horizonRollDegrees: Float = 0f,
    val horizonSupported: Boolean = false,
    val horizonStatus: String = "꺼짐",
    val thermalLevel: String = "FULL",
    val thermalLabel: String = "정상",
    val aspectRatio: AspectRatioOption = AspectRatioOption.RATIO_4_3,
    val supportedRatios: List<AspectRatioOption> = AspectRatioOption.entries,
    val showRatioSheet: Boolean = false,
    val flashMode: FlashMode = FlashMode.OFF,
    val flashAvailable: Boolean = false,
    val actionMode: Boolean = false,
    val displayRotationDegrees: Int = 0,
    /** "AUTO" or an absolute degree value as text; default 0°. */
    val previewRotationMode: String = "0",
    val aiMaxUpscale: Int = 1,
    val showAiSheet: Boolean = false,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = CameraController(app)
    private val sink = MediaStoreSink(app)
    private val settings = SettingsStore(app)

    // ---------------------------------------------------------------- AI layer
    private val modelManager = ModelManager(app)
    private val governor = PerformanceGovernor(app)
    private val sceneAnalyzer = SceneAnalyzer(modelManager)
    private val enhancer = PhotoEnhancer(modelManager, sceneAnalyzer)
    private val captureSource = MediaStoreCaptureSource(app)
    private val enhancedDestination = MediaStoreEnhancedDestination(sink)
    private val enhanceQueue = EnhanceQueue(
        enhancer = enhancer,
        sceneAnalyzer = sceneAnalyzer,
        governor = governor,
        store = SidecarJobStore(sink.sidecarStore()),
        source = captureSource,
        destination = enhancedDestination,
        scope = viewModelScope,
        maxUpscale = 1,
    )

    private val videoScheduler = VideoEnhanceScheduler(app)

    private val horizonController = HorizonLockController(app)
    private val recordingHorizonSamples = mutableListOf<HorizonSample>()
    private var lastHorizonSampleUs = 0L

    private var liveProcessor: LivePreviewProcessor? = null
    private var previewTextureRef: android.graphics.SurfaceTexture? = null
    private var previewViewSize: Pair<Int, Int> = 1080 to 1920

    private val _ui = MutableStateFlow(CameraUiState())
    val ui: StateFlow<CameraUiState> = _ui.asStateFlow()

    init {
        val style = settings.uiStyle
        val rotationMode = settings.previewRotationMode
        settings.lastAspectRatio
            ?.let { name -> runCatching { AspectRatioOption.valueOf(name) }.getOrNull() }
            ?.let { controller.setAspectRatio(it) }
        _ui.update {
            it.copy(
                uiStyle = style,
                previewRotationMode = rotationMode,
                showGrid = settings.showGrid,
                // The pro design keeps the exposure panel open by default; the Galaxy design does not.
                showProPanel = style == CameraUiStyle.PRO,
            )
        }
        val cameras = controller.discoverCameras()
        _ui.update { it.copy(cameras = cameras, selectedCameraId = cameras.firstOrNull()?.cameraId) }
        viewModelScope.launch { controller.state.collect { state -> _ui.update { it.copy(engineState = state) } } }
        viewModelScope.launch { controller.frameInfo.collect { frame -> _ui.update { it.copy(frame = frame) } } }
        viewModelScope.launch { controller.previewGeometry.collect { geo -> _ui.update { it.copy(geometry = geo) } } }
        viewModelScope.launch {
            controller.activeCamera.collect { active ->
                if (active != null) {
                    _ui.update {
                        it.copy(
                            capabilities = active.capabilities,
                            manual = active.manual,
                            mode = active.mode,
                            flashAvailable = active.capabilities.flashAvailable,
                            // Video can only use ratios the encoder really supports (no cropping).
                            supportedRatios = controller.supportedRatios(video = active.mode == CaptureMode.VIDEO),
                        )
                    }
                    ensureGeometry()
                }
            }
        }
        syncRecordingState()
        setupAi()
        _ui.update {
            it.copy(
                aspectRatio = controller.aspectRatio,
                supportedRatios = controller.supportedRatios(video = false),
            )
        }
        // Jobs recorded before a restart still owe their enhanced output; resume them all.
        videoScheduler.resumeOutstanding()
        refreshVideoJobs()
        _ui.update {
            it.copy(
                horizonSupported = horizonController.isSupported,
                thermalLabel = governor.thermalLabel(),
                thermalLevel = governor.level().name,
            )
        }
        // Sensor roll feeds the live shader and is recorded on the job so post-processing matches.
        viewModelScope.launch {
            // 50 Hz sensor data goes straight to the renderer. Recomposition is throttled to 5 Hz,
            // which is what made the screen feel frozen when horizon lock was switched on.
            var lastUiUpdateNanos = 0L
            horizonController.rollDegrees.collect { roll ->
                // The preview matrix (TextureView.setTransform) applies the leveling, so the GL tone
                // chain must NOT rotate as well or the frame would be leveled twice.
                controller.setHorizonRoll(roll)
                val recordingStartedAt = _ui.value.recordingStartedAt
                if (recordingStartedAt != null && _ui.value.horizonLock) {
                    val timeUs = ((System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)) * 1_000L
                    synchronized(recordingHorizonSamples) {
                        if (timeUs - lastHorizonSampleUs >= 30_000L) {
                            recordingHorizonSamples += HorizonSample(timeUs, roll)
                            lastHorizonSampleUs = timeUs
                        }
                    }
                }
                val now = System.nanoTime()
                if (now - lastUiUpdateNanos > 200_000_000L) {
                    lastUiUpdateNanos = now
                    _ui.update {
                        it.copy(
                            horizonRollDegrees = roll,
                            horizonStatus = horizonController.statusLabel(),
                        )
                    }
                }
            }
        }
    }

    private fun setupAi() {
        viewModelScope.launch {
            enhanceQueue.state.collect { queue -> _ui.update { it.copy(aiQueue = queue) } }
        }
        viewModelScope.launch {
            // Select the verified runtime before resuming any persisted enhancement jobs.
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                BackendProbe.probe(getApplication())
            }
            enhanceQueue.setBackend(report.selected)
            _ui.update { it.copy(aiBackend = report) }
            val models = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                modelManager.installed()
            }
            _ui.update { it.copy(installedModels = models) }
            enhanceQueue.start()
        }
    }

    private fun syncRecordingState() {
        viewModelScope.launch {
            controller.state.collect { state ->
                val startedAt = (state as? EngineState.Recording)?.startedAtMs
                _ui.update { it.copy(recordingStartedAt = startedAt) }
            }
        }
    }

    // ------------------------------------------------------------------ preview

    fun attachPreview(texture: SurfaceTexture, width: Int, height: Int) {
        previewTextureRef = texture
        previewViewSize = width to height
        if (_ui.value.realtimeProcessing) {
            startLiveProcessor(texture, width, height)
        } else {
            controller.attachPreviewTexture(texture, width, height)
        }
        ensureGeometry()
        val cameraId = _ui.value.selectedCameraId ?: return
        viewModelScope.launch {
            runCatching { controller.openCamera(cameraId) }
                .onFailure { error ->
                    AiproLog.e("ViewModel", "openCamera failed", error)
                    _ui.update { it.copy(message = "카메라를 열 수 없습니다: ${error.message}") }
                }
        }
    }

    fun detachPreview() {
        stopLiveProcessor()
        controller.detachPreviewTexture()
        previewTextureRef = null
    }

    // ------------------------------------------------------------- real-time video

    /**
     * Switches the preview to the GL tone chain (stage 5). The chain reads camera frames and writes
     * them to the view surface, so the MediaRecorder's own surface keeps recording the untouched
     * original no matter what happens here; a GL failure falls back to the plain preview.
     */
    fun toggleRealtimeProcessing() {
        val enable = !_ui.value.realtimeProcessing
        _ui.update { it.copy(realtimeProcessing = enable) }
        val texture = previewTextureRef ?: return
        val (width, height) = previewViewSize
        if (enable) {
            startLiveProcessor(texture, width, height)
        } else {
            stopLiveProcessor()
            controller.attachPreviewTexture(texture, width, height)
            _ui.update { it.copy(message = "실시간 처리 꺼짐 · 원본 미리보기") }
        }
    }

    private fun startLiveProcessor(texture: android.graphics.SurfaceTexture, width: Int, height: Int) {
        stopLiveProcessor()
        val state = _ui.value
        val tone = VideoPostProcessor.toneFor(state.purpose.name, state.nightMode)
        val buffer = controller.activeCamera.value?.previewSize
        val processor = LivePreviewProcessor(
            viewSurface = Surface(texture),
            viewWidth = width.coerceAtLeast(1),
            viewHeight = height.coerceAtLeast(1),
            bufferWidth = buffer?.width ?: width.coerceAtLeast(1),
            bufferHeight = buffer?.height ?: height.coerceAtLeast(1),
            initialTone = tone,
        )
        val input = if (processor.start()) processor.cameraInputSurface else null
        if (input == null) {
            processor.stop()
            controller.attachPreviewTexture(texture, width, height)
            _ui.update { it.copy(realtimeProcessing = false, message = "실시간 GL 처리 시작 실패 · 기본 미리보기 사용") }
            return
        }
        liveProcessor = processor
        controller.attachPreviewSurface(input, width, height)
        _ui.update { it.copy(message = "실시간 처리 켜짐 · 톤 체인 적용") }
    }

    private fun stopLiveProcessor() {
        liveProcessor?.let { processor ->
            controller.detachPreviewSurface()
            processor.stop()
        }
        liveProcessor = null
    }

    private fun refreshLiveTone() {
        val state = _ui.value
        liveProcessor?.setTone(VideoPostProcessor.toneFor(state.purpose.name, state.nightMode))
    }

    // --------------------------------------------------------------- video jobs

    fun refreshVideoJobs() {
        _ui.update { it.copy(videoJobs = videoScheduler.jobs()) }
    }

    fun resumeVideoJobs() {
        val count = videoScheduler.resumeOutstanding()
        refreshVideoJobs()
        _ui.update { it.copy(message = "동영상 후처리 ${count}건 재개") }
    }

    // ------------------------------------------------------------ horizon lock

    /**
     * Experimental horizon lock. Sensor failures are contained here so an unavailable or busy
     * sensor service cannot terminate the camera Activity.
     */
    fun toggleHorizonLock() {
        if (!horizonController.isSupported) {
            _ui.update { it.copy(message = "이 기기에서 수평 잠금을 사용할 수 없습니다: ${horizonController.unsupportedReason}") }
            return
        }
        val enable = !_ui.value.horizonLock
        if (enable) {
            val startResult = runCatching { horizonController.start() }
            startResult.exceptionOrNull()?.let {
                AiproLog.e("ViewModel", "horizon lock start failed", it)
            }
            val started = startResult.getOrDefault(false)
            controller.setHorizonRoll(if (started) horizonController.rollDegrees.value else 0f)
            _ui.update {
                it.copy(
                    horizonLock = started,
                    horizonStatus = horizonController.statusLabel(),
                    message = when {
                        started -> "수평 잠금 켜짐 · 센서 기반 프리뷰/사진 수평 보정 (동영상은 후처리)"
                        startResult.isFailure -> "수평 잠금 시작 실패: ${startResult.exceptionOrNull()?.message ?: "센서 오류"}"
                        else -> "센서 등록 실패로 수평 잠금을 켤 수 없습니다"
                    },
                )
            }
        } else {
            runCatching { horizonController.stop() }
                .onFailure { AiproLog.e("ViewModel", "horizon lock stop failed", it) }
            controller.setHorizonRoll(0f)
            _ui.update { it.copy(horizonLock = false, horizonStatus = "꺼짐", horizonRollDegrees = 0f) }
        }
    }

    /**
     * Builds the preview geometry in the ViewModel itself when the engine has not published one yet.
     * The preview must never depend on the order in which engine events arrive: a null geometry means
     * no rotation and no aspect correction at all (the camera looked 90° sideways).
     */
    private fun ensureGeometry() {
        if (_ui.value.geometry != null) return
        val active = controller.activeCamera.value ?: return
        val (viewWidth, viewHeight) = previewViewSize
        if (viewWidth <= 0 || viewHeight <= 0) return
        val geometry = PreviewTransform.compute(
            bufferWidth = active.previewSize.width,
            bufferHeight = active.previewSize.height,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            sensorOrientation = active.capabilities.sensorOrientation,
            displayRotationDegrees = _ui.value.displayRotationDegrees,
            facingFront = active.capabilities.facing == Facing.FRONT,
        )
        _ui.update { it.copy(geometry = geometry) }
    }

    /** Live roll for the preview transform; collected off the recomposition path. */
    val horizonRollFlow: kotlinx.coroutines.flow.StateFlow<Float> get() = horizonController.rollDegrees

    private fun currentHorizonRoll(): Float =
        if (_ui.value.horizonLock && horizonController.isSupported) horizonController.rollDegrees.value else 0f

    fun refreshThermalState() {
        _ui.update { it.copy(thermalLabel = governor.thermalLabel(), thermalLevel = governor.level().name) }
    }

    fun cancelVideoJob(id: String) {
        videoScheduler.cancel(id)
        refreshVideoJobs()
    }

    fun retryVideoJob(id: String) {
        videoScheduler.retry(id)
        refreshVideoJobs()
    }

    fun setDisplayRotation(degrees: Int) {
        _ui.update { it.copy(displayRotationDegrees = degrees) }
        ensureGeometry()
        // While the horizon lock is on the image is levelled against the device roll, so the UI must
        // stay in the orientation it was locked in — otherwise the window itself rotates and the
        // gimbal effect cancels out (this is how Samsung's 360° horizon lock behaves).
        if (_ui.value.horizonLock && degrees != lockedDisplayRotation) return
        lockedDisplayRotation = degrees
        controller.setDisplayRotationDegrees(degrees)
    }

    private var lockedDisplayRotation = 0

    // ------------------------------------------------------- framing ratio / flash

    /** 화질/비율 설정(1:1 · 4:3 · 16:9 · 22:9). Ratios the sensor cannot serve are never shown. */
    fun setAspectRatio(option: AspectRatioOption) {
        if (_ui.value.recordingStartedAt != null) return
        val applied = controller.setAspectRatio(option)
        settings.lastAspectRatio = option.name
        _ui.update {
            it.copy(
                aspectRatio = controller.aspectRatio,
                supportedRatios = controller.supportedRatios(video = _ui.value.mode == CaptureMode.VIDEO),
                showRatioSheet = false,
                message = if (applied) "비율 ${option.label} (가로 기준 · 세로에서는 ${option.label.split(":").reversed().joinToString(":")})" else it.message,
            )
        }
    }

    fun setRatioSheetVisible(visible: Boolean) = _ui.update { it.copy(showRatioSheet = visible) }

    /** Cycles OFF → AUTO → ON. Hidden entirely when the camera has no flash. */
    fun cycleFlash() {
        if (!_ui.value.flashAvailable) return
        val next = _ui.value.flashMode.next()
        controller.setFlashMode(next)
        _ui.update { it.copy(flashMode = next, message = "플래시 ${next.label}") }
    }

    /** Front/back toggle used by the Galaxy-style shutter row. */
    fun switchCamera() {
        if (_ui.value.recordingStartedAt != null) return
        val cameras = _ui.value.cameras
        if (cameras.isEmpty()) return
        val current = cameras.firstOrNull { it.cameraId == _ui.value.selectedCameraId }
        val target = if (current?.facing == Facing.FRONT) {
            cameras.filter { it.facing == Facing.BACK }
                .maxByOrNull { it.equivalentFocalLength35mm ?: 0.0 }
        } else {
            cameras.filter { it.facing == Facing.FRONT }.minByOrNull { it.equivalentFocalLength35mm ?: 0.0 }
        } ?: return
        selectCamera(target.cameraId)
    }

    /** 액션 모드: 플랫폼 EIS/OIS + 디지털 크롭으로 흔들림을 줄인다 (동영상 전용). */
    fun toggleActionMode() {
        val enable = !_ui.value.actionMode
        controller.setActionMode(enable)
        _ui.update {
            it.copy(
                actionMode = enable,
                message = if (enable) {
                    "액션 모드 켜짐 · 손떨림 보정(EIS+OIS) + 1.15배 크롭"
                } else {
                    "액션 모드 꺼짐"
                },
            )
        }
    }

    /**
     * 줌 알약: 초광각은 0.6배, 광각(메인)은 1배로 표기한다. 그 외 렌즈는 35 mm 환산 배율로 계산하며,
     * 렌즈 정보가 없으면 표시하지 않는다(없는 배율을 만들어내지 않음).
     */
    fun zoomPills(): List<Pair<String, String>> {
        val back = _ui.value.cameras.filter { it.facing == Facing.BACK }
        val wide = back.firstOrNull { it.lensType == LensType.WIDE }
        val wideEquivalent = wide?.equivalentFocalLength35mm
        return back.mapNotNull { camera ->
            val label = when (camera.lensType) {
                LensType.ULTRA_WIDE -> "0.6×"
                LensType.WIDE -> "1×"
                else -> {
                    val equivalent = camera.equivalentFocalLength35mm
                    if (equivalent != null && wideEquivalent != null && wideEquivalent > 0.0) {
                        val factor = equivalent / wideEquivalent
                        if (factor > 1.05) "${"%.1f".format(factor)}×" else null
                    } else {
                        null
                    }
                }
            } ?: return@mapNotNull null
            camera.cameraId to label
        }.sortedBy { if (it.second.startsWith("0.")) 0 else 1 }
    }

    fun selectCamera(cameraId: String) {
        if (_ui.value.recordingStartedAt != null) return
        _ui.update { it.copy(selectedCameraId = cameraId) }
        viewModelScope.launch { runCatching { controller.selectCamera(cameraId) } }
    }

    fun setMode(mode: CaptureMode) {
        if (_ui.value.recordingStartedAt != null) return
        controller.setMode(mode)
        _ui.update { it.copy(mode = mode) }
    }

    fun setPurpose(purpose: ShootingPurpose) {
        settings.lastPurpose = purpose.name
        _ui.update { it.copy(purpose = purpose) }
        refreshLiveTone()
    }

    fun setUiStyle(style: CameraUiStyle) {
        settings.uiStyle = style
        _ui.update { it.copy(uiStyle = style, showProPanel = style == CameraUiStyle.PRO || it.showProPanel) }
    }

    /**
     * 프리뷰 회전 보정: "0"(기본, 센서 방향 그대로) / "90" / "180" / "270" 은 센서 회전에 더해지는
     * 오프셋입니다. "AUTO" 는 엔진이 계산한 센서·디스플레이 조합을 그대로 사용합니다(오프셋 0과 동일).
     */
    fun setPreviewRotationMode(mode: String) {
        settings.previewRotationMode = mode
        _ui.update { it.copy(previewRotationMode = mode) }
    }

    fun setShowGrid(show: Boolean) {
        settings.showGrid = show
        _ui.update { it.copy(showGrid = show) }
    }

    fun toggleNightMode() {
        _ui.update { it.copy(nightMode = !it.nightMode) }
        refreshLiveTone()
    }

    fun toggleHdr() = _ui.update { it.copy(hdrEnabled = !it.hdrEnabled) }

    fun toggleProPanel() = _ui.update { it.copy(showProPanel = !it.showProPanel) }

    fun toggleRaw() = _ui.update { it.copy(captureRaw = !it.captureRaw && (it.capabilities?.manual?.raw == true)) }

    fun updateManual(transform: (ManualSettings) -> ManualSettings) {
        val updated = transform(_ui.value.manual)
        controller.updateManual(updated)
        _ui.update { it.copy(manual = updated) }
    }

    // ------------------------------------------------------------------ capture

    fun capture() {
        val state = _ui.value
        if (state.engineState is EngineState.Capturing) return
        // Night mode and HDR both need multiple frames; they are independent of the shooting purpose.
        if (state.nightMode || state.hdrEnabled) {
            captureMultiFrame(state)
        } else {
            captureSingle(state)
        }
    }

    private fun captureSingle(state: CameraUiState) {
        viewModelScope.launch {
            val request = StillCaptureRequest(
                purpose = state.purpose,
                nightMode = state.nightMode,
                format = StillFormat.JPEG,
                includeRaw = state.captureRaw,
            )
            controller.captureStill(sink, request)
                .onSuccess { outcome -> onCaptureSaved(outcome, state) }
                .onFailure { error ->
                    AiproLog.e("ViewModel", "capture failed", error)
                    _ui.update { it.copy(message = "촬영 실패: ${error.message}") }
                }
        }
    }

    private fun captureMultiFrame(state: CameraUiState) {
        viewModelScope.launch {
            val mergeMode = if (state.hdrEnabled) MultiFrameMode.HDR_FUSION else MultiFrameMode.NIGHT_STACK
            // Thermal/battery policy (spec section 13): fewer frames instead of a hot, dropped burst.
            refreshThermalState()
            val level = governor.level()
            val baseFrames = if (state.hdrEnabled) 3 else 6
            val frameCount = when (level) {
                PerformanceGovernor.Level.FULL -> baseFrames
                PerformanceGovernor.Level.REDUCED -> (baseFrames - 2).coerceAtLeast(2)
                PerformanceGovernor.Level.PAUSED -> (baseFrames - 3).coerceAtLeast(2)
            }
            if (level != PerformanceGovernor.Level.FULL) {
                _ui.update { it.copy(message = "발열/배터리 보호: ${frameCount}프레임으로 축소 촬영") }
            }
            _ui.update { it.copy(message = "다중 프레임 촬영 중… (${frameCount}프레임)") }
            controller.captureMultiFrame(frames = frameCount, mergeMode = mergeMode)
                .onSuccess { capture -> saveMultiFrame(capture, state) }
                .onFailure { error ->
                    AiproLog.e("ViewModel", "multi-frame capture failed", error)
                    _ui.update { it.copy(message = "다중 프레임 촬영 실패: ${error.message}") }
                }
        }
    }

    private fun saveMultiFrame(capture: MultiFrameCapture, state: CameraUiState) {
        viewModelScope.launch {
            val written = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                writeJpegWithOrientation(capture)
            }
            if (written == null) {
                _ui.update { it.copy(message = "저장 실패") }
                return@launch
            }
            val record = CaptureRecord(
                id = UUID.randomUUID().toString(),
                createdAtEpochMs = System.currentTimeMillis(),
                mode = CaptureMode.PHOTO.name,
                shootingPurpose = state.purpose.name,
                nightMode = state.nightMode || !state.hdrEnabled,
                lens = state.capabilities?.lensType?.name ?: "UNKNOWN",
                facing = state.capabilities?.facing?.name ?: "BACK",
                cameraId = state.selectedCameraId ?: "",
                originalUri = written,
                originalPath = CameraController.ORIGINAL_IMAGE_DIR,
                mimeType = "image/jpeg",
                width = capture.width,
                height = capture.height,
                iso = _ui.value.frame.iso,
                exposureTimeNs = _ui.value.frame.exposureTimeNs,
                focalLengthMm = state.capabilities?.focalLengthsMm?.firstOrNull(),
                fNumber = state.capabilities?.apertureFNumbers?.firstOrNull(),
                whiteBalanceMode = state.manual.whiteBalanceKelvin?.let { "MANUAL_${it}K" } ?: "AUTO",
                exposureCompensationEv = state.manual.exposureCompensationEv,
                focusDistanceDiopters = state.manual.focusDiopters(
                    state.capabilities?.minimumFocusDistanceDiopters ?: 0f,
                ),
                isManualIso = state.manual.isManualIso,
                isManualShutter = state.manual.isManualShutter,
                isManualFocus = state.manual.isManualFocus,
                isManualWhiteBalance = state.manual.isManualWhiteBalance,
                processingStatus = ProcessingStatus.ORIGINAL_ONLY.name,
                multiFrameMode = capture.mode.name,
                framesUsed = capture.framesUsed,
                motionRatio = capture.motionRatio,
                noiseRatio = capture.noiseRatio,
                alignmentShiftPx = capture.meanShiftPx,
                horizonRollDegrees = currentHorizonRoll(),
                aspectRatio = state.aspectRatio.label,
            )
            sink.writeSidecar(record)
            if (_ui.value.aiAutoEnhance) enhanceQueue.enqueue(record)
            _ui.update {
                it.copy(
                    lastPhotoUri = written,
                    multiFrameSummary = capture.summary,
                    message = "저장 완료 · ${capture.width}×${capture.height} · ${capture.summary}",
                )
            }
        }
    }

    /** Writes the merged JPEG to MediaStore with the rotation tag applied through a temp file. */
    private fun writeJpegWithOrientation(capture: MultiFrameCapture): String? = runCatching {
        val temp = java.io.File(
            getApplication<Application>().cacheDir,
            "merged_${System.currentTimeMillis()}.jpg",
        )
        temp.outputStream().use { it.write(capture.jpegBytes) }
        runCatching {
            androidx.exifinterface.media.ExifInterface(temp.absolutePath).apply {
                setAttribute(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    orientationTagFor(capture.orientationDegrees).toString(),
                )
                saveAttributes()
            }
        }
        val target = sink.createImageTarget("image/jpeg", CameraController.ORIGINAL_IMAGE_DIR, "jpg")
        temp.inputStream().use { input -> target.outputStream.use { output -> input.copyTo(output) } }
        target.close()
        temp.delete()
        target.uri
    }.onFailure { AiproLog.e("ViewModel", "merged save failed", it) }.getOrNull()

    private fun orientationTagFor(degrees: Int): Int = when (((degrees % 360) + 360) % 360) {
        90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }

    private fun onCaptureSaved(outcome: CaptureOutcome, state: CameraUiState) {
        val record = CaptureRecord(
            id = UUID.randomUUID().toString(),
            createdAtEpochMs = System.currentTimeMillis(),
            mode = CaptureMode.PHOTO.name,
            shootingPurpose = state.purpose.name,
            nightMode = state.nightMode,
            lens = outcome.lens.name,
            facing = outcome.facing.name,
            cameraId = state.selectedCameraId ?: "",
            originalUri = outcome.primaryUri,
            originalPath = outcome.primaryPath,
            rawUri = outcome.rawUri,
            mimeType = "image/jpeg",
            width = outcome.width,
            height = outcome.height,
            iso = outcome.iso,
            exposureTimeNs = outcome.exposureTimeNs,
            focalLengthMm = state.capabilities?.focalLengthsMm?.firstOrNull(),
            fNumber = state.capabilities?.apertureFNumbers?.firstOrNull(),
            whiteBalanceMode = state.manual.whiteBalanceKelvin?.let { "MANUAL_${it}K" } ?: "AUTO",
            exposureCompensationEv = state.manual.exposureCompensationEv,
            focusDistanceDiopters = state.manual.focusDiopters(state.capabilities?.minimumFocusDistanceDiopters ?: 0f),
            isManualIso = state.manual.isManualIso,
            isManualShutter = state.manual.isManualShutter,
            isManualFocus = state.manual.isManualFocus,
            isManualWhiteBalance = state.manual.isManualWhiteBalance,
            processingStatus = ProcessingStatus.ORIGINAL_ONLY.name,
        )
        sink.writeSidecar(record)
        if (_ui.value.aiAutoEnhance) {
            enhanceQueue.enqueue(record)
        }
        _ui.update {
            it.copy(
                lastPhotoUri = outcome.primaryUri,
                message = "저장 완료 · ${outcome.width}×${outcome.height} · ${outcome.processingMillis}ms" +
                    if (outcome.rawUri != null) " · RAW 함께 저장" else "",
            )
        }
    }

    fun toggleRecording() {
        val state = _ui.value
        if (state.recordingStartedAt != null) {
            val recordedRoll = synchronized(recordingHorizonSamples) {
                recordingHorizonSamples.toList().also {
                    recordingHorizonSamples.clear()
                    lastHorizonSampleUs = 0L
                }
            }
            viewModelScope.launch {
                controller.stopRecording(sink)
                    .onSuccess { outcome ->
                        val state = _ui.value
                        // The original is already closed and safe; the enhanced variant is queued.
                        val job = videoScheduler.enqueue(
                            sourceUri = outcome.uri,
                            purpose = state.purpose.name,
                            nightMode = state.nightMode,
                            tone = VideoPostProcessor.toneFor(state.purpose.name, state.nightMode),
                            horizonRollDegrees = currentHorizonRoll(),
                            horizonSamples = recordedRoll,
                        )
                        refreshVideoJobs()
                        _ui.update {
                            it.copy(
                                lastVideo = outcome,
                                message = "동영상 저장 · ${outcome.width}×${outcome.height} ${outcome.codec} · " +
                                    "${outcome.durationMs / 1000}s · 후처리 대기(${job.id.take(6)})",
                            )
                        }
                    }
                    .onFailure { error -> _ui.update { it.copy(message = "녹화 종료 실패: ${error.message}") } }
            }
        } else {
            synchronized(recordingHorizonSamples) {
                recordingHorizonSamples.clear()
                lastHorizonSampleUs = 0L
            }
            viewModelScope.launch {
                val withAudio = getApplication<Application>().let {
                    androidx.core.content.ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                }
                controller.startRecording(sink, withAudio = withAudio)
                    .onSuccess {
                        if (_ui.value.horizonLock) {
                            synchronized(recordingHorizonSamples) {
                                recordingHorizonSamples.clear()
                                recordingHorizonSamples += HorizonSample(0L, currentHorizonRoll())
                                lastHorizonSampleUs = 0L
                            }
                        }
                    }
                    .onFailure { error -> _ui.update { it.copy(message = "녹화 시작 실패: ${error.message}") } }
            }
        }
    }

    // ------------------------------------------------------------------ reports

    fun exportCapabilityReport() {
        val json = runCatching { CapabilityProbe.reportJson(controller.allCapabilities()) }.getOrNull() ?: return
        val path = sink.sidecarStore().writeTextArtifact("capabilities_${Build.MODEL}.json", json)
        _ui.update {
            it.copy(
                capabilityJson = json,
                message = path?.let { p -> "카메라 지원 정보 저장: $p" } ?: "저장 실패",
            )
        }
    }

    // ------------------------------------------------------------------- AI API

    fun toggleAutoEnhance() = _ui.update { it.copy(aiAutoEnhance = !it.aiAutoEnhance) }

    fun setMaxUpscale(factor: Int) {
        _ui.update { it.copy(aiMaxUpscale = factor.coerceIn(1, 2)) }
        enhanceQueue.setMaxUpscale(factor.coerceIn(1, 2))
    }

    fun setAiSheetVisible(visible: Boolean) = _ui.update { it.copy(showAiSheet = visible) }

    fun pauseAi() = enhanceQueue.pause()

    fun resumeAi() = enhanceQueue.resume()

    fun cancelAiJob(id: String) = enhanceQueue.cancel(id)

    fun retryAiJob(id: String) = enhanceQueue.retry(id)

    fun installModel(uri: Uri, task: ModelTask, displayName: String) {
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                modelManager.install(uri, task, displayName)
            }
            result.onSuccess { manifest ->
                val models = modelManager.installed()
                _ui.update {
                    it.copy(
                        installedModels = models,
                        message = "모델 설치됨: ${manifest.displayName} (검증 ${if (manifest.verified) "성공" else "실패"})",
                    )
                }
            }.onFailure { error ->
                _ui.update { it.copy(message = "모델 설치 실패: ${error.message}") }
            }
        }
    }

    fun removeModel(id: String) {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { modelManager.remove(id) }
            _ui.update { it.copy(installedModels = modelManager.installed()) }
        }
    }

    fun refreshAiReport() {
        viewModelScope.launch {
            val report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                BackendProbe.probe(getApplication())
            }
            enhanceQueue.setBackend(report.selected)
            _ui.update { it.copy(aiBackend = report, installedModels = modelManager.installed()) }
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    override fun onCleared() {
        // Release the sensor HandlerThread as well as camera/AI resources; otherwise recreating the
        // Activity leaves an orphaned horizon-sensor thread behind.
        runCatching { horizonController.close() }
            .onFailure { AiproLog.e("ViewModel", "horizon sensor shutdown failed", it) }
        super.onCleared()
        enhanceQueue.shutdown()
        viewModelScope.launch { controller.shutdown() }
    }
}
