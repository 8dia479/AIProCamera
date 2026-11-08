package com.aipro.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the preview transform actually applied. Shown as a tiny on-screen readout so the user (and the
 * developer, over adb) can tell a stale build from a wrong rotation instead of guessing.
 */
object PreviewDiag {
    private val _text = MutableStateFlow("rot=?")
    val text: StateFlow<String> = _text.asStateFlow()

    fun publish(text: String) {
        if (_text.value != text) _text.value = text
    }
}
