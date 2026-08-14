package com.example.watcher.data.repository

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.example.watcher.data.local.VideoAudioAssetDao
import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoVideoRequest
import com.example.watcher.data.remote.VideoContentItem
import com.example.watcher.data.remote.VideoMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val CLASSROOM_AUDIO_TAG = "Watcher.Classroom.Audio"

internal class ClassroomAudioOutlineProcessor(
    private val apiService: DoubaoApiService,
    private val audioAssetDao: VideoAudioAssetDao,
    private val remoteFileResolver: VideoRemoteFileResolver,
    private val audioAssetBuilder: VideoAudioAssetBuilder,
    private val audioModel: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger
) {
    suspend fun buildMasterAudioFromFiles(
        runId: Long,
        segmentFiles: List<File>,
        outputRoot: File,
        expectedDurationMs: Long
    ): File? {
        if (segmentFiles.isEmpty()) return null
        val asset = withContext(Dispatchers.IO) {
            audioAssetBuilder.buildMasterAudioAssetFromAudioFiles(
                runId = runId,
                audioSources = segmentFiles,
                outputRoot = outputRoot,
                expectedDurationMs = expectedDurationMs
            )
        }
        audioAssetDao.upsert(asset)
        val file = File(asset.localFilePath)
        if (file.exists() && file.length() > 0L) {
            remoteFileResolver.recordLocalFileBinding(
                file = file,
                runId = runId,
                segmentRunId = null,
                assetKind = VideoRemoteAssetKind.MasterAudio,
                mediaType = "audio/mp4"
            )
        }
        return file.takeIf { it.exists() && it.length() > 0L }
    }

    suspend fun buildMasterAudioAsset(
        runId: Long,
        results: List<SegmentExecutionResult>,
        outputRoot: File,
        expectedDurationMs: Long
    ): String? {
        val segmentFiles = results
            .sortedBy { it.segment.segmentIndex }
            .mapNotNull { it.audioAssetPath?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::exists) }
        return buildMasterAudioFromFiles(runId, segmentFiles, outputRoot, expectedDurationMs)?.absolutePath
    }

    suspend fun generateAudioOutline(
        runId: Long,
        audioFile: File,
        task: VideoProcessTaskDraft,
        durationSeconds: Int,
        traceId: String
    ): VideoAnalysisResult {
        val context = VideoAiTraceContext(
            traceId = traceId,
            runId = runId,
            taskId = task.taskId,
            node = "ClassroomAudioOutlineProcessor",
            model = audioModel,
            requestKind = "classroom_audio_outline"
        )
        val startedAt = System.currentTimeMillis()
        val uploadFile = withContext(Dispatchers.IO) { extractAdtsAac(audioFile) } ?: audioFile
        val audioMediaType = if (uploadFile.extension.equals("aac", ignoreCase = true)) {
            "audio/aac"
        } else {
            "audio/mp4"
        }
        val basePrompt = ClassroomPromptBuilder.audioOutlineBasePrompt()
        val renderedPrompt = ClassroomPromptBuilder.audioOutlinePrompt(task, durationSeconds)
        val remoteAudio = remoteFileResolver.resolveAudioFile(
            file = uploadFile,
            runId = runId,
            segmentRunId = null,
            assetKind = VideoRemoteAssetKind.MasterAudio,
            mediaType = audioMediaType
        )
        waitForFileReady(remoteAudio.fileId)
        val contentItems = listOf(
            VideoContentItem(type = "input_audio", fileId = remoteAudio.fileId),
            VideoContentItem(type = "input_text", text = renderedPrompt)
        )
        val request = DoubaoVideoRequest(
            model = audioModel,
            input = listOf(VideoMessage(role = "user", content = contentItems))
        )

        return try {
            traceLogger.beginNode(
                context,
                aiTracePayload(
                    "audioFilePath" to audioFile.absolutePath,
                    "uploadFilePath" to uploadFile.absolutePath,
                    "audioFileId" to remoteAudio.fileId,
                    "mediaType" to audioMediaType,
                    "durationSeconds" to durationSeconds
                )
            )
            traceLogger.logPrompt(context, basePrompt = basePrompt, renderedPrompt = renderedPrompt)
            traceLogger.logRequest(
                context,
                aiTracePayload(
                    "model" to request.model,
                    "audioFileId" to remoteAudio.fileId,
                    "mediaType" to audioMediaType,
                    "promptLength" to renderedPrompt.length
                )
            )
            val rawText = retryRemoteCall {
                apiService.analyzeVideo(
                    authorization = "Bearer $apiKey",
                    request = request
                )
                    .requireOutputText("classroom audio outline")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            val summary = rawText.lines().firstOrNull { it.isNotBlank() }?.removePrefix("#")?.trim().orEmpty()
            traceLogger.logResponse(context, rawText, durationMs)
            traceLogger.logParsed(
                context = context,
                parsedSummary = summary,
                parsedJson = aiTracePayload("parseStatus" to "success", "summary" to summary),
                parseStatus = "success"
            )
            traceLogger.finishNode(context, durationMs)
            VideoAnalysisResult(
                summary = summary,
                conclusion = "",
                timelineEvents = emptyList(),
                rawResponse = rawText,
                markdownNote = rawText
            )
        } catch (error: Throwable) {
            traceLogger.logError(context, error, System.currentTimeMillis() - startedAt)
            throw error
        }
    }

    private fun extractAdtsAac(sourceFile: File): File? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null
        val aacFile = File(sourceFile.parent, sourceFile.nameWithoutExtension + ".aac")
        if (aacFile.exists() && aacFile.length() > 0L) return aacFile
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val profile = runCatching { format.getInteger(MediaFormat.KEY_AAC_PROFILE) }.getOrDefault(2)
            val freqIndex = sampleRateToFreqIndex(sampleRate)
            val buffer = java.nio.ByteBuffer.allocateDirect(64 * 1024)
            aacFile.outputStream().buffered().use { out ->
                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    out.write(buildAdtsHeader(profile, freqIndex, channelCount, sampleSize + 7))
                    val data = ByteArray(sampleSize)
                    buffer.position(0)
                    buffer.get(data, 0, sampleSize)
                    out.write(data)
                    extractor.advance()
                }
            }
            aacFile.takeIf { it.exists() && it.length() > 0L }
        } catch (error: Exception) {
            Log.w(CLASSROOM_AUDIO_TAG, "AAC extraction failed: ${error.message}")
            aacFile.delete()
            null
        } finally {
            extractor.release()
        }
    }

    private fun buildAdtsHeader(profile: Int, freqIndex: Int, channels: Int, frameLength: Int): ByteArray {
        val header = ByteArray(7)
        val profileAdts = (profile - 1) and 0x03
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte()
        header[2] = ((profileAdts shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte()
        header[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte()
        return header
    }

    private fun sampleRateToFreqIndex(sampleRate: Int): Int = when (sampleRate) {
        96000 -> 0
        88200 -> 1
        64000 -> 2
        48000 -> 3
        44100 -> 4
        32000 -> 5
        24000 -> 6
        22050 -> 7
        16000 -> 8
        12000 -> 9
        11025 -> 10
        8000 -> 11
        else -> 3
    }

    private suspend fun waitForFileReady(fileId: String) {
        repeat(FILE_POLL_ATTEMPTS) { attempt ->
            val file = retryRemoteCall { apiService.getFile("Bearer $apiKey", fileId) }
            val status = file.status?.lowercase()
            when {
                status in setOf("active", "processed", "ready", "succeeded") -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status ?: "ready")
                    return
                }
                status == "failed" -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status, "preprocessing failed")
                    error("Ark file preprocessing failed for file $fileId.")
                }
                attempt == FILE_POLL_ATTEMPTS - 1 -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status ?: "unknown", "preprocessing timed out")
                    error("Ark file preprocessing timed out (last status: $status) for file $fileId.")
                }
                else -> delay(FILE_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun <T> retryRemoteCall(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(REMOTE_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException || !error.isRetryableRemoteFailure() || attempt == REMOTE_RETRY_ATTEMPTS - 1) {
                    throw error
                }
                lastError = error
                delay(REMOTE_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Remote call failed.")
    }

    private fun Throwable.isRetryableRemoteFailure(): Boolean {
        val text = message.orEmpty()
        return this is IOException ||
            text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true)
    }

    private companion object {
        private const val FILE_POLL_ATTEMPTS = 150
        private const val FILE_POLL_INTERVAL_MS = 2_000L
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}
