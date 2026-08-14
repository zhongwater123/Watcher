package com.example.watcher.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorStatus
import com.example.watcher.data.model.TimelineEventEntity
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoRunStatus
import com.example.watcher.data.model.VideoTaskCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class HubPage(
    val pageIndex: Int,
    val label: String,
    val icon: ImageVector
) {
    Monitor(0, "实时监控", Icons.Default.Sensors),
    Hub(1, "总览", Icons.Default.Home),
    Analysis(2, "视频分析", Icons.Default.Analytics),
    History(3, "历史记录", Icons.Default.History),
    Templates(4, "统一配置", Icons.Default.Dashboard);

    companion object {
        fun fromPage(page: Int): HubPage = entries.firstOrNull { it.pageIndex == page } ?: Hub
    }
}

internal data class StepState(
    val label: String,
    val completed: Boolean,
    val active: Boolean
)

internal data class WorkspaceHeaderContent(
    val eyebrow: String,
    val title: String,
    val subtitle: String
)

internal fun workspaceHeaderFor(page: HubPage): WorkspaceHeaderContent {
    return when (page) {
        HubPage.Monitor -> WorkspaceHeaderContent(
            eyebrow = "左滑页 / 实时监控",
            title = "实时监控工作台",
            subtitle = "用三张卡片完成预览、复用历史任务和配置新任务。"
        )

        HubPage.Hub -> WorkspaceHeaderContent(
            eyebrow = "Watcher",
            title = "Beyond Vision",
            subtitle = "开机，磁吸，启动！"
        )

        HubPage.Analysis -> WorkspaceHeaderContent(
            eyebrow = "右滑页 / 视频分析",
            title = "视频分析工作台",
            subtitle = "沿用同一视频源，生成计划、确认参数并启动分析。"
        )

        HubPage.History -> WorkspaceHeaderContent(
            eyebrow = "历史归档 / 全部记录",
            title = "历史数据管理",
            subtitle = "统一查看实时监控与视频分析的执行记录、结果和本地媒体文件。"
        )

        HubPage.Templates -> WorkspaceHeaderContent(
            eyebrow = "配置中心 / 统一配置",
            title = "统一配置界面",
            subtitle = "统一管理监控模板、视频模板、智囊团模板、专家库、模型接口与 AI 观众配置。"
        )
    }
}

internal data class HubSummaryModel(
    val eyebrow: String,
    val title: String,
    val subtitle: String,
    val progress: Float,
    val tags: List<String>,
    val accent: Color,
    val icon: ImageVector
)

internal fun buildHubSummary(
    currentTask: IntentResult?,
    monitorStatus: MonitorStatus,
    currentVideoTask: VideoProcessTaskDraft?,
    videoProcessingStatus: VideoProcessingStatus
): HubSummaryModel {
    return when {
        videoProcessingStatus.isBusy || videoProcessingStatus.stage !in setOf(
            VideoRunStatus.Idle,
            VideoRunStatus.Completed,
            VideoRunStatus.CompletedDegraded,
            VideoRunStatus.Failed,
            VideoRunStatus.Cancelled
        ) -> HubSummaryModel(
            eyebrow = "视频分析进行中",
            title = currentVideoTask?.title ?: "视频分析任务",
            subtitle = videoHeadlineStatus(videoProcessingStatus),
            progress = videoProgress(videoProcessingStatus),
            tags = listOf(
                videoStageLabel(videoProcessingStatus.stage),
                "已录 ${videoProcessingStatus.recordedSegmentCount}/${videoProcessingStatus.segmentCount} 段",
                "已析 ${videoProcessingStatus.analyzedSegmentCount}/${videoProcessingStatus.segmentCount} 段"
            ),
            accent = Color(0xFF0058BE),
            icon = Icons.Default.Analytics
        )

        monitorStatus.isRunning -> HubSummaryModel(
            eyebrow = "实时监控运行中",
            title = currentTask?.title ?: "持续监控任务",
            subtitle = buildMonitorHubSubtitle(monitorStatus, currentTask),
            progress = if (monitorStatus.isPaused) 0.45f else 0.72f,
            tags = listOf(
                if (monitorStatus.isPaused) "已暂停" else "巡检中",
                checkResultLabel(monitorStatus.lastResult)
            ),
            accent = Color(0xFF006C49),
            icon = Icons.Default.Sensors
        )

        currentVideoTask != null -> HubSummaryModel(
            eyebrow = "待启动的视频分析",
            title = currentVideoTask.title,
            subtitle = currentVideoTask.userRequirement,
            progress = 0.36f,
            tags = listOf(
                videoTaskCategoryLabel(currentVideoTask.taskCategory),
                buildVideoRhythmShortLabel(
                    segmentDurationSeconds = currentVideoTask.plannedSegmentDurationSeconds,
                    captureIntervalSeconds = currentVideoTask.captureIntervalSeconds
                )
            ),
            accent = Color(0xFF005DA8),
            icon = Icons.Default.Analytics
        )

        currentTask != null -> HubSummaryModel(
            eyebrow = "待启动的监控任务",
            title = currentTask.title,
            subtitle = currentTask.userRequirement,
            progress = 0.28f,
            tags = listOf("任务已生成", "每 ${currentTask.checkInterval} 秒巡检"),
            accent = Color(0xFF006C49),
            icon = Icons.Default.Sensors
        )

        else -> HubSummaryModel(
            eyebrow = "当前没有活动任务",
            title = "今天要和 Watcher\n一起做什么？",
            subtitle = "首页会持续显示连接状态、当前任务和最近进展。",
            progress = 0.1f,
            tags = listOf("首页总览", "等待任务"),
            accent = Color(0xFF0058BE),
            icon = Icons.Default.Videocam
        )
    }
}

private fun buildMonitorHubSubtitle(
    monitorStatus: MonitorStatus,
    currentTask: IntentResult?
): String {
    val summary = monitorStatus.lastSummary.ifBlank {
        currentTask?.userRequirement ?: "正在持续巡检当前画面。"
    }
    val remark = monitorStatus.lastRemark.trim()
    return if (remark.isBlank()) summary else "$summary\n$remark"
}

internal fun videoProgress(status: VideoProcessingStatus): Float {
    val totalSegments = status.segmentCount.coerceAtLeast(1)
    val recordingProgress = if (status.segmentCount == 0) {
        0f
    } else {
        status.recordedSegmentCount.toFloat() / totalSegments.toFloat()
    }
    val analysisProgress = if (status.segmentCount == 0) {
        0f
    } else {
        status.analyzedSegmentCount.toFloat() / totalSegments.toFloat()
    }
    return when (status.stage) {
        VideoRunStatus.Idle -> 0.08f
        VideoRunStatus.Planning -> 0.18f
        VideoRunStatus.AwaitingConfirmation -> 0.34f
        VideoRunStatus.Recording -> 0.34f + (recordingProgress * 0.28f)
        VideoRunStatus.Uploading,
        VideoRunStatus.Preprocessing,
        VideoRunStatus.Analyzing -> 0.46f + (analysisProgress * 0.4f)
        VideoRunStatus.Summarizing -> 0.92f
        VideoRunStatus.Completed,
        VideoRunStatus.CompletedDegraded -> 1f
        VideoRunStatus.Failed,
        VideoRunStatus.Cancelled -> 0.64f
    }
}

internal fun videoStageLabel(stage: VideoRunStatus): String {
    return when (stage) {
        VideoRunStatus.Idle -> "空闲"
        VideoRunStatus.Planning -> "生成计划"
        VideoRunStatus.AwaitingConfirmation -> "待确认"
        VideoRunStatus.Recording -> "录制中"
        VideoRunStatus.Uploading -> "上传中"
        VideoRunStatus.Preprocessing -> "预处理中"
        VideoRunStatus.Analyzing -> "分析中"
        VideoRunStatus.Summarizing -> "汇总中"
        VideoRunStatus.Completed -> "已完成"
        VideoRunStatus.CompletedDegraded -> "完成(降级)"
        VideoRunStatus.Failed -> "失败"
        VideoRunStatus.Cancelled -> "已取消"
    }
}

internal fun checkResultLabel(result: CheckResult): String {
    return when (result) {
        CheckResult.NONE -> "等待结果"
        CheckResult.ALERT -> "告警"
        CheckResult.WARNING -> "预警"
        CheckResult.NORMAL -> "正常"
        CheckResult.UNKNOWN -> "未知"
    }
}

internal fun videoTaskCategoryLabel(taskCategory: String?): String {
    return when (VideoTaskCategory.fromValue(taskCategory)) {
        VideoTaskCategory.LongHorizonSummary -> "长时段回顾"
        VideoTaskCategory.ContinuousWatch -> "连续观察"
        VideoTaskCategory.ShortBurstDense -> "短时高密度观察"
        null -> "通用视频任务"
    }
}

internal fun buildVideoRhythmSummary(
    segmentDurationSeconds: Int,
    captureIntervalSeconds: Int,
    segmentCount: Int
): String {
    return "执行节奏：每隔 ${captureIntervalSeconds}s 录制 ${segmentDurationSeconds}s，预计 ${segmentCount} 段"
}

internal fun buildVideoRhythmShortLabel(
    segmentDurationSeconds: Int,
    captureIntervalSeconds: Int
): String {
    return "${captureIntervalSeconds}s / ${segmentDurationSeconds}s"
}

internal fun formatTimelineSeconds(seconds: Int): String {
    val minutes = seconds / 60
    val remainingSeconds = seconds % 60
    return "%02d:%02d".format(minutes, remainingSeconds)
}

internal fun formatDateTime(timestamp: Long): String {
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

internal fun buildMonitorTaskJson(task: IntentResult): String {
    return """
        {
          "title": "${escapeJsonValue(task.title)}",
          "userRequirement": "${escapeJsonValue(task.userRequirement)}",
          "checkInterval": ${task.checkInterval},
          "monitorMode": "${escapeJsonValue(task.monitorMode.name)}",
          "targetTrigger": "${escapeJsonValue(task.targetTrigger.name)}",
          "baselineSource": "${escapeJsonValue(task.baselineSource.name)}",
          "promptTemplate": "${escapeJsonValue(task.promptTemplate)}"
        }
    """.trimIndent()
}

internal fun buildVideoTaskJson(task: VideoProcessTaskDraft): String {
    return """
        {
          "templateId": "${escapeJsonValue(task.templateId.orEmpty())}",
          "templateLabel": "${escapeJsonValue(task.templateLabel.orEmpty())}",
          "taskCategory": "${escapeJsonValue(task.taskCategory.orEmpty())}",
          "strategyReason": "${escapeJsonValue(task.strategyReason)}",
          "title": "${escapeJsonValue(task.title)}",
          "userRequirement": "${escapeJsonValue(task.userRequirement)}",
          "sceneContext": "${escapeJsonValue(task.sceneContext)}",
          "plannedDurationSeconds": ${task.plannedDurationSeconds},
          "plannedSamplingFps": ${task.plannedSamplingFps},
          "plannedSegmentDurationSeconds": ${task.plannedSegmentDurationSeconds},
          "captureIntervalSeconds": ${task.captureIntervalSeconds},
          "plannedSegmentCount": ${task.plannedSegmentCount},
          "autoStartStreamingOutput": ${task.autoStartStreamingOutput},
          "finalSummaryEnabled": ${task.finalSummaryEnabled},
          "segmentAnalysisPrompt": "${escapeJsonValue(task.segmentAnalysisPrompt)}",
          "finalSummaryPrompt": "${escapeJsonValue(task.finalSummaryPrompt)}",
          "confirmationNotes": "${escapeJsonValue(task.confirmationNotes)}"
        }
    """.trimIndent()
}

internal fun visibleTimelineEvents(
    selectedRunEvents: List<TimelineEventEntity>,
    status: VideoProcessingStatus
): List<TimelineEventEntity> {
    return if (selectedRunEvents.isNotEmpty()) {
        selectedRunEvents.take(3)
    } else {
        status.timelineEvents.take(3).map {
            TimelineEventEntity(
                runId = status.activeRunId ?: 0L,
                timestampSeconds = it.timestampSeconds,
                title = it.title,
                detail = it.detail,
                confidence = it.confidence
            )
        }
    }
}

private fun escapeJsonValue(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
}
