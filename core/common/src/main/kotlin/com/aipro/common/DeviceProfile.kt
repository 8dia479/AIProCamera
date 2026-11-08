package com.aipro.common

import android.os.Build

/**
 * Runtime identity of the device. Used for reporting only: no feature is enabled purely because the
 * model string matches. Every capability is queried from Camera2 at runtime (see CameraCapabilities).
 */
data class DeviceProfile(
    val manufacturer: String = Build.MANUFACTURER,
    val brand: String = Build.BRAND,
    val model: String = Build.MODEL,
    val device: String = Build.DEVICE,
    val hardware: String = Build.HARDWARE,
    val socManufacturer: String = Build.SOC_MANUFACTURER,
    val socModel: String = Build.SOC_MODEL,
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val release: String = Build.VERSION.RELEASE,
) {
    /** Galaxy Z Flip 6 ships as SM-F741x (F741B/F741N/F741U/F741W...). Reporting convenience only. */
    val isZFlip6: Boolean
        get() = model.uppercase().startsWith("SM-F741")

    val isSamsung: Boolean
        get() = manufacturer.equals("samsung", ignoreCase = true)

    fun summary(): String = "$manufacturer $model ($device) · Android $release (API $sdkInt) · $socManufacturer $socModel"
}
