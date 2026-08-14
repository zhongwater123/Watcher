package com.example.watcher.data.repository

import com.example.watcher.data.model.VideoAnalysisResult
import org.json.JSONArray
import org.json.JSONObject

internal enum class ClassroomParseStatus {
    Success,
    Fallback,
    Failed
}

internal data class ClassroomEvidenceRef(
    val evidenceId: String,
    val segmentIndex: Int? = null,
    val timeRange: String = "",
    val source: String = "",
    val summary: String = ""
)

internal data class ClassroomAskableIndexItem(
    val noteBlockId: String,
    val topic: String,
    val evidenceIds: List<String> = emptyList()
)

internal data class ClassroomNoteResult(
    val markdownNote: String,
    val structuredNoteJson: String,
    val summary: String,
    val coverageNotice: String = "",
    val evidenceRefs: List<ClassroomEvidenceRef> = emptyList(),
    val askableIndex: List<ClassroomAskableIndexItem> = emptyList(),
    val rawResponse: String = "",
    val parseStatus: ClassroomParseStatus = ClassroomParseStatus.Success
) {
    fun toVideoAnalysisResult(): VideoAnalysisResult = VideoAnalysisResult(
        summary = summary,
        conclusion = coverageNotice,
        timelineEvents = emptyList(),
        rawResponse = rawResponse.ifBlank { markdownNote },
        structuredNoteJson = structuredNoteJson,
        markdownNote = markdownNote,
        evidenceJson = evidenceRefs.joinToString(prefix = "[", postfix = "]") { ref ->
            JSONObject()
                .put("evidenceId", ref.evidenceId)
                .put("segmentIndex", ref.segmentIndex)
                .put("timeRange", ref.timeRange)
                .put("source", ref.source)
                .put("summary", ref.summary)
                .toString()
        }
    )
}

internal object ClassroomNoteResultParser {
    fun parse(rawText: String): ClassroomNoteResult {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return ClassroomNoteResult(
                markdownNote = "",
                structuredNoteJson = "",
                summary = "",
                rawResponse = rawText,
                parseStatus = ClassroomParseStatus.Failed
            )
        }

        val jsonText = extractJsonObject(trimmed)
        if (jsonText == null) {
            return fallbackFromText(trimmed, ClassroomParseStatus.Fallback)
        }

        return runCatching {
            val root = JSONObject(jsonText)
            val markdownNote = root.optString("markdownNote")
                .takeIf(String::isNotBlank)
                ?: renderMarkdownFromStructuredJson(root)
            val summary = root.optJSONObject("courseOverview")?.optString("summary")
                ?.takeIf(String::isNotBlank)
                ?: root.optString("summary").takeIf(String::isNotBlank)
                ?: markdownNote.lines().firstOrNull { it.isNotBlank() }.orEmpty().removePrefix("#").trim()
            val coverageNotice = root.optString("coverageNotice")
            ClassroomNoteResult(
                markdownNote = markdownNote,
                structuredNoteJson = root.toString(),
                summary = summary,
                coverageNotice = coverageNotice,
                evidenceRefs = parseEvidenceRefs(root.optJSONArray("evidenceRefs")),
                askableIndex = parseAskableIndex(root.optJSONArray("askableIndex")),
                rawResponse = rawText,
                parseStatus = ClassroomParseStatus.Success
            )
        }.getOrElse {
            fallbackFromText(trimmed, ClassroomParseStatus.Fallback)
        }
    }

    private fun fallbackFromText(text: String, status: ClassroomParseStatus): ClassroomNoteResult {
        val summary = text.lines()
            .firstOrNull { it.isNotBlank() }
            ?.removePrefix("#")
            ?.trim()
            .orEmpty()
        return ClassroomNoteResult(
            markdownNote = text,
            structuredNoteJson = "",
            summary = summary,
            rawResponse = text,
            parseStatus = status
        )
    }

    private fun extractJsonObject(text: String): String? {
        val codeBlock = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        val candidate = codeBlock ?: text
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        return if (start >= 0 && end > start) candidate.substring(start, end + 1) else null
    }

    private fun renderMarkdownFromStructuredJson(root: JSONObject): String {
        val overview = root.optJSONObject("courseOverview")
        val title = overview?.optString("title")?.takeIf(String::isNotBlank)
            ?: root.optString("title").takeIf(String::isNotBlank)
            ?: "课堂笔记"
        return buildString {
            appendLine("# $title")
            overview?.optString("summary")?.takeIf(String::isNotBlank)?.let {
                appendLine()
                appendLine(it)
            }
            appendArraySection("学习目标", root.optJSONArray("learningObjectives"))
            appendArraySection("课堂顺序", root.optJSONArray("orderedOutline"))
            appendArraySection("概念与定义", root.optJSONArray("definitions"))
            appendArraySection("例子与演示", root.optJSONArray("examplesAndDemos"))
            appendArraySection("易错点", root.optJSONArray("commonMisunderstandings"))
            appendArraySection("复习清单", root.optJSONArray("reviewChecklist"))
            appendArraySection("自测题", root.optJSONArray("selfTestQuestions"))
            root.optString("coverageNotice").takeIf(String::isNotBlank)?.let {
                appendLine()
                appendLine("## 覆盖说明")
                appendLine(it)
            }
        }.trim()
    }

    private fun StringBuilder.appendArraySection(title: String, array: JSONArray?) {
        if (array == null || array.length() == 0) return
        appendLine()
        appendLine("## $title")
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            val text = when (item) {
                is JSONObject -> item.optString("title")
                    .ifBlank { item.optString("name") }
                    .ifBlank { item.optString("topic") }
                    .ifBlank { item.optString("summary") }
                    .ifBlank { item.toString() }
                else -> item?.toString().orEmpty()
            }
            if (text.isNotBlank()) appendLine("- $text")
        }
    }

    private fun parseEvidenceRefs(array: JSONArray?): List<ClassroomEvidenceRef> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            ClassroomEvidenceRef(
                evidenceId = obj.optString("evidenceId"),
                segmentIndex = obj.optInt("segmentIndex").takeIf { obj.has("segmentIndex") },
                timeRange = obj.optString("timeRange"),
                source = obj.optString("source"),
                summary = obj.optString("summary")
            )
        }.filter { it.evidenceId.isNotBlank() || it.summary.isNotBlank() }
    }

    private fun parseAskableIndex(array: JSONArray?): List<ClassroomAskableIndexItem> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            ClassroomAskableIndexItem(
                noteBlockId = obj.optString("noteBlockId"),
                topic = obj.optString("topic"),
                evidenceIds = obj.optJSONArray("evidenceIds")?.let { ids ->
                    (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty()
            )
        }.filter { it.noteBlockId.isNotBlank() || it.topic.isNotBlank() }
    }
}
