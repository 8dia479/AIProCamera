package com.aipro.app.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Physical posture of the fold, derived from Jetpack WindowManager (no Samsung-private API). */
enum class FoldPosture {
    /** Flat / unknown: treat as a normal phone. */
    FLAT,
    /** Tabletop (hinge horizontal): preview on top, controls underneath - "Flex Mode". */
    HORIZONTAL_HALF_OPENED,
    /** Book posture (hinge vertical). */
    VERTICAL_HALF_OPENED,
}

data class FoldInfo(
    val posture: FoldPosture = FoldPosture.FLAT,
    /** Continuous hinge angle in degrees when the device reports Sensor.TYPE_HINGE_ANGLE. */
    val hingeDegrees: Int? = null,
    val hingeAngleSupported: Boolean = false,
)

/**
 * Observes WindowManager layout info and the hinge sensor. Everything is optional: on a non-foldable
 * device the posture stays FLAT and no hinge UI is shown.
 */
@Composable
fun rememberFoldInfo(): FoldInfo {
    val context = LocalContext.current
    var posture by remember { mutableStateOf(FoldPosture.FLAT) }
    var hingeDegrees by remember { mutableStateOf<Int?>(null) }
    var angleSupported by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val tracker = WindowInfoTracker.getOrCreate(context)
        val activity = context.findActivity()
        val scope = MainScope()
        val job = activity?.let { act ->
            scope.launch {
                tracker.windowLayoutInfo(act).collect { info: WindowLayoutInfo ->
                    posture = info.toPosture()
                }
            }
        }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val hingeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        angleSupported = hingeSensor != null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                hingeDegrees = event.values.firstOrNull()?.toInt()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensorManager != null && hingeSensor != null) {
            sensorManager.registerListener(listener, hingeSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        onDispose {
            job?.cancel()
            if (sensorManager != null && hingeSensor != null) sensorManager.unregisterListener(listener)
            scope.cancel()
        }
    }
    return FoldInfo(posture = posture, hingeDegrees = hingeDegrees, hingeAngleSupported = angleSupported)
}

private fun WindowLayoutInfo.toPosture(): FoldPosture {
    val feature = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull() ?: return FoldPosture.FLAT
    if (feature.state != FoldingFeature.State.HALF_OPENED) return FoldPosture.FLAT
    return when (feature.orientation) {
        FoldingFeature.Orientation.HORIZONTAL -> FoldPosture.HORIZONTAL_HALF_OPENED
        FoldingFeature.Orientation.VERTICAL -> FoldPosture.VERTICAL_HALF_OPENED
        else -> FoldPosture.FLAT
    }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
