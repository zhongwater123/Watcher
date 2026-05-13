package com.example.watcher.data.repository

import com.example.watcher.data.local.MonitorTaskDao
import com.example.watcher.data.local.TemplateDao
import com.example.watcher.data.local.VideoProcessTaskDao
import com.example.watcher.data.model.MonitorTask
import com.example.watcher.data.model.MonitorTemplateEntity
import com.example.watcher.data.model.VideoProcessTask
import com.example.watcher.data.model.VideoTemplateEntity
import java.util.UUID

class HistoryTemplateConverter(
    private val monitorTaskDao: MonitorTaskDao,
    private val videoProcessTaskDao: VideoProcessTaskDao,
    private val templateDao: TemplateDao
) {
    suspend fun convertMonitorToTemplate(taskId: Long): Result<MonitorTemplateEntity> {
        val task = monitorTaskDao.getTaskById(taskId)
            ?: return Result.failure(IllegalStateException("源任务已被删除，无法转换"))
        return Result.success(task.toTemplateEntity())
    }

    suspend fun convertVideoToTemplate(taskId: Long): Result<VideoTemplateEntity> {
        val task = videoProcessTaskDao.getTaskById(taskId)
            ?: return Result.failure(IllegalStateException("源任务已被删除，无法转换"))
        return Result.success(task.toTemplateEntity())
    }

    suspend fun saveMonitorTemplate(entity: MonitorTemplateEntity) {
        templateDao.upsertMonitor(entity)
    }

    suspend fun saveVideoTemplate(entity: VideoTemplateEntity) {
        templateDao.upsertVideo(entity)
    }

    fun exportMonitorAsShareText(entity: MonitorTemplateEntity): String {
        return TemplateShareManager.exportMonitorTemplate(entity)
    }

    fun exportVideoAsShareText(entity: VideoTemplateEntity): String {
        return TemplateShareManager.exportVideoTemplate(entity)
    }

    private fun MonitorTask.toTemplateEntity(): MonitorTemplateEntity {
        return MonitorTemplateEntity(
            templateId = historyTemplateId(),
            label = title,
            description = title.take(50),
            userRequirement = userRequirement,
            originalSceneDescription = originalSceneDescription,
            checkIntervalSeconds = checkInterval,
            promptTemplate = promptTemplate,
            monitorMode = monitorMode.name,
            targetTrigger = targetTrigger.name,
            baselineSource = baselineSource.name,
            isDefault = false,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun VideoProcessTask.toTemplateEntity(): VideoTemplateEntity {
        return VideoTemplateEntity(
            templateId = historyTemplateId(),
            label = title,
            description = title.take(50),
            taskCategory = taskCategory ?: "",
            strategyReason = strategyReason,
            userRequirement = userRequirement,
            sceneContext = sceneContext,
            segmentAnalysisPrompt = segmentAnalysisPrompt,
            finalSummaryPrompt = finalSummaryPrompt,
            recordingDurationSeconds = plannedDurationSeconds,
            segmentDurationSeconds = plannedSegmentDurationSeconds,
            captureIntervalSeconds = captureIntervalSeconds,
            samplingFps = plannedSamplingFps,
            autoStartStreamingOutput = autoStartStreamingOutput,
            finalSummaryEnabled = finalSummaryEnabled,
            isDefault = false,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun historyTemplateId(): String {
        return "history_${UUID.randomUUID().toString().take(8)}"
    }
}
