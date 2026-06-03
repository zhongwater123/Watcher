package com.example.watcher.data.local.pose

import android.util.Log
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.UniversalAudioInputStream
import be.tarsos.dsp.onsets.ComplexOnsetDetector
import be.tarsos.dsp.onsets.OnsetHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wraps TarsosDSP for onset detection and BPM estimation from WAV files.
 * Runs entirely on-device, typically 2-5 seconds for a 3-minute audio track.
 */
class TarsosDspAnalyzer {

    data class DspResult(
        val estimatedBpm: Float,
        val onsets: List<OnsetEvent>,
        val durationMs: Long
    )

    data class OnsetEvent(
        val timestampMs: Long,
        val strength: Float
    )

    /**
     * Analyze a WAV file for onset detection and BPM estimation.
     * The WAV file should be 16-bit PCM (as produced by VideoAudioAssetBuilder.convertToWav).
     */
    fun analyze(wavFile: File): DspResult {
        Log.i(TAG, "Starting DSP analysis: ${wavFile.name} (${wavFile.length() / 1024}KB)")
        val wavBytes = wavFile.readBytes()
        if (wavBytes.size < 44) {
            Log.e(TAG, "WAV file too small: ${wavBytes.size} bytes")
            return DspResult(0f, emptyList(), 0L)
        }

        // Parse WAV header
        val headerBuf = ByteBuffer.wrap(wavBytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        headerBuf.position(22)
        val channelCount = headerBuf.short.toInt()
        val sampleRate = headerBuf.int
        headerBuf.position(34)
        val bitsPerSample = headerBuf.short.toInt()

        Log.i(TAG, "WAV format: ${sampleRate}Hz, ${channelCount}ch, ${bitsPerSample}bit")

        if (bitsPerSample != 16) {
            Log.e(TAG, "Unsupported bits per sample: $bitsPerSample (expected 16)")
            return DspResult(0f, emptyList(), 0L)
        }

        // Calculate duration
        val pcmDataSize = wavBytes.size - 44
        val sampleCount = pcmDataSize / (channelCount * 2)
        val durationMs = (sampleCount.toLong() * 1000L) / sampleRate
        Log.i(TAG, "PCM data: ${pcmDataSize / 1024}KB, ${sampleCount} samples, duration=${durationMs}ms")

        // Use TarsosDSP AudioDispatcher with UniversalAudioInputStream
        val onsets = mutableListOf<OnsetEvent>()
        val bufferSize = 1024
        val overlap = 512

        val format = TarsosDSPAudioFormat(
            sampleRate.toFloat(), bitsPerSample, channelCount, true, false
        )
        val pcmStream = ByteArrayInputStream(wavBytes, 44, pcmDataSize)
        val audioStream = UniversalAudioInputStream(pcmStream, format)
        val dispatcher = AudioDispatcher(audioStream, bufferSize, overlap)

        val onsetDetector = ComplexOnsetDetector(bufferSize)
        onsetDetector.setHandler(OnsetHandler { time, salience ->
            onsets.add(OnsetEvent(
                timestampMs = (time * 1000.0).toLong(),
                strength = salience.toFloat().coerceIn(0f, 1f)
            ))
        })
        dispatcher.addAudioProcessor(onsetDetector)
        Log.i(TAG, "Starting AudioDispatcher.run()...")
        val startTime = System.currentTimeMillis()
        dispatcher.run()
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "AudioDispatcher finished in ${elapsed}ms, detected ${onsets.size} onsets")

        // Estimate BPM from onset intervals
        val bpm = estimateBpm(onsets, durationMs)
        Log.i(TAG, "Estimated BPM: $bpm")

        return DspResult(
            estimatedBpm = bpm,
            onsets = onsets,
            durationMs = durationMs
        )
    }

    companion object {
        private const val TAG = "BeatAnalysis"
    }

    /**
     * Estimate BPM from onset intervals using histogram peak detection.
     * Looks for the most common inter-onset interval in the 60-200 BPM range.
     */
    private fun estimateBpm(onsets: List<OnsetEvent>, durationMs: Long): Float {
        if (onsets.size < 4) return 0f

        // Calculate intervals between consecutive onsets
        val intervals = mutableListOf<Long>()
        for (i in 1 until onsets.size) {
            val interval = onsets[i].timestampMs - onsets[i - 1].timestampMs
            // Filter to plausible beat intervals: 300ms (200 BPM) to 1000ms (60 BPM)
            if (interval in 300..1000) {
                intervals.add(interval)
            }
        }

        if (intervals.isEmpty()) return 0f

        // Build histogram with 10ms bins
        val binSize = 10L
        val histogram = mutableMapOf<Long, Int>()
        intervals.forEach { interval ->
            val bin = (interval / binSize) * binSize
            histogram[bin] = (histogram[bin] ?: 0) + 1
        }

        // Find the peak bin
        val peakBin = histogram.maxByOrNull { it.value }?.key ?: return 0f
        val peakIntervalMs = peakBin + binSize / 2 // center of bin

        return if (peakIntervalMs > 0) {
            (60000f / peakIntervalMs).coerceIn(60f, 200f)
        } else 0f
    }
}
