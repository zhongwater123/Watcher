package com.example.watcher.ui.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal const val FRONT_CAMERA_SOURCE_LABEL = "手机前置摄像头（降级）"
internal const val BACK_CAMERA_SOURCE_LABEL = "手机后置摄像头（降级）"

enum class CameraFallbackLens { Front, Back }

internal object CameraFallbackPreference {
    @Volatile
    var selectedLens: CameraFallbackLens = CameraFallbackLens.Front
        private set

    @Volatile
    var preferFallbackFirst: Boolean = false
        private set

    fun recordSelectedLens(lens: CameraFallbackLens) {
        selectedLens = lens
        preferFallbackFirst = true
    }

    fun markRemoteConnected() {
        preferFallbackFirst = false
    }
}

internal fun cameraSourceLabel(lens: CameraFallbackLens): String = when (lens) {
    CameraFallbackLens.Front -> FRONT_CAMERA_SOURCE_LABEL
    CameraFallbackLens.Back -> BACK_CAMERA_SOURCE_LABEL
}

internal data class CameraFallbackState(
    val currentFrame: Bitmap? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val fps: Int = 0,
    val sourceLabel: String = FRONT_CAMERA_SOURCE_LABEL,
    val permissionDenied: Boolean = false
)

@Deprecated("Use CameraFallbackState", ReplaceWith("CameraFallbackState"))
internal typealias FrontCameraFallbackState = CameraFallbackState

@Composable
internal fun rememberCameraFallbackState(
    active: Boolean,
    lens: CameraFallbackLens = CameraFallbackLens.Front,
    reconnectToken: Int,
    frameTransform: (Bitmap) -> Bitmap = { it },
    onFrameUpdate: (Bitmap?) -> Unit = {}
): CameraFallbackState {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val targetRotation = resolveTargetRotation(context)

    var currentFrame by remember { mutableStateOf<Bitmap?>(null) }
    var connectionStatus by remember { mutableStateOf<ConnectionStatus>(ConnectionStatus.Disconnected) }
    var fps by remember { mutableIntStateOf(0) }
    var permissionDenied by remember { mutableStateOf(false) }
    val latestFrameTransform by rememberUpdatedState(frameTransform)
    var hasPermission by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permissionDenied = !granted
        if (!granted) {
            currentFrame = null
            fps = 0
            val cameraName = if (lens == CameraFallbackLens.Front) "前置" else "后置"
            connectionStatus = ConnectionStatus.Error(
                "ESP32 视频流不可用，且相机权限未授予，无法切换到手机${cameraName}摄像头。"
            )
            onFrameUpdate(null)
        }
    }

    DisposableEffect(cameraExecutor) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(active, reconnectToken) {
        if (!active) {
            currentFrame = null
            fps = 0
            permissionDenied = false
            connectionStatus = ConnectionStatus.Disconnected
            onFrameUpdate(null)
            return@LaunchedEffect
        }

        if (!hasPermission) {
            connectionStatus = ConnectionStatus.Connecting
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(
        active,
        hasPermission,
        lens,
        reconnectToken,
        lifecycleOwner,
        configuration.orientation,
        targetRotation
    ) {
        if (!active) {
            onDispose { }
        } else if (!hasPermission) {
            onDispose { }
        } else {
            connectionStatus = ConnectionStatus.Connecting
            var cameraProvider: ProcessCameraProvider? = null
            var fpsFrameCount = 0
            var fpsWindowStart = System.currentTimeMillis()

            val providerFuture = ProcessCameraProvider.getInstance(context)
            val bindRunnable = Runnable {
                runCatching {
                    val provider = providerFuture.get()
                    val analysis = buildImageAnalysis(
                        executor = cameraExecutor,
                        targetRotation = targetRotation
                    ) { image ->
                        val frame = latestFrameTransform(image.toBitmap())
                        image.close()

                        mainExecutor.execute {
                            currentFrame = frame
                            connectionStatus = ConnectionStatus.Connected
                            onFrameUpdate(frame)

                            fpsFrameCount += 1
                            val now = System.currentTimeMillis()
                            if (now - fpsWindowStart >= 1_000) {
                                fps = fpsFrameCount
                                fpsFrameCount = 0
                                fpsWindowStart = now
                            }
                        }
                    }

                    val cameraSelector = when (lens) {
                        CameraFallbackLens.Front -> CameraSelector.DEFAULT_FRONT_CAMERA
                        CameraFallbackLens.Back -> CameraSelector.DEFAULT_BACK_CAMERA
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        analysis
                    )
                    cameraProvider = provider
                }.onFailure { error ->
                    val cameraName = if (lens == CameraFallbackLens.Front) "前置" else "后置"
                    Log.e("CameraFallback", "Failed to start $cameraName camera fallback", error)
                    connectionStatus = ConnectionStatus.Error(
                        error.message ?: "手机${cameraName}摄像头启动失败。"
                    )
                    currentFrame = null
                    fps = 0
                    onFrameUpdate(null)
                }
            }

            providerFuture.addListener(bindRunnable, mainExecutor)

            onDispose {
                cameraProvider?.unbindAll()
                currentFrame = null
                fps = 0
                connectionStatus = ConnectionStatus.Disconnected
                onFrameUpdate(null)
            }
        }
    }

    return CameraFallbackState(
        currentFrame = currentFrame,
        connectionStatus = connectionStatus,
        fps = fps,
        sourceLabel = cameraSourceLabel(lens),
        permissionDenied = permissionDenied
    )
}

private fun buildImageAnalysis(
    executor: ExecutorService,
    targetRotation: Int,
    onFrame: (ImageProxy) -> Unit
): ImageAnalysis {
    return ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setTargetResolution(Size(640, 480))
        .setTargetRotation(targetRotation)
        .setOutputImageRotationEnabled(true)
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .build()
        .apply {
            setAnalyzer(executor, onFrame)
        }
}

private fun resolveTargetRotation(context: Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return context.display?.rotation ?: Surface.ROTATION_0
    }
    @Suppress("DEPRECATION")
    return (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
}

private fun ImageProxy.toBitmap(): Bitmap {
    val plane = planes.first()
    val width = width
    val height = height
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val rowBytes = ByteArray(rowStride)
    val pixels = IntArray(width * height)
    var pixelIndex = 0

    for (row in 0 until height) {
        buffer.position(row * rowStride)
        buffer.get(rowBytes, 0, rowStride)
        var columnOffset = 0
        while (columnOffset < width * pixelStride && pixelIndex < pixels.size) {
            val red = rowBytes[columnOffset].toInt() and 0xFF
            val green = rowBytes[columnOffset + 1].toInt() and 0xFF
            val blue = rowBytes[columnOffset + 2].toInt() and 0xFF
            val alpha = rowBytes[columnOffset + 3].toInt() and 0xFF
            pixels[pixelIndex] =
                android.graphics.Color.argb(alpha, red, green, blue)
            pixelIndex += 1
            columnOffset += pixelStride
        }
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}
