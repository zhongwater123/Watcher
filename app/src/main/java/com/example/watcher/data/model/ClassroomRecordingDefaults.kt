package com.example.watcher.data.model

/**
 * 一键录课模式的预设参数。
 * 简洁模式下跳过 AI 规划步骤，直接使用这些默认值构造 VideoProcessTaskDraft。
 */
object ClassroomRecordingDefaults {

    const val DURATION_45_MIN = 2700
    const val DURATION_60_MIN = 3600
    const val DURATION_90_MIN = 5400

    val DURATION_PRESETS: List<Pair<Int, String>> = listOf(
        DURATION_45_MIN to "45 分钟",
        DURATION_60_MIN to "60 分钟",
        DURATION_90_MIN to "90 分钟"
    )

    private const val SEGMENT_DURATION_SECONDS = 120
    private const val CAPTURE_INTERVAL_SECONDS = 120
    private const val SAMPLING_FPS = 1

    private val SEGMENT_ANALYSIS_PROMPT = buildString {
        append("你正在分析一节课堂录像的一个片段。请重点提取：")
        append("1）本片段中讲授的知识点和概念；")
        append("2）教师使用的例子、类比或演示；")
        append("3）板书或幻灯片上的关键内容；")
        append("4）学生互动或提问（如有）；")
        append("5）重要的教学转折（新话题开始、总结等）。")
        append("只返回 JSON，字段为 summary、conclusion、timelineEvents。")
        append("timelineEvents 每一项必须包含 timestampSeconds、title、detail、confidence。")
        append("JSON 字段名保持英文，字段值与说明文字请使用简体中文。")
        append("confidence 优先使用 0 到 1 之间的数字。")
        append("timestampSeconds 使用当前片段内的相对秒数。")
    }

    private val FINAL_SUMMARY_PROMPT = buildString {
        append("请基于全部分片分析结果，生成完整的课堂笔记。包含：")
        append("1）课程主题和核心目标；")
        append("2）知识大纲（按讲授顺序组织）；")
        append("3）重点和难点标注；")
        append("4）关键例子和演示总结；")
        append("5）复习清单（用于课后回顾的要点）；")
        append("6）自测问题（3-5个帮助巩固理解的问题）。")
        append("只返回 JSON，字段为 summary、conclusion、timelineEvents。")
        append("timelineEvents 每一项必须包含 timestampSeconds、title、detail、confidence。")
        append("JSON 字段名保持英文，字段值与说明文字请使用简体中文。")
        append("timestampSeconds 使用整个任务时间线上的绝对秒数。")
    }

    fun buildDraft(courseName: String, durationSeconds: Int = DURATION_45_MIN): VideoProcessTaskDraft {
        val title = courseName.ifBlank { "课堂录制" }
        return VideoProcessTaskDraft(
            taskCategory = VideoTaskCategory.LongHorizonSummary.value,
            strategyReason = "一键录课模式：自动使用课堂优化参数",
            title = title.take(VideoProcessTaskDraft.MAX_TITLE_LENGTH),
            userInput = "课堂录制：$title",
            userRequirement = "完整记录本节课的内容，提取知识点大纲、重点难点、示例、复习要点。",
            sceneContext = "课堂/讲座场景，摄像头对准讲台或黑板/屏幕。",
            segmentAnalysisPrompt = SEGMENT_ANALYSIS_PROMPT,
            finalSummaryPrompt = FINAL_SUMMARY_PROMPT,
            recordingScenario = RecordingScenario.ClassLecture.value,
            speechInputEnabled = false,
            plannedDurationSeconds = durationSeconds,
            plannedSamplingFps = SAMPLING_FPS,
            plannedSegmentDurationSeconds = SEGMENT_DURATION_SECONDS,
            captureIntervalSeconds = CAPTURE_INTERVAL_SECONDS,
            autoStartStreamingOutput = true,
            finalSummaryEnabled = true
        ).normalized()
    }
}
