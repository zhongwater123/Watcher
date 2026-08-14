package com.example.watcher.data.repository

internal fun buildClassroomTranscriptDraft(
    title: String,
    stableTranscript: String,
    realtimeInsights: List<String>
): String {
    val safeTitle = title.trim().ifBlank { "课堂记录" }
    val insights = realtimeInsights
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .take(6)
    val transcriptLines = stableTranscript
        .lines()
        .map(String::trim)
        .filter(String::isNotBlank)

    return buildString {
        append("# 临时课堂草稿：")
        appendLine(safeTitle)
        appendLine()
        appendLine("> 基于实时转写和录制中课堂要点生成，音频大纲完成后会自动刷新。")
        appendLine()
        if (insights.isNotEmpty()) {
            appendLine("## 课堂要点")
            insights.forEach { insight ->
                append("- ")
                appendLine(insight)
            }
            appendLine()
        }
        if (transcriptLines.isNotEmpty()) {
            appendLine("## 稳定转写")
            transcriptLines.forEach { line ->
                appendLine(line)
            }
        } else {
            appendLine("正在收尾实时转写，并准备生成音频大纲。")
        }
    }.trim()
}
