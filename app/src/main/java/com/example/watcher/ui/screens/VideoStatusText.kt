package com.example.watcher.ui.screens

import com.example.watcher.data.model.VideoProcessingStatus
import com.example.watcher.data.model.VideoRunStatus

internal fun videoHeadlineStatus(status: VideoProcessingStatus): String {
    val message = status.message.trim()
    return when {
        !status.errorMessage.isNullOrBlank() -> status.errorMessage
        status.stage in setOf(VideoRunStatus.Completed, VideoRunStatus.CompletedDegraded) && status.finalConclusion.isNotBlank() -> status.finalConclusion
        message.isNotBlank() -> message
        else -> fallbackVideoStatusMessage(status)
    }
}

private fun fallbackVideoStatusMessage(status: VideoProcessingStatus): String {
    return when (status.stage) {
        VideoRunStatus.Idle -> "暂无活动的视频处理任务"
        VideoRunStatus.Planning -> "正在生成这次视频任务的执行计划"
        VideoRunStatus.AwaitingConfirmation -> "计划已就绪，可以微调后直接开始处理"
        VideoRunStatus.Recording -> {
            if (status.isAnalysisActive) {
                "录制继续进行中，前序片段已经开始后台分析"
            } else {
                "正在录制新的分析片段"
            }
        }
        VideoRunStatus.Uploading -> "前序片段已录制完成，正在上传到模型服务"
        VideoRunStatus.Preprocessing -> "片段上传完成，正在等待远端预处理"
        VideoRunStatus.Analyzing -> {
            if (status.activeStreamingSegmentIndex > 0) {
                "模型正在分析第 ${status.activeStreamingSegmentIndex}/${status.segmentCount} 段"
            } else {
                "模型正在分析当前视频片段"
            }
        }
        VideoRunStatus.Summarizing -> "所有片段已完成，正在生成最终汇总"
        VideoRunStatus.Completed -> "视频分析已经完成"
        VideoRunStatus.CompletedDegraded -> "分析完成，但完整视频合并失败"
        VideoRunStatus.Failed -> "视频处理失败，请检查错误信息后重试"
        VideoRunStatus.Cancelled -> "当前视频处理任务已取消"
    }
}
