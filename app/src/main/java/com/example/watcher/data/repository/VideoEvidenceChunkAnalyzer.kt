package com.example.watcher.data.repository

import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoVideoRequest
import com.example.watcher.data.remote.VideoContentItem
import com.example.watcher.data.remote.VideoMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Node 6: Merged video chunk evidence analysis.
 * Analyzes large merged video chunks for visual evidence (PPT, board text,
 * demos, scene transitions) as supplementary material for the final report.
 */
internal class VideoEvidenceChunkAnalyzer(
    private val apiService: DoubaoApiService,
    private val videoModel: String,
    private val apiKey: String
) {

    suspend fun analyze(
        fileId: String,
        task: VideoProcessTaskDraft,
        chunkIndex: Int,
        chunkCount: Int
    ): String {
        val contentItems = listOf(
            VideoContentItem(type = "input_video", fileId = fileId),
            VideoContentItem(
                type = "input_text",
                text = buildPrompt(task = task, chunkIndex = chunkIndex, chunkCount = chunkCount)
            )
        )
        val request = DoubaoVideoRequest(
            model = videoModel,
            input = listOf(VideoMessage(role = "user", content = contentItems))
        )
        return retryRemoteCall {
            apiService.analyzeVideo(
                authorization = bearerToken(),
                request = request
            ).requireOutputText("merged video chunk evidence")
        }
    }

    // region Prompt

    private fun buildPrompt(
        task: VideoProcessTaskDraft,
        chunkIndex: Int,
        chunkCount: Int
    ): String {
        val scenario = RecordingScenario.fromValue(task.recordingScenario)
        return buildString {
            appendLine("请以分镜叙述的形式记录这段合并视频中的视觉证据，作为最终报告的补充素材。")
            appendLine("重点关注：PPT/白板文字、产品演示细节、场景转换、屏幕上的文字信息。")
            appendLine("音频内容仅作为辅助校验（最终报告已有完整的音频大纲）。")
            appendLine("按时间顺序分段，标注 **[起始秒-结束秒]**，描述画面中的关键视觉信息。")
            appendLine()
            appendLine("任务背景: ${task.userRequirement}")
            appendLine("场景参考: ${task.sceneContext}")
            appendLine("合并块: $chunkIndex/$chunkCount")
            appendLine("录制场景: ${scenario.label}")
        }
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
