package com.example.watcher.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.watcher.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-contained startup video overlay that operates at the Window DecorView level,
 * completely independent of Compose. Uses ExoPlayer for robust codec handling.
 */
class StartupVideoController private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "StartupVideo"
        private val processStartTime = SystemClock.elapsedRealtime()
        private val hasShownInProcess = AtomicBoolean(false)

        private const val VIDEO_FADE_MS = 600L
        private const val REVEAL_DELAY_MS = 1600L
        private const val REVEAL_FADE_MS = 800L
        private const val WIDTH_FRACTION = 0.72f
        private const val HEIGHT_FRACTION = 0.64f
        private const val FIRST_FRAME_TIMEOUT_MS = 5000L
        private const val ASPECT_RATIO = 9f / 16f

        fun createIfFirstLaunch(context: Context): StartupVideoController? {
            if (hasShownInProcess.getAndSet(true)) return null
            return StartupVideoController(context.applicationContext)
        }
    }

    private var window: Window? = null
    private var overlayView: FrameLayout? = null
    private var player: ExoPlayer? = null
    private var onFadeStart: (() -> Unit)? = null
    private var onFinished: (() -> Unit)? = null
    private var released = false
    private var hasStartedPlayback = false
    private val handler = Handler(Looper.getMainLooper())
    private val hapticEngine = StartupHapticEngine(context)

    private val timeoutRunnable = Runnable {
        if (!hasStartedPlayback && !released) {
            Log.w(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] timeout — no playback")
            finishAndDetach()
        }
    }

    private val immersiveEnforcer = object : Runnable {
        override fun run() {
            if (released) return
            window?.let { enterImmersiveMode(it) }
            handler.postDelayed(this, 100)
        }
    }

    fun attach(window: Window, onFadeStart: () -> Unit = {}, onFinished: () -> Unit = {}) {
        this.window = window
        this.onFadeStart = onFadeStart
        this.onFinished = onFinished
        Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] attach()")

        enterImmersiveMode(window)
        handler.postDelayed(immersiveEnforcer, 100)

        val decorView = window.decorView as ViewGroup
        val overlay = buildOverlayView(window.context)
        decorView.addView(overlay)
        overlayView = overlay

        handler.postDelayed(timeoutRunnable, FIRST_FRAME_TIMEOUT_MS)
    }

    fun release() {
        if (released) return
        released = true
        hapticEngine.stop()
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(immersiveEnforcer)
        player?.release()
        player = null
        removeOverlay()
    }

    fun startPlayback() { /* no-op, playback starts on surface ready */ }

    private fun buildOverlayView(context: Context): FrameLayout {
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val textureView = TextureView(context).apply {
            isOpaque = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surfaceTexture: SurfaceTexture, width: Int, height: Int
                ) {
                    onSurfaceReady(this@apply)
                }

                override fun onSurfaceTextureSizeChanged(
                    surfaceTexture: SurfaceTexture, width: Int, height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                    player?.clearVideoTextureView(this@apply)
                    return true
                }

                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
            }
        }

        val videoContainer = FrameLayout(context).apply {
            clipChildren = true
            clipToPadding = true
            setBackgroundColor(Color.BLACK)
            addView(textureView)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        container.addView(videoContainer)
        container.addView(VignetteView(context))

        container.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val containerWidth = right - left
            val containerHeight = bottom - top
            if (containerWidth <= 0 || containerHeight <= 0) return@addOnLayoutChangeListener

            val viewportWidth = (containerWidth * WIDTH_FRACTION).toInt()
            val viewportHeight = (containerHeight * HEIGHT_FRACTION).toInt()

            var videoWidth = viewportWidth
            var videoHeight = (videoWidth / ASPECT_RATIO).toInt()
            if (videoHeight > viewportHeight) {
                videoHeight = viewportHeight
                videoWidth = (videoHeight * ASPECT_RATIO).toInt()
            }

            textureView.layoutParams = FrameLayout.LayoutParams(videoWidth, videoHeight)
            videoContainer.layoutParams = FrameLayout.LayoutParams(
                videoWidth, videoHeight, Gravity.CENTER
            )
        }

        return container
    }

    private fun onSurfaceReady(textureView: TextureView) {
        if (released || hasStartedPlayback) return
        Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] surface ready, building ExoPlayer")

        val resourceUri = Uri.parse("android.resource://${context.packageName}/${R.raw.app_openvideo}")
        val exoPlayer = runCatching {
            ExoPlayer.Builder(context).build().apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                setMediaItem(MediaItem.fromUri(resourceUri))
                setVideoTextureView(textureView)

                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        hasStartedPlayback = true
                        handler.removeCallbacks(timeoutRunnable)
                        hapticEngine.startMarbles()
                        Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] first frame rendered")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] playback ended")
                            fadeOutAndDetach()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] ExoPlayer error: ${error.message}")
                        finishAndDetach()
                    }
                })

                prepare()
                playWhenReady = true
            }
        }.getOrNull()

        if (exoPlayer == null) {
            Log.e(TAG, "ExoPlayer creation failed")
            finishAndDetach()
            return
        }

        player = exoPlayer
        Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] ExoPlayer prepared, playWhenReady=true")
    }

    private fun fadeOutAndDetach() {
        if (released) return
        Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] video ended — phase 1: fade to black")
        handler.removeCallbacks(timeoutRunnable)

        // Switch haptics: marbles → rain crescendo
        hapticEngine.startRain()

        // Release player — TextureView goes black, overlay becomes solid black
        player?.release()
        player = null

        val view = overlayView ?: run { finishAndDetach(); return }

        // Phase 1: Quick fade of video content to black (vignette makes this subtle)
        // The overlay background is already black, just need TextureView to clear
        view.animate()
            .alpha(1f) // keep at 1.0 (pure black overlay)
            .setDuration(VIDEO_FADE_MS)
            .withEndAction {
                if (released) return@withEndAction
                Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] phase 2: loading MainScreen")
                // Phase 2: Trigger MainScreen loading
                val callback = onFadeStart
                onFadeStart = null
                callback?.invoke()

                // Phase 3: After MainScreen initializes, re-enforce immersive then reveal
                handler.postDelayed({
                    if (released) return@postDelayed
                    Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] phase 3: revealing MainScreen")
                    view.animate()
                        .alpha(0f)
                        .setDuration(REVEAL_FADE_MS)
                        .withEndAction { finishAndDetach() }
                        .start()
                }, REVEAL_DELAY_MS)
            }
            .start()
    }

    private fun finishAndDetach() {
        if (released) return
        Log.d(TAG, "[T+${SystemClock.elapsedRealtime() - processStartTime}ms] finishAndDetach")
        released = true
        hapticEngine.stop()
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(immersiveEnforcer)
        player?.release()
        player = null
        window?.let { exitImmersiveMode(it) }
        removeOverlay()
        onFinished?.invoke()
        onFinished = null
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        overlayView = null
    }

    private var savedStatusBarColor = 0
    private var savedNavBarColor = 0

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode(window: Window) {
        // Save original bar colors
        savedStatusBarColor = window.statusBarColor
        savedNavBarColor = window.navigationBarColor
        // Make bars black so they blend with our overlay even if they appear
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        // Also attempt to hide them
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun exitImmersiveMode(window: Window) {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = savedStatusBarColor
        window.navigationBarColor = savedNavBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private class VignetteView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: RadialGradient? = null
        private var lastWidth = 0
        private var lastHeight = 0

        init {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        override fun onDraw(canvas: Canvas) {
            val w = width
            val h = height
            if (w <= 0 || h <= 0) return

            if (shader == null || w != lastWidth || h != lastHeight) {
                lastWidth = w
                lastHeight = h
                val cx = w / 2f
                val cy = h / 2f
                val radius = minOf(w, h) * 0.33f
                shader = RadialGradient(
                    cx, cy, radius,
                    intArrayOf(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT,
                        Color.argb(180, 0, 0, 0),
                        Color.argb(255, 0, 0, 0)
                    ),
                    floatArrayOf(0f, 0.6f, 0.85f, 1f),
                    Shader.TileMode.CLAMP
                )
                paint.shader = shader
            }

            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }
}
