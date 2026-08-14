package com.example.watcher.data.repository

import android.util.Log
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.watcher.data.local.VideoAudioAssetDao
import com.example.watcher.data.model.RecordingScenario
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

private const val TAG = "Watcher.Video.AudioOutline"

internal class AudioOutlineProcessor(
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
        return if (file.exists() && file.length() > 0L) file else null
    }

    suspend fun buildMasterAudioAsset(
        runId: Long,
        results: List<SegmentExecutionResult>,
        outputRoot: File,
        expectedDurationMs: Long
    ): String? {
        val segmentFiles = results
            .sortedBy { it.segment.segmentIndex }
            .mapNotNull { result ->
                result.audioAssetPath
                    ?.takeIf(String::isNotBlank)
                    ?.let(::File)
                    ?.takeIf(File::exists)
            }
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
        File(asset.localFilePath)
            .takeIf { it.exists() && it.length() > 0L }
            ?.let { file ->
                remoteFileResolver.recordLocalFileBinding(
                    file = file,
                    runId = runId,
                    segmentRunId = null,
                    assetKind = VideoRemoteAssetKind.MasterAudio,
                    mediaType = "audio/mp4"
                )
            }
        return asset.localFilePath
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
            node = "AudioOutlineProcessor",
            model = audioModel,
            requestKind = "audio_outline"
        )
        val startedAt = System.currentTimeMillis()
        Log.d(TAG, "Generating outline: audioFile=${audioFile.name} size=${audioFile.length()} duration=${durationSeconds}s")
        // Extract raw ADTS AAC from the MediaMuxer-produced .m4a for upload.
        // MediaMuxer's MP4 container is detected as video/mp4 by Ark, causing input_audio rejection.
        // Raw ADTS .aac bypasses the container issue entirely.
        val uploadFile = withContext(Dispatchers.IO) { extractAdtsAac(audioFile) } ?: audioFile
        val audioMediaType = if (uploadFile.extension.equals("aac", ignoreCase = true))
            "audio/aac" else "audio/mp4"
        val remoteAudio = remoteFileResolver.resolveAudioFile(
            file = uploadFile,
            runId = runId,
            segmentRunId = null,
            assetKind = VideoRemoteAssetKind.MasterAudio,
            mediaType = audioMediaType
        )
        waitForFileReady(remoteAudio.fileId)

        val scenario = RecordingScenario.fromValue(task.recordingScenario)
        val prompt = buildString {
            appendLine("你是一个专业的内容分析助手。请对以下完整音频生成一份结构化大纲报告。")
            appendLine("要求:")
            appendLine("1. 识别音频中的说话人数量和角色")
            appendLine("2. 按时间顺序列出主题/话题段落（每段标注起止秒数）")
            appendLine("3. 提取每段的关键信息点（具体数据、名称、结论等客观事实）")
            appendLine("4. 生成一份 Markdown 格式的简略报告")
            appendLine("任务背景: ${task.userRequirement}")
            appendLine("场景: ${task.sceneContext}")
            appendLine("录制场景: ${scenario.label}")
            appendLine("音频时长: ${durationSeconds} 秒")
            appendLine("请直接输出 Markdown 格式的大纲报告，包含标题、时间线、关键信息点。使用简体中文。")
        }

        val contentItems = listOf(
            VideoContentItem(type = "input_audio", fileId = remoteAudio.fileId),
            VideoContentItem(type = "input_text", text = prompt)
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
            traceLogger.logPrompt(context, basePrompt = prompt, renderedPrompt = prompt)
            traceLogger.logRequest(
                context,
                aiTracePayload(
                    "model" to request.model,
                    "audioFileId" to remoteAudio.fileId,
                    "mediaType" to audioMediaType,
                    "promptLength" to prompt.length
                )
            )
            val rawText = retryRemoteCall {
                apiService.analyzeVideo(
                    authorization = bearerToken(),
                    request = request
                ).requireOutputText("audio outline generation")
            }

            val durationMs = System.currentTimeMillis() - startedAt
            Log.d(TAG, "Outline generated: length=${rawText.length} firstLine=${rawText.lines().firstOrNull()?.take(60)}")
            val summary = rawText.lines().firstOrNull { it.isNotBlank() }?.removePrefix("# ") ?: ""
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

    /**
     * Extract raw ADTS AAC from an .m4a file, bypassing MediaMuxer's MP4 container.
     * The resulting .aac file is correctly identified as audio by Ark API.
     */
    private fun extractAdtsAac(sourceFile: File): File? {
        if (!sourceFile.exists() || sourceFile.length() == 0L) return null
        val aacFile = File(sourceFile.parent, sourceFile.nameWithoutExtension + ".aac")
        if (aacFile.exists() && aacFile.length() > 0L) return aacFile

        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(sourceFile.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val profile = format.getIntegerOrDefault(MediaFormat.KEY_AAC_PROFILE, 2) // AAC-LC = 2
            val freqIndex = sampleRateToFreqIndex(sampleRate)

            val buffer = java.nio.ByteBuffer.allocateDirect(64 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()

            aacFile.outputStream().buffered().use { out ->
                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    // Write 7-byte ADTS header + raw AAC frame
                    val frameLength = sampleSize + 7
                    val adtsHeader = buildAdtsHeader(profile, freqIndex, channelCount, frameLength)
                    out.write(adtsHeader)
                    // Write AAC frame data
                    val frameData = ByteArray(sampleSize)
                    buffer.position(0)
                    buffer.get(frameData, 0, sampleSize)
                    out.write(frameData)
                    extractor.advance()
                }
            }

            if (aacFile.exists() && aacFile.length() > 0L) aacFile else null
        } catch (e: Exception) {
            aacFile.delete()
            null
        } finally {
            extractor.release()
        }
    }

    private fun buildAdtsHeader(profile: Int, freqIndex: Int, channels: Int, frameLength: Int): ByteArray {
        // ADTS fixed header (7 bytes, no CRC)
        val header = ByteArray(7)
        // Syncword 0xFFF, MPEG-4, Layer 0, no CRC
        header[0] = 0xFF.toByte()
        header[1] = 0xF1.toByte() // 1111 0001 (MPEG-4, no CRC)
        // Profile (AAC-LC=1 in ADTS, stored as profile-1), freq index, channel config
        val profileAdts = (profile - 1) and 0x03
        header[2] = ((profileAdts shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte()
        header[3] = (((channels and 0x03) shl 6) or ((frameLength shr 11) and 0x03)).toByte()
        header[4] = ((frameLength shr 3) and 0xFF).toByte()
        header[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
        header[6] = 0xFC.toByte() // buffer fullness 0x7FF (VBR), 0 raw data blocks
        return header
    }

    private fun sampleRateToFreqIndex(sampleRate: Int): Int {
        return when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3
            44100 -> 4; 32000 -> 5; 24000 -> 6; 22050 -> 7
            16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11
            else -> 3 // default to 48000
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return try { getInteger(key) } catch (_: Exception) { default }
    }

    private suspend fun waitForFileReady(fileId: String) {
        repeat(FILE_POLL_ATTEMPTS) { attempt ->
            val file = retryRemoteCall { apiService.getFile(bearerToken(), fileId) }
            val status = file.status?.lowercase()
            when {
                status == "active" || status == "processed" || status == "ready" || status == "succeeded" -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status)
                    return
                }
                status == "failed" -> {
                    remoteFileResolver.recordRemoteFileStatus(fileId, status, "preprocessing failed")
                    error("Ark file preprocessing failed for file $fileId.")
                }
                else -> {
                    delay(FILE_POLL_INTERVAL_MS)
                    if (attempt == FILE_POLL_ATTEMPTS - 1) {
                        remoteFileResolver.recordRemoteFileStatus(
                            fileId = fileId,
                            status = status ?: "unknown",
                            message = "preprocessing timed out"
                        )
                        error("Ark file preprocessing timed out (last status: $status) for file $fileId.")
                    }
                }
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

    private fun bearerToken() = "Bearer $apiKey"

    companion object {
        private const val FILE_POLL_ATTEMPTS = 150
        private const val FILE_POLL_INTERVAL_MS = 2_000L
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}
