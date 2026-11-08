package com.aipro.ai.performance

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Thermal / battery policy for heavy AI work (spec section 13).
 *
 * * Thermally throttled or above `THERMAL_STATUS_MODERATE` → only light processing is allowed.
 * * Battery below 15 % and not charging → pause heavy jobs instead of draining the phone.
 * Both signals come from the platform, so the decision is real rather than guessed.
 */
class PerformanceGovernor(private val context: Context) {

    enum class Level { FULL, REDUCED, PAUSED }

    fun level(): Level {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermal = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) return Level.PAUSED
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) return Level.REDUCED
        val battery = batteryState()
        if (!battery.charging && battery.percent in 0..14) return Level.PAUSED
        if (!battery.charging && battery.percent in 15..29) return Level.REDUCED
        return Level.FULL
    }

    fun thermalStatus(): Int =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus
            ?: PowerManager.THERMAL_STATUS_NONE

    fun thermalLabel(): String = when (thermalStatus()) {
        PowerManager.THERMAL_STATUS_NONE -> "정상"
        PowerManager.THERMAL_STATUS_LIGHT -> "약간 따뜻함"
        PowerManager.THERMAL_STATUS_MODERATE -> "발열 주의"
        PowerManager.THERMAL_STATUS_SEVERE -> "발열 심각"
        PowerManager.THERMAL_STATUS_CRITICAL -> "발열 위험"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "발열 비상"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "종료 단계"
        else -> "알 수 없음"
    }

    fun batteryState(): BatteryState {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryState(percent, charging)
    }

    /** Thread count for LiteRT: fewer threads under thermal pressure. */
    fun recommendedThreads(): Int = when (level()) {
        Level.FULL -> 4
        Level.REDUCED -> 2
        Level.PAUSED -> 1
    }

    data class BatteryState(val percent: Int, val charging: Boolean)
}
