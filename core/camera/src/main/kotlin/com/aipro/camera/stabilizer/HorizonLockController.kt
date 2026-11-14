package com.aipro.camera.stabilizer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.aipro.common.AiproLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Gyroscope + accelerometer horizon estimator.
 *
 * Sensor events are processed on a private thread at GAME cadence. The filter runs for every sample,
 * while the renderer receives a bounded ~60 Hz flow so a high-rate gyro cannot flood TextureView
 * property updates or starve camera callbacks. The thread is created only while the lock is active
 * and is stopped when the ViewModel is cleared.
 */
class HorizonLockController(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    // Fused 3-D orientation is preferred: integrating only gyro Z loses tracking when the
    // phone rotates around more than one device axis.
    private val gameRotationVector: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val filter = HorizonFilter()
    private val filterLock = Any()

    @Volatile private var sensorThread: HandlerThread? = null
    @Volatile private var sensorHandler: Handler? = null
    private var lastPublishedTimestampNanos = 0L

    /** Receives every fused sensor sample for timestamp-accurate video stabilization. */
    @Volatile
    var onSample: ((timestampNanos: Long, rollDegrees: Float) -> Unit)? = null

    private val rotationMatrix = FloatArray(9)

    private val _rollDegrees = MutableStateFlow(0f)
    val rollDegrees: StateFlow<Float> = _rollDegrees.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Prefer the fused game rotation vector; retain gyro+accelerometer as a fallback. */
    val isSupported: Boolean
        get() = gameRotationVector != null || (gyroscope != null && accelerometer != null)

    val unsupportedReason: String?
        get() = when {
            sensorManager == null -> "SensorManager 없음"
            gameRotationVector == null && gyroscope == null && accelerometer == null -> "회전/자이로/가속도 센서 없음"
            gameRotationVector == null && gyroscope == null -> "자이로스코프 없음"
            gameRotationVector == null && accelerometer == null -> "가속도계 없음"
            else -> null
        }

    @Synchronized
    fun start(): Boolean {
        val manager = sensorManager ?: return false
        if (!isSupported) return false
        if (_active.value) return true

        val thread = runCatching { HandlerThread("horizon-sensors").apply { start() } }
            .getOrElse {
                AiproLog.e(TAG, "센서 스레드 시작 실패", it)
                return false
            }
        val handler = Handler(thread.looper)
        sensorThread = thread
        sensorHandler = handler
        synchronized(filterLock) { filter.reset() }
        lastPublishedTimestampNanos = 0L

        // Request ~200 Hz from the fused 3-D sensor; if the HAL rejects it, fall back
        // to the previous gyro + accelerometer estimator.
        val vectorOk = gameRotationVector?.let { sensor ->
            runCatching { manager.registerListener(this, sensor, 5_000, handler) }.getOrDefault(false)
        } ?: false
        val registrationOk = if (vectorOk) {
            true
        } else {
            manager.unregisterListener(this)
            val gyroOk = gyroscope?.let { sensor ->
                runCatching {
                    manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
                }.getOrDefault(false)
            } ?: false
            val accelOk = accelerometer?.let { sensor ->
                runCatching {
                    manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
                }.getOrDefault(false)
            } ?: false
            gyroOk && accelOk
        }

        if (!registrationOk) {
            manager.unregisterListener(this)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            sensorHandler = null
            sensorThread = null
            _active.value = false
            AiproLog.e(TAG, "센서 리스너 등록 실패 rotationVector=${gameRotationVector != null}")
            return false
        }

        _active.value = true
        return true
    }

    @Synchronized
    fun stop() {
        _active.value = false
        runCatching { sensorManager?.unregisterListener(this) }
        synchronized(filterLock) { filter.reset() }
        _rollDegrees.value = 0f
        lastPublishedTimestampNanos = 0L

        sensorHandler?.removeCallbacksAndMessages(null)
        sensorHandler = null
        sensorThread?.let { runCatching { it.quitSafely() } }
        sensorThread = null
    }

    /** Explicit lifecycle cleanup; safe to call more than once. */
    fun close() = stop()

    fun renderState(): Pair<Float, Float> = synchronized(filterLock) {
        val radians = Math.toRadians(_rollDegrees.value.toDouble()).toFloat()
        radians to filter.fillZoom(radians)
    }

    fun statusLabel(): String {
        if (!isSupported) return unsupportedReason ?: "지원 안 함"
        if (!_active.value) return "꺼짐"
        val roll = _rollDegrees.value
        if (gameRotationVector != null) return "3축 수평 추적 · ${"%.1f".format(roll)}°"
        val disagreement = synchronized(filterLock) { filter.disagreement }
        return if (disagreement > 8f) "동작 감지 · 보정 완만" else "수평 ${"%.1f".format(roll)}°"
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!_active.value) return
        val roll = if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            // The rotation vector fuses gyro + gravity in 3-D. Extract gravity direction
            // in device coordinates, then calculate the camera horizon angle in the screen plane.
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val gx = rotationMatrix[6]
            val gy = rotationMatrix[7]
            if (hypot(gx.toDouble(), gy.toDouble()) < 0.05) {
                _rollDegrees.value
            } else {
                val raw = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
                ((raw + 180f) % 360f + 360f) % 360f - 180f
            }
        } else {
            synchronized(filterLock) {
                when (event.sensor.type) {
                    Sensor.TYPE_GYROSCOPE -> filter.onGyroscope(event.values[2], event.timestamp)
                    Sensor.TYPE_ACCELEROMETER -> filter.onAccelerometer(
                        event.values[0], event.values[1], event.values[2], event.timestamp,
                    )
                    else -> return
                }
            }
        }

        _rollDegrees.value = roll
        lastPublishedTimestampNanos = event.timestamp
        // Record every sensor timestamp directly; a conflated StateFlow is not precise enough
        // for fast rotations during video.
        runCatching { onSample?.invoke(event.timestamp, roll) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "HorizonLock"
    }
}
