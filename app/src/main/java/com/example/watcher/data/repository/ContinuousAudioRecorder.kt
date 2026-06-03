package com.example.watcher.data.repository

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Independent continuous audio recorder that runs for the entire task duration.
 * Produces a single .m4a file aligned to system clock T₀.
 *
 * Audio configuration: 48kHz mono, AAC 128kbps, VOICE_RECOGNITION source,
 * NoiseSuppressor + AcousticEchoCanceler enabled when available.
 */
class ContinuousAudioRecorder {
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex: Int = -1
    private var muxerStarted: Boolean = false
    private var recordingThread: Thread? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private val stopRequested = AtomicBoolean(false)
    private var outputFile: File? = null
    private var samplesWritten: Long = 0L

    var startedAtMs: Long = 0L
        private set
    var enhancementInfo: String = ""
        private set
    val isRecording: Boolean
        get() = recordingThread?.isAlive == true

    @android.annotation.SuppressLint("MissingPermission")
    fun start(outputFile: File): Boolean {
        if (isRecording) return false
        this.outputFile = outputFile
        outputFile.parentFile?.mkdirs()
        stopRequested.set(false)
        samplesWritten = 0L

        return runCatching {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) return@runCatching false

            var audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            audioRecord = AudioRecord(
                audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBufferSize * 2, CHUNK_BYTES * 2)
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { audioRecord?.release() }
                audioSource = MediaRecorder.AudioSource.MIC
                audioRecord = AudioRecord(
                    audioSource, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBufferSize * 2, CHUNK_BYTES * 2)
                )
            }
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return@runCatching false

            val sourceLabel = if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION)
                "VOICE_RECOGNITION" else "MIC"
            val audioSessionId = audioRecord?.audioSessionId ?: 0
            val nsEnabled = enableNoiseSuppressor(audioSessionId)
            val aecEnabled = enableEchoCanceler(audioSessionId)
            enhancementInfo = "source=$sourceLabel, ns=$nsEnabled, aec=$aecEnabled, profile=continuous_recording"

            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerTrackIndex = -1
            muxerStarted = false

            audioRecord?.startRecording()
            startedAtMs = System.currentTimeMillis()

            recordingThread = Thread {
                encodeLoop()
            }.apply {
                name = "ContinuousAudioRecorder-${outputFile.nameWithoutExtension}"
                start()
            }
            true
        }.getOrDefault(false)
    }

    fun stop(): ContinuousAudioResult {
        stopRequested.set(true)
        recordingThread?.join(3_000L)
        if (recordingThread?.isAlive == true) {
            recordingThread?.interrupt()
            recordingThread?.join(1_000L)
        }
        recordingThread = null

        val durationMs = samplesWritten * 1_000L / SAMPLE_RATE
        val file = outputFile ?: File("")
        val hasAudio = durationMs > 0L && file.exists() && file.length() > 0L

        release()

        return ContinuousAudioResult(
            file = file,
            durationMs = durationMs,
            startedAtMs = startedAtMs,
            hasAudio = hasAudio,
            enhancementInfo = enhancementInfo
        )
    }

    fun release() {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching {
            if (muxerStarted) muxer?.stop()
        }
        runCatching { muxer?.release() }
        audioRecord = null
        noiseSuppressor = null
        echoCanceler = null
        encoder = null
        muxer = null
        muxerStarted = false
        muxerTrackIndex = -1
    }

    private fun encodeLoop() {
        val record = audioRecord ?: return
        val codec = encoder ?: return
        val pcmBuffer = ByteArray(CHUNK_BYTES)
        val bufferInfo = MediaCodec.BufferInfo()

        while (!stopRequested.get()) {
            val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
            if (bytesRead > 0) {
                feedEncoder(codec, pcmBuffer, bytesRead)
                drainEncoder(codec, bufferInfo, endOfStream = false)
            }
        }

        // Signal end of stream
        val inputIndex = codec.dequeueInputBuffer(10_000L)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex, 0, 0,
                samplesWritten * 1_000_000L / SAMPLE_RATE,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
        drainEncoder(codec, bufferInfo, endOfStream = true)
    }

    private fun feedEncoder(codec: MediaCodec, pcmData: ByteArray, size: Int) {
        var offset = 0
        while (offset < size && !stopRequested.get()) {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex < 0) continue
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
            inputBuffer.clear()
            val writeSize = minOf(size - offset, inputBuffer.remaining())
            inputBuffer.put(pcmData, offset, writeSize)
            val presentationTimeUs = samplesWritten * 1_000_000L / SAMPLE_RATE
            codec.queueInputBuffer(inputIndex, 0, writeSize, presentationTimeUs, 0)
            samplesWritten += writeSize / BYTES_PER_SAMPLE
            offset += writeSize
        }
    }

    private fun drainEncoder(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo, endOfStream: Boolean) {
        val mux = muxer ?: return
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerTrackIndex < 0) {
                        muxerTrackIndex = mux.addTrack(codec.outputFormat)
                        mux.start()
                        muxerStarted = true
                    }
                }
                outputIndex >= 0 -> {
                    if (muxerStarted && muxerTrackIndex >= 0 && bufferInfo.size > 0) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val buffer = codec.getOutputBuffer(outputIndex)
                            if (buffer != null) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                mux.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    private fun enableNoiseSuppressor(audioSessionId: Int): Boolean {
        if (audioSessionId == 0 || !NoiseSuppressor.isAvailable()) return false
        return runCatching {
            noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
            noiseSuppressor?.enabled == true
        }.getOrDefault(false)
    }

    private fun enableEchoCanceler(audioSessionId: Int): Boolean {
        if (audioSessionId == 0 || !AcousticEchoCanceler.isAvailable()) return false
        return runCatching {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
            echoCanceler?.enabled == true
        }.getOrDefault(false)
    }

    private companion object {
        private const val SAMPLE_RATE = 48_000
        private const val CHANNEL_COUNT = 1
        private const val BIT_RATE = 128_000
        private const val CHUNK_BYTES = 4_096
        private const val BYTES_PER_SAMPLE = 2
    }
}

data class ContinuousAudioResult(
    val file: File,
    val durationMs: Long,
    val startedAtMs: Long,
    val hasAudio: Boolean,
    val enhancementInfo: String
)
