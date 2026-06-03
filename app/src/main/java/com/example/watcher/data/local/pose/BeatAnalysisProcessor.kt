package com.example.watcher.data.local.pose

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.example.watcher.data.local.AppDatabase
import com.example.watcher.data.remote.DoubaoVideoRequest
import com.example.watcher.data.remote.VideoContentItem
import com.example.watcher.data.remote.VideoMessage
import com.example.watcher.data.remote.extractOutputText
import com.example.watcher.data.repository.ArkConfig
import com.example.watcher.data.repository.VideoAudioAssetBuilder
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.ByteBuffer

/**
 * Orchestrates beat analysis: audio extraction → DSP + upload parallel → LLM analysis → .beat file.
 * File ID is cached in PoseVideoSession.audioFileId to avoid re-uploads.
 */
class BeatAnalysisProcessor(private val context: Context) {

    companion object {
        private const val TAG = "BeatAnalysis"
    }

    sealed class AnalysisProgress {
        data object ExtractingAudio : AnalysisProgress()
        data object RunningDSP : AnalysisProgress()
        data object UploadingAudio : AnalysisProgress()
        data object WaitingLLM : AnalysisProgress()
        data object WritingBeatFile : AnalysisProgress()
        data class Complete(val beatFilePath: String) : AnalysisProgress()
        data class Failed(val error: String, val fallbackPath: String?) : AnalysisProgress()
    }

    private val apiService = com.example.watcher.data.remote.RetrofitClient.doubaoApiService
    private val apiKey = ArkConfig.apiKey
    private val audioModel = ArkConfig.videoAnalysisModel
    private val dao = AppDatabase.getDatabase(context).poseVideoSessionDao()
    private val audioAssetBuilder = VideoAudioAssetBuilder()
    private val dspAnalyzer = TarsosDspAnalyzer()
    private val gson = Gson()

    /**
     * Run the full beat analysis pipeline.
     * Returns the path to the generated .beat file.
     */
    suspend fun analyze(
        session: PoseVideoSession,
        onProgress: (AnalysisProgress) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "pose_data").apply { mkdirs() }
        val beatFile = File(outputDir, "session_${session.id}.beat")
        Log.i(TAG, "Starting beat analysis for session ${session.id}, video=${session.sourceVideoPath}")

        try {
            // Step 1: Extract WAV for local DSP analysis
            onProgress(AnalysisProgress.ExtractingAudio)
            val videoFile = File(session.sourceVideoPath)
            val wavFile = audioAssetBuilder.convertToWav(videoFile)
            Log.i(TAG, "Audio extraction: wav=${wavFile?.length() ?: 0}B, video=${videoFile.length()}B")

            if (wavFile == null && !videoFile.exists()) {
                throw IllegalStateException("No audio track in video")
            }

            // Step 2: DSP analysis (local, ~3s)
            onProgress(AnalysisProgress.RunningDSP)
            val dspResult = runDsp(wavFile)
            Log.i(TAG, "DSP result: bpm=${dspResult?.estimatedBpm}, onsets=${dspResult?.onsets?.size}")

            // Step 3: Upload video (may take a while for large files)
            onProgress(AnalysisProgress.UploadingAudio)
            val fileId = resolveFileId(session)
            Log.i(TAG, "Upload fileId: $fileId")

            // Step 3: LLM analysis
            onProgress(AnalysisProgress.WaitingLLM)
            val llmResult = runLlmAnalysis(fileId, dspResult)
            Log.i(TAG, "LLM result: bpm=${llmResult?.correctedBpm}, firstBeat=${llmResult?.firstBeatMs}, accents=${llmResult?.accents?.size}, segments=${llmResult?.segments?.size}")

            // Step 4: Write .beat file
            onProgress(AnalysisProgress.WritingBeatFile)
            writeBeatFile(beatFile, session, dspResult, llmResult)
            Log.i(TAG, "Beat file written: ${beatFile.length()} bytes")

            // Update session — re-read from DB to preserve audioFileId saved during upload
            val latestSession = dao.getById(session.id) ?: session
            dao.upsert(latestSession.copy(
                beatFilePath = beatFile.absolutePath,
                updatedAt = System.currentTimeMillis()
            ))

            onProgress(AnalysisProgress.Complete(beatFile.absolutePath))
            beatFile.absolutePath

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Beat analysis failed: ${e.message}", e)
            // Try DSP-only fallback
            val fallbackPath = tryDspOnlyFallback(session, beatFile, e)
            onProgress(AnalysisProgress.Failed(
                error = e.message ?: "Unknown error",
                fallbackPath = fallbackPath
            ))
            fallbackPath ?: throw e
        }
    }

    /**
     * Extract raw ADTS AAC from video, bypassing MediaMuxer container issues.
     * This is the only format reliably accepted by Ark API as audio/aac.
     */
    private fun extractAdtsAac(videoFile: File): File? {
        if (!videoFile.exists() || videoFile.length() == 0L) return null
        val aacFile = File(videoFile.parent, videoFile.nameWithoutExtension + "_beat.aac")
        if (aacFile.exists() && aacFile.length() > 0L) return aacFile

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(videoFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val profile = try { format.getInteger(MediaFormat.KEY_AAC_PROFILE) } catch (_: Exception) { 2 }
            val freqIndex = sampleRateToFreqIndex(sampleRate)

            val buffer = ByteBuffer.allocateDirect(64 * 1024)

            aacFile.outputStream().buffered().use { out ->
                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    val frameLength = sampleSize + 7
                    val adtsHeader = buildAdtsHeader(profile, freqIndex, channelCount, frameLength)
                    out.write(adtsHeader)
                    val frameData = ByteArray(sampleSize)
                    buffer.position(0)
                    buffer.get(frameData, 0, sampleSize)
                    out.write(frameData)
                    extractor.advance()
                }
            }

            if (aacFile.exists() && aacFile.length() > 0L) aacFile else null
        } catch (e: Exception) {
            Log.e(TAG, "ADTS extraction failed: ${e.message}")
            aacFile.delete()
            null
        } finally {
            extractor.release()
        }
    }

    private fun buildAdtsHeader(profile: Int, freqIndex: Int, channels: Int, frameLength: Int): ByteArray {
        val header = ByteArray(7)
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        val profileAdts = (profile - 1) and 0x03
        header[2] = ((profileAdts shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte()
        header[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        return header
    }

    private fun sampleRateToFreqIndex(sampleRate: Int): Int = when (sampleRate) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
        44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
        16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
        else -> 3
    }

    private suspend fun runDsp(wavFile: File?): TarsosDspAnalyzer.DspResult? {
        if (wavFile == null || !wavFile.exists()) return null
        return withTimeoutOrNull(15_000L) {
            withContext(Dispatchers.Default) {
                try {
                    val result = dspAnalyzer.analyze(wavFile)
                    Log.i(TAG, "DSP analysis: ${result.onsets.size} onsets, BPM=${result.estimatedBpm}")
                    result
                } catch (e: Exception) {
                    Log.e(TAG, "DSP analysis failed: ${e.message}")
                    null
                }
            }
        }.also { if (it == null) Log.w(TAG, "DSP timed out after 15s, skipping (LLM will handle beats)") }
    }

    /**
     * Get or create a file_id for this session's source video.
     * Uploads the full MP4 so LLM can see dance movements AND hear audio.
     * Caches in PoseVideoSession.audioFileId (reused as general remoteFileId).
     */
    private suspend fun resolveFileId(session: PoseVideoSession): String {
        // Check cached fileId
        if (session.audioFileId.isNotBlank()) {
            try {
                val fileInfo = apiService.getFile(
                    authorization = "Bearer $apiKey",
                    fileId = session.audioFileId
                )
                val status = fileInfo.status?.lowercase()
                if (status == "active" || status == "processed" || status == "ready" || status == "succeeded") {
                    Log.i(TAG, "Reusing cached fileId: ${session.audioFileId} (status=$status)")
                    return session.audioFileId
                }
            } catch (_: Exception) {
                Log.i(TAG, "Cached fileId invalid, re-uploading")
            }
        }

        // Upload source video MP4 (model gets audio + visual context)
        val videoFile = File(session.sourceVideoPath)
        Log.i(TAG, "Uploading video ${videoFile.name} (${videoFile.length() / 1024}KB) for beat analysis")
        val response = apiService.uploadFile(
            authorization = "Bearer $apiKey",
            purpose = "user_data".toRequestBody("text/plain".toMediaType()),
            preprocessConfigs = mapOf(
                "preprocess_configs[video][fps]" to "1"
                    .toRequestBody("text/plain".toMediaType())
            ),
            file = MultipartBody.Part.createFormData(
                name = "file",
                filename = videoFile.name,
                body = videoFile.asRequestBody("video/mp4".toMediaType())
            )
        )
        val fileId = response.resolvedId()
            ?: throw IllegalStateException("Video upload succeeded but file_id was missing")
        Log.i(TAG, "Upload complete, fileId=$fileId, waiting for processing...")

        // Wait for processing
        waitForFileReady(fileId)
        Log.i(TAG, "File ready: $fileId")

        // Persist file_id to session
        dao.upsert(session.copy(
            audioFileId = fileId,
            updatedAt = System.currentTimeMillis()
        ))

        return fileId
    }

    private suspend fun waitForFileReady(fileId: String) {
        repeat(90) { attempt ->
            val info = apiService.getFile(
                authorization = "Bearer $apiKey",
                fileId = fileId
            )
            val status = info.status?.lowercase()
            Log.i(TAG, "File poll #$attempt: status=$status")
            when {
                status == "active" || status == "processed" || status == "ready" || status == "succeeded" -> return
                status == "failed" || status == "error" -> throw IllegalStateException("File processing failed: $fileId")
            }
            delay(2000L)
        }
        throw IllegalStateException("File processing timeout: $fileId")
    }

    private suspend fun runLlmAnalysis(
        fileId: String,
        dspResult: TarsosDspAnalyzer.DspResult?
    ): LlmBeatResult? {
        val promptText = BeatAnalysisSchemas.buildBeatAnalysisPrompt(
            dspBpm = dspResult?.estimatedBpm ?: 0f,
            onsetCount = dspResult?.onsets?.size ?: 0,
            durationMs = dspResult?.durationMs ?: 0L,
            onsetSamples = dspResult?.onsets?.map { it.timestampMs } ?: emptyList()
        )

        val request = DoubaoVideoRequest(
            model = audioModel,
            input = listOf(VideoMessage(
                role = "user",
                content = listOf(
                    VideoContentItem(type = "input_video", fileId = fileId),
                    VideoContentItem(type = "input_text", text = promptText)
                )
            )),
            responseFormat = BeatAnalysisSchemas.beatAnalysisResponseFormat,
            thinking = null
        )

        val response = try {
            apiService.analyzeVideo(
                authorization = "Bearer $apiKey",
                request = request
            )
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 400) {
                val errorBody = e.response()?.errorBody()?.string()
                Log.w(TAG, "json_schema not supported with video, retrying without: $errorBody")
                // Retry without response_format (structured output may not work with video)
                apiService.analyzeVideo(
                    authorization = "Bearer $apiKey",
                    request = request.copy(responseFormat = null, thinking = null)
                )
            } else {
                val errorBody = e.response()?.errorBody()?.string()
                Log.e(TAG, "LLM API error ${e.code()}: $errorBody")
                throw e
            }
        }

        val outputText = response.extractOutputText() ?: return null
        Log.i(TAG, "LLM response full: $outputText")
        return parseLlmResponse(outputText)
    }

    private fun parseLlmResponse(jsonText: String): LlmBeatResult? {
        return try {
            // Strip markdown code blocks if present
            val cleaned = jsonText.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = gson.fromJson(cleaned, JsonObject::class.java)
            val correctedBpm = json.get("correctedBpm")?.asFloat ?: 0f
            val firstBeatMs = json.get("firstBeatMs")?.asInt ?: 0
            val ts = json.getAsJsonObject("timeSignature")
            val tsNum = ts?.get("numerator")?.asInt ?: 4
            val tsDen = ts?.get("denominator")?.asInt ?: 4

            val tempoChanges = json.getAsJsonArray("tempoChanges")?.mapNotNull { elem ->
                val obj = elem.asJsonObject
                val timestampMs = obj.get("timestampMs")?.asInt ?: return@mapNotNull null
                val newBpm = obj.get("newBpm")?.asFloat ?: return@mapNotNull null
                TempoChange(timestampMs, newBpm)
            } ?: emptyList()

            val accents = json.getAsJsonArray("accents")?.mapNotNull { elem ->
                val obj = elem.asJsonObject
                val timestampMs = obj.get("timestampMs")?.asInt ?: return@mapNotNull null
                val intensity = obj.get("intensity")?.asFloat ?: 0.8f
                AccentMark(timestampMs, intensity)
            } ?: emptyList()

            val segments = json.getAsJsonArray("segments")?.mapNotNull { elem ->
                val obj = elem.asJsonObject
                LlmSegment(
                    startMs = obj.get("startMs")?.asInt ?: return@mapNotNull null,
                    endMs = obj.get("endMs")?.asInt ?: return@mapNotNull null,
                    type = obj.get("type")?.asString ?: "verse",
                    energyLevel = obj.get("energyLevel")?.asFloat ?: 0.5f
                )
            } ?: emptyList()

            val phrases = json.getAsJsonArray("phrases")?.mapNotNull { elem ->
                val obj = elem.asJsonObject
                LlmPhrase(
                    startMs = obj.get("startMs")?.asInt ?: return@mapNotNull null,
                    endMs = obj.get("endMs")?.asInt ?: return@mapNotNull null,
                    beatCount = obj.get("beatCount")?.asInt ?: 8,
                    phraseType = obj.get("phraseType")?.asString ?: "8-count",
                    difficulty = obj.get("difficulty")?.asFloat ?: 0.5f
                )
            } ?: emptyList()

            Log.i(TAG, "Parsed: bpm=$correctedBpm, firstBeat=$firstBeatMs, tempoChanges=${tempoChanges.size}, accents=${accents.size}, segments=${segments.size}, phrases=${phrases.size}")
            LlmBeatResult(correctedBpm, firstBeatMs, tsNum, tsDen, tempoChanges, accents, segments, phrases)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM response: ${e.message}")
            null
        }
    }

    private fun writeBeatFile(
        file: File,
        session: PoseVideoSession,
        dspResult: TarsosDspAnalyzer.DspResult?,
        llmResult: LlmBeatResult?
    ) {
        val fps = session.sourceFps.coerceAtLeast(1)
        val totalFrames = session.frameCount.coerceAtLeast(1)
        val durationMs = if (session.clipEndMs > session.clipStartMs) {
            session.clipEndMs - session.clipStartMs
        } else {
            session.sourceVideoDurationMs
        }

        val bpm = llmResult?.correctedBpm ?: dspResult?.estimatedBpm ?: 0f
        val hasLlm = llmResult != null
        val dspOnly = !hasLlm && dspResult != null

        // Generate full beat grid from BPM + firstBeatMs
        val beats: List<BeatFileFormat.BeatEntry> = if (bpm > 0f) {
            val firstBeatMs = llmResult?.firstBeatMs ?: 0
            val beatsPerMeasure = llmResult?.tsNum ?: 4
            val beatIntervalMs = (60000.0 / bpm)
            val accentSet = llmResult?.accents?.map { it.timestampMs }?.toSet() ?: emptySet()

            val generatedBeats = mutableListOf<BeatFileFormat.BeatEntry>()
            var currentMs = firstBeatMs.toDouble()
            var beatIndex = 0
            var currentBpm = bpm
            val tempoChanges = llmResult?.tempoChanges ?: emptyList()
            var nextTempoIdx = 0

            while (currentMs < durationMs) {
                // Check for tempo change
                if (nextTempoIdx < tempoChanges.size && currentMs >= tempoChanges[nextTempoIdx].timestampMs) {
                    currentBpm = tempoChanges[nextTempoIdx].newBpm
                    nextTempoIdx++
                }

                val tsMs = currentMs.toInt()
                val isDownbeat = beatIndex % beatsPerMeasure == 0
                val isAccent = accentSet.any { kotlin.math.abs(it - tsMs) < 50 }

                val beatType = when {
                    isAccent -> BeatFileFormat.BeatType.ACCENT
                    isDownbeat -> BeatFileFormat.BeatType.DOWNBEAT
                    else -> BeatFileFormat.BeatType.UPBEAT
                }

                generatedBeats.add(BeatFileFormat.BeatEntry(
                    timestampMs = tsMs,
                    frameIndex = BeatFileFormat.frameIndexForTimestamp(tsMs, fps, totalFrames),
                    strength = if (isDownbeat) 1f else if (isAccent) 0.9f else 0.7f,
                    beatType = beatType,
                    confidence = if (hasLlm) 0.95f else 0.7f
                ))

                currentMs += 60000.0 / currentBpm
                beatIndex++
            }
            Log.i(TAG, "Generated ${generatedBeats.size} beats from grid (BPM=$bpm, firstBeat=$firstBeatMs)")
            generatedBeats
        } else emptyList()

        // Build segment entries from LLM
        val segments: List<BeatFileFormat.SegmentEntry> = llmResult?.segments?.map { s ->
            BeatFileFormat.SegmentEntry(
                startMs = s.startMs,
                endMs = s.endMs,
                startFrameIdx = BeatFileFormat.frameIndexForTimestamp(s.startMs, fps, totalFrames),
                endFrameIdx = BeatFileFormat.frameIndexForTimestamp(s.endMs, fps, totalFrames),
                segmentType = when (s.type) {
                    "intro" -> BeatFileFormat.SegmentType.INTRO
                    "chorus" -> BeatFileFormat.SegmentType.CHORUS
                    "bridge" -> BeatFileFormat.SegmentType.BRIDGE
                    "outro" -> BeatFileFormat.SegmentType.OUTRO
                    "break" -> BeatFileFormat.SegmentType.BREAK
                    else -> BeatFileFormat.SegmentType.VERSE
                },
                energyLevel = s.energyLevel
            )
        } ?: emptyList()

        // Build phrase entries from LLM
        val phrases: List<BeatFileFormat.PhraseEntry> = llmResult?.phrases?.map { p ->
            BeatFileFormat.PhraseEntry(
                startMs = p.startMs,
                endMs = p.endMs,
                startFrameIdx = BeatFileFormat.frameIndexForTimestamp(p.startMs, fps, totalFrames),
                endFrameIdx = BeatFileFormat.frameIndexForTimestamp(p.endMs, fps, totalFrames),
                beatCountInPhrase = p.beatCount,
                phraseType = when (p.phraseType) {
                    "4-count" -> BeatFileFormat.PhraseType.FOUR_COUNT
                    "custom" -> BeatFileFormat.PhraseType.CUSTOM
                    else -> BeatFileFormat.PhraseType.EIGHT_COUNT
                },
                difficulty = p.difficulty
            )
        } ?: emptyList()

        val header = BeatFileFormat.BeatFileHeader(
            totalFrameCount = totalFrames,
            fps = fps.toShort(),
            videoDurationMs = durationMs,
            bpmTenths = (bpm * 10).toInt(),
            beatCount = beats.size,
            segmentCount = segments.size,
            phraseCount = phrases.size,
            timeSignatureNum = (llmResult?.tsNum ?: 4).toShort(),
            timeSignatureDen = (llmResult?.tsDen ?: 4).toShort(),
            flags = (if (hasLlm) 1 else 0) or (if (dspOnly) 2 else 0)
        )

        BeatFileFormat.createFile(file, header, beats, segments, phrases)
    }

    private suspend fun tryDspOnlyFallback(
        session: PoseVideoSession,
        beatFile: File,
        originalError: Exception
    ): String? {
        Log.i(TAG, "Attempting DSP-only fallback after: ${originalError.message}")
        return try {
            val videoFile = File(session.sourceVideoPath)
            val wavFile = audioAssetBuilder.convertToWav(videoFile) ?: return null
            val dspResult = dspAnalyzer.analyze(wavFile)
            if (dspResult.onsets.isEmpty()) {
                Log.i(TAG, "DSP fallback: no onsets detected")
                return null
            }
            Log.i(TAG, "DSP fallback: ${dspResult.onsets.size} onsets, BPM=${dspResult.estimatedBpm}")

            writeBeatFile(beatFile, session, dspResult, null)
            val latestSession = dao.getById(session.id) ?: session
            dao.upsert(latestSession.copy(
                beatFilePath = beatFile.absolutePath,
                updatedAt = System.currentTimeMillis()
            ))
            Log.i(TAG, "DSP-only .beat file written: ${beatFile.length()} bytes")
            beatFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "DSP fallback also failed: ${e.message}")
            null
        }
    }

    // Internal LLM response models
    private data class LlmBeatResult(
        val correctedBpm: Float,
        val firstBeatMs: Int,
        val tsNum: Int,
        val tsDen: Int,
        val tempoChanges: List<TempoChange>,
        val accents: List<AccentMark>,
        val segments: List<LlmSegment>,
        val phrases: List<LlmPhrase>
    )

    private data class TempoChange(val timestampMs: Int, val newBpm: Float)
    private data class AccentMark(val timestampMs: Int, val intensity: Float)
    private data class LlmSegment(val startMs: Int, val endMs: Int, val type: String, val energyLevel: Float)
    private data class LlmPhrase(val startMs: Int, val endMs: Int, val beatCount: Int, val phraseType: String, val difficulty: Float)
}
