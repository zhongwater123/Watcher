package com.example.watcher

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.watcher.data.model.VideoStreamSettings
import com.example.watcher.data.remote.LcdPushClient
import com.example.watcher.ui.components.StatusPill
import com.example.watcher.ui.components.WatcherTopBar
import com.example.watcher.ui.screens.HubPage
import com.example.watcher.ui.theme.WatcherTheme
import com.example.watcher.ui.viewmodel.IntentViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class BackScreenPushActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WatcherTheme {
                BackScreenPushRoute(onClose = ::finish)
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent {
            return Intent(context, BackScreenPushActivity::class.java)
        }
    }
}

@Composable
private fun BackScreenPushRoute(
    onClose: () -> Unit,
    viewModel: IntentViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    val streamSettings by viewModel.videoStreamSettings.collectAsStateWithLifecycle(initialValue = null)
    val settings = (streamSettings ?: VideoStreamSettings()).normalized()
    val deviceIp = settings.ipAddress

    // Camera state
    var displayFrame by remember { mutableStateOf<Bitmap?>(null) }
    var cameraFps by remember { mutableIntStateOf(0) }
    var cameraReady by remember { mutableStateOf(false) }
    val latestFrame = remember { AtomicReference<Bitmap?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    // Zoom: rotation 0 = default (linearZoom 0.5), CW = telephoto, CCW = wide
    var rotaryRotationDegrees by rememberSaveable { mutableStateOf(0f) }
    var currentZoom by remember { mutableFloatStateOf(0.5f) }

    // Push state
    var pushCount by remember { mutableIntStateOf(0) }
    var pushError by remember { mutableStateOf<String?>(null) }
    var captureMessage by remember { mutableStateOf<String?>(null) }
    val lcdClient = remember { LcdPushClient() }

    // Stop main stream
    DisposableEffect(Unit) {
        viewModel.setStreamPlaying(false)
        onDispose { cameraExecutor.shutdown() }
    }

    // Initialize CameraX
    DisposableEffect(lifecycleOwner) {
        val targetRotation = resolveTargetRotation(context)
        var fpsFrameCount = 0
        var fpsWindowStart = System.currentTimeMillis()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(640, 480))
            .setTargetRotation(targetRotation)
            .setOutputImageRotationEnabled(true)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .apply {
                setAnalyzer(cameraExecutor) { image ->
                    val frame = image.toBitmapCompat()
                    image.close()
                    mainExecutor.execute {
                        displayFrame = frame
                        latestFrame.set(frame)
                        cameraReady = true
                        fpsFrameCount += 1
                        val now = System.currentTimeMillis()
                        if (now - fpsWindowStart >= 1_000) {
                            cameraFps = fpsFrameCount
                            fpsFrameCount = 0
                            fpsWindowStart = now
                        }
                    }
                }
            }

        val capture = ImageCapture.Builder()
            .setTargetRotation(targetRotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = capture

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    analysis,
                    capture
                )
                cameraControl = camera.cameraControl
                camera.cameraControl.setLinearZoom(0.5f)
            }.onFailure { error ->
                Log.e("BackScreenPush", "Camera init failed", error)
            }
        }, mainExecutor)

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            imageCapture = null
            cameraControl = null
        }
    }

    // Apply zoom when rotation changes
    LaunchedEffect(rotaryRotationDegrees) {
        val linearZoom = ((rotaryRotationDegrees + MAX_ZOOM_ROTATION) / (2f * MAX_ZOOM_ROTATION)).coerceIn(0f, 1f)
        currentZoom = linearZoom
        cameraControl?.setLinearZoom(linearZoom)
    }

    // Push loop
    LaunchedEffect(deviceIp) {
        while (true) {
            val frame = latestFrame.getAndSet(null)
            if (frame != null) {
                val result = lcdClient.pushFrame(deviceIp, frame)
                if (result.success) {
                    pushCount += 1
                    pushError = null
                } else {
                    pushError = result.error
                }
            } else {
                kotlinx.coroutines.delay(30L)
            }
        }
    }

    // Clear capture message
    LaunchedEffect(captureMessage) {
        if (captureMessage != null) {
            kotlinx.coroutines.delay(2000L)
            captureMessage = null
        }
    }

    // Capture action
    val doCapture: () -> Unit = doCapture@{
        val cap = imageCapture ?: return@doCapture
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Watcher_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Watcher")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
        cap.takePicture(
            outputOptions,
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    captureMessage = "已保存到相册"
                    triggerCaptureHaptic(context)
                }
                override fun onError(exception: ImageCaptureException) {
                    captureMessage = "拍照失败: ${exception.message}"
                }
            }
        )
    }

    val accent = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiaryContainer
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .drawBehind {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        colors = listOf(
                            accent.copy(alpha = 0.18f),
                            accent.copy(alpha = 0.08f),
                            tertiary.copy(alpha = 0.12f),
                            Color.Transparent
                        )
                    )
                )
            }
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 48.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header with rotary zoom dial
        WatcherTopBar(
            eyebrow = "Watcher",
            title = "背屏推送",
            subtitle = "实时预览 · 旋转变焦 · 轻触快拍",
            currentPage = HubPage.Hub,
            pageOffset = 0f,
            rotaryRotationDegrees = rotaryRotationDegrees,
            onRotaryRotationChange = { rotaryRotationDegrees = it.coerceIn(-MAX_ZOOM_ROTATION, MAX_ZOOM_ROTATION) },
            onOpenSettings = null,
            onRotaryTap = doCapture,
            onRotaryLongPress = { rotaryRotationDegrees = 0f }
        )

        // Hero: Camera preview card with depth
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color.Black,
            shadowElevation = 16.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(28.dp))
            ) {
                val frame = displayFrame
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "后置摄像头预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Subtle vignette overlay
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.08f),
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.20f)
                                    )
                                )
                            )
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "正在启动后置摄像头...",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                // Top-left: zoom & FPS
                Box(modifier = Modifier.padding(14.dp).align(Alignment.TopStart)) {
                    val zoomLabel = when {
                        currentZoom < 0.48f -> "W"
                        currentZoom > 0.52f -> "${String.format("%.1f", currentZoom * 2)}x"
                        else -> "1x"
                    }
                    StatusPill(
                        text = if (cameraReady) "$zoomLabel · $cameraFps FPS" else "...",
                        accent = MaterialTheme.colorScheme.primary
                    )
                }
                // Bottom-right: push counter
                if (cameraReady && pushCount > 0) {
                    Box(modifier = Modifier.padding(14.dp).align(Alignment.BottomEnd)) {
                        StatusPill(
                            text = "已推送 $pushCount 帧",
                            accent = if (pushError == null) Color(0xFF0E8B65)
                            else MaterialTheme.colorScheme.error
                        )
                    }
                }
                // Center: capture feedback toast
                captureMessage?.let { msg ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(text = msg, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        // Connection status row — minimal, integrated
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (pushError == null && pushCount > 0) Color(0xFF0E8B65)
                            else if (pushError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (pushError == null && pushCount > 0) "正在推送到 $deviceIp"
                    else if (pushError != null) pushError!!
                    else "等待设备连接...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (pushCount > 0) "$pushCount 帧" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }

        // Photo gallery — recent captures from DCIM/Watcher
        CapturedPhotosGallery(context = context, refreshKey = captureMessage)
    }
    }
}

@Composable
private fun CapturedPhotosGallery(context: Context, refreshKey: Any?) {
    val photos = remember(refreshKey) {
        loadWatcherPhotos(context)
    }
    if (photos.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "最近拍摄",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${photos.size} 张",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(photos.size) { index ->
                val uri = photos[index]
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
                    )
                ) {
                    val thumbnail = remember(uri) {
                        runCatching {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            android.graphics.BitmapFactory.decodeStream(inputStream, null, opts)
                                .also { inputStream?.close() }
                        }.getOrNull()
                    }
                    if (thumbnail != null) {
                        Image(
                            bitmap = thumbnail.asImageBitmap(),
                            contentDescription = "拍摄的照片",
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(14.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(80.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("?", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun loadWatcherPhotos(context: Context): List<android.net.Uri> {
    val photos = mutableListOf<android.net.Uri>()
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    } else {
        "${MediaStore.Images.Media.DATA} LIKE ?"
    }
    val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf("DCIM/Watcher%")
    } else {
        arrayOf("%DCIM/Watcher%")
    }
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    runCatching {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var count = 0
            while (cursor.moveToNext() && count < 20) {
                val id = cursor.getLong(idColumn)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                photos.add(uri)
                count++
            }
        }
    }
    return photos
}

private const val MAX_ZOOM_ROTATION = 1080f

private fun triggerCaptureHaptic(context: Context) {
    // Pen-click haptic: CLICK (press) + TICK (rebound) — same as concise mode toggle
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (vibrator.hasVibrator()) {
            val click = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            vibrator.vibrate(click)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val tick = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                vibrator.vibrate(tick)
            }, 30)
        }
    }
}

private fun resolveTargetRotation(context: Context): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        return context.display?.rotation ?: Surface.ROTATION_0
    }
    @Suppress("DEPRECATION")
    return (context as? Activity)?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
}

private fun ImageProxy.toBitmapCompat(): Bitmap {
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
            pixels[pixelIndex] = android.graphics.Color.argb(alpha, red, green, blue)
            pixelIndex += 1
            columnOffset += pixelStride
        }
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}
