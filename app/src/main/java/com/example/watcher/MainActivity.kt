package com.example.watcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.watcher.ui.components.StartupMainContentPolicy
import com.example.watcher.ui.components.StartupVideoController
import com.example.watcher.ui.screens.MainScreen
import com.example.watcher.ui.theme.WatcherTheme

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private var videoController: StartupVideoController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d("StartupVideo", "onCreate: start")
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        Log.d("StartupVideo", "onCreate: after super")

        videoController = StartupVideoController.createIfFirstLaunch(this)
        val showMain = mutableStateOf(
            StartupMainContentPolicy.shouldCreateMainContentBeforeReveal(
                hasStartupVideoOverlay = videoController != null
            )
        )
        val allowSystemBars = mutableStateOf(false)

        if (showMain.value) {
            Log.d("StartupVideo", "onCreate: warming MainScreen behind startup video")
        }

        videoController?.attach(
            window = window,
            onFadeStart = {
                // Load real MainScreen — black overlay stays on top during init
                showMain.value = true
            },
            onFinished = {
                // After reveal fade completes — let MainScreen manage bars normally
                enableEdgeToEdge()
                allowSystemBars.value = true
                requestNotificationPermissionIfNeeded()
                watcherApplication().initializeLiteRt()
            }
        )

        if (videoController == null) {
            enableEdgeToEdge()
            showMain.value = true
            allowSystemBars.value = true
            requestNotificationPermissionIfNeeded()
            watcherApplication().initializeLiteRt()
        }

        Log.d("StartupVideo", "onCreate: before setContent")
        setContent {
            WatcherTheme {
                if (showMain.value) {
                    MainScreen(manageSystemBars = allowSystemBars.value)
                } else {
                    Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }
        }
        Log.d("StartupVideo", "onCreate: after setContent")
    }

    override fun onDestroy() {
        videoController?.release()
        videoController = null
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
