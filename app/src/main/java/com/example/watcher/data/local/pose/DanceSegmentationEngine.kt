package com.example.watcher.data.local.pose

import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File
import kotlin.math.sqrt

/**
 * Dance Segmentation Engine — Motion Structure layer.
 *
 * Analyzes a .pose file to detect motion boundaries (where movements start/end),
 * producing hierarchical segmentation: atomic moves + phrases.
 *
 * Algorithm:
 * 1. Read all filled frames → extract normalized landmark positions
 * 2. Compute per-frame velocity (mean displacement of all 33 joints)
 * 3. Gaussian smooth the velocity curve
 * 4. Detect valleys (local minima below adaptive threshold) = candidate cut points
 * 5. Merge cuts that are too close (<500ms)
 * 6. Split segments that are too long (>8s) at internal valley
 * 7. Group atomic moves into phrases
 */
class DanceSegmentationEngine {

    companion object {
        private const val TAG = "DanceSegment"
        private const val MIN_MOVE_DURATION_MS = 700L    // <700ms = fragment, merge
        private const val MAX_MOVE_DURATION_MS = 2500L   // >2500ms = compound, split
        private const val MAX_PHRASE_DURATION_MS = 10000L
        private const val SMOOTHING_SIGMA = 2
    }

    /**
     * Run segmentation on a .pose file. Pure local computation, no network.
     * Returns null if the pose file is invalid or has insufficient data.
     */
    fun segment(poseFile: File, sessionId: Long): DanceSegmentation? {
        if (!poseFile.exists() || poseFile.length() < 64) return null

        val slotFile = try {
            PoseFileFormat.SlotFile(poseFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open pose file: ${e.message}")
            return null
        }

        try {
            val fps = slotFile.header.fps.toInt().coerceAtLeast(1)
            val totalFrames = slotFile.totalFrames
            val filledCount = slotFile.filledFrameCount
            val durationMs = slotFile.header.videoDurationMs

            Log.i(TAG, "Starting segmentation: $filledCount/$totalFrames frames, ${durationMs}ms, ${fps}fps")

            if (filledCount < 30) {
                Log.w(TAG, "Too few frames for segmentation: $filledCount")
                return null
            }

            // Step 1: Read all filled frames and extract positions
            val framePositions = readFramePositions(slotFile, totalFrames)
            val coverage = filledCount.toFloat() / totalFrames * 100
            Log.i(TAG, "Read ${framePositions.size} frame positions (coverage: ${"%.1f".format(coverage)}%)")

            // Frame gap analysis
            if (framePositions.size > 1) {
                val gaps = (1 until framePositions.size).map {
                    framePositions[it].frameIndex - framePositions[it - 1].frameIndex
                }
                val avgGap = gaps.average()
                val maxGap = gaps.maxOrNull() ?: 0
                Log.i(TAG, "Frame gaps: avg=${"%.1f".format(avgGap)}, max=$maxGap, effective_fps=${"%.1f".format(fps / avgGap)}")
            }

            // Step 2: Compute multi-signal motion intensity (each entry has actual timestamp in ms)
            val timeVelocity = computeTimeVelocity(framePositions, fps)
            val velocityValues = timeVelocity.map { it.second }.toFloatArray()

            // Step 3: Gaussian smooth
            val smoothed = gaussianSmooth(velocityValues, SMOOTHING_SIGMA)
            Log.i(TAG, "Motion intensity: min=${"%.4f".format(smoothed.min())}, max=${"%.4f".format(smoothed.max())}, mean=${"%.4f".format(smoothed.average())}")
            Log.i(TAG, "Signals: bodyVel×0.35 + angleChange×0.20 + comVel×0.15 + vis×0.10 + regional×0.10 + depth×0.10")

            // Step 4: Valley detection (low velocity = pause between moves)
            val sorted = smoothed.sorted()
            val p50 = sorted[(sorted.size * 0.50).toInt()]
            val valleyThreshold = p50 * 0.75f  // more permissive → more valleys
            val valleyIndices = detectValleys(smoothed, valleyThreshold)
            Log.i(TAG, "Valley threshold: ${"%.4f".format(valleyThreshold)} (median=${"%.4f".format(p50)}), valleys: ${valleyIndices.size}")

            // Also detect sharp acceleration changes (velocity direction reversal = new move)
            val accelCuts = detectAccelerationReversals(smoothed)
            Log.i(TAG, "Acceleration reversals: ${accelCuts.size}")

            // Combine all cut sources
            val allCutIndices = (valleyIndices + accelCuts).distinct().sorted()
            val candidateCutsMs = allCutIndices
                .filter { it in timeVelocity.indices }
                .map { timeVelocity[it].first }
            Log.i(TAG, "Combined candidate cuts: ${candidateCutsMs.size}")

            // Step 5: Merge close cuts
            val mergedCuts = mergeCuts(candidateCutsMs, MIN_MOVE_DURATION_MS)
            Log.i(TAG, "After merge: ${mergedCuts.size} cuts")

            // Step 6: Recursive split of long segments
            val finalCuts = splitLongSegments(mergedCuts, durationMs, smoothed, timeVelocity, MAX_MOVE_DURATION_MS)
            Log.i(TAG, "After split: ${finalCuts.size} cuts → ${finalCuts.size + 1} atomic moves")

            // Step 7: Build atomic moves
            val rawMoves = buildAtomicMoves(finalCuts, durationMs, fps, smoothed, timeVelocity)
            Log.i(TAG, "Raw moves: ${rawMoves.size}")

            // Step 8: Smart merge — absorb fragments (<700ms) into neighbors
            val atomicMoves = smartMerge(rawMoves)
            Log.i(TAG, "After smart merge: ${atomicMoves.size} moves")

            // Step 9: Group into phrases
            val phrases = buildPhrases(atomicMoves)
            Log.i(TAG, "Phrases: ${phrases.size}")

            // Distribution stats
            val durations = atomicMoves.map { it.endMs - it.startMs }
            val under1s = durations.count { it < 1000 }
            val in1to2_5s = durations.count { it in 1000..2500 }
            val over2_5s = durations.count { it > 2500 }
            Log.i(TAG, "DISTRIBUTION: <1s=$under1s | 1-2.5s=$in1to2_5s | >2.5s=$over2_5s | target: 90%+ in 1-2.5s (actual: ${"%.0f".format(in1to2_5s * 100f / durations.size)}%)")

            // Detailed quality log
            Log.i(TAG, "=== SEGMENTATION RESULT ===")
            atomicMoves.forEachIndexed { i, m ->
                val durSec = "%.1f".format((m.endMs - m.startMs) / 1000.0)
                Log.i(TAG, "  ${m.id}: ${m.startMs}ms-${m.endMs}ms (${durSec}s) peak=${"%.4f".format(m.peakVelocity)}")
            }
            phrases.forEachIndexed { i, p ->
                Log.i(TAG, "  ${p.id}: ${p.startMs}ms-${p.endMs}ms, ${p.moveIds.size} moves, diff=${"%.2f".format(p.difficulty)}")
            }
            Log.i(TAG, "=== END ===")

            return DanceSegmentation(
                sessionId = sessionId,
                totalDurationMs = durationMs,
                fps = fps,
                atomicMoves = atomicMoves,
                phrases = phrases,
                velocityCurve = smoothed
            )
        } finally {
            slotFile.close()
        }
    }

    /**
     * Save segmentation result to JSON file.
     */
    fun saveToFile(segmentation: DanceSegmentation, outputFile: File) {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = mapOf(
            "sessionId" to segmentation.sessionId,
            "totalDurationMs" to segmentation.totalDurationMs,
            "fps" to segmentation.fps,
            "atomicMoves" to segmentation.atomicMoves,
            "phrases" to segmentation.phrases
        )
        outputFile.writeText(gson.toJson(json))
        Log.i(TAG, "Segmentation saved: ${outputFile.name} (${segmentation.atomicMoves.size} moves, ${segmentation.phrases.size} phrases)")
    }

    /**
     * Load segmentation from JSON file.
     */
    fun loadFromFile(file: File): DanceSegmentation? {
        if (!file.exists()) return null
        return try {
            val gson = GsonBuilder().create()
            val json = gson.fromJson(file.readText(), Map::class.java)
            val sessionId = (json["sessionId"] as Number).toLong()
            val totalDurationMs = (json["totalDurationMs"] as Number).toLong()
            val fps = (json["fps"] as Number).toInt()

            @Suppress("UNCHECKED_CAST")
            val movesRaw = json["atomicMoves"] as? List<Map<String, Any>> ?: emptyList()
            val atomicMoves = movesRaw.map { m ->
                MoveSegment(
                    id = m["id"] as String,
                    startMs = (m["startMs"] as Number).toLong(),
                    endMs = (m["endMs"] as Number).toLong(),
                    startFrame = (m["startFrame"] as Number).toInt(),
                    endFrame = (m["endFrame"] as Number).toInt(),
                    peakVelocity = (m["peakVelocity"] as Number).toFloat(),
                    boundaryType = m["boundaryType"] as String
                )
            }

            @Suppress("UNCHECKED_CAST")
            val phrasesRaw = json["phrases"] as? List<Map<String, Any>> ?: emptyList()
            val phrases = phrasesRaw.map { p ->
                @Suppress("UNCHECKED_CAST")
                PhraseSegment(
                    id = p["id"] as String,
                    startMs = (p["startMs"] as Number).toLong(),
                    endMs = (p["endMs"] as Number).toLong(),
                    moveIds = (p["moveIds"] as List<String>),
                    difficulty = (p["difficulty"] as Number).toFloat()
                )
            }

            DanceSegmentation(sessionId, totalDurationMs, fps, atomicMoves, phrases, FloatArray(0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load segmentation: ${e.message}")
            null
        }
    }

    // ── Internal algorithms ──

    /** Full landmark data per frame for multi-signal analysis */
    private data class LandmarkData(
        val nx: Float, val ny: Float, val nz: Float, val visibility: Float
    )
    private data class FramePos(val frameIndex: Int, val landmarks: List<LandmarkData>)

    // MediaPipe landmark indices
    private object LM {
        const val LEFT_SHOULDER = 11; const val RIGHT_SHOULDER = 12
        const val LEFT_ELBOW = 13; const val RIGHT_ELBOW = 14
        const val LEFT_WRIST = 15; const val RIGHT_WRIST = 16
        const val LEFT_HIP = 23; const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25; const val RIGHT_KNEE = 26
        const val LEFT_ANKLE = 27; const val RIGHT_ANKLE = 28
        val UPPER_BODY = (0..22).toList()
        val LOWER_BODY = (23..32).toList()
        val HANDS = listOf(15, 16, 17, 18, 19, 20, 21, 22)
    }

    private fun readFramePositions(slotFile: PoseFileFormat.SlotFile, totalFrames: Int): List<FramePos> {
        val positions = mutableListOf<FramePos>()
        for (i in 0 until totalFrames) {
            if (!slotFile.isFrameFilled(i)) continue
            val frame = slotFile.readFrame(i) ?: continue
            val lm = frame.landmarks.map { LandmarkData(it.nx, it.ny, it.nz, it.visibility) }
            positions.add(FramePos(i, lm))
        }
        return positions
    }

    /**
     * Multi-signal boundary score: combines velocity, joint angles, center of mass,
     * visibility changes, regional velocity, and depth changes into a single
     * "motion change intensity" per time point.
     */
    private fun computeTimeVelocity(frames: List<FramePos>, fps: Int): List<Pair<Long, Float>> {
        if (frames.size < 2) return emptyList()
        val result = mutableListOf<Pair<Long, Float>>()
        result.add(Pair((frames[0].frameIndex.toLong() * 1000L) / fps, 0f))

        for (i in 1 until frames.size) {
            val prev = frames[i - 1]
            val curr = frames[i]
            val timeMs = (curr.frameIndex.toLong() * 1000L) / fps
            val timeDeltaSec = ((curr.frameIndex - prev.frameIndex).toLong() * 1000L / fps)
                .coerceAtLeast(1L) / 1000f

            // Signal 1: Overall body velocity (xy displacement)
            var totalDisp = 0f
            for (j in prev.landmarks.indices) {
                val dx = curr.landmarks[j].nx - prev.landmarks[j].nx
                val dy = curr.landmarks[j].ny - prev.landmarks[j].ny
                totalDisp += sqrt(dx * dx + dy * dy)
            }
            val bodyVelocity = totalDisp / (33f * timeDeltaSec)

            // Signal 2: Joint angle changes (elbow, knee, shoulder)
            val angleChange = computeAngleChange(prev.landmarks, curr.landmarks) / timeDeltaSec

            // Signal 3: Center of mass (hip midpoint) velocity
            val comVelocity = computeComVelocity(prev.landmarks, curr.landmarks, timeDeltaSec)

            // Signal 4: Visibility change (sudden occlusion = turn/transition)
            val visChange = computeVisibilityChange(prev.landmarks, curr.landmarks)

            // Signal 5: Regional velocity difference (upper vs lower vs hands)
            val regionalDiff = computeRegionalDiff(prev.landmarks, curr.landmarks, timeDeltaSec)

            // Signal 6: Depth (nz) change
            val depthChange = computeDepthChange(prev.landmarks, curr.landmarks, timeDeltaSec)

            // Weighted combination → single "motion intensity" score
            val combined = bodyVelocity * 0.35f +
                angleChange * 0.20f +
                comVelocity * 0.15f +
                visChange * 0.10f +
                regionalDiff * 0.10f +
                depthChange * 0.10f

            result.add(Pair(timeMs, combined))
        }
        if (result.size > 1) result[0] = result[0].copy(second = result[1].second)
        return result
    }

    /** Compute change in key joint angles (elbow, knee, shoulder) */
    private fun computeAngleChange(prev: List<LandmarkData>, curr: List<LandmarkData>): Float {
        fun angle(a: LandmarkData, b: LandmarkData, c: LandmarkData): Float {
            val ba = Pair(a.nx - b.nx, a.ny - b.ny)
            val bc = Pair(c.nx - b.nx, c.ny - b.ny)
            val dot = ba.first * bc.first + ba.second * bc.second
            val magA = sqrt(ba.first * ba.first + ba.second * ba.second).coerceAtLeast(0.001f)
            val magC = sqrt(bc.first * bc.first + bc.second * bc.second).coerceAtLeast(0.001f)
            return kotlin.math.acos((dot / (magA * magC)).coerceIn(-1f, 1f))
        }

        var totalChange = 0f
        // Left/right elbow angles
        totalChange += kotlin.math.abs(
            angle(prev[LM.LEFT_SHOULDER], prev[LM.LEFT_ELBOW], prev[LM.LEFT_WRIST]) -
            angle(curr[LM.LEFT_SHOULDER], curr[LM.LEFT_ELBOW], curr[LM.LEFT_WRIST])
        )
        totalChange += kotlin.math.abs(
            angle(prev[LM.RIGHT_SHOULDER], prev[LM.RIGHT_ELBOW], prev[LM.RIGHT_WRIST]) -
            angle(curr[LM.RIGHT_SHOULDER], curr[LM.RIGHT_ELBOW], curr[LM.RIGHT_WRIST])
        )
        // Left/right knee angles
        totalChange += kotlin.math.abs(
            angle(prev[LM.LEFT_HIP], prev[LM.LEFT_KNEE], prev[LM.LEFT_ANKLE]) -
            angle(curr[LM.LEFT_HIP], curr[LM.LEFT_KNEE], curr[LM.LEFT_ANKLE])
        )
        totalChange += kotlin.math.abs(
            angle(prev[LM.RIGHT_HIP], prev[LM.RIGHT_KNEE], prev[LM.RIGHT_ANKLE]) -
            angle(curr[LM.RIGHT_HIP], curr[LM.RIGHT_KNEE], curr[LM.RIGHT_ANKLE])
        )
        return totalChange / 4f  // Average of 4 joints
    }

    /** Center of mass velocity (hip midpoint) */
    private fun computeComVelocity(prev: List<LandmarkData>, curr: List<LandmarkData>, dt: Float): Float {
        val prevCom = Pair(
            (prev[LM.LEFT_HIP].nx + prev[LM.RIGHT_HIP].nx) / 2f,
            (prev[LM.LEFT_HIP].ny + prev[LM.RIGHT_HIP].ny) / 2f
        )
        val currCom = Pair(
            (curr[LM.LEFT_HIP].nx + curr[LM.RIGHT_HIP].nx) / 2f,
            (curr[LM.LEFT_HIP].ny + curr[LM.RIGHT_HIP].ny) / 2f
        )
        val dx = currCom.first - prevCom.first
        val dy = currCom.second - prevCom.second
        return sqrt(dx * dx + dy * dy) / dt
    }

    /** Visibility change detection (mean absolute change across all landmarks) */
    private fun computeVisibilityChange(prev: List<LandmarkData>, curr: List<LandmarkData>): Float {
        var totalChange = 0f
        for (j in prev.indices) {
            totalChange += kotlin.math.abs(curr[j].visibility - prev[j].visibility)
        }
        return totalChange / prev.size
    }

    /** Regional velocity difference: max difference between upper/lower/hands speeds */
    private fun computeRegionalDiff(prev: List<LandmarkData>, curr: List<LandmarkData>, dt: Float): Float {
        fun regionSpeed(indices: List<Int>): Float {
            var disp = 0f
            for (idx in indices) {
                if (idx >= prev.size || idx >= curr.size) continue
                val dx = curr[idx].nx - prev[idx].nx
                val dy = curr[idx].ny - prev[idx].ny
                disp += sqrt(dx * dx + dy * dy)
            }
            return disp / (indices.size.coerceAtLeast(1) * dt)
        }
        val upper = regionSpeed(LM.UPPER_BODY)
        val lower = regionSpeed(LM.LOWER_BODY)
        val hands = regionSpeed(LM.HANDS)
        // Return the variance/spread — high spread = different body parts doing different things
        val mean = (upper + lower + hands) / 3f
        return kotlin.math.abs(upper - mean) + kotlin.math.abs(lower - mean) + kotlin.math.abs(hands - mean)
    }

    /** Depth (nz) change velocity */
    private fun computeDepthChange(prev: List<LandmarkData>, curr: List<LandmarkData>, dt: Float): Float {
        var totalDepthChange = 0f
        for (j in prev.indices) {
            totalDepthChange += kotlin.math.abs(curr[j].nz - prev[j].nz)
        }
        return totalDepthChange / (prev.size * dt)
    }

    private fun gaussianSmooth(data: FloatArray, sigma: Int): FloatArray {
        if (data.isEmpty()) return data
        val kernel = createGaussianKernel(sigma)
        val result = FloatArray(data.size)
        val halfK = kernel.size / 2
        for (i in data.indices) {
            var sum = 0f
            var weightSum = 0f
            for (k in kernel.indices) {
                val idx = i + k - halfK
                if (idx in data.indices) {
                    sum += data[idx] * kernel[k]
                    weightSum += kernel[k]
                }
            }
            result[i] = if (weightSum > 0) sum / weightSum else data[i]
        }
        return result
    }

    private fun createGaussianKernel(sigma: Int): FloatArray {
        val size = sigma * 6 + 1
        val kernel = FloatArray(size)
        val s = sigma.toFloat()
        for (i in 0 until size) {
            val x = (i - size / 2).toFloat()
            kernel[i] = kotlin.math.exp(-(x * x) / (2 * s * s))
        }
        return kernel
    }

    private fun detectValleys(velocity: FloatArray, threshold: Float): List<Int> {
        val valleys = mutableListOf<Int>()
        for (i in 1 until velocity.size - 1) {
            if (velocity[i] < velocity[i - 1] &&
                velocity[i] < velocity[i + 1] &&
                velocity[i] < threshold
            ) {
                valleys.add(i)
            }
        }
        return valleys
    }

    /**
     * Detect points where velocity sharply drops after a peak (deceleration).
     * These indicate the END of a move / START of transition.
     * Specifically: find indices where velocity drops by >40% within 2 samples.
     */
    private fun detectAccelerationReversals(velocity: FloatArray): List<Int> {
        val cuts = mutableListOf<Int>()
        if (velocity.size < 4) return cuts
        for (i in 2 until velocity.size - 1) {
            val prev = velocity[i - 2].coerceAtLeast(0.001f)
            val curr = velocity[i]
            // Sharp deceleration: current is <50% of value 2 steps ago AND next is also low
            if (curr < prev * 0.5f && velocity[i + 1] <= curr * 1.2f) {
                cuts.add(i)
            }
        }
        return cuts
    }

    private fun mergeCuts(cuts: List<Long>, minGapMs: Long): List<Long> {
        if (cuts.isEmpty()) return emptyList()
        val merged = mutableListOf(cuts[0])
        for (i in 1 until cuts.size) {
            if (cuts[i] - merged.last() >= minGapMs) {
                merged.add(cuts[i])
            } else {
                // Keep the one that exists (first wins since we already merged it)
            }
        }
        return merged
    }

    /**
     * Recursively split long segments until all are under maxDurationMs.
     * Uses the time-indexed velocity to find the deepest valley within each over-long segment.
     */
    private fun splitLongSegments(
        cuts: List<Long>,
        totalDurationMs: Long,
        velocity: FloatArray,
        timeVelocity: List<Pair<Long, Float>>,
        maxDurationMs: Long
    ): List<Long> {
        val allCuts = (cuts + listOf(0L, totalDurationMs)).distinct().sorted().toMutableList()

        var changed = true
        var iterations = 0
        while (changed && iterations < 200) {
            changed = false
            iterations++
            val newCuts = mutableListOf<Long>()
            for (i in 0 until allCuts.size - 1) {
                val segStart = allCuts[i]
                val segEnd = allCuts[i + 1]
                val duration = segEnd - segStart
                if (duration > maxDurationMs) {
                    // Try valley first
                    val valleySplit = findDeepestValleyInTimeRange(segStart, segEnd, timeVelocity)
                    if (valleySplit != null && valleySplit > segStart + MIN_MOVE_DURATION_MS && valleySplit < segEnd - MIN_MOVE_DURATION_MS) {
                        newCuts.add(valleySplit)
                    } else {
                        // Fallback: find peak and cut at the descent after it
                        val peakSplit = findPostPeakDescent(segStart, segEnd, timeVelocity)
                        if (peakSplit != null && peakSplit > segStart + MIN_MOVE_DURATION_MS && peakSplit < segEnd - MIN_MOVE_DURATION_MS) {
                            newCuts.add(peakSplit)
                        } else {
                            newCuts.add((segStart + segEnd) / 2)
                        }
                    }
                    changed = true
                }
            }
            allCuts.addAll(newCuts)
            allCuts.sort()
            // Deduplicate
            val deduped = allCuts.distinct().toMutableList()
            allCuts.clear()
            allCuts.addAll(deduped)
        }

        return allCuts.filter { it > 0L && it < totalDurationMs }
    }

    /**
     * Find the deepest velocity valley within a time range using the time-indexed data.
     */
    private fun findDeepestValleyInTimeRange(
        startMs: Long, endMs: Long, timeVelocity: List<Pair<Long, Float>>
    ): Long? {
        var minVal = Float.MAX_VALUE
        var minTime: Long? = null
        for ((t, v) in timeVelocity) {
            if (t > startMs && t < endMs && v < minVal) {
                minVal = v
                minTime = t
            }
        }
        return minTime
    }

    /**
     * Fallback split: find the peak velocity in range, then cut at the first descent point after it.
     * Logic: "after the most intense movement there's a deceleration = natural move end"
     */
    private fun findPostPeakDescent(
        startMs: Long, endMs: Long, timeVelocity: List<Pair<Long, Float>>
    ): Long? {
        val rangeData = timeVelocity.filter { it.first in (startMs + 1)..(endMs - 1) }
        if (rangeData.size < 3) return null

        // Find peak
        val peakIdx = rangeData.indices.maxByOrNull { rangeData[it].second } ?: return null

        // Find first descent point after peak (velocity drops below peak × 0.5)
        val peakVal = rangeData[peakIdx].second
        for (i in peakIdx + 1 until rangeData.size) {
            if (rangeData[i].second < peakVal * 0.5f) {
                return rangeData[i].first
            }
        }
        // If no clear descent, cut right after peak
        return if (peakIdx + 1 < rangeData.size) rangeData[peakIdx + 1].first else null
    }

    private fun buildAtomicMoves(
        cuts: List<Long>,
        totalDurationMs: Long,
        fps: Int,
        velocity: FloatArray,
        timeVelocity: List<Pair<Long, Float>>
    ): List<MoveSegment> {
        val boundaries = listOf(0L) + cuts + listOf(totalDurationMs)
        return (0 until boundaries.size - 1).map { i ->
            val startMs = boundaries[i]
            val endMs = boundaries[i + 1]
            val startFrame = ((startMs * fps) / 1000).toInt()
            val endFrame = ((endMs * fps) / 1000).toInt()

            // Find peak velocity in this time range from time-indexed data
            val peakV = timeVelocity
                .filter { it.first in startMs..endMs }
                .maxOfOrNull { it.second } ?: 0f

            MoveSegment(
                id = "move_%02d".format(i + 1),
                startMs = startMs,
                endMs = endMs,
                startFrame = startFrame,
                endFrame = endFrame,
                peakVelocity = peakV,
                boundaryType = "motion"
            )
        }
    }

    /**
     * Smart merge: absorb fragments (<MIN_MOVE_DURATION_MS) into neighboring moves.
     * A fragment is merged to the neighbor with lower peak velocity (more similar intensity).
     * Exception: if the fragment has very high peak velocity relative to neighbors, keep it
     * (it's a genuine quick/explosive move).
     */
    private fun smartMerge(moves: List<MoveSegment>): List<MoveSegment> {
        if (moves.size < 3) return moves
        val result = moves.toMutableList()
        var i = 0
        while (i < result.size) {
            val move = result[i]
            val duration = move.endMs - move.startMs
            if (duration >= MIN_MOVE_DURATION_MS) {
                i++
                continue
            }

            // Check if this is a genuine quick explosive move (keep it)
            val prevPeak = if (i > 0) result[i - 1].peakVelocity else Float.MAX_VALUE
            val nextPeak = if (i < result.size - 1) result[i + 1].peakVelocity else Float.MAX_VALUE
            val neighborAvg = minOf(prevPeak, nextPeak)
            if (move.peakVelocity > neighborAvg * 1.5f) {
                // High intensity short move — keep it
                i++
                continue
            }

            // Merge into the neighbor with lower intensity (more natural absorption)
            if (i == 0) {
                // First move — merge into next
                result[1] = result[1].copy(startMs = move.startMs, startFrame = move.startFrame)
                result.removeAt(0)
            } else if (i == result.size - 1) {
                // Last move — merge into prev
                result[i - 1] = result[i - 1].copy(endMs = move.endMs, endFrame = move.endFrame)
                result.removeAt(i)
            } else {
                // Middle — merge to the side with lower peak
                if (prevPeak <= nextPeak) {
                    result[i - 1] = result[i - 1].copy(endMs = move.endMs, endFrame = move.endFrame)
                } else {
                    result[i + 1] = result[i + 1].copy(startMs = move.startMs, startFrame = move.startFrame)
                }
                result.removeAt(i)
            }
            // Don't increment i — check the merged result again
        }

        // Re-index IDs
        return result.mapIndexed { idx, m -> m.copy(id = "move_%02d".format(idx + 1)) }
    }

    private fun buildPhrases(moves: List<MoveSegment>): List<PhraseSegment> {
        if (moves.isEmpty()) return emptyList()

        val maxVelocity = moves.maxOfOrNull { it.peakVelocity } ?: 1f
        val phrases = mutableListOf<PhraseSegment>()
        var phraseStart = 0
        var phraseIdx = 0

        for (i in moves.indices) {
            val elapsed = moves[i].endMs - moves[phraseStart].startMs
            val moveCount = i - phraseStart + 1

            val shouldCut = elapsed > MAX_PHRASE_DURATION_MS ||
                (moveCount >= 4 && elapsed > 6000L)

            if (shouldCut && moveCount > 1) {
                // End phrase at previous move
                val phraseMoves = moves.subList(phraseStart, i)
                val avgPeak = phraseMoves.map { it.peakVelocity }.average().toFloat()
                phrases.add(PhraseSegment(
                    id = "phrase_${('A' + phraseIdx % 26).toChar()}",
                    startMs = phraseMoves.first().startMs,
                    endMs = phraseMoves.last().endMs,
                    moveIds = phraseMoves.map { it.id },
                    difficulty = (avgPeak / maxVelocity).coerceIn(0f, 1f)
                ))
                phraseIdx++
                phraseStart = i
            }
        }

        // Last phrase
        if (phraseStart < moves.size) {
            val phraseMoves = moves.subList(phraseStart, moves.size)
            val avgPeak = phraseMoves.map { it.peakVelocity }.average().toFloat()
            phrases.add(PhraseSegment(
                id = "phrase_${('A' + phraseIdx % 26).toChar()}",
                startMs = phraseMoves.first().startMs,
                endMs = phraseMoves.last().endMs,
                moveIds = phraseMoves.map { it.id },
                difficulty = (avgPeak / maxVelocity).coerceIn(0f, 1f)
            ))
        }

        return phrases
    }
}
