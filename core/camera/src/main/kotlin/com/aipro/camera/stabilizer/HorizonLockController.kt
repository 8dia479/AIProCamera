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

/**
 * Experimental horizon-lock stabilisation (spec section 10: gyro/accelerometer + frame timestamps).
 *
 * Real sensor listeners feed [HorizonFilter]; the resulting roll is published as a StateFlow so the
 * preview shader and the video post job can both use the very same number. This is deliberately
 * marked experimental in the UI: it levels the *preview* and the *enhanced* video, it does not
 * re-time the sensor stream against individual encoded frames the way OEM EIS does, and the original
 * file is never modified.
 */
class HorizonLockController(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyroscope: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accelerometer: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val filter = HorizonFilter()

    /**
     * Sensor events are delivered ~50 Hz. Running that on the main looper fights with Compose
     * recomposition and made the preview look frozen, so they are handled on their own thread.
     */
    private val sensorThread = HandlerThread("horizon-sensors").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)

    private val _rollDegrees = MutableStateFlow(0f)
    val rollDegrees: StateFlow<Float> = _rollDegrees.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /** Both sensors are required; a device with only one of them must not show the toggle. */
    val isSupported: Boolean get() = gyroscope != null && accelerometer != null

    val unsupportedReason: String?
        get() = when {
            sensorManager == null -> "SensorManager 없음"
            gyroscope == null && accelerometer == null -> "자이로/가속도 센서 없음"
            gyroscope == null -> "자이로스코프 없음"
            accelerometer == null -> "가속도계 없음"
            else -> null
        }

    fun start(): Boolean {
        val manager = sensorManager ?: return false
        if (!isSupported) return false
        if (_active.value) return true
        // The gyro is the fast signal the lock depends on: sample it as fast as the sensor allows so a
        // quick twist is followed within a few milliseconds. The accelerometer only provides the slow
        // absolute reference, so GAME (~50 Hz) is plenty for it.
        val gyroOk = manager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler)
        val accelOk = manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        filter.reset()
        _active.value = gyroOk && accelOk
        if (!_active.value) AiproLog.e(TAG, "센서 리스너 등록 실패 gyro=$gyroOk accel=$accelOk")
        return _active.value
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        _rollDegrees.value = 0f
        filter.reset()
        _rollDegrees.value = 0f
        _active.value = false
    }

    /** Current roll in radians for the shader, plus the zoom needed to keep the frame filled. */
    fun renderState(): Pair<Float, Float> = filter.rollRadians() to filter.fillZoom()

    fun statusLabel(): String = when {
        !isSupported -> unsupportedReason ?: "지원 안 함"
        !_active.value -> "꺼짐"
        filter.disagreement > 8f -> "동작 감지 · 보정 완만"
        else -> "수평 ${"%.1f".format(filter.roll)}°"
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                filter.onGyroscope(event.values[0], event.timestamp)
                _rollDegrees.value = filter.roll
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val roll = filter.onAccelerometer(
                    event.values[0],
                    event.values[1],
                    event.values[2],
                    event.timestamp,
                )
                _rollDegrees.value = roll
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val TAG = "HorizonLock"
    }
}
