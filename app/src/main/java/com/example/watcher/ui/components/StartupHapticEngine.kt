package com.example.watcher.ui.components

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.random.Random

/**
 * Haptic engine for the startup video animation.
 * Produces three distinct tactile phases:
 * 1. Marbles rolling — irregular light taps simulating beads bouncing
 * 2. Rain crescendo — sparse-to-dense pulses growing in intensity
 * 3. Abrupt stop — instant silence for dramatic effect
 */
class StartupHapticEngine(context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // Fixed-seed random for reproducible "organic" feel
    private var random = Random(42)
    private var marblePhaseActive = false

    /** Phase 1: Mahjong tile shuffle — dense continuous clatter. */
    fun startMarbles(durationMs: Long = 5000L) {
        Log.d(TAG, "startMarbles() canVibrate=${canVibrate()} duration=${durationMs}ms")
        if (!canVibrate()) return
        stop()
        running = true
        marblePhaseActive = true
        random = Random(42)
        scheduleNextMarble()
        // Auto-stop marbles after specified duration (fade to silence before rain)
        handler.postDelayed({
            marblePhaseActive = false
            vibrator?.cancel()
        }, durationMs)
    }

    /** Phase 2: Rain growing from drizzle to downpour. */
    fun startRain() {
        Log.d(TAG, "startRain()")
        if (!canVibrate()) return
        stop()
        running = true
        rainStartTime = System.currentTimeMillis()
        scheduleNextRaindrop()
    }

    /** Phase 3: Abrupt stop — cancel everything instantly. */
    fun stop() {
        Log.d(TAG, "stop()")
        running = false
        handler.removeCallbacksAndMessages(null)
        vibrator?.cancel()
    }

    // ─── Mahjong shuffle implementation ────────────────────────────────

    private fun scheduleNextMarble() {
        if (!running || !marblePhaseActive) return

        // Dense continuous texture: very short gaps simulating
        // many tiles clinking together in rapid succession
        val delay: Long
        val amplitude: Int

        val roll = random.nextFloat()
        when {
            roll < 0.06f -> {
                // 6% chance: brief micro-pause (lull between shuffles)
                delay = random.nextLong(45, 75)
                amplitude = random.nextInt(50, 90)
            }
            roll < 0.22f -> {
                // 16% chance: heavy collision (two tiles smack together)
                delay = random.nextLong(12, 22)
                amplitude = random.nextInt(190, 255)
            }
            else -> {
                // 78%: sustained dense clatter (the main shuffle texture)
                delay = random.nextLong(12, 30)
                amplitude = random.nextInt(90, 180)
            }
        }

        val runnable = Runnable {
            if (!running || !marblePhaseActive) return@Runnable
            vibrate(6, amplitude)
            scheduleNextMarble()
        }
        handler.postDelayed(runnable, delay)
    }

    // ─── Rain implementation ────────────────────────────────────────────

    private var rainStartTime = 0L

    private fun scheduleNextRaindrop() {
        if (!running) return
        val elapsed = System.currentTimeMillis() - rainStartTime
        val totalDuration = RAIN_DURATION_MS

        if (elapsed > totalDuration) {
            // Rain phase at max intensity — dense rapid taps
            val runnable = Runnable {
                if (!running) return@Runnable
                vibrate(8, 220)
                scheduleNextRaindrop()
            }
            handler.postDelayed(runnable, 30)
            return
        }

        val progress = (elapsed.toFloat() / totalDuration).coerceIn(0f, 1f)

        // Interpolate: sparse light drizzle → dense heavy downpour
        val interval = lerp(200f, 30f, progress).toLong()
        val amplitude = lerp(40f, 220f, progress).toInt()
        val duration = lerp(3f, 8f, progress).toLong()

        val runnable = Runnable {
            if (!running) return@Runnable
            vibrate(duration, amplitude)
            scheduleNextRaindrop()
        }
        handler.postDelayed(runnable, interval)
    }

    // ─── Utility ────────────────────────────────────────────────────────

    private fun canVibrate(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return vibrator?.hasVibrator() == true
    }

    private var pulseCount = 0

    private fun vibrate(durationMs: Long, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pulseCount++
            if (pulseCount <= 3 || pulseCount % 20 == 0) {
                Log.d(TAG, "vibrate #$pulseCount dur=${durationMs}ms amp=$amplitude")
            }
            val effect = VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
            vibrator?.vibrate(effect)
        }
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction
    }

    companion object {
        private const val TAG = "StartupVideo"
        private val HAPTIC_TOKEN = Any()
        private const val RAIN_DURATION_MS = 2400L

        private fun resolveVibrator(context: Context): Vibrator? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }
}
