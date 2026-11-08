package com.aipro.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.aipro.app.ui.AiproTheme
import com.aipro.app.ui.CameraScreen

class MainActivity : ComponentActivity() {

    private val viewModel: CameraViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The update notification needs POST_NOTIFICATIONS on Android 13+; retry once the user has
        // answered so the "업데이트되었습니다" notice is not silently lost.
        UpdateNotifier.notifyIfUpdated(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissionsIfNeeded()
        // Fires once per installed version, after the permission request has been launched so a
        // freshly granted notification permission can still show it on the next launch.
        UpdateNotifier.notifyIfUpdated(this)
        setContent {
            AiproTheme {
                val state by viewModel.ui.collectAsState()
                // Samsung's 360° horizon lock keeps the UI upright and levels the image instead, so the
                // window orientation is frozen while the lock is on.
                androidx.compose.runtime.LaunchedEffect(state.horizonLock) {
                    requestedOrientation = if (state.horizonLock) {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    } else {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    }
                }
                CameraScreen(vm = viewModel, state = state)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Display rotation drives both the preview geometry and the JPEG orientation tag,
        // so it is pushed to the engine on every resume and rotation.
        viewModel.setDisplayRotation(currentRotationDegrees())
    }

    private fun requestPermissionsIfNeeded() {
        val needed = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        viewModel.setDisplayRotation(currentRotationDegrees())
    }

    private fun currentRotationDegrees(): Int = when (display?.rotation ?: Surface.ROTATION_0) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}
