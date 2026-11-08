package com.aipro.camera.manual

/** Capture flash behaviour. Only offered in the UI when the camera reports a flash unit. */
enum class FlashMode(val label: String) {
    OFF("꺼짐"),
    AUTO("자동"),
    ON("켜짐"),
    ;

    /** Next state of the 3-way toggle used by the top-bar button. */
    fun next(): FlashMode = when (this) {
        OFF -> AUTO
        AUTO -> ON
        ON -> OFF
    }
}
