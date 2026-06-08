package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoVideoRequest
import com.example.watcher.data.remote.VideoContentItem
import com.example.watcher.data.remote.VideoMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

private const val TAG = "Watcher.Video.Analyzer"

/**
 * Node 3: Segment analysis.
 * Sends a single merged-segment video to the vision model and receives
 * a screenplay-style Markdown narrative combining audio and visual facts.
 */
internal class VideoSegmentAnalyzer(
    private val apiService: DoubaoApiService,
    private val videoModel: String,
    private val apiKey: String
) {

    suspend fun analyze(
        fileId: String,
        audioFileId: String?,
        isMergedInput: Boolean,
        task: VideoProcessTaskDraft,
        segmentNumber: Int,
        segmentCount: Int
    ): VideoAnalysisResult {
        val prompt = buildPrompt(task = task, segmentNumber = segmentNumber, segmentCount = segmentCount)
        Log.d(TAG, "Segment $segmentNumber/$segmentCount prompt built length=${prompt.length} merged=$isMergedInput")
        val contentItems = buildList {
            if (!isMergedInput) {
                audioFileId?.takeIf(String::isNotBlank)?.let { audioId ->
                    add(VideoContentItem(type = "input_audio", fileId = audioId))
                }
            }
            add(VideoContentItem(type = "input_video", fileId = fileId))
            add(VideoContentItem(type = "input_text", text = prompt))
        }

        val request = DoubaoVideoRequest(
            model = videoModel,
            input = listOf(VideoMessage(role = "user", content = contentItems))
        )

        Log.d(TAG, "Segment $segmentNumber API request sending model=$videoModel items=${contentItems.size}")
        val rawText = doRetryRemoteCall {
            apiService.analyzeVideo(
                authorization = "Bearer $apiKey",
                request = request
            ).requireOutputText("video segment analysis")
        }
        Log.d(TAG, "Segment $segmentNumber API response length=${rawText.length} preview=${rawText.take(80)}")

        return VideoAnalysisResult(
            summary = extractNarrativeSummary(rawText),
            conclusion = "",
            timelineEvents = emptyList(),
            rawResponse = rawText,
            evidenceJson = rawText,
            markdownNote = rawText
        )
    }

    private fun buildPrompt(
        task: VideoProcessTaskDraft,
        segmentNumber: Int,
        segmentCount: Int
    ): String {
        val scenario = RecordingScenario.fromValue(task.recordingScenario)
        val userFocus = doSanitizeUserInstruction(task.segmentAnalysisPrompt)
        return buildString {
            appendLine("你是专业的视听内容记录员。请以电影分镜剧本的形式，客观记录本段视频中的所有音视频事实。")
            appendLine()
            appendLine("格式要求：")
            appendLine("- 按时间顺序分段，每段标注 **[起始秒-结束秒]**")
            appendLine("- 每个时间段内，音频和画面交织叙述")
            appendLine("- 音频内嵌在视频文件中，请直接从视频轨道提取语音内容")
            appendLine("- 如果语音不清晰，标注 [语音不清]，不要编造")
            appendLine("- 使用简体中文")
            appendLine()
            appendLine("信息优先级：")
            appendLine("- 语音/对话是第一优先信息源：对话原文、发言要点必须完整记录，尽可能还原原话而非概括")
            appendLine("- 不要遗漏任何人的发言，即使是简短的回应、附和或插话")
            appendLine("- 视觉信息分两类处理：")
            appendLine("  - 承载信息的元素（黑板板书、PPT文字、屏幕演示内容、正在操作的产品、正在创作的作品、人物的关键动作和表演等）必须详细描述其具体内容，并随事件推进持续跟踪变化")
            appendLine("  - 装饰性背景（花盆、吊顶、墙面装饰等环境布置）首次出现时一句带过即可，无需反复描述")
            appendLine()
            appendLine("质量标准：")
            appendLine("- 读者仅通过这份文档就能复现这段视频内发生的一切")
            appendLine("- 场景中承载信息的视觉元素（如PPT翻页后的新内容、板书新增的文字、演示操作的步骤变化）必须结合事件推进逐步描述，而不是笼统概括")
            appendLine("- 人物说话时的神态、肢体语言应与对话内容同步呈现，形成完整的现场感")
            appendLine()
            appendLine("任务背景: ${task.userRequirement}")
            appendLine("场景参考: ${task.sceneContext}")
            appendLine("当前分片: $segmentNumber/$segmentCount")
            appendLine("分片时长: ${task.plannedSegmentDurationSeconds} 秒")
            appendLine("录制场景: ${scenario.label}")
            if (userFocus.isNotBlank()) {
                appendLine("用户关注重点: $userFocus")
            }
        }
    }

    private fun extractNarrativeSummary(rawText: String): String {
        val speechLine = rawText.lines().firstOrNull { line ->
            line.isNotBlank() && line.contains("\uFF1A")
        }
        if (speechLine != null) {
            return speechLine
                .replace(Regex("\\*\\*\\[\\d+-\\d+]\\*\\*\\s*"), "")
                .trim()
                .take(150)
        }
        return rawText.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            ?.replace(Regex("\\*\\*\\[\\d+-\\d+]\\*\\*\\s*"), "")
            .orEmpty()
            .trim()
            .take(150)
    }

    private fun doSanitizeUserInstruction(raw: String): String {
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

    private suspend fun <T> doRetryRemoteCall(block: suspend () -> T): T {
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
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
    }
}
