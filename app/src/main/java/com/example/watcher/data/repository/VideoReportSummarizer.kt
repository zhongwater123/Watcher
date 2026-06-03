package com.example.watcher.data.repository

import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoTimelineEvent
import com.example.watcher.data.remote.ContentItem
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Node 5: Final report summarization.
 * Fuses the audio outline (backbone) with segment narratives (detail)
 * to produce a structured JSON report.
 */
internal class VideoReportSummarizer(
    private val apiService: DoubaoApiService,
    private val planningModel: String,
    private val apiKey: String
) {

    suspend fun summarize(
        task: VideoProcessTaskDraft,
        results: List<SegmentExecutionResult>,
        outlineMarkdown: String = "",
        mergedChunkEvidence: List<VideoMergedChunkResult> = emptyList()
    ): VideoAnalysisResult {
        val payload = buildPayload(
            task = task,
            results = results,
            outlineMarkdown = outlineMarkdown,
            mergedChunkEvidence = mergedChunkEvidence
        )

        val request = DoubaoRequest(
            model = planningModel,
            input = listOf(
                Message(
                    role = "user",
                    content = listOf(ContentItem(type = "input_text", text = payload))
                )
            )
        )

        val rawText = retryRemoteCall {
            apiService.analyzeIntent(
                authorization = bearerToken(),
                request = request
            ).requireOutputText("video summary")
        }

        return ModelOutputParser.parseVideoAnalysis(rawText)
    }

    fun combineSegmentResults(
        results: List<SegmentExecutionResult>,
        timelineEvents: List<VideoTimelineEvent>
    ): VideoAnalysisResult {
        return VideoAnalysisResult(
            summary = results.joinToString("\n") { result ->
                "Segment ${result.segment.segmentIndex}: ${result.analysisResult.summary}"
            },
            conclusion = results.joinToString("\n") { result ->
                "Segment ${result.segment.segmentIndex}: ${result.analysisResult.conclusion}"
            },
            timelineEvents = timelineEvents,
            rawResponse = results.joinToString("\n\n") { result ->
                result.analysisResult.rawResponse
            }
        )
    }

    // region Prompt

    private fun buildPayload(
        task: VideoProcessTaskDraft,
        results: List<SegmentExecutionResult>,
        outlineMarkdown: String = "",
        mergedChunkEvidence: List<VideoMergedChunkResult> = emptyList()
    ): String {
        val scenario = RecordingScenario.fromValue(task.recordingScenario)
        val userSummaryFocus = sanitizeUserInstruction(task.finalSummaryPrompt)
        return buildString {
            appendLine("你是专业的报告撰写助手。请基于以下音频大纲和分片叙述记录，生成最终用户报告。")
            appendLine()
            appendLine("输出格式：JSON，包含以下字段：")
            appendLine("reportType, title, briefSummary, keyConclusions, structuredNotes, outline, knowledgePoints, reviewOrActionItems, evidenceHighlights, coverageNotice, timeline")
            appendLine()
            appendLine("reportType 根据内容选择：learning_notes（课堂/讲座）、meeting_minutes（会议）、training_notes（培训）、interview_notes（访谈）、scene_observation（场景观察）、general_record（其他）")
            appendLine()
            if (outlineMarkdown.isNotBlank()) {
                appendLine("重要：音频大纲是完整音频的分析结果，是报告的主干骨架。分片叙述记录提供了画面细节作为佐证和补充。")
                appendLine("如果分片叙述与音频大纲矛盾，以音频大纲为准。")
                appendLine("将画面证据融入音频大纲的结构中，而非替代它。")
            } else {
                appendLine("无音频大纲可用，仅基于分片叙述记录生成报告。")
                appendLine("按主题合并重复内容，优先采信对话/语音内容，视觉内容作为补充证据。")
            }
            appendLine()
            appendLine("对于 scene_observation 或 general_record 类型，knowledgePoints 和 reviewOrActionItems 可为空数组。")
            appendLine("coverageNotice 用于记录录制中断、音频不清等限制信息。")
            appendLine()
            appendLine("任务目标: ${task.userRequirement}")
            appendLine("场景参考: ${task.sceneContext}")
            appendLine("录制场景: ${scenario.label}")
            appendLine("报告侧重: ${scenario.outputFocus}")
            if (userSummaryFocus.isNotBlank()) {
                appendLine("用户关注重点: $userSummaryFocus")
            }
            if (outlineMarkdown.isNotBlank()) {
                appendLine("AUDIO_OUTLINE (PRIMARY SOURCE — use as report backbone):")
                appendLine(outlineMarkdown)
            }
            appendLine("AUDIO_DIAGNOSTICS_BY_SEGMENT:")
            results.sortedBy { it.segment.segmentIndex }.forEach { result ->
                appendLine("SEGMENT ${result.segment.segmentIndex} AUDIO_ASSET: ${result.audioAssetPath ?: "missing"}")
                appendLine("SEGMENT ${result.segment.segmentIndex} AUDIO_DIAGNOSTICS: ${result.audioDiagnosticsJson.ifBlank { "{}" }}")
            }
            appendLine("SEGMENT_NARRATIVES (audio-visual scene descriptions, screenplay style):")
            results.sortedBy { it.segment.segmentIndex }.forEach { result ->
                if (result.analysisResult.evidenceJson.isNotBlank()) {
                    appendLine("--- SEGMENT ${result.segment.segmentIndex} ---")
                    appendLine(result.analysisResult.evidenceJson)
                    appendLine()
                }
            }
            if (mergedChunkEvidence.isNotEmpty()) {
                appendLine("MERGED_VIDEO_CHUNK_EVIDENCE:")
                mergedChunkEvidence.sortedBy { it.chunkIndex }.forEach { chunk ->
                    appendLine("CHUNK ${chunk.chunkIndex} PATH: ${chunk.filePath}")
                    appendLine("CHUNK ${chunk.chunkIndex} BYTES: ${chunk.fileSizeBytes}")
                    if (chunk.errorMessage != null) {
                        appendLine("CHUNK ${chunk.chunkIndex} ERROR: ${chunk.errorMessage}")
                    } else {
                        appendLine("CHUNK ${chunk.chunkIndex} SUMMARY: ${chunk.summary}")
                        appendLine("CHUNK ${chunk.chunkIndex} EVIDENCE:")
                        appendLine(chunk.evidenceJson)
                    }
                }
            }
            val coverageLimitations = results
                .mapNotNull { it.coverageLimitation }
                .distinct()
            if (coverageLimitations.isNotEmpty()) {
                appendLine("SEGMENT_COVERAGE_LIMITATIONS:")
                coverageLimitations.forEachIndexed { idx, limitation ->
                    appendLine("  ${idx + 1}. $limitation")
                }
                appendLine("Include these limitations in the coverageNotice field of the final report.")
            }
        }
    }

    private fun sanitizeUserInstruction(raw: String): String {
        if (raw.isBlank()) return ""
        val formatPatterns = listOf(
            Regex("只返回[\\s\\S]*?JSON[\\s\\S]*?。"),
            Regex("字段为[\\s\\S]*?。"),
            Regex("timelineEvents[\\s\\S]*?。"),
            Regex("JSON\\s*字段名[\\s\\S]*?。"),
            Regex("confidence[\\s\\S]*?。"),
            Regex("timestampSeconds[\\s\\S]*?。")
        )
        var result = raw
        formatPatterns.forEach { result = result.replace(it, "") }
        return result.trim().takeIf { it.length > 5 } ?: ""
    }

    // endregion

    // region Utilities

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

    private fun bearerToken(): String = "Bearer $apiKey"

    // endregion

    private companion object {
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}
