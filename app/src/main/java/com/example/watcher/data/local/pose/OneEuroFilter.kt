package com.example.watcher.data.local.pose

import kotlin.math.abs

/**
 * One Euro Filter implementation for smoothing noisy signals.
 * Ideal for human body landmark trajectories — adapts cutoff frequency
 * based on speed of movement (slow = more smoothing, fast = less lag).
 *
 * Reference: https://cristal.univ-lille.fr/~casiez/1euro/
 */
class OneEuroFilter(
    private val minCutoff: Double = 1.0,
    private val beta: Double = 0.01,
    private val dCutoff: Double = 1.0
) {
    private var xFilter: LowPassFilter? = null
    private var dxFilter: LowPassFilter? = null
    private var lastTime: Double = -1.0

    fun filter(value: Double, timestamp: Double): Double {
        if (lastTime < 0) {
            lastTime = timestamp
            xFilter = LowPassFilter(computeAlpha(minCutoff, 1.0 / 30.0), value)
            dxFilter = LowPassFilter(computeAlpha(dCutoff, 1.0 / 30.0), 0.0)
            return value
        }

        val dt = (timestamp - lastTime).coerceAtLeast(1.0 / 120.0)
        lastTime = timestamp

        val dxAlpha = computeAlpha(dCutoff, dt)
        val dx = (value - (xFilter?.lastValue ?: value)) / dt
        val edx = dxFilter!!.filter(dx, dxAlpha)

        val cutoff = minCutoff + beta * abs(edx)
        val alpha = computeAlpha(cutoff, dt)
        return xFilter!!.filter(value, alpha)
    }

    fun reset() {
        xFilter = null
        dxFilter = null
        lastTime = -1.0
    }

    private fun computeAlpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }

    private class LowPassFilter(alpha: Double, initValue: Double) {
        var lastValue: Double = initValue
            private set

        fun filter(value: Double, alpha: Double): Double {
            lastValue = alpha * value + (1.0 - alpha) * lastValue
            return lastValue
        }
    }
}

/**
 * Applies One Euro Filter to all landmark coordinates across a sequence of frames.
 * Each landmark coordinate (nx, ny, nz, wx, wy, wz) gets its own independent filter.
 */
class PoseLandmarkSmoother(
    private val landmarkCount: Int = 33,
    minCutoff: Double = 1.0,
    beta: Double = 0.007,
    dCutoff: Double = 1.0
) {
    // 33 landmarks × 6 coordinates (nx, ny, nz, wx, wy, wz) = 198 filters
    private val filters: Array<OneEuroFilter> = Array(landmarkCount * 6) {
        OneEuroFilter(minCutoff, beta, dCutoff)
    }

    fun smoothFrame(frame: PoseFileFormat.PoseFrame): PoseFileFormat.PoseFrame {
        val timestamp = frame.timestampMs / 1000.0 // Convert to seconds

        val smoothedLandmarks = frame.landmarks.mapIndexed { idx, lm ->
            val baseIdx = idx * 6
            PoseFileFormat.PoseLandmarkData(
                nx = filters[baseIdx + 0].filter(lm.nx.toDouble(), timestamp).toFloat(),
                ny = filters[baseIdx + 1].filter(lm.ny.toDouble(), timestamp).toFloat(),
                nz = filters[baseIdx + 2].filter(lm.nz.toDouble(), timestamp).toFloat(),
                visibility = lm.visibility, // Don't smooth confidence values
                presence = lm.presence,
                wx = filters[baseIdx + 3].filter(lm.wx.toDouble(), timestamp).toFloat(),
                wy = filters[baseIdx + 4].filter(lm.wy.toDouble(), timestamp).toFloat(),
                wz = filters[baseIdx + 5].filter(lm.wz.toDouble(), timestamp).toFloat()
            )
        }

        return frame.copy(landmarks = smoothedLandmarks)
    }

    fun reset() {
        filters.forEach { it.reset() }
    }
}
