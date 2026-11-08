package com.aipro.app.ui

import android.content.Context

/**
 * Two interchangeable camera designs. The user picks one in Settings; both drive the same engine,
 * so no feature exists in one style but not the other.
 */
enum class CameraUiStyle(val label: String, val description: String) {
    GALAXY(
        label = "갤럭시 기본 스타일",
        description = "삼성 기본 카메라와 유사한 구성 · 하단 모드/줌 칩, 중앙 원형 셔터, 상단 촬영 용도 칩",
    ),
    PRO(
        label = "프로 카메라 스타일",
        description = "DSLR/미러리스 스타일 · 좌우 기술 HUD, 모노스페이스 노출계, 상시 노출 히스토그램",
    );

    companion object {
        fun fromName(name: String?): CameraUiStyle =
            entries.firstOrNull { it.name == name } ?: GALAXY
    }
}

/** Small persistent store (no external dependency) for UI preferences. */
class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("aipro_settings", Context.MODE_PRIVATE)

    init {
        // 1.3.2 changed the rotation setting from "absolute" to "offset from the sensor". A value
        // saved by an older build (e.g. 270) would now mean "rotate 270° on top of the sensor" and
        // the preview would look sideways, so it is reset to the default once.
        if (!prefs.getBoolean(KEY_ROTATION_OFFSET_SEMANTICS, false)) {
            prefs.edit()
                .putString(KEY_ROTATION_MODE, "0")
                .putBoolean(KEY_ROTATION_OFFSET_SEMANTICS, true)
                .apply()
        }
        // 1.3.9 makes the explicit sensor-correction offset zero by default.
        // Reset a prior non-zero value once on upgrade; later user choices continue to persist.
        if (!prefs.getBoolean(KEY_ROTATION_ZERO_DEFAULT_V3, false)) {
            prefs.edit()
                .putString(KEY_ROTATION_MODE, "0")
                .putBoolean(KEY_ROTATION_ZERO_DEFAULT_V3, true)
                .apply()
        }
    }

    var uiStyle: CameraUiStyle
        get() = CameraUiStyle.fromName(prefs.getString(KEY_STYLE, CameraUiStyle.GALAXY.name))
        set(value) = prefs.edit().putString(KEY_STYLE, value.name).apply()

    var showGrid: Boolean
        get() = prefs.getBoolean(KEY_GRID, true)
        set(value) = prefs.edit().putBoolean(KEY_GRID, value).apply()

    var lastPurpose: String?
        get() = prefs.getString(KEY_PURPOSE, null)
        set(value) = prefs.edit().putString(KEY_PURPOSE, value).apply()

    /**
     * Additional manual sensor correction: "0" (default), "90", "180", "270", or "AUTO" (zero).
     * TextureView handles the physical sensor orientation; this value adds no correction by default.
     */
    var previewRotationMode: String
        get() = prefs.getString(KEY_ROTATION_MODE, "0") ?: "0"
        set(value) = prefs.edit().putString(KEY_ROTATION_MODE, value).apply()

    /** Framing ratio (AspectRatioOption name), persisted so the choice survives a restart. */
    var lastAspectRatio: String?
        get() = prefs.getString(KEY_ASPECT, null)
        set(value) = prefs.edit().putString(KEY_ASPECT, value).apply()

    private companion object {
        const val KEY_STYLE = "ui_style"
        const val KEY_GRID = "show_grid"
        const val KEY_PURPOSE = "last_purpose"
        const val KEY_ASPECT = "last_aspect_ratio"
        const val KEY_ROTATION_MODE = "preview_rotation_mode"
        const val KEY_ROTATION_OFFSET_SEMANTICS = "preview_rotation_is_offset_v2"
        const val KEY_ROTATION_ZERO_DEFAULT_V3 = "preview_rotation_zero_default_v3"
    }
}
