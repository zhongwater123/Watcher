package com.example.watcher.data.repository

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.watcher.data.model.VideoAudioAssetEntity
import com.example.watcher.data.model.VideoAudioAssetType
import com.example.watcher.data.model.VideoSegmentRun
import com.google.gson.GsonBuilder
import java.io.File
import java.nio.ByteBuffer

class VideoAudioAssetBuilder {
    fun buildSegmentAudioAsset(
        runId: Long,
        segment: VideoSegmentRun,
        sourceVideo: File,
        outputRoot: File,
        expectedDurationMs: Long
    ): VideoAudioAssetEntity {
        val outputFile = File(outputRoot, "video_runs/run_${runId}_segment_${segment.segmentIndex}_audio.m4a")
        return buildAudioAsset(
            runId = runId,
            segmentRunId = segment.id,
            segmentIndex = segment.segmentIndex,
            assetType = VideoAudioAssetType.SegmentAudio,
            sourceVideo = sourceVideo,
            outputFile = outputFile,
            expectedDurationMs = expectedDurationMs
        )
    }

    fun buildSegmentAudioAssetFromFile(
        runId: Long,
        segment: VideoSegmentRun,
        audioFile: File,
        expectedDurationMs: Long,
        diagnosticsExtras: Map<String, Any?> = emptyMap()
    ): VideoAudioAssetEntity {
        val now = System.currentTimeMillis()
        val diagnostics = inspectAudioFile(
            audioFile = audioFile,
            expectedDurationMs = expectedDurationMs,
            diagnosticsExtras = diagnosticsExtras
        )
        return VideoAudioAssetEntity(
            runId = runId,
            segmentRunId = segment.id,
            segmentIndex = segment.segmentIndex,
            assetType = VideoAudioAssetType.SegmentAudio.value,
            localFilePath = audioFile.absolutePath,
            durationMs = diagnostics.durationMs,
            sampleRate = diagnostics.sampleRate,
            channelCount = diagnostics.channelCount,
            codecMime = diagnostics.codecMime,
            sourceVideoPath = null,
            diagnosticsJson = audioAssetGson.toJson(diagnostics),
            createdAt = now,
            updatedAt = now
        )
    }

    fun buildMasterAudioAsset(
        runId: Long,
        segmentSources: List<File>,
        outputRoot: File,
        expectedDurationMs: Long
    ): VideoAudioAssetEntity {
        val outputFile = File(outputRoot, "video_runs/run_${runId}_master_audio.m4a")
        return buildAudioAsset(
            runId = runId,
            segmentRunId = null,
            segmentIndex = 0,
            assetType = VideoAudioAssetType.MasterAudio,
            sourceVideo = segmentSources.firstOrNull(),
            sourceVideos = segmentSources,
            outputFile = outputFile,
            expectedDurationMs = expectedDurationMs
        )
    }

    fun buildMasterAudioAssetFromAudioFiles(
        runId: Long,
        audioSources: List<File>,
        outputRoot: File,
        expectedDurationMs: Long
    ): VideoAudioAssetEntity {
        return buildMasterAudioAsset(
            runId = runId,
            segmentSources = audioSources,
            outputRoot = outputRoot,
            expectedDurationMs = expectedDurationMs
        )
    }

    private fun buildAudioAsset(
        runId: Long,
        segmentRunId: Long?,
        segmentIndex: Int?,
        assetType: VideoAudioAssetType,
        sourceVideo: File?,
        sourceVideos: List<File> = sourceVideo?.let { listOf(it) }.orEmpty(),
        outputFile: File,
        expectedDurationMs: Long
    ): VideoAudioAssetEntity {
        val now = System.currentTimeMillis()
        val diagnostics = runCatching {
            extractAudio(
                sourceVideos = sourceVideos,
                outputFile = outputFile,
                expectedDurationMs = expectedDurationMs
            )
        }.getOrElse { error ->
            outputFile.delete()
            AudioAssetDiagnostics(
                exists = false,
                readable = false,
                lengthBytes = 0L,
                durationMs = 0L,
                expectedDurationMs = expectedDurationMs,
                durationDeltaMs = expectedDurationMs,
                sampleRate = null,
                channelCount = null,
                codecMime = "",
                sampleCount = 0,
                audioContinuous = false,
                maxSampleGapUs = 0L,
                sourceVideoPath = sourceVideo?.absolutePath,
                sourceVideoCount = sourceVideos.size,
                errorMessage = error.message ?: error::class.java.simpleName
            )
        }
        return VideoAudioAssetEntity(
            runId = runId,
            segmentRunId = segmentRunId,
            segmentIndex = segmentIndex,
            assetType = assetType.value,
            localFilePath = outputFile.absolutePath,
            durationMs = diagnostics.durationMs,
            sampleRate = diagnostics.sampleRate,
            channelCount = diagnostics.channelCount,
            codecMime = diagnostics.codecMime,
            sourceVideoPath = sourceVideo?.absolutePath,
            diagnosticsJson = audioAssetGson.toJson(diagnostics),
            createdAt = now,
            updatedAt = now
        )
    }

    private fun extractAudio(
        sourceVideos: List<File>,
        outputFile: File,
        expectedDurationMs: Long
    ): AudioAssetDiagnostics {
        val validSources = sourceVideos.filter { it.exists() && it.length() > 0L && findAudioFormat(it) != null }
        if (validSources.isEmpty()) {
            return AudioAssetDiagnostics(
                exists = false,
                readable = false,
                lengthBytes = 0L,
                durationMs = 0L,
                expectedDurationMs = expectedDurationMs,
                durationDeltaMs = expectedDurationMs,
                sampleRate = null,
                channelCount = null,
                codecMime = "",
                sampleCount = 0,
                audioContinuous = false,
                maxSampleGapUs = 0L,
                sourceVideoPath = sourceVideos.firstOrNull()?.absolutePath,
                sourceVideoCount = sourceVideos.size,
                errorMessage = "No readable audio track in source media."
            )
        }

        outputFile.parentFile?.mkdirs()
        outputFile.delete()
        val firstFormat = findAudioFormat(validSources.first())
            ?: error("No readable audio track in source media.")
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outputTrackIndex = muxer.addTrack(firstFormat)
        val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_SIZE)
        var offsetUs = 0L
        var totalSamples = 0
        var maxGapUs = 0L

        try {
            muxer.start()
            validSources.forEach { source ->
                val stats = copyAudioSamples(
                    source = source,
                    outputTrackIndex = outputTrackIndex,
                    muxer = muxer,
                    buffer = buffer,
                    presentationOffsetUs = offsetUs
                )
                totalSamples += stats.sampleCount
                maxGapUs = maxOf(maxGapUs, stats.maxGapUs)
                offsetUs += stats.durationUs.coerceAtLeast(MIN_AUDIO_SEGMENT_OFFSET_US)
            }
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }

        if (totalSamples <= 0) {
            outputFile.delete()
        }

        val durationMs = offsetUs / 1_000L
        val sampleRate = firstFormat.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = firstFormat.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT)
        val codecMime = firstFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        return AudioAssetDiagnostics(
            exists = outputFile.exists(),
            readable = outputFile.canRead(),
            lengthBytes = if (outputFile.exists()) outputFile.length() else 0L,
            durationMs = durationMs,
            expectedDurationMs = expectedDurationMs,
            durationDeltaMs = kotlin.math.abs(durationMs - expectedDurationMs),
            sampleRate = sampleRate,
            channelCount = channelCount,
            codecMime = codecMime,
            sampleCount = totalSamples,
            audioContinuous = totalSamples > 0 && maxGapUs <= MAX_CONTINUOUS_GAP_US,
            maxSampleGapUs = maxGapUs,
            sourceVideoPath = validSources.firstOrNull()?.absolutePath,
            sourceVideoCount = sourceVideos.size,
            errorMessage = null
        )
    }

    private fun copyAudioSamples(
        source: File,
        outputTrackIndex: Int,
        muxer: MediaMuxer,
        buffer: ByteBuffer,
        presentationOffsetUs: Long
    ): AudioCopyStats {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            val inputTrackIndex = findAudioTrackIndex(extractor) ?: return AudioCopyStats()
            extractor.selectTrack(inputTrackIndex)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0
            var firstPtsUs: Long? = null
            var lastPtsUs = 0L
            var lastDeltaUs = DEFAULT_AUDIO_SAMPLE_DELTA_US
            var previousPtsUs: Long? = null
            var maxGapUs = 0L
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                val firstPts = firstPtsUs ?: sampleTimeUs.also { firstPtsUs = it }
                val normalizedPtsUs = (sampleTimeUs - firstPts).coerceAtLeast(0L)
                previousPtsUs?.let { previous ->
                    val delta = normalizedPtsUs - previous
                    if (delta > 0L) {
                        lastDeltaUs = delta
                        maxGapUs = maxOf(maxGapUs, delta)
                    }
                }
                bufferInfo.set(
                    0,
                    sampleSize,
                    presentationOffsetUs + normalizedPtsUs,
                    extractor.sampleFlags
                )
                muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                sampleCount++
                lastPtsUs = normalizedPtsUs
                previousPtsUs = normalizedPtsUs
                extractor.advance()
            }
            AudioCopyStats(
                sampleCount = sampleCount,
                durationUs = if (sampleCount > 0) lastPtsUs + lastDeltaUs else 0L,
                maxGapUs = maxGapUs
            )
        } finally {
            extractor.release()
        }
    }

    private fun findAudioFormat(file: File): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            findAudioTrackIndex(extractor)?.let(extractor::getTrackFormat)
        } catch (_: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun inspectAudioFile(
        audioFile: File,
        expectedDurationMs: Long,
        diagnosticsExtras: Map<String, Any?>
    ): AudioAssetDiagnostics {
        if (!audioFile.exists() || audioFile.length() <= 0L) {
            return AudioAssetDiagnostics(
                exists = false,
                readable = false,
                lengthBytes = 0L,
                durationMs = 0L,
                expectedDurationMs = expectedDurationMs,
                durationDeltaMs = expectedDurationMs,
                sampleRate = null,
                channelCount = null,
                codecMime = "",
                sampleCount = 0,
                audioContinuous = false,
                maxSampleGapUs = 0L,
                sourceVideoPath = null,
                sourceVideoCount = 0,
                errorMessage = "Audio file is missing or empty.",
                extras = diagnosticsExtras
            )
        }
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(audioFile.absolutePath)
            val audioTrackIndex = findAudioTrackIndex(extractor)
                ?: return AudioAssetDiagnostics(
                    exists = audioFile.exists(),
                    readable = audioFile.canRead(),
                    lengthBytes = audioFile.length(),
                    durationMs = 0L,
                    expectedDurationMs = expectedDurationMs,
                    durationDeltaMs = expectedDurationMs,
                    sampleRate = null,
                    channelCount = null,
                    codecMime = "",
                    sampleCount = 0,
                    audioContinuous = false,
                    maxSampleGapUs = 0L,
                    sourceVideoPath = null,
                    sourceVideoCount = 0,
                    errorMessage = "No readable audio track in audio file.",
                    extras = diagnosticsExtras
                )
            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            var sampleCount = 0
            var firstPtsUs: Long? = null
            var previousPtsUs: Long? = null
            var lastPtsUs = 0L
            var lastDeltaUs = DEFAULT_AUDIO_SAMPLE_DELTA_US
            var maxGapUs = 0L
            val buffer = ByteBuffer.allocateDirect(COPY_BUFFER_SIZE)
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                val firstPts = firstPtsUs ?: sampleTimeUs.also { firstPtsUs = it }
                val normalizedPtsUs = (sampleTimeUs - firstPts).coerceAtLeast(0L)
                previousPtsUs?.let { previous ->
                    val delta = normalizedPtsUs - previous
                    if (delta > 0L) {
                        lastDeltaUs = delta
                        maxGapUs = maxOf(maxGapUs, delta)
                    }
                }
                sampleCount++
                lastPtsUs = normalizedPtsUs
                previousPtsUs = normalizedPtsUs
                extractor.advance()
            }
            val durationMs = if (sampleCount > 0) (lastPtsUs + lastDeltaUs) / 1_000L else 0L
            AudioAssetDiagnostics(
                exists = audioFile.exists(),
                readable = audioFile.canRead(),
                lengthBytes = audioFile.length(),
                durationMs = durationMs,
                expectedDurationMs = expectedDurationMs,
                durationDeltaMs = kotlin.math.abs(durationMs - expectedDurationMs),
                sampleRate = format.getIntegerOrNull(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.getIntegerOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                codecMime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
                sampleCount = sampleCount,
                audioContinuous = sampleCount > 0 && maxGapUs <= MAX_CONTINUOUS_GAP_US,
                maxSampleGapUs = maxGapUs,
                sourceVideoPath = null,
                sourceVideoCount = 0,
                errorMessage = null,
                extras = diagnosticsExtras
            )
        } catch (error: Exception) {
            AudioAssetDiagnostics(
                exists = audioFile.exists(),
                readable = audioFile.canRead(),
                lengthBytes = audioFile.length(),
                durationMs = 0L,
                expectedDurationMs = expectedDurationMs,
                durationDeltaMs = expectedDurationMs,
                sampleRate = null,
                channelCount = null,
                codecMime = "",
                sampleCount = 0,
                audioContinuous = false,
                maxSampleGapUs = 0L,
                sourceVideoPath = null,
                sourceVideoCount = 0,
                errorMessage = error.message ?: error::class.java.simpleName,
                extras = diagnosticsExtras
            )
        } finally {
            extractor.release()
        }
    }

    private fun findAudioTrackIndex(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return index
        }
        return null
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? {
        return runCatching {
            if (containsKey(key)) getInteger(key) else null
        }.getOrNull()
    }

    private data class AudioCopyStats(
        val sampleCount: Int = 0,
        val durationUs: Long = 0L,
        val maxGapUs: Long = 0L
    )

    private data class AudioAssetDiagnostics(
        val exists: Boolean,
        val readable: Boolean,
        val lengthBytes: Long,
        val durationMs: Long,
        val expectedDurationMs: Long,
        val durationDeltaMs: Long,
        val sampleRate: Int?,
        val channelCount: Int?,
        val codecMime: String,
        val sampleCount: Int,
        val audioContinuous: Boolean,
        val maxSampleGapUs: Long,
        val sourceVideoPath: String?,
        val sourceVideoCount: Int,
        val errorMessage: String?,
        val extras: Map<String, Any?> = emptyMap()
    )

    private companion object {
        private const val COPY_BUFFER_SIZE = 512 * 1024
        private const val DEFAULT_AUDIO_SAMPLE_DELTA_US = 21_333L
        private const val MIN_AUDIO_SEGMENT_OFFSET_US = 1_000L
        private const val MAX_CONTINUOUS_GAP_US = 250_000L
        private val audioAssetGson = GsonBuilder().serializeNulls().create()
    }

    /**
     * Re-encode a concatenated .m4a into a clean single-pass .m4a.
     * Decodes all AAC frames to PCM, then re-encodes with a fresh MediaCodec AAC encoder
     * into a new MediaMuxer output. Produces a spec-compliant container.
     */
    fun reencodeM4aClean(inputFile: File): File? {
        if (!inputFile.exists() || inputFile.length() == 0L) return null
        val outputFile = File(inputFile.parent, inputFile.nameWithoutExtension + "_clean.m4a")
        outputFile.delete()

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            val audioTrackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(audioTrackIndex)

            val inputFormat = extractor.getTrackFormat(audioTrackIndex)
            val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null

            // Setup decoder
            val decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Setup encoder
            val encoderFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, COPY_BUFFER_SIZE)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // Muxer setup deferred until encoder produces output format
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrackIndex = -1
            var muxerStarted = false

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderOutputDone = false

            try {
                while (!encoderOutputDone) {
                    // Feed decoder from extractor
                    if (!decoderInputDone) {
                        val inIdx = decoder.dequeueInputBuffer(5_000)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val read = extractor.readSampleData(buf, 0)
                            if (read < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                decoderInputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    // Drain decoder → feed encoder
                    if (!decoderOutputDone) {
                        val outIdx = decoder.dequeueOutputBuffer(decInfo, 5_000)
                        if (outIdx >= 0) {
                            if (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                decoderOutputDone = true
                                val encInIdx = encoder.dequeueInputBuffer(5_000)
                                if (encInIdx >= 0) {
                                    encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                }
                            } else if (decInfo.size > 0) {
                                val pcmBuf = decoder.getOutputBuffer(outIdx)!!
                                val encInIdx = encoder.dequeueInputBuffer(5_000)
                                if (encInIdx >= 0) {
                                    val encBuf = encoder.getInputBuffer(encInIdx)!!
                                    val copySize = decInfo.size.coerceAtMost(encBuf.capacity())
                                    pcmBuf.limit(pcmBuf.position() + copySize)
                                    encBuf.put(pcmBuf)
                                    encoder.queueInputBuffer(encInIdx, 0, copySize, decInfo.presentationTimeUs, 0)
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                        }
                    }

                    // Yield CPU to avoid starving other threads
                    Thread.yield()

                    // Drain encoder → write to muxer
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 5_000)
                    when {
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encOutIdx >= 0 -> {
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderOutputDone = true
                            } else if (encInfo.size > 0 && muxerStarted) {
                                val encOutBuf = encoder.getOutputBuffer(encOutIdx)!!
                                encOutBuf.position(encInfo.offset)
                                encOutBuf.limit(encInfo.offset + encInfo.size)
                                muxer.writeSampleData(muxerTrackIndex, encOutBuf, encInfo)
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                        }
                    }
                }
            } finally {
                decoder.stop()
                decoder.release()
                encoder.stop()
                encoder.release()
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                    runCatching { muxer.release() }
                } else {
                    runCatching { muxer.release() }
                }
            }

            if (outputFile.exists() && outputFile.length() > 0L) outputFile else null
        } catch (e: Exception) {
            outputFile.delete()
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Convert an M4A/AAC file to WAV (PCM). Pure Android API, no native code.
     * WAV is reliably identified as audio/wav by remote APIs.
     */
    fun convertToWav(inputFile: File): File? {
        if (!inputFile.exists() || inputFile.length() == 0L) return null
        val outputFile = File(inputFile.parent, inputFile.nameWithoutExtension + ".wav")
        outputFile.delete()

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val pcmChunks = mutableListOf<ByteArray>()
            var totalPcmBytes = 0

            try {
                while (true) {
                    if (!inputDone) {
                        val inIdx = decoder.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = decoder.getInputBuffer(inIdx)!!
                            val read = extractor.readSampleData(buf, 0)
                            if (read < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        if (info.size > 0) {
                            val outBuf = decoder.getOutputBuffer(outIdx)!!
                            val chunk = ByteArray(info.size)
                            outBuf.get(chunk)
                            pcmChunks.add(chunk)
                            totalPcmBytes += chunk.size
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                        break
                    }
                    Thread.yield()
                }
            } finally {
                decoder.stop()
                decoder.release()
            }

            // Write WAV file
            val bitsPerSample = 16
            val byteRate = sampleRate * channelCount * bitsPerSample / 8
            val blockAlign = channelCount * bitsPerSample / 8
            outputFile.outputStream().buffered().use { out ->
                // RIFF header
                out.write("RIFF".toByteArray())
                out.write(intToLittleEndian(36 + totalPcmBytes))
                out.write("WAVE".toByteArray())
                // fmt sub-chunk
                out.write("fmt ".toByteArray())
                out.write(intToLittleEndian(16)) // sub-chunk size
                out.write(shortToLittleEndian(1)) // PCM format
                out.write(shortToLittleEndian(channelCount))
                out.write(intToLittleEndian(sampleRate))
                out.write(intToLittleEndian(byteRate))
                out.write(shortToLittleEndian(blockAlign))
                out.write(shortToLittleEndian(bitsPerSample))
                // data sub-chunk
                out.write("data".toByteArray())
                out.write(intToLittleEndian(totalPcmBytes))
                for (chunk in pcmChunks) {
                    out.write(chunk)
                }
            }

            if (outputFile.exists() && outputFile.length() > 44L) outputFile else null
        } catch (e: Exception) {
            outputFile.delete()
            null
        } finally {
            extractor.release()
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        )
    }
}
