package com.aipro.common

import android.util.Log

/**
 * Thin logging facade. Tag is prefixed with the AIProCamera marker so on-device `adb logcat`
 * sessions can be filtered with a single grep.
 */
object AiproLog {
    private const val PREFIX = "AIPro"

    fun d(module: String, message: String) = Log.d(PREFIX + module, message)
    fun i(module: String, message: String) = Log.i(PREFIX + module, message)
    fun w(module: String, message: String, tr: Throwable? = null) =
        if (tr == null) Log.w(PREFIX + module, message) else Log.w(PREFIX + module, message, tr)

    fun e(module: String, message: String, tr: Throwable? = null) =
        if (tr == null) Log.e(PREFIX + module, message) else Log.e(PREFIX + module, message, tr)
}
