package com.example.watcher.data.repository

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class MjpegVideoRecorder {
    @Suppress("UNUSED_PARAMETER")
    suspend fun recordSegment(
        outputFile: File,
        durationSeconds: Int,
        samplingFps: Int = 0,
        frameProvider: () -> Bitmap?,
        audioEnabled: Boolean = true,
        shouldStopRequested: () -> Boolean = { false }
    ): RecordingResult = withContext(Dispatchers.IO) {
        outputFile.parentFile?.mkdirs()
        val durationMs = durationSeconds.coerceAtLeast(1) * 1_000L
        val firstFrame = waitForFirstFrame(frameProvider)
            ?: throw IllegalStateException("No video frame was available during recording.")
        val safeFirstFrame = normalizeBitmapForEncoding(firstFrame).copy(Bitmap.Config.ARGB_8888, false)
        val muxerSession = MuxerSession(outputFile)
        val videoEncoder = VideoTrackEncoder(
            muxerSession = muxerSession,
            width = safeFirstFrame.width,
            height = safeFirstFrame.height,
            frameRate = RECORDING_FRAME_RATE
        )
        val audioEncoder = if (audioEnabled) AudioTrackEncoder(muxerSession) else null
        val stopRequested = AtomicBoolean(false)
        val startedAt = System.currentTimeMillis()
        var capturedFrameCount = 0
        var interrupted = false
        var lastFrame: Bitmap = safeFirstFrame

        try {
            videoEncoder.start()
            val audioAvailable = audioEncoder?.start() ?: false
            val audioThread = if (audioAvailable) {
                Thread { audioEncoder!!.encodeUntilStopped(stopRequested) }.apply {
                    name = "WatcherSegmentAudio-${outputFile.nameWithoutExtension}"
                    start()
                }
            } else {
                muxerSession.disableAudio()
                null
            }

            var nextFrameAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - startedAt < durationMs && !shouldStopRequested()) {
                frameProvider()
                    ?.let(::normalizeBitmapForEncoding)
                    ?.copy(Bitmap.Config.ARGB_8888, false)
                    ?.let { newFrame ->
                        if (lastFrame !== safeFirstFrame) {
                            lastFrame.recycle()
                        }
                        lastFrame = newFrame
                    }
                videoEncoder.encodeFrame(lastFrame)
                capturedFrameCount += 1
                nextFrameAt += 1_000L / RECORDING_FRAME_RATE
                delay((nextFrameAt - System.currentTimeMillis()).coerceAtLeast(1L))
            }
            interrupted = shouldStopRequested() && System.currentTimeMillis() - startedAt < durationMs
            stopRequested.set(true)
            audioThread?.join(2_000L)
            if (audioThread?.isAlive == true) {
                audioThread.interrupt()
                audioThread.join(1_000L)
            }
        } finally {
            stopRequested.set(true)
            runCatching { audioEncoder?.close() }
            runCatching { videoEncoder.close() }
            runCatching { muxerSession.close() }
            if (lastFrame !== safeFirstFrame) {
                runCatching { lastFrame.recycle() }
            }
            runCatching { safeFirstFrame.recycle() }
        }

        if (capturedFrameCount == 0) {
            outputFile.delete()
            throw IllegalStateException("No video frame was captured during recording.")
        }

        RecordingResult(
            file = outputFile,
            capturedFrameCount = capturedFrameCount,
            durationSeconds = (muxerSession.durationUs / 1_000_000L).toInt().coerceAtLeast(1),
            durationMs = (muxerSession.durationUs / 1_000L).coerceAtLeast(0L),
            interrupted = interrupted,
            hasAudio = muxerSession.hasActualAudioData,
            audioEnhancementInfo = audioEncoder?.enhancementInfo.orEmpty()
        )
    }

    private suspend fun waitForFirstFrame(frameProvider: () -> Bitmap?): Bitmap? {
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < FIRST_FRAME_TIMEOUT_MS) {
            frameProvider()?.let { return it }
            delay(50L)
        }
        return null
    }

    private companion object {
        private const val FIRST_FRAME_TIMEOUT_MS = 3_000L
        private const val RECORDING_FRAME_RATE = 30
    }
}

internal fun normalizeBitmapForEncoding(bitmap: Bitmap): Bitmap {
    val safeWidth = bitmap.width - (bitmap.width % 2)
    val safeHeight = bitmap.height - (bitmap.height % 2)
    if (safeWidth == bitmap.width && safeHeight == bitmap.height) {
        return bitmap
    }
    return Bitmap.createScaledBitmap(bitmap, safeWidth.coerceAtLeast(2), safeHeight.coerceAtLeast(2), true)
}

data class RecordingResult(
    val file: File,
    val capturedFrameCount: Int,
    val durationSeconds: Int,
    val durationMs: Long = durationSeconds * 1_000L,
    val interrupted: Boolean = false,
    val hasAudio: Boolean = false,
    val audioEnhancementInfo: String = ""
)

private class VideoTrackEncoder(
    private val muxerSession: MuxerSession,
    private val width: Int,
    private val height: Int,
    private val frameRate: Int
) : Closeable {
    private val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private lateinit var inputSurface: Surface
    private val bufferInfo = MediaCodec.BufferInfo()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var started = false

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * frameRate * 0.08f).toInt().coerceAtLeast(800_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        started = true
    }

    fun encodeFrame(bitmap: Bitmap) {
        if (!started) return
        val canvas: Canvas = inputSurface.lockCanvas(null)
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, Rect(0, 0, bitmap.width, bitmap.height), Rect(0, 0, width, height), paint)
        } finally {
            inputSurface.unlockCanvasAndPost(canvas)
        }
        drain(endOfStream = false)
    }

    private fun drain(endOfStream: Boolean) {
        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }
        while (true) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerSession.setVideoFormat(encoder.outputFormat)
                }
                outputIndex >= 0 -> {
                    encoder.getOutputBuffer(outputIndex)?.let { buffer ->
                        muxerSession.writeVideoSample(buffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    override fun close() {
        if (!started) return
        runCatching { drain(endOfStream = true) }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { inputSurface.release() }
        started = false
    }
}

private class AudioTrackEncoder(
    private val muxerSession: MuxerSession
) : Closeable {
    private var audioRecord: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var gainControl: AutomaticGainControl? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var started = false
    var enhancementInfo: String = ""
        private set

    @android.annotation.SuppressLint("MissingPermission")
    fun start(): Boolean {
        return runCatching {
            val minBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) return@runCatching false
            var audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            audioRecord = AudioRecord(
                audioSource,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize * 2, AUDIO_CHUNK_BYTES * 2)
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { audioRecord?.release() }
                audioSource = MediaRecorder.AudioSource.MIC
                audioRecord = AudioRecord(
                    audioSource,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBufferSize * 2, AUDIO_CHUNK_BYTES * 2)
                )
            }
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return@runCatching false
            val sourceLabel = if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                "VOICE_RECOGNITION"
            } else {
                "MIC"
            }
            val audioSessionId = audioRecord?.audioSessionId ?: 0
            val nsEnabled = enableNoiseSuppressor(audioSessionId)
            val aecEnabled = enableEchoCanceler(audioSessionId)
            val agcEnabled = false
            enhancementInfo = "source=$sourceLabel, ns=$nsEnabled, aec=$aecEnabled, agc=$agcEnabled, profile=speech_recording_clean"
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            audioRecord?.startRecording()
            started = true
            true
        }.getOrDefault(false)
    }

    fun encodeUntilStopped(stopRequested: AtomicBoolean) {
        val record = audioRecord ?: return
        val codec = encoder ?: return
        val pcmBuffer = ByteArray(AUDIO_CHUNK_BYTES)
        var samplesWritten = 0L
        while (!stopRequested.get()) {
            val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
            if (bytesRead > 0) {
                var readOffset = 0
                while (readOffset < bytesRead && !stopRequested.get()) {
                    val inputIndex = codec.dequeueInputBuffer(10_000L)
                    if (inputIndex < 0) {
                        drain(codec, endOfStream = false)
                        continue
                    }
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: continue
                    inputBuffer.clear()
                    val writeSize = minOf(bytesRead - readOffset, inputBuffer.remaining())
                    inputBuffer.put(pcmBuffer, readOffset, writeSize)
                    val presentationTimeUs = samplesWritten * 1_000_000L / AUDIO_SAMPLE_RATE
                    codec.queueInputBuffer(inputIndex, 0, writeSize, presentationTimeUs, 0)
                    samplesWritten += writeSize / BYTES_PER_PCM16_SAMPLE
                    readOffset += writeSize
                    drain(codec, endOfStream = false)
                }
            }
            drain(codec, endOfStream = false)
        }
        val inputIndex = codec.dequeueInputBuffer(10_000L)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex,
                0,
                0,
                samplesWritten * 1_000_000L / AUDIO_SAMPLE_RATE,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }
        drain(codec, endOfStream = true)
    }

    private fun drain(codec: MediaCodec, endOfStream: Boolean) {
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerSession.setAudioFormat(codec.outputFormat)
                }
                outputIndex >= 0 -> {
                    codec.getOutputBuffer(outputIndex)?.let { buffer ->
                        muxerSession.writeAudioSample(buffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    override fun close() {
        if (!started) return
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { echoCanceler?.release() }
        runCatching { gainControl?.release() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        audioRecord = null
        noiseSuppressor = null
        echoCanceler = null
        gainControl = null
        encoder = null
        started = false
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

    private fun enableGainControl(audioSessionId: Int): Boolean {
        if (audioSessionId == 0 || !AutomaticGainControl.isAvailable()) return false
        return runCatching {
            gainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
            gainControl?.enabled == true
        }.getOrDefault(false)
    }

    private companion object {
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BIT_RATE = 128_000
        private const val AUDIO_CHUNK_BYTES = 4_096
        private const val BYTES_PER_PCM16_SAMPLE = 2
    }
}

private class MuxerSession(outputFile: File) : Closeable {
    private val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var audioDisabled = false
    private var started = false
    private var audioSampleCount = 0
    private var firstVideoPtsUs: Long? = null
    private var firstAudioPtsUs: Long? = null
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var maxWrittenPtsUs = 0L
    val hasActualAudioData: Boolean
        @Synchronized get() = audioTrackIndex >= 0 && audioSampleCount > 0
    val durationUs: Long
        @Synchronized get() = maxWrittenPtsUs.coerceAtLeast(0L)

    @Synchronized
    fun setVideoFormat(format: MediaFormat) {
        if (videoTrackIndex < 0) {
            videoTrackIndex = muxer.addTrack(format)
            maybeStart()
        }
    }

    @Synchronized
    fun setAudioFormat(format: MediaFormat) {
        if (audioTrackIndex < 0 && !audioDisabled) {
            audioTrackIndex = muxer.addTrack(format)
            maybeStart()
        }
    }

    @Synchronized
    fun disableAudio() {
        audioDisabled = true
        maybeStart()
    }

    fun writeVideoSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        writeSample(videoTrackIndex, data, info)
    }

    fun writeAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        writeSample(audioTrackIndex, data, info)
    }

    @Synchronized
    private fun writeSample(trackIndex: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || trackIndex < 0 || info.size <= 0) return
        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return
        data.position(info.offset)
        data.limit(info.offset + info.size)
        val normalizedInfo = MediaCodec.BufferInfo()
        val normalizedPtsUs = normalizePresentationTimeUs(trackIndex, info.presentationTimeUs)
        normalizedInfo.set(info.offset, info.size, normalizedPtsUs, info.flags)
        muxer.writeSampleData(trackIndex, data, normalizedInfo)
        maxWrittenPtsUs = maxWrittenPtsUs.coerceAtLeast(normalizedPtsUs)
        if (trackIndex == audioTrackIndex) audioSampleCount++
    }

    private fun normalizePresentationTimeUs(trackIndex: Int, presentationTimeUs: Long): Long {
        return if (trackIndex == videoTrackIndex) {
            val first = firstVideoPtsUs ?: presentationTimeUs.also { firstVideoPtsUs = it }
            val normalized = (presentationTimeUs - first).coerceAtLeast(0L)
            val monotonic = if (normalized <= lastVideoPtsUs) lastVideoPtsUs + VIDEO_MIN_DELTA_US else normalized
            lastVideoPtsUs = monotonic
            monotonic
        } else {
            val first = firstAudioPtsUs ?: presentationTimeUs.also { firstAudioPtsUs = it }
            val normalized = (presentationTimeUs - first).coerceAtLeast(0L)
            val monotonic = if (normalized <= lastAudioPtsUs) lastAudioPtsUs + AUDIO_MIN_DELTA_US else normalized
            lastAudioPtsUs = monotonic
            monotonic
        }
    }

    @Synchronized
    private fun maybeStart() {
        if (!started && videoTrackIndex >= 0 && (audioDisabled || audioTrackIndex >= 0)) {
            muxer.start()
            started = true
        }
    }

    @Synchronized
    override fun close() {
        runCatching {
            if (started) muxer.stop()
        }
        runCatching { muxer.release() }
    }

    private companion object {
        private const val VIDEO_MIN_DELTA_US = 33_333L
        private const val AUDIO_MIN_DELTA_US = 1_000L
    }
}
