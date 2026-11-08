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
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val filter = HorizonFilter()
    private val filterLock = Any()

    @Volatile private var sensorThread: HandlerThread? = null
    @Volatile private var sensorHandler: Handler? = null
    private var lastPublishedTimestampNanos = 0L

    private val _rollDegrees = MutableStateFlow(0f)
    val rollDegrees: StateFlow<Float> = _rollDegrees.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Both sensors are required; a device with only one must not show the toggle. */
    val isSupported: Boolean get() = gyroscope != null && accelerometer != null

    val unsupportedReason: String?
        get() = when {
            sensorManager == null -> "SensorManager 없음"
            gyroscope == null && accelerometer == null -> "자이로/가속도 센서 없음"
            gyroscope == null -> "자이로스코프 없음"
            accelerometer == null -> "가속도계 없음"
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

        val gyroOk = runCatching {
            manager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME, handler)
        }.getOrDefault(false)
        val accelOk = runCatching {
            manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, handler)
        }.getOrDefault(false)

        if (!gyroOk || !accelOk) {
            manager.unregisterListener(this)
            handler.removeCallbacksAndMessages(null)
            thread.quitSafely()
            sensorHandler = null
            sensorThread = null
            _active.value = false
            AiproLog.e(TAG, "센서 리스너 등록 실패 gyro=$gyroOk accel=$accelOk")
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
        filter.rollRadians() to filter.fillZoom()
    }

    fun statusLabel(): String {
        if (!isSupported) return unsupportedReason ?: "지원 안 함"
        if (!_active.value) return "꺼짐"
        val (disagreement, roll) = synchronized(filterLock) {
            filter.disagreement to filter.roll
        }
        return if (disagreement > 8f) "동작 감지 · 보정 완만" else "수평 ${"%.1f".format(roll)}°"
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!_active.value) return
        val roll = synchronized(filterLock) {
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> filter.onGyroscope(event.values[2], event.timestamp)
                Sensor.TYPE_ACCELEROMETER -> filter.onAccelerometer(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    event.timestamp,
                )
                else -> return
            }
        }

        // SensorManager may deliver the gyro much faster than the display refresh rate. The filter
        // still consumes each event; only StateFlow publication is throttled.
        if (
            lastPublishedTimestampNanos == 0L ||
            event.timestamp - lastPublishedTimestampNanos >= 16_000_000L
        ) {
            lastPublishedTimestampNanos = event.timestamp
            _rollDegrees.value = roll
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "HorizonLock"
    }
}
