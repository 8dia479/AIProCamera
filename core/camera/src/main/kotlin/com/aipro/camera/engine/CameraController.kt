package com.aipro.camera.engine

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.graphics.YuvImage
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.aipro.camera.capture.AspectRatioOption
import com.aipro.camera.capture.CropRect
import com.aipro.camera.capture.RatioSizing
import com.aipro.camera.discovery.CameraCapabilities
import com.aipro.camera.discovery.CameraDescriptor
import com.aipro.camera.discovery.CapabilityProbe
import com.aipro.camera.discovery.SizeInfo
import com.aipro.camera.manual.CaptureRequestApplier
import com.aipro.camera.manual.FlashMode
import com.aipro.camera.manual.ManualSettings
import com.aipro.camera.preview.PreviewGeometry
import com.aipro.camera.preview.PreviewMath
import com.aipro.camera.preview.PreviewTransform
import com.aipro.camera.video.VideoProfileSelector
import com.aipro.common.AiproLog
import com.aipro.common.CaptureMode
import com.aipro.common.Facing
import com.aipro.common.LensType
import com.aipro.common.ShootingPurpose
import com.aipro.nativebridge.NativeMath
import com.aipro.nativebridge.NativeMultiFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Stage 1 Camera2 engine.
 *
 * Responsibilities (spec section 3, "Camera Core"): device discovery, lens switching, preview,
 * still capture (JPEG + optional RAW/DNG), video recording, manual controls and RAW support probing.
 * Everything is driven on a private HandlerThread; public API is main-safe and suspend-based.
 */
class CameraController(context: Context) {

    companion object {
        private const val TAG = "Controller"
        /**
         * Upper bound for a single-frame capture. 50 MP JPEG (8160×6120) is a special Samsung HAL
         * mode: requesting it in a normal session makes the capture time out, so the default tops out
         * at 12.5 MP and the user keeps a fast, reliable shutter.
         */
        private const val MAX_STILL_PIXELS = 12_500_000L

        private const val CAPTURE_TIMEOUT_MS = 12_000L
        private const val CONVERGENCE_TIMEOUT_MS = 2_000L

        // Storage layout from spec section 12.
        const val ORIGINAL_IMAGE_DIR = "Pictures/AIProCamera/Original"
        const val ENHANCED_IMAGE_DIR = "Pictures/AIProCamera/Enhanced"
        const val ORIGINAL_VIDEO_DIR = "Pictures/AIProCamera/Video/Original"
        const val ENHANCED_VIDEO_DIR = "Pictures/AIProCamera/Video/Enhanced"
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("AiproCamera").apply { start() }
    private val handler = Handler(thread.looper)

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _frameInfo = MutableStateFlow(FrameInfo())
    val frameInfo: StateFlow<FrameInfo> = _frameInfo.asStateFlow()

    private val _previewGeometry = MutableStateFlow<PreviewGeometry?>(null)
    val previewGeometry: StateFlow<PreviewGeometry?> = _previewGeometry.asStateFlow()

    private val _activeCamera = MutableStateFlow<ActiveCamera?>(null)
    val activeCamera: StateFlow<ActiveCamera?> = _activeCamera.asStateFlow()

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    private val capabilityCache = LinkedHashMap<String, CameraCapabilities>()

    private var cameraId: String? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var repeatingRequest: CaptureRequest? = null
    private var characteristics: CameraCharacteristics? = null

    private var previewTexture: SurfaceTexture? = null
    /** Current framing ratio for stills (video uses the same option, native sizes only). */
    @Volatile
    private var stillAspect: AspectRatioOption = AspectRatioOption.RATIO_4_3

    val aspectRatio: AspectRatioOption get() = stillAspect

    /**
     * Action mode (삼성 "액션 캠"): platform video stabilisation + optical stabilisation with a small
     * digital crop. It is real EIS/OIS requested through Camera2 — nothing is simulated.
     */
    @Volatile
    var actionMode: Boolean = false
        private set

    fun setActionMode(enabled: Boolean) {
        if (actionMode == enabled) return
        actionMode = enabled
        handler.post {
            if (device != null) recreateSession()
        }
    }

    /** Extra zoom that action mode adds so the stabiliser has room to move the crop window. */
    private fun actionCropZoom(): Float = if (actionMode) 1.15f else 1f

    /**
     * Live roll reported by the horizon lock, in degrees. Read when a still is written so the saved
     * image is leveled exactly like the preview the user framed.
     */
    @Volatile
    var horizonRollDegrees: Float = 0f
        private set

    fun setHorizonRoll(degrees: Float) {
        horizonRollDegrees = degrees
    }

    /** Capture flash mode; only meaningful when the camera reports a flash unit. */
    @Volatile
    var flashMode: FlashMode = FlashMode.OFF
        private set

    fun setFlashMode(mode: FlashMode) {
        if (flashMode == mode) return
        flashMode = mode
        // Re-apply the repeating request so the change is visible in the preview immediately.
        if (device != null) handler.post { recreateSession() }
    }

    private var previewSurface: Surface? = null

    /** False when the preview surface belongs to the real-time GL chain (it releases it itself). */
    private var previewSurfaceOwnedByEngine = true
    private var previewSize: Size = Size(1280, 720)
    private var viewSize: Size = Size(1080, 2400)

    private var readersCameraId: String? = null
    private var jpegReader: android.media.ImageReader? = null
    private var rawReader: android.media.ImageReader? = null
    private var analysisReader: android.media.ImageReader? = null
    private var multiFrameReader: android.media.ImageReader? = null
    private var multiFrameSize: Size = Size(4000, 3000)
    private var pendingYuvFrame: kotlinx.coroutines.CompletableDeferred<YuvFrame>? = null
    private var stillSize: Size = Size(4000, 3000)

    private var mode: CaptureMode = CaptureMode.PHOTO
    private var manual = ManualSettings()

    /** Aspect ratio of the session's output streams; the sensor crop must use exactly this ratio. */
    private val streamAspect: Float
        get() = when {
            mode == CaptureMode.VIDEO && recorderSize != null && recorderSize!!.width > 0 ->
                recorderSize!!.width.toFloat() / recorderSize!!.height
            previewSize.width > 0 && previewSize.height > 0 ->
                previewSize.width.toFloat() / previewSize.height
            else -> 4f / 3f
        }

    private var recorder: MediaRecorder? = null
    private var recorderTarget: SinkTarget? = null
    private var recorderSize: Size? = null
    private var recordStartedAt = 0L

    @Volatile private var lastResult: CaptureResult? = null
    private var pendingStill: PendingStill? = null

    private val closing = AtomicBoolean(false)

    /** Last view size the preview was attached with, so repeated layout passes do not rebuild. */
    private var lastAttachedViewSize: android.util.Size? = null

    /** Display rotation in degrees, pushed in by the UI so JPEG orientation is always correct. */
    @Volatile private var displayRotationDegrees = 0

    /** Surfaces that belong to the *current* capture session; requests may only target these. */
    private val sessionSurfaces = mutableSetOf<Surface>()

    // ---------------------------------------------------------------- discovery

    /** Reads every camera the device exposes. Never cached across camera service restarts. */
    fun discoverCameras(): List<CameraDescriptor> {
        val result = mutableListOf<CameraDescriptor>()
        runCatching {
            manager.cameraIdList.forEach { id ->
                val caps = capabilities(id) ?: return@forEach
                result += CapabilityProbe.descriptor(caps)
            }
        }.onFailure { AiproLog.e(TAG, "camera discovery failed", it) }
        // Ultra-wide first for rear cameras, matching how camera apps order lenses left to right.
        return result.sortedWith(
            compareBy<CameraDescriptor> { if (it.facing == Facing.BACK) 0 else 1 }
                .thenBy { it.equivalentFocalLength35mm ?: 0.0 },
        )
    }

    fun capabilities(cameraId: String): CameraCapabilities? {
        capabilityCache[cameraId]?.let { return it }
        return runCatching {
            val characteristics = manager.getCameraCharacteristics(cameraId)
            CapabilityProbe.probe(cameraId, characteristics).also { capabilityCache[cameraId] = it }
        }.getOrNull()
    }

    fun allCapabilities(): List<CameraCapabilities> = discoverCameras().mapNotNull { capabilities(it.cameraId) }

    // ------------------------------------------------------------------ preview

    /** Binds the UI's SurfaceTexture. The engine owns buffer sizing so the preview never stretches. */
    fun attachPreviewTexture(texture: SurfaceTexture, viewWidth: Int, viewHeight: Int) {
        viewSize = Size(viewWidth.coerceAtLeast(1), viewHeight.coerceAtLeast(1))
        val id = cameraId ?: run {
            previewTexture = texture
            return
        }
        val caps = capabilities(id) ?: run {
            previewTexture = texture
            return
        }
        val newPreviewSize = choosePreviewSize(caps, viewSize)
        // Rebuilding the session for every layout pass is what left the first frame black and made
        // the user change the ratio (which forces a rebuild) to see anything at all.
        val sameTarget = previewTexture === texture &&
            previewSurface != null &&
            previewSurfaceOwnedByEngine &&
            newPreviewSize == previewSize &&
            viewSize == lastAttachedViewSize
        previewTexture = texture
        if (sameTarget && session != null) {
            updateGeometry(caps)
            return
        }
        previewSize = newPreviewSize
        lastAttachedViewSize = viewSize
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        if (previewSurfaceOwnedByEngine) previewSurface?.release()
        previewSurface = Surface(texture)
        previewSurfaceOwnedByEngine = true
        updateGeometry(caps)
        if (device != null) {
            handler.post { recreateSession() }
        }
    }

    /**
     * Binds an externally owned [surface] as the preview target. Used by the real-time GL chain
     * (LivePreviewProcessor), which owns the EGL window surface; the engine only feeds it.
     */
    fun attachPreviewSurface(surface: Surface, viewWidth: Int, viewHeight: Int) {
        viewSize = Size(viewWidth.coerceAtLeast(1), viewHeight.coerceAtLeast(1))
        val id = cameraId ?: return
        val caps = capabilities(id) ?: return
        previewSize = choosePreviewSize(caps, viewSize)
        if (previewSurfaceOwnedByEngine) previewSurface?.release()
        previewSurface = surface
        previewSurfaceOwnedByEngine = false
        previewTexture = null
        updateGeometry(caps)
        if (device != null) {
            handler.post { recreateSession() }
        }
    }

    fun detachPreviewTexture() {
        if (previewSurfaceOwnedByEngine) previewSurface?.release()
        previewSurface = null
        previewSurfaceOwnedByEngine = true
        previewTexture = null
    }

    /** Detach without releasing: the GL chain still owns the surface until it is stopped. */
    fun detachPreviewSurface() {
        previewSurface = null
        previewSurfaceOwnedByEngine = true
        previewTexture = null
    }

    /**
     * Preview stream size.
     *
     * It is chosen in **sensor (landscape) orientation** and capped at ~2 MP. Matching the *view*
     * aspect instead produced square 2992×2992 / 2880×2992 streams on the Flip 6 (the HAL advertises
     * 1:1 preview sizes), which squashed the preview and, worse, ate so much bandwidth that JPEG
     * captures timed out.
     */
    private fun choosePreviewSize(caps: CameraCapabilities, view: Size): Size {
        // The stream aspect must equal the still stream aspect *and* the framing ratio, otherwise the
        // single SCALER_CROP_REGION either stretches a stream or forces a big crop. So: pick a preview
        // size whose ratio matches the still/ratio exactly, largest area first, capped at ~2.4 MP.
        val landscape = caps.previewSizes.map { size ->
            if (size.width >= size.height) size else SizeInfo(size.height, size.width)
        }
        val inRange = landscape.filter { size ->
            val area = size.width.toLong() * size.height
            area in 500_000L..3_000_000L
        }
        val target = if (stillSize.width > 0 && stillSize.height > 0) {
            stillSize.width.toDouble() / stillSize.height
        } else {
            stillAspect.ratio.toDouble()
        }
        // 1) exact ratio match (<=2%), biggest first  2) otherwise the closest ratio
        val exact = inRange.filter { size ->
            kotlin.math.abs(size.width.toDouble() / size.height - target) / target <= 0.02
        }
        val best = exact.maxByOrNull { it.width.toLong() * it.height }
            ?: inRange.minByOrNull { size ->
                kotlin.math.abs(size.width.toDouble() / size.height - target) / target
            }
            ?: SizeInfo(1280, 720)
        AiproLog.i(
            TAG,
            "preview size ${best.width}x${best.height} (aspect %.3f) for still %dx%d (aspect %.3f, ratio %s)"
                .format(
                    best.width.toDouble() / best.height,
                    stillSize.width, stillSize.height, target, stillAspect.label,
                ),
        )
        return Size(best.width, best.height)
    }

    private fun updateGeometry(caps: CameraCapabilities) {
        val geometry = PreviewTransform.compute(
            bufferWidth = previewSize.width,
            bufferHeight = previewSize.height,
            viewWidth = viewSize.width,
            viewHeight = viewSize.height,
            sensorOrientation = caps.sensorOrientation,
            displayRotationDegrees = displayRotationDegrees,
            facingFront = caps.facing == Facing.FRONT,
        )
        _previewGeometry.value = geometry
    }

    // ------------------------------------------------------------- open / close

    suspend fun openCamera(id: String) {
        if (cameraId == id && device != null) return
        closeSession()
        closeDevice()
        val caps = capabilities(id) ?: run {
            _state.value = EngineState.Error("카메라 $id 정보를 읽을 수 없습니다")
            return
        }
        characteristics = manager.getCameraCharacteristics(id)
        cameraId = id
        _state.value = EngineState.Opening(id)
        stillSize = chooseStillSize(caps)
        previewSize = choosePreviewSize(caps, viewSize)
        alignStillToPreview(caps)
        previewTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        updateGeometry(caps)
        manual = ManualSettings.auto(caps.maxDigitalZoom)
        // The TextureView usually arrives *before* the camera is opened, so the engine has to build
        // its preview surface here. Without this the first session had no preview target and the
        // screen stayed black until some unrelated layout change (a ratio switch) rebuilt it.
        previewTexture?.let { texture ->
            if (previewSurfaceOwnedByEngine) previewSurface?.release()
            previewSurface = Surface(texture)
            previewSurfaceOwnedByEngine = true
            lastAttachedViewSize = viewSize
        }
        publishActiveCamera(caps)
        val opened = runCatching { openDeviceSuspend(id) }
        opened.onFailure {
            _state.value = EngineState.Error("카메라를 열 수 없습니다: ${it.message}", it)
            AiproLog.e(TAG, "openCamera($id) failed", it)
            return
        }
        createSessionIfReady()
    }

    /**
     * A request carries exactly one `SCALER_CROP_REGION` for all of its streams, so the preview and
     * still streams must share an aspect ratio - otherwise the driver stretches one of them. If the
     * selected ratio's JPEG size does not match the preview stream, the still size follows the stream
     * and the framing ratio is applied when the JPEG is written (and by the preview clip box).
     */
    private fun alignStillToPreview(caps: CameraCapabilities) {
        if (previewSize.width <= 0 || stillSize.width <= 0) return
        val preview = previewSize.width.toFloat() / previewSize.height
        val still = stillSize.width.toFloat() / stillSize.height
        if (kotlin.math.abs(preview - still) / preview <= 0.02f) return
        val sizes = caps.jpegSizes.ifEmpty { caps.yuvSizes }
        val plan = RatioSizing.planRatio(sizes, preview, MAX_STILL_PIXELS) ?: return
        AiproLog.i(
            TAG,
            "still size aligned to preview stream: ${stillSize.width}x${stillSize.height} → " +
                "${plan.outputWidth}x${plan.outputHeight} (stream aspect %.3f)".format(preview),
        )
        stillSize = Size(plan.streamSize.width, plan.streamSize.height)
    }

    private fun chooseStillSize(caps: CameraCapabilities): Size {
        val sizes = caps.jpegSizes.ifEmpty { caps.yuvSizes }
        val plan = RatioSizing.plan(sizes, stillAspect, maxPixels = MAX_STILL_PIXELS)
        val chosen = plan?.streamSize ?: SizeInfo(4000, 3000)
        return Size(chosen.width, chosen.height)
    }

    /** Sets the framing ratio. Re-selects the JPEG stream size; the caller must reopen the session. */
    fun setAspectRatio(option: AspectRatioOption): Boolean {
        if (stillAspect == option) return false
        stillAspect = option
        val caps = cameraId?.let { capabilities(it) } ?: return true
        stillSize = chooseStillSize(caps)
        previewSize = choosePreviewSize(caps, viewSize)
        alignStillToPreview(caps)
        previewTexture?.let { attachPreviewTexture(it, viewSize.width, viewSize.height) }
        publishActiveCamera(caps)
        if (device != null && mode == CaptureMode.PHOTO) {
            handler.post { recreateSession() }
        }
        return true
    }

    /** Ratios this camera can actually shoot, per mode (no fake options in the UI). */
    fun supportedRatios(video: Boolean = false): List<AspectRatioOption> {
        val caps = cameraId?.let { capabilities(it) } ?: return emptyList()
        val options = AspectRatioOption.entries
        return if (video) {
            // Video is never cropped (that would mean re-encoding every frame), so only native ratios.
            RatioSizing.nativelySupported(caps.videoSizes, options)
        } else {
            options.filter { RatioSizing.plan(caps.jpegSizes.ifEmpty { caps.yuvSizes }, it) != null }
        }
    }

    private suspend fun openDeviceSuspend(id: String): Unit = suspendCancellableCoroutine { cont ->
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onDisconnected(camera: CameraDevice) {
                AiproLog.w(TAG, "camera $id disconnected")
                camera.close()
                device = null
                if (cont.isActive) cont.resumeWithException(IllegalStateException("camera disconnected"))
                _state.value = EngineState.Error("카메라 연결이 끊어졌습니다")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                device = null
                if (cont.isActive) cont.resumeWithException(IllegalStateException("camera error $error"))
            }
        }
        try {
            manager.openCamera(id, callback, handler)
        } catch (security: SecurityException) {
            if (cont.isActive) cont.resumeWithException(security)
        } catch (illegal: IllegalArgumentException) {
            if (cont.isActive) cont.resumeWithException(illegal)
        }
    }

    /** Switches lens while keeping the preview surface alive. */
    suspend fun selectCamera(id: String) {
        if (id == cameraId && device != null) return
        openCamera(id)
    }

    fun setMode(newMode: CaptureMode) {
        if (mode == newMode) return
        mode = newMode
        _activeCamera.value = _activeCamera.value?.copy(mode = newMode)
        handler.post { recreateSession() }
    }

    fun updateManual(settings: ManualSettings) {
        val caps = cameraId?.let { capabilities(it) } ?: return
        manual = ManualSettings.normalize(
            requested = settings,
            support = caps.manual,
            isoRange = caps.isoRange,
            exposureRangeNs = caps.exposureTimeRangeNs,
            evRange = caps.exposureCompensationRange,
            evStep = caps.exposureCompensationStepEv,
            maxZoom = caps.maxDigitalZoom,
            minimumFocusDiopters = caps.minimumFocusDistanceDiopters,
        )
        _activeCamera.value = _activeCamera.value?.copy(manual = manual)
        handler.post { applyRepeatingRequest() }
    }

    // ------------------------------------------------------------------ session

    private fun createSessionIfReady() {
        val caps = cameraId?.let { capabilities(it) } ?: return
        val characteristics = characteristics ?: return
        if (device == null || previewSurface == null) return
        runCatching { ensureReaders(caps, characteristics) }
            .onFailure {
                _state.value = EngineState.Error("이미지 리더 생성 실패: ${it.message}", it)
                return
            }
        val surfaces = mutableListOf<Surface>()
        previewSurface?.let { surfaces += it }
        jpegReader?.let { surfaces += it.surface }
        analysisReader?.let { surfaces += it.surface }
        multiFrameReader?.let { surfaces += it.surface }
        if (surfaces.isEmpty()) return
        sessionSurfaces.clear()
        sessionSurfaces.addAll(surfaces)
        val generation = ++sessionGeneration
        @Suppress("DEPRECATION")
        device?.createCaptureSession(surfaces, sessionCallbackFor(generation), handler)
    }

    private fun ensureReaders(caps: CameraCapabilities, characteristics: CameraCharacteristics) {
        // The JPEG reader *is* the capture stream: its size decides the resolution the HAL delivers.
        // It used to be reused across ratio changes, so a 16:9 plan captured a 4:3 buffer and the
        // saved file ignored the selected ratio entirely.
        val sameLens = readersCameraId == caps.cameraId &&
            jpegReader?.width == stillSize.width &&
            jpegReader?.height == stillSize.height &&
            analysisReader != null
        if (sameLens) {
            rawReader?.close()
            rawReader = createRawReader(caps, characteristics)
            return
        }
        jpegReader?.close()
        analysisReader?.close()
        jpegReader = android.media.ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.JPEG, 2)
            .also { reader -> reader.setOnImageAvailableListener({ onStillJpegAvailable() }, handler) }

        val analysisSize = pickAnalysisSize(caps)
        analysisReader = android.media.ImageReader.newInstance(
            analysisSize.width, analysisSize.height, ImageFormat.YUV_420_888, 2,
        ).also { reader -> reader.setOnImageAvailableListener({ onAnalysisFrameAvailable() }, handler) }

        multiFrameReader?.close()
        multiFrameSize = chooseMultiFrameSize(caps)
        multiFrameReader = android.media.ImageReader.newInstance(
            multiFrameSize.width, multiFrameSize.height, ImageFormat.YUV_420_888, 2,
        ).also { reader -> reader.setOnImageAvailableListener({ onMultiFrameImageAvailable() }, handler) }

        rawReader?.close()
        rawReader = createRawReader(caps, characteristics)
        readersCameraId = caps.cameraId
    }

    private fun createRawReader(caps: CameraCapabilities, characteristics: CameraCharacteristics): android.media.ImageReader? {
        if (!caps.rawSupported) return null
        val rawSize = caps.rawSizes.maxByOrNull { it.width.toLong() * it.height } ?: return null
        return android.media.ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2)
            .also { reader -> reader.setOnImageAvailableListener({ onRawImageAvailable() }, handler) }
    }

    /**
     * Burst frames are merged in memory, so they are capped at 12 MP: a 50 MP YUV burst would need
     * hundreds of megabytes of heap and stall the capture.
     */
    private fun chooseMultiFrameSize(caps: CameraCapabilities): Size {
        val candidates = caps.yuvSizes.filter { size ->
            val pixels = size.width.toLong() * size.height
            pixels <= 12_000_000L && kotlin.math.abs(size.width.toDouble() / size.height - 4.0 / 3.0) < 0.03
        }
        val best = candidates.maxByOrNull { it.width.toLong() * it.height }
            ?: caps.yuvSizes.minByOrNull { it.width.toLong() * it.height }
            ?: SizeInfo(1920, 1440)
        return Size(best.width, best.height)
    }

    private fun pickAnalysisSize(caps: CameraCapabilities): Size {
        val candidates = caps.yuvSizes
        val target = 640
        val best = candidates.minByOrNull { kotlin.math.abs(it.width - target) + kotlin.math.abs(it.height - target * 9 / 16) }
        return Size(best?.width ?: 640, best?.height ?: 480)
    }

    /** Bumped for every createCaptureSession call; stale callbacks are ignored instead of reused. */
    private var sessionGeneration = 0

    private fun sessionCallbackFor(generation: Int) = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(newSession: CameraCaptureSession) {
            if (generation != sessionGeneration || closing.get()) {
                // A newer session (or shutdown) already replaced this one; closing it here is what
                // stops the "Session has been closed" spam and the black preview.
                runCatching { newSession.close() }
                AiproLog.i(TAG, "stale session ignored (gen=$generation, current=$sessionGeneration)")
                return
            }
            session = newSession
            applyRepeatingRequest()
            if (_state.value !is EngineState.Recording) {
                _state.value = EngineState.Previewing(cameraId ?: "")
            }
            AiproLog.i(TAG, "session ready gen=$generation preview=${previewSize.width}x${previewSize.height}")
        }

        override fun onConfigureFailed(failed: CameraCaptureSession) {
            if (generation != sessionGeneration) return
            _state.value = EngineState.Error("캡처 세션 구성에 실패했습니다")
            AiproLog.e(TAG, "session configure failed gen=$generation")
        }
    }

    private fun recreateSession() {
        closeSession()
        createSessionIfReady()
    }

    private fun buildPreviewRequest(): CaptureRequest? {
        val device = device ?: return null
        val characteristics = characteristics ?: return null
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { if (it in sessionSurfaces) builder.addTarget(it) }
        analysisReader?.let { if (it.surface in sessionSurfaces) builder.addTarget(it.surface) }
        CaptureRequestApplier.apply(builder, manual, characteristics, flashMode, cropAspect = streamAspect)
        if (mode == CaptureMode.VIDEO) {
            applyVideoStabilization(builder, characteristics)
        }
        if (actionMode) {
            applyZoom(builder, manual.zoomRatio * actionCropZoom(), characteristics)
        }
        return builder.build()
    }

    private fun applyZoom(builder: CaptureRequest.Builder, zoom: Float, characteristics: CameraCharacteristics) {
        val maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val ratio = zoom.coerceIn(1f, maxZoom)
        val aspect = streamAspect
        if (com.aipro.camera.capture.CropPlanner.isFullFrame(active.width(), active.height(), aspect, ratio)) return
        // Aspect-preserving crop: a crop with a different ratio than the streams makes the driver
        // stretch the image (this is what looked like a horizontally squeezed preview).
        val window = com.aipro.camera.capture.CropPlanner.centered(active.width(), active.height(), aspect, ratio)
        builder.set(
            CaptureRequest.SCALER_CROP_REGION,
            android.graphics.Rect(
                active.left + window.left,
                active.top + window.top,
                active.left + window.left + window.width,
                active.top + window.top + window.height,
            ),
        )
    }

    private fun applyVideoStabilization(builder: CaptureRequest.Builder, characteristics: CameraCharacteristics) {
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (modes != null && modes.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
        val ois = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        if (ois != null && ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    private fun applyRepeatingRequest() {
        val session = session ?: return
        val request = buildPreviewRequest() ?: return
        repeatingRequest = request
        runCatching {
            @Suppress("DEPRECATION")
            session.setRepeatingRequest(request, repeatingCallback, handler)
        }.onFailure {
            AiproLog.e(TAG, "setRepeatingRequest failed", it)
            _state.value = EngineState.Error("프리뷰 요청 실패: ${it.message}", it)
        }
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            lastResult = result
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            val previous = _frameInfo.value
            _frameInfo.value = previous.copy(
                iso = iso,
                exposureTimeNs = exposure,
                focusDistanceDiopters = focus,
                awbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
                afState = result.get(CaptureResult.CONTROL_AF_STATE),
                aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                zoomRatio = manual.zoomRatio,
            )
        }
    }

    // -------------------------------------------------------------- still capture

    suspend fun captureStill(
        sink: MediaSink,
        request: StillCaptureRequest = StillCaptureRequest(),
    ): Result<CaptureOutcome> {
        val caps = cameraId?.let { capabilities(it) } ?: return Result.failure(IllegalStateException("카메라가 열려 있지 않습니다"))
        val session = session ?: return Result.failure(IllegalStateException("프리뷰 세션이 준비되지 않았습니다"))
        if (mode != CaptureMode.PHOTO) return Result.failure(IllegalStateException("사진 모드가 아닙니다"))
        if (request.format != StillFormat.JPEG) {
            return Result.failure(UnsupportedOperationException("${request.format} 출력은 아직 구현되지 않았습니다 (JPEG만 지원)"))
        }
        val characteristics = characteristics ?: return Result.failure(IllegalStateException("카메라 특성 없음"))
        val startAt = System.currentTimeMillis()

        val imageTarget = runCatching {
            sink.createImageTarget("image/jpeg", ORIGINAL_IMAGE_DIR, "jpg")
        }.getOrElse { return Result.failure(it) }

        val rawTarget = if (request.includeRaw && caps.rawSupported && rawReader != null) {
            runCatching { sink.createImageTarget("image/x-adobe-dng", ORIGINAL_IMAGE_DIR, "dng") }.getOrNull()
        } else {
            null
        }

        drainPendingFrames(jpegReader)
        _state.value = EngineState.Capturing(cameraId ?: "")
        val deferred = CompletableDeferred<Result<StillMeta>>()
        val stillPlan = RatioSizing.plan(
            caps.jpegSizes.ifEmpty { caps.yuvSizes },
            stillAspect,
            maxPixels = MAX_STILL_PIXELS,
        )
        pendingStill = PendingStill(
            target = imageTarget,
            rawTarget = rawTarget,
            deferred = deferred,
            crop = stillPlan?.cropRect,
        )

        try {
            if (manual.focusSlider01 == null) {
                triggerPrecapture(session)
                awaitConvergence()
            }
            val builder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            jpegReader?.let { builder.addTarget(it.surface) }
            if (rawTarget != null) rawReader?.let { builder.addTarget(it.surface) }
            CaptureRequestApplier.apply(builder, manual, characteristics, flashMode, cropAspect = streamAspect)
            builder.set(
                CaptureRequest.JPEG_ORIENTATION,
                PreviewTransform.jpegOrientation(
                    caps.sensorOrientation,
                    currentDisplayRotationDegrees(),
                    caps.facing == Facing.FRONT,
                ),
            )
            builder.set(CaptureRequest.CONTROL_AF_MODE, afModeForCapture(characteristics))
            session.capture(builder.build(), stillCallback, handler)

            val meta = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { deferred.await() }
                ?: Result.failure(IllegalStateException("촬영 시간 초과"))
            return meta.map { info ->
                CaptureOutcome(
                    primaryUri = imageTarget.uri,
                    primaryPath = imageTarget.relativePath,
                    rawUri = rawTarget?.uri,
                    width = info.width,
                    height = info.height,
                    iso = info.iso,
                    exposureTimeNs = info.exposureTimeNs,
                    processingMillis = System.currentTimeMillis() - startAt,
                    format = "JPEG",
                    lens = caps.lensType,
                    facing = caps.facing,
                    capturedFrames = if (rawTarget != null) 2 else 1,
                )
            }
        } catch (t: Throwable) {
            pendingStill = null
            runCatching { imageTarget.close() }
            runCatching { sink.delete(imageTarget.uri) }
            rawTarget?.let {
                runCatching { it.close() }
                runCatching { sink.delete(it.uri) }
            }
            _state.value = EngineState.Previewing(cameraId ?: "")
            return Result.failure(t)
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            lastResult = result
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: android.hardware.camera2.CaptureFailure,
        ) {
            pendingStill?.deferred?.complete(Result.failure(IllegalStateException("촬영 요청 실패 (reason=${failure.reason})")))
            pendingStill = null
            _state.value = EngineState.Previewing(cameraId ?: "")
        }
    }

    private fun afModeForCapture(characteristics: CameraCharacteristics): Int {
        if (manual.focusSlider01 != null) return CameraMetadata.CONTROL_AF_MODE_OFF
        val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return CameraMetadata.CONTROL_AF_MODE_AUTO
        return if (modes.contains(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        } else {
            CameraMetadata.CONTROL_AF_MODE_AUTO
        }
    }

    private suspend fun triggerPrecapture(session: CameraCaptureSession) {
        val device = device ?: return
        val characteristics = characteristics ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { if (it in sessionSurfaces) builder.addTarget(it) }
        analysisReader?.let { if (it.surface in sessionSurfaces) builder.addTarget(it.surface) }
        CaptureRequestApplier.apply(builder, manual, characteristics, flashMode, cropAspect = streamAspect)
        if (manual.iso == null && manual.exposureTimeNs == null) {
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        runCatching {
            @Suppress("DEPRECATION")
            session.setRepeatingRequest(builder.build(), repeatingCallback, handler)
        }
    }

    /** Waits for AF/AE to settle instead of firing blind - the difference between a sharp and a soft frame. */
    private suspend fun awaitConvergence() {
        withTimeoutOrNull(CONVERGENCE_TIMEOUT_MS) {
            while (true) {
                val result = lastResult
                val af = result?.get(CaptureResult.CONTROL_AF_STATE)
                val ae = result?.get(CaptureResult.CONTROL_AE_STATE)
                val afReady = af == null ||
                    af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                    af == CaptureResult.CONTROL_AF_STATE_INACTIVE
                val aeReady = ae == null ||
                    ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                    ae == CaptureResult.CONTROL_AE_STATE_LOCKED ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                if (afReady && aeReady) return@withTimeoutOrNull
                delay(30)
            }
        }
    }

    /** Removes frames an aborted capture left in the buffer queue so they cannot shadow a new one. */
    private fun drainPendingFrames(reader: android.media.ImageReader?) {
        if (reader == null) return
        while (true) {
            val stale = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
            runCatching { stale.close() }
        }
    }

    private fun onStillJpegAvailable() {
        val reader = jpegReader ?: return
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val pending = pendingStill
        if (pending == null) {
            // A frame from an earlier (aborted) capture. Discard it, but never touch the state of a
            // request that is still in flight - doing so used to drop the deferred and time out.
            image.close()
            return
        }
        runCatching {
            val buffer = image.planes[0].buffer
            val rawBytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val crop = pending?.crop
            val levelRoll = normalizeRoll(horizonRollDegrees)
            val bytes = if (crop != null || levelRoll != 0f) {
                renderJpeg(rawBytes, stillAspect.ratio, levelRoll) ?: rawBytes
            } else {
                rawBytes
            }
            pending?.target?.let { target ->
                target.outputStream.use { it.write(bytes) }
                target.close()
            }
            val result = lastResult
            val rendered = if (bytes !== rawBytes) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                bounds.outWidth to bounds.outHeight
            } else {
                image.width to image.height
            }
            AiproLog.i(
                TAG,
                "still ${if (crop != null) "cropped" else "native"} ratio=${stillAspect.label} " +
                    "buffer=${image.width}x${image.height} out=${rendered.first}x${rendered.second} bytes=${bytes.size}",
            )
            val info = StillMeta(
                width = rendered.first,
                height = rendered.second,
                iso = result?.get(CaptureResult.SENSOR_SENSITIVITY),
                exposureTimeNs = result?.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                byteCount = bytes.size,
            )
            pending?.deferred?.complete(Result.success(info))
        }.onFailure { error ->
            AiproLog.e(TAG, "JPEG write failed", error)
            runCatching { pending?.target?.close() }
            pending?.deferred?.complete(Result.failure(error))
        }
        image.close()
        if (pendingStill === pending) pendingStill = null
        if (_stateIsCapturing()) _state.value = EngineState.Previewing(cameraId ?: "")
    }

    private fun onRawImageAvailable() {
        val reader = rawReader ?: return
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val target = pendingStill?.rawTarget
        val characteristics = characteristics
        val result = lastResult
        if (target == null || characteristics == null || result == null) {
            image.close()
            return
        }
        runCatching {
            val dng = DngCreator(characteristics, result)
            target.outputStream.use { stream -> dng.writeImage(stream, image) }
            target.close()
            dng.close()
            AiproLog.i(TAG, "RAW saved: ${target.uri}")
        }.onFailure {
            AiproLog.e(TAG, "RAW write failed", it)
            runCatching { target.close() }
        }
        image.close()
    }

    private fun onAnalysisFrameAvailable() {
        val reader = analysisReader ?: return
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        runCatching {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val histogram = NativeMath.lumaHistogram(buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            val mean = NativeMath.meanLuma(buffer, image.width, image.height, plane.rowStride, plane.pixelStride)
            if (histogram != null) {
                _frameInfo.value = _frameInfo.value.copy(histogram = histogram, meanLuma = mean)
            }
        }
        image.close()
    }

    private fun _stateIsCapturing(): Boolean = _state.value is EngineState.Capturing

    // ------------------------------------------------------- multi-frame capture

    /**
     * Burst capture for night mode / HDR (stage 4).
     *
     * Frames are captured as YUV_420_888, aligned with a coarse block-matching search and merged on
     * the luma plane. The chroma planes are averaged, then the result is encoded to JPEG. Alignment
     * plus motion weighting means a moving subject is never averaged into a ghost.
     */
    suspend fun captureMultiFrame(
        frames: Int = 5,
        mergeMode: MultiFrameMode = MultiFrameMode.NIGHT_STACK,
        exposureBracketEv: Float = 2.0f,
    ): Result<MultiFrameCapture> {
        val caps = cameraId?.let { capabilities(it) } ?: return Result.failure(IllegalStateException("카메라가 열려 있지 않습니다"))
        val characteristics = characteristics ?: return Result.failure(IllegalStateException("카메라 특성 없음"))
        if (mode != CaptureMode.PHOTO) return Result.failure(IllegalStateException("사진 모드가 아닙니다"))
        if (multiFrameReader == null) {
            createSessionIfReady()
            if (multiFrameReader == null) return Result.failure(IllegalStateException("멀티프레임 리더를 만들 수 없습니다"))
        }
        val session = session ?: return Result.failure(IllegalStateException("프리뷰 세션이 준비되지 않았습니다"))
        if (!NativeMultiFrame.isAvailable) return Result.failure(IllegalStateException("네이티브 병합 모듈을 사용할 수 없습니다"))

        val frameCount = frames.coerceIn(2, 9)
        val capturedY = ArrayList<ByteArray>(frameCount)
        var capturedU = ByteArray(0)
        var capturedV = ByteArray(0)
        var chromaWidth = 0
        var chromaHeight = 0
        var yStride = 0
        var uStride = 0
        var vStride = 0
        var uPixelStride = 1
        var vPixelStride = 1
        var width = multiFrameSize.width
        var height = multiFrameSize.height
        averagedChromaSamples = 1

        _state.value = EngineState.Capturing(cameraId ?: "")
        try {
            if (manual.focusSlider01 == null) {
                triggerPrecapture(session)
                awaitConvergence()
            }
            for (index in 0 until frameCount) {
                val deferred = kotlinx.coroutines.CompletableDeferred<YuvFrame>()
                pendingYuvFrame = deferred
                val builder = device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                multiFrameReader?.let { builder.addTarget(it.surface) }
                CaptureRequestApplier.apply(builder, manual, characteristics, flashMode, cropAspect = streamAspect)
                if (mergeMode == MultiFrameMode.HDR_FUSION && manual.iso == null && manual.exposureTimeNs == null) {
                    // Bracketing needs AE in charge; alternate under/over exposure around the metered value.
                    val compensationSteps = intArrayOf(
                        -Math.round(exposureBracketEv), 0, Math.round(exposureBracketEv),
                    )
                    val ev = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                    val step = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    val stepValue = if (step == null || step.denominator == 0) 0.1f
                    else step.numerator.toFloat() / step.denominator
                    val desiredEv = exposureBracketEv * (if (index % 3 == 0) -1f else if (index % 3 == 1) 0f else 1f)
                    val steps = if (stepValue > 0f) Math.round(desiredEv / stepValue) else compensationSteps[0]
                    if (ev != null) {
                        builder.set(
                            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                            steps.coerceIn(ev.lower, ev.upper),
                        )
                    }
                }
                session.capture(builder.build(), null, handler)
                val frame = withTimeoutOrNull(4_000L) { deferred.await() }
                    ?: return Result.failure(IllegalStateException("프레임 ${index + 1} 캡처 시간 초과"))
                width = frame.width
                height = frame.height
                yStride = frame.yRowStride
                uStride = frame.uRowStride
                vStride = frame.vRowStride
                uPixelStride = frame.uPixelStride
                vPixelStride = frame.vPixelStride
                chromaWidth = frame.chromaWidth
                chromaHeight = frame.chromaHeight
                capturedY += frame.y
                if (capturedU.isEmpty()) {
                    capturedU = frame.u
                    capturedV = frame.v
                } else {
                    averageInto(capturedU, frame.u)
                    averageInto(capturedV, frame.v)
                }
                if (mergeMode == MultiFrameMode.HDR_FUSION) kotlinx.coroutines.delay(180)
            }

            val mergeStart = System.currentTimeMillis()
            val mergedY = ByteArray(capturedY[0].size)
            val nativeMode = if (mergeMode == MultiFrameMode.HDR_FUSION) NativeMultiFrame.Mode.EXPOSURE_FUSION
            else NativeMultiFrame.Mode.NIGHT_STACK
            val stats = NativeMultiFrame.mergeFrames(
                frames = capturedY.toTypedArray(),
                width = width,
                height = height,
                stride = yStride,
                out = mergedY,
                mode = nativeMode,
                motionThreshold = if (mergeMode == MultiFrameMode.HDR_FUSION) 26f else 18f,
                maxFrames = frameCount,
            ) ?: return Result.failure(IllegalStateException("프레임 병합에 실패했습니다"))

            val nv21Size = width * height * 3 / 2
            val nv21 = ByteArray(nv21Size)
            val converted = NativeMultiFrame.yuv420ToNv21(
                y = mergedY, u = capturedU, v = capturedV,
                width = width, height = height,
                yStride = yStride, uStride = uStride, vStride = vStride,
                uPixelStride = uPixelStride, vPixelStride = vPixelStride,
                out = nv21,
            )
            if (!converted) return Result.failure(IllegalStateException("NV21 변환에 실패했습니다"))

            val jpegOut = ByteArrayOutputStream()
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val compressed = yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 98, jpegOut)
            if (!compressed) return Result.failure(IllegalStateException("JPEG 인코딩에 실패했습니다"))

            // Same framing rule as single shots: crop to the selected ratio when the sensor has no
            // native size for it (multi-frame always merges a 4:3 YUV stream).
            val mergedCrop = RatioSizing.centeredCrop(SizeInfo(width, height), stillAspect.ratio)
            val mergedRoll = normalizeRoll(horizonRollDegrees)
            val mergedBytes = if (mergedCrop != null || mergedRoll != 0f) {
                renderJpeg(jpegOut.toByteArray(), stillAspect.ratio, mergedRoll) ?: jpegOut.toByteArray()
            } else {
                jpegOut.toByteArray()
            }

            AiproLog.i(TAG, "multi-frame ${stats.framesUsed}f mode=$mergeMode noiseRatio=${"%.2f".format(stats.noiseRatio)} chroma=${chromaWidth}x$chromaHeight")
            return Result.success(
                MultiFrameCapture(
                    jpegBytes = mergedBytes,
                    width = mergedCrop?.width ?: width,
                    height = mergedCrop?.height ?: height,
                    framesRequested = frameCount,
                    framesUsed = stats.framesUsed,
                    mode = mergeMode,
                    motionRatio = stats.motionRatio,
                    noiseRatio = stats.noiseRatio,
                    meanShiftPx = stats.meanShiftPx,
                    mergeMillis = System.currentTimeMillis() - mergeStart,
                    orientationDegrees = PreviewTransform.jpegOrientation(
                        caps.sensorOrientation,
                        currentDisplayRotationDegrees(),
                        caps.facing == Facing.FRONT,
                    ),
                ),
            )
        } catch (t: Throwable) {
            AiproLog.e(TAG, "multi-frame capture failed", t)
            return Result.failure(t)
        } finally {
            pendingYuvFrame = null
            if (_state.value is EngineState.Capturing) _state.value = EngineState.Previewing(cameraId ?: "")
        }
    }

    /** Running average so averaged chroma never accumulates rounding drift. */
    private fun averageInto(target: ByteArray, incoming: ByteArray) {
        // Frame-averaging in-place would lose precision across many frames, so a simple running
        // mean on the stored buffer is used (each call adds one sample).
        averagedChromaSamples += 1
        val n = averagedChromaSamples
        val limit = minOf(target.size, incoming.size)
        for (i in 0 until limit) {
            val current = target[i].toInt() and 0xFF
            val next = incoming[i].toInt() and 0xFF
            target[i] = (((current * (n - 1)) + next) / n).toByte()
        }
    }

    private var averagedChromaSamples = 1

    private fun onMultiFrameImageAvailable() {
        val reader = multiFrameReader ?: return
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val pending = pendingYuvFrame
        if (pending == null) {
            image.close()
            return
        }
        runCatching {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val y = ByteArray(yPlane.buffer.remaining()).also { yPlane.buffer.get(it) }
            val u = ByteArray(uPlane.buffer.remaining()).also { uPlane.buffer.get(it) }
            val v = ByteArray(vPlane.buffer.remaining()).also { vPlane.buffer.get(it) }
            pending.complete(
                YuvFrame(
                    y = y, u = u, v = v,
                    width = image.width, height = image.height,
                    yRowStride = yPlane.rowStride, uRowStride = uPlane.rowStride, vRowStride = vPlane.rowStride,
                    uPixelStride = uPlane.pixelStride, vPixelStride = vPlane.pixelStride,
                    chromaWidth = image.width / 2, chromaHeight = image.height / 2,
                ),
            )
        }.onFailure { pending.completeExceptionally(it) }
        image.close()
    }

    private data class YuvFrame(
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
        val width: Int,
        val height: Int,
        val yRowStride: Int,
        val uRowStride: Int,
        val vRowStride: Int,
        val uPixelStride: Int,
        val vPixelStride: Int,
        val chromaWidth: Int,
        val chromaHeight: Int,
    )

    // --------------------------------------------------------------------- video

    suspend fun startRecording(sink: MediaSink, withAudio: Boolean): Result<Long> {
        val caps = cameraId?.let { capabilities(it) } ?: return Result.failure(IllegalStateException("카메라가 열려 있지 않습니다"))
        val characteristics = characteristics ?: return Result.failure(IllegalStateException("카메라 특성 없음"))
        if (device == null) return Result.failure(IllegalStateException("카메라가 준비되지 않았습니다"))
        val selection = VideoProfileSelector.select(caps.videoSizes, aspect = stillAspect)
            ?: return Result.failure(IllegalStateException("이 카메라는 MediaRecorder 출력 크기를 보고하지 않습니다"))

        val target = runCatching { sink.createVideoTarget("video/mp4", ORIGINAL_VIDEO_DIR, "mp4") }
            .getOrElse { return Result.failure(it) }

        // MediaRecorder is a strict state machine:
        // sources -> output format -> output file -> codecs -> prepare().
        // Calling setAudioEncoder() before setOutputFormat() throws
        // "setAudioEncoder called in an invalid state(2)" and no file is ever written.
        val prepared = runCatching {
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else @Suppress("DEPRECATION") MediaRecorder()
            if (withAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setOutputFile(target.fileDescriptor)
            mediaRecorder.setVideoSize(selection.size.width, selection.size.height)
            mediaRecorder.setVideoFrameRate(selection.fps)
            mediaRecorder.setVideoEncodingBitRate(VideoProfileSelector.estimateBitrate(selection.size, selection.fps))
            mediaRecorder.setVideoEncoder(preferredVideoEncoder())
            if (withAudio) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mediaRecorder.setAudioEncodingBitRate(192_000)
                mediaRecorder.setAudioSamplingRate(48_000)
                mediaRecorder.setAudioChannels(2)
            }
            mediaRecorder.setOrientationHint(
                PreviewTransform.jpegOrientation(caps.sensorOrientation, currentDisplayRotationDegrees(), caps.facing == Facing.FRONT),
            )
            mediaRecorder.prepare()
            mediaRecorder
        }
        prepared.onFailure { error ->
            runCatching { target.close() }
            runCatching { sink.delete(target.uri) }
            AiproLog.e(TAG, "recorder prepare failed size=${selection.size.width}x${selection.size.height} audio=$withAudio", error)
            return Result.failure(
                IllegalStateException("녹화 준비 실패: ${error.javaClass.simpleName}: ${error.message ?: "원인 미상"}", error),
            )
        }
        recorder = prepared.getOrThrow()
        recorderTarget = target
        recorderSize = Size(selection.size.width, selection.size.height)
        mode = CaptureMode.VIDEO
        recreateSessionForRecording()
        return withContext(Dispatchers.Main.immediate) {
            runCatching {
                recorder?.start()
                applyRepeatingRequest()
                recordStartedAt = System.currentTimeMillis()
                _state.value = EngineState.Recording(cameraId ?: "", recordStartedAt)
                recordStartedAt
            }.onFailure {
                AiproLog.e(TAG, "recorder start failed", it)
                releaseRecorder(deleteFile = true)
                runCatching { sink.delete(target.uri) }
                _state.value = EngineState.Error("녹화 시작 실패: ${it.message}", it)
            }
        }
    }

    private suspend fun recreateSessionForRecording() {
        closeSession()
        val surfaces = mutableListOf<Surface>()
        previewSurface?.let { surfaces += it }
        recorder?.let { surfaces += it.surface }
        val device = device ?: return
        sessionSurfaces.clear()
        sessionSurfaces.addAll(surfaces)
        suspendCancellableCoroutine<Unit> { cont ->
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(newSession: CameraCaptureSession) {
                    session = newSession
                    // A MediaRecorder surface rejects frames until start(); the repeating request is
                    // pushed right after recorder.start() instead of here.
                    if (recorder == null) applyRepeatingRequest()
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onConfigureFailed(failedSession: CameraCaptureSession) {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("녹화 세션 구성 실패"))
                }
            }, handler)
        }
    }

    suspend fun stopRecording(sink: MediaSink): Result<VideoOutcome> {
        val target = recorderTarget ?: return Result.failure(IllegalStateException("녹화 중이 아닙니다"))
        val size = recorderSize ?: Size(1920, 1080)
        val duration = if (recordStartedAt > 0) System.currentTimeMillis() - recordStartedAt else 0L
        val stopResult = runCatching { recorder?.stop() }
        releaseRecorder(deleteFile = false)
        recordStartedAt = 0L
        mode = CaptureMode.PHOTO
        recreateSession()
        stopResult.onFailure { error ->
            AiproLog.e(TAG, "recorder stop failed", error)
            runCatching { sink.delete(target.uri) }
            _state.value = EngineState.Error("녹화 종료 실패: ${error.message}", error)
            return Result.failure(error)
        }
        _state.value = EngineState.Previewing(cameraId ?: "")
        return Result.success(
            VideoOutcome(
                uri = target.uri,
                path = target.relativePath,
                durationMs = duration,
                sizeBytes = runCatching { sink.fileSize(target.uri) }.getOrDefault(0L),
                width = size.width,
                height = size.height,
                codec = if (preferredVideoEncoder() == MediaRecorder.VideoEncoder.HEVC) "HEVC" else "H.264",
            ),
        )
    }

    private fun releaseRecorder(deleteFile: Boolean) {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        recorderSize = null
        // The MediaStore entry is closed either way; the caller deletes it when the take failed.
        recorderTarget?.let { runCatching { it.close() } }
        recorderTarget = null
        if (deleteFile) AiproLog.w(TAG, "recording discarded")
    }

    private fun preferredVideoEncoder(): Int {
        val hevcSupported = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { type -> type.equals("video/hevc", ignoreCase = true) }
        }
        return if (hevcSupported) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264
    }

    // -------------------------------------------------------------------- teardown

    private fun closeSession() {
        sessionSurfaces.clear()
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        repeatingRequest = null
    }

    private fun closeDevice() {
        runCatching { device?.close() }
        device = null
        jpegReader?.close(); jpegReader = null
        rawReader?.close(); rawReader = null
        analysisReader?.close(); analysisReader = null
        multiFrameReader?.close(); multiFrameReader = null
    }

    suspend fun shutdown() {
        if (!closing.compareAndSet(false, true)) return
        withContext(Dispatchers.Main.immediate) {
            closeSession()
            releaseRecorder(deleteFile = false)
            closeDevice()
            detachPreviewTexture()
            _state.value = EngineState.Closed
            thread.quitSafely()
        }
    }

    private fun publishActiveCamera(caps: CameraCapabilities) {
        _activeCamera.value = ActiveCamera(
            descriptor = CapabilityProbe.descriptor(caps),
            capabilities = caps,
            previewSize = SizeInfo(previewSize.width, previewSize.height),
            stillSize = SizeInfo(stillSize.width, stillSize.height),
            mode = mode,
            manual = manual,
        )
    }

    fun setDisplayRotationDegrees(degrees: Int) {
        displayRotationDegrees = ((degrees % 360) + 360) % 360
        _activeCamera.value?.let { active ->
            _previewGeometry.value = PreviewTransform.compute(
                bufferWidth = previewSize.width,
                bufferHeight = previewSize.height,
                viewWidth = viewSize.width,
                viewHeight = viewSize.height,
                sensorOrientation = active.capabilities.sensorOrientation,
                displayRotationDegrees = displayRotationDegrees,
                facingFront = active.capabilities.facing == Facing.FRONT,
            )
        }
    }

    private fun currentDisplayRotationDegrees(): Int = displayRotationDegrees

    private data class PendingStill(
        val target: SinkTarget,
        val rawTarget: SinkTarget?,
        val deferred: CompletableDeferred<Result<StillMeta>>,
        val crop: CropRect? = null,
    )

    /** Rolls below this are sensor noise, not a real tilt; re-encoding for them would be wasteful. */
    private fun normalizeRoll(degrees: Float): Float {
        if (degrees.isNaN()) return 0f
        val value = ((degrees % 360f) + 360f) % 360f
        val signed = if (value > 180f) value - 360f else value
        return if (abs(signed) < 0.15f) 0f else signed
    }

    /** EXIF orientation of a JPEG held in memory (0 if absent/unreadable). */
    private fun exifOrientation(bytes: ByteArray): Int = runCatching {
        android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION,
            android.media.ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(android.media.ExifInterface.ORIENTATION_NORMAL)

    /**
     * Rotation the HAL stored *only as an EXIF tag* instead of rotating the pixels. Samsung leaves
     * the buffer in sensor orientation, so this is almost always 90 on the Z Flip 6.
     */
    private fun exifRotationDegrees(orientation: Int): Int = when (orientation) {
        android.media.ExifInterface.ORIENTATION_ROTATE_90,
        android.media.ExifInterface.ORIENTATION_TRANSPOSE,
        -> 90
        android.media.ExifInterface.ORIENTATION_ROTATE_180,
        android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL,
        -> 180
        android.media.ExifInterface.ORIENTATION_ROTATE_270,
        android.media.ExifInterface.ORIENTATION_TRANSVERSE,
        -> 270
        else -> 0
    }

    /**
     * Crops the captured JPEG to [aspect] (landscape-defined) and/or levels it by [rollDegrees],
     * then re-encodes at quality 98 — one decode and one encode for both operations.
     *
     * Two things matter here:
     *  * The HAL usually writes sensor-orientation pixels plus an EXIF rotation tag. `BitmapFactory`
     *    ignores EXIF, so re-encoding without handling it produced a landscape file with no tag at
     *    all — the "portrait phone, 90° rotated photo" bug. The rotation is therefore baked into the
     *    pixels and the output carries no orientation tag.
     *  * Leveling rotates the pixels, so the empty corners are removed by keeping the largest
     *    inscribed rectangle of the requested aspect (see [PreviewMath]) — the same maths the preview
     *    uses, so what is framed is what is saved.
     *
     * Returns null on failure so the caller can fall back to the untouched bytes instead of writing
     * a broken file.
     */
    private fun renderJpeg(
        bytes: ByteArray,
        aspect: Float,
        rollDegrees: Float,
        quality: Int = 98,
    ): ByteArray? = runCatching {
        val options = BitmapFactory.Options()
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val orientationRotation = exifRotationDegrees(exifOrientation(bytes))
        val pixelsLandscape = source.width >= source.height
        // The requested ratio is defined in the landscape frame; in a portrait pixel buffer the same
        // framing is its reciprocal.
        val cropAspect = when {
            aspect <= 0.05f -> source.width.toFloat() / source.height
            pixelsLandscape -> aspect
            else -> 1f / aspect
        }
        val totalRotation = orientationRotation - rollDegrees
        val (cropWidth, cropHeight) = PreviewMath.largestInscribedAspectRect(
            source.width,
            source.height,
            cropAspect,
            totalRotation,
        )
        val swapped = orientationRotation == 90 || orientationRotation == 270
        val outWidth = if (swapped) cropHeight else cropWidth
        val outHeight = if (swapped) cropWidth else cropHeight
        val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        val fill = PreviewMath.levelFillScale(source.width, source.height, cropWidth, cropHeight, totalRotation)
        val matrix = Matrix()
        matrix.postTranslate(-source.width / 2f, -source.height / 2f)
        matrix.postScale(fill, fill)
        matrix.postRotate(totalRotation.toFloat())
        matrix.postTranslate(outWidth / 2f, outHeight / 2f)
        canvas.drawBitmap(source, matrix, paint)
        val out = java.io.ByteArrayOutputStream(outWidth * outHeight / 4)
        output.compress(Bitmap.CompressFormat.JPEG, quality, out)
        AiproLog.i(
            TAG,
            "render ${source.width}x${source.height} → ${outWidth}x${outHeight} " +
                "(ratio=$aspect exif=${orientationRotation}° roll=${"%.1f".format(rollDegrees)}°)",
        )
        source.recycle()
        output.recycle()
        out.toByteArray()
    }.onFailure { AiproLog.e(TAG, "ratio/level render failed", it) }.getOrNull()

    private data class StillMeta(
        val width: Int,
        val height: Int,
        val iso: Int?,
        val exposureTimeNs: Long?,
        val byteCount: Int,
    )
}
