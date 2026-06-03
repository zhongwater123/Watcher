package com.example.watcher.ui.screens

import com.example.watcher.data.model.VideoHistoryDetail
import com.example.watcher.data.model.VideoSegmentRun
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class VideoReportTranscriptEntry(
    val timestampSeconds: Int,
    val text: String,
    val speakerHint: String,
    val confidence: Float,
    val uncertain: Boolean,
    val segmentIndex: Int
)

internal fun parseStructuredAudioFacts(segments: List<VideoSegmentRun>): List<VideoReportTranscriptEntry> {
    return segments.flatMap { segment ->
        // Narrative format has no structured audioFacts — skip
        if (isNarrativeEvidence(segment.evidenceJson)) return@flatMap emptyList()
        val root = parseEvidenceObject(segment.evidenceJson) ?: return@flatMap emptyList()
        val audioFacts = root.get("audioFacts") ?: return@flatMap emptyList()
        if (!audioFacts.isJsonArray) return@flatMap emptyList()

        val segmentOffsetSeconds = ((segment.mediaStartMs ?: 0L) / 1000L).toInt()
        audioFacts.asJsonArray.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val obj = element.asJsonObject
            VideoReportTranscriptEntry(
                timestampSeconds = segmentOffsetSeconds + (obj.get("timestampSeconds")?.asInt ?: return@mapNotNull null),
                text = obj.get("text")?.asString?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null,
                speakerHint = obj.get("speakerHint")?.asString?.trim().orEmpty(),
                confidence = runCatching { obj.get("confidence")?.asFloat }.getOrNull() ?: 0.5f,
                uncertain = runCatching { obj.get("uncertain")?.asBoolean }.getOrNull() ?: false,
                segmentIndex = segment.segmentIndex
            )
        }
    }.sortedBy { it.timestampSeconds }
}

internal data class VideoReportEvidenceSegment(
    val segmentIndex: Int,
    val durationSeconds: Int,
    val mediaStartMs: Long?,
    val mediaEndMs: Long?,
    val summary: String,
    val transcriptExtracts: List<String>,
    val speechKeyPoints: List<String>,
    val visualEvidence: List<String>,
    val screenOrBoardContent: List<String>,
    val demonstrations: List<String>,
    val timelineEvents: List<String>,
    val uncertainties: List<String>,
    val localFilePath: String?,
    /** Full Markdown narrative for new-format segments. Empty for legacy JSON segments. */
    val narrativeMarkdown: String = ""
)

internal data class VideoReportOutlineItem(
    val title: String,
    val children: List<String> = emptyList()
)

internal data class VideoReportActionItem(
    val text: String,
    val source: String? = null
)

internal data class VideoReportContent(
    val reportType: String,
    val summary: String,
    val conclusion: String,
    val markdownBody: String,
    val outlineItems: List<VideoReportOutlineItem>,
    val keyPoints: List<String>,
    val actionItems: List<VideoReportActionItem>,
    val evidenceHighlights: List<String>,
    val coverageNotice: String,
    val timelineEvents: List<String>
)

private data class ReportPayload(
    val reportType: String = "",
    val summary: String = "",
    val conclusion: String = "",
    val markdownBody: String = "",
    val outline: List<String> = emptyList(),
    val keyPoints: List<String> = emptyList(),
    val followUps: List<String> = emptyList(),
    val evidenceHighlights: List<String> = emptyList(),
    val coverageNotice: String = "",
    val timelineEvents: List<String> = emptyList()
)

internal fun parseVideoReportEvidence(segments: List<VideoSegmentRun>): List<VideoReportEvidenceSegment> {
    return segments.map { segment ->
        val isNarrative = isNarrativeEvidence(segment.evidenceJson)
        if (isNarrative) {
            // New format: Markdown narrative — use raw text as the segment content
            parseNarrativeSegment(segment)
        } else {
            // Legacy format: JSON fact packet — extract structured fields
            parseLegacyJsonSegment(segment)
        }
    }
}

private fun parseNarrativeSegment(segment: VideoSegmentRun): VideoReportEvidenceSegment {
    val narrative = segment.evidenceJson.trim()
    // Extract time-stamped lines as timeline events
    val timelineLines = narrative.lines()
        .filter { it.contains(Regex("\\*\\*\\[\\d+")) }
        .map { it.replace(Regex("\\*\\*\\["), "[").replace(Regex("]\\*\\*"), "]").trim() }
        .take(12)
    return VideoReportEvidenceSegment(
        segmentIndex = segment.segmentIndex,
        durationSeconds = segment.durationSeconds,
        mediaStartMs = segment.mediaStartMs,
        mediaEndMs = segment.mediaEndMs,
        summary = segment.summary.ifBlank {
            narrative.lines().firstOrNull { it.isNotBlank() }?.take(200).orEmpty()
        },
        transcriptExtracts = emptyList(),
        speechKeyPoints = emptyList(),
        visualEvidence = emptyList(),
        screenOrBoardContent = emptyList(),
        demonstrations = emptyList(),
        timelineEvents = timelineLines,
        uncertainties = emptyList(),
        localFilePath = segment.localFilePath,
        narrativeMarkdown = narrative
    )
}

private fun parseLegacyJsonSegment(segment: VideoSegmentRun): VideoReportEvidenceSegment {
    val root = parseEvidenceObject(segment.evidenceJson)
    val timelineEvents = root.extractList(
        "timelineFacts",
        "timelineEvents",
        "timeline_events",
        "events"
    )
    val visualEvidence = root.extractList(
        "visualFacts",
        "visualEvidence",
        "visual_evidence",
        "visualObservations",
        "frameEvidence"
    ).ifEmpty {
        timelineEvents.filter { it.containsAny(visualEvidenceKeywords) }
    }
    val screenOrBoardContent = root.extractList(
        "screenOrBoardFacts",
        "screenOrBoardContent",
        "screen_or_board_content",
        "screenContent",
        "boardContent",
        "slides",
        "pptContent"
    ).ifEmpty {
        timelineEvents.filter { it.containsAny(screenOrBoardKeywords) }
    }
    val demonstrations = root.extractList(
        "demonstrationFacts",
        "demonstrations",
        "demoSteps",
        "operations",
        "examples"
    ).ifEmpty {
        timelineEvents.filter { it.containsAny(demonstrationKeywords) }
    }
    val uncertainties = root.extractList(
        "uncertainties",
        "uncertainPoints",
        "questions",
        "risks"
    ).ifEmpty {
        timelineEvents.filter { it.containsAny(uncertaintyKeywords) }
    }
    return VideoReportEvidenceSegment(
        segmentIndex = segment.segmentIndex,
        durationSeconds = segment.durationSeconds,
        mediaStartMs = segment.mediaStartMs,
        mediaEndMs = segment.mediaEndMs,
        summary = firstUsefulText(
            root.firstString("segmentTopic", "topic"),
            root.firstString("summary", "segmentSummary", "overview"),
            segment.summary,
            segment.conclusion
        ),
        transcriptExtracts = root.extractList(
            "audioFacts",
            "audioTranscriptExtracts",
            "audio_transcript_extracts",
            "transcriptExtracts",
            "audioExtracts",
            "keyTranscript"
        ),
        speechKeyPoints = root.extractList(
            "speechKeyPoints",
            "speech_key_points",
            "audioKeyPoints",
            "spokenKeyPoints"
        ),
        visualEvidence = visualEvidence,
        screenOrBoardContent = screenOrBoardContent,
        demonstrations = demonstrations,
        timelineEvents = timelineEvents,
        uncertainties = uncertainties,
        localFilePath = segment.localFilePath
    )
}

internal fun buildVideoReportContent(
    detail: VideoHistoryDetail,
    evidenceSegments: List<VideoReportEvidenceSegment>
): VideoReportContent {
    val payloads = listOf(
        detail.run.markdownNote,
        detail.run.structuredNoteJson,
        detail.run.finalSummary
    ).mapNotNull { parseReportPayload(it) }
    val reportType = payloads.firstNotNullOfOrNull {
        it.reportType.takeIf(String::isNotBlank)
    }.orEmpty()
    val isObservationReport = reportType == "scene_observation" ||
        reportType == "general_record" ||
        detail.run.recordingScenario == "general"

    val summary = firstUsefulText(
        payloads.firstNotNullOfOrNull { it.summary.takeIf(String::isNotBlank) },
        payloads.firstNotNullOfOrNull { markdownLeadParagraph(it.markdownBody).takeIf(String::isNotBlank) },
        detail.summary,
        detail.run.finalSummary,
        evidenceSegments.firstOrNull()?.summary
    ).ifBlank { "本次分析尚未生成可展示摘要。" }

    val conclusion = firstUsefulText(
        payloads.firstNotNullOfOrNull { it.conclusion.takeIf(String::isNotBlank) },
        detail.run.finalConclusion,
        evidenceSegments.firstOrNull { it.summary.isNotBlank() }?.summary
    )

    val markdownBody = cleanMarkdownBodyForReport(
        markdown = firstUsefulText(
        payloads.firstNotNullOfOrNull { it.markdownBody.takeIf(String::isNotBlank) },
        detail.run.markdownNote
        ).ifBlank {
            fallbackMarkdown(summary = summary, conclusion = conclusion)
        },
        isObservationReport = isObservationReport,
        summary = summary,
        conclusion = conclusion
    )

    val outlineItems = payloads
        .flatMap { payload ->
            payload.outline.map { VideoReportOutlineItem(title = normalizeOutlineText(it)) }
        }
        .ifEmpty {
            buildVideoReportOutline(
                markdownNote = markdownBody,
                structuredNoteJson = detail.run.structuredNoteJson,
                evidenceSegments = evidenceSegments
            )
        }
        .filterNot { isPlaceholderReportItem(it.title) }
        .dedupeByTitle()
        .take(12)

    val keyPoints = payloads
        .flatMap { it.keyPoints }
        .filterNot { isCoverageOrQualityNote(it) }
        .ifEmpty { if (isObservationReport) emptyList() else outlineItems.map { it.title } }
        .map(::normalizeOutlineText)
        .filter { it.isNotBlank() && !isPlaceholderReportItem(it) }
        .distinctReportItems()
        .take(8)

    val actionItems = payloads
        .flatMap { it.followUps }
        .ifEmpty {
            buildVideoReportActionItems(
                structuredNoteJson = detail.run.structuredNoteJson,
                markdownNote = markdownBody,
                evidenceSegments = evidenceSegments
            ).map { it.text }
        }
        .map(::normalizeOutlineText)
        .filter { it.isNotBlank() && !isPlaceholderReportItem(it) && !isCoverageOrQualityNote(it) }
        .distinctReportItems()
        .take(12)
        .map { VideoReportActionItem(it) }

    val evidenceHighlights = payloads
        .flatMap { it.evidenceHighlights }
        .map(::normalizeOutlineText)
        .filter { it.isNotBlank() && !isPlaceholderReportItem(it) }
        .distinctReportItems()
        .take(12)
    val coverageNotice = firstUsefulText(
        payloads.firstNotNullOfOrNull { it.coverageNotice.takeIf(String::isNotBlank) }
    )

    val timelineEvents = (payloads.flatMap { it.timelineEvents } +
        evidenceSegments.flatMap { segment -> segment.timelineEvents.map { "第 ${segment.segmentIndex} 段：$it" } })
        .map(String::trim)
        .filter { it.isNotBlank() && !isPlaceholderReportItem(it) }
        .distinctReportItems()
        .take(30)

    return VideoReportContent(
        reportType = reportType,
        summary = summary,
        conclusion = conclusion,
        markdownBody = markdownBody,
        outlineItems = outlineItems,
        keyPoints = keyPoints,
        actionItems = actionItems,
        evidenceHighlights = evidenceHighlights,
        coverageNotice = coverageNotice,
        timelineEvents = timelineEvents
    )
}

internal fun buildVideoReportOutline(
    markdownNote: String,
    structuredNoteJson: String,
    evidenceSegments: List<VideoReportEvidenceSegment>
): List<VideoReportOutlineItem> {
    val markdownOutline = outlineFromMarkdown(markdownNote)
    if (markdownOutline.isNotEmpty()) return markdownOutline

    val structuredOutline = outlineFromStructuredJson(structuredNoteJson)
    if (structuredOutline.isNotEmpty()) return structuredOutline

    return evidenceSegments
        .filter { segment ->
            segment.summary.isNotBlank() ||
                segment.speechKeyPoints.isNotEmpty() ||
                segment.visualEvidence.isNotEmpty() ||
                segment.screenOrBoardContent.isNotEmpty()
        }
        .map { segment ->
            VideoReportOutlineItem(
                title = "第 ${segment.segmentIndex} 段",
                children = listOf(segment.summary)
                    .filter(String::isNotBlank)
                    .plus(segment.speechKeyPoints)
                    .plus(segment.visualEvidence)
                    .plus(segment.screenOrBoardContent)
                    .take(6)
            )
        }
}

internal fun buildVideoReportActionItems(
    structuredNoteJson: String,
    markdownNote: String,
    evidenceSegments: List<VideoReportEvidenceSegment>
): List<VideoReportActionItem> {
    val fromJson = parseEvidenceObject(structuredNoteJson).extractList(
        "actionItems",
        "actions",
        "todo",
        "todos",
        "followUps",
        "reviewItems",
        "复习清单",
        "待办事项"
    )
    val fromMarkdown = extractActionLinesFromMarkdown(markdownNote)
    val fromEvidence = evidenceSegments
        .flatMap { it.timelineEvents + it.uncertainties }
        .filter { item -> actionKeywords.any { item.contains(it, ignoreCase = true) } }

    return (fromJson + fromMarkdown + fromEvidence)
        .map(String::trim)
        .filter { it.isNotBlank() && !isPlaceholderReportItem(it) && !isCoverageOrQualityNote(it) }
        .distinctReportItems()
        .take(12)
        .map { VideoReportActionItem(it) }
}

internal fun buildUserReportMarkdown(detail: VideoHistoryDetail): String {
    val evidenceSegments = parseVideoReportEvidence(detail.segments)
    return buildVideoReportContent(detail, evidenceSegments).markdownBody
}

internal fun buildEvidenceTranscriptPreview(segments: List<VideoSegmentRun>): String {
    return parseVideoReportEvidence(segments)
        .filter {
            it.transcriptExtracts.isNotEmpty() ||
                it.speechKeyPoints.isNotEmpty() ||
                it.narrativeMarkdown.isNotBlank()
        }
        .joinToString("\n\n") { segment ->
            buildString {
                append("### 第 ${segment.segmentIndex} 段\n")
                if (segment.narrativeMarkdown.isNotBlank()) {
                    // Narrative format: show first meaningful lines
                    segment.narrativeMarkdown.lines()
                        .filter { it.isNotBlank() }
                        .take(6)
                        .forEach { append(it).append('\n') }
                } else {
                    (segment.transcriptExtracts + segment.speechKeyPoints)
                        .take(8)
                        .forEach { append("- ").append(it).append('\n') }
                }
            }.trim()
        }
}

internal fun buildEvidenceCardPreview(evidenceJson: String): String {
    if (evidenceJson.isBlank()) return ""
    // Narrative format: show first few lines as preview
    if (isNarrativeEvidence(evidenceJson)) {
        return evidenceJson.lines()
            .filter { it.isNotBlank() }
            .take(4)
            .joinToString("\n") { it.take(120) }
    }
    // Legacy JSON format
    val root = parseEvidenceObject(evidenceJson) ?: return ""
    val preview = listOf(
        root.extractList("audioFacts", "audioTranscriptExtracts", "speechKeyPoints", "speech_key_points"),
        root.extractList("visualFacts", "visualEvidence", "visual_evidence"),
        root.extractList("screenOrBoardFacts", "screenOrBoardContent", "screen_or_board_content"),
        root.extractList("demonstrationFacts", "demonstrations"),
        root.extractList("timelineFacts", "timelineEvents"),
        root.extractList("uncertainties")
    ).flatten()
    return preview.take(8).joinToString("\n")
}

internal fun looksLikeStructuredPayload(text: String): Boolean {
    val compact = text.trim()
    return compact.length > 1 &&
        ((compact.startsWith("{") && compact.endsWith("}")) ||
            (compact.startsWith("[") && compact.endsWith("]"))) &&
        compact.contains(":")
}

internal fun looksLikeJsonishText(text: String): Boolean {
    val compact = text.trim()
    return compact.length > 1 &&
        (compact.startsWith("{") || compact.startsWith("[")) &&
        compact.contains(":")
}

internal fun isTechnicalFallbackText(text: String): Boolean {
    val compact = text.trim()
    if (compact.isBlank()) return true
    return compact == "视频结果未能按约定结构返回。" ||
        compact == "视频分析结果未能按约定结构返回。" ||
        compact.contains("未能按约定结构返回")
}

private val actionKeywords = listOf(
    "待办",
    "行动",
    "复习",
    "跟进",
    "todo",
    "action",
    "review",
    "follow"
)

private val visualEvidenceKeywords = listOf("画面", "镜头", "出镜", "切回", "拍摄", "显示", "展示")
private val screenOrBoardKeywords = listOf("PPT", "ppt", "幻灯片", "投影", "页面", "板书", "海报", "图示", "标识")
private val demonstrationKeywords = listOf("演示", "案例", "操作", "讲解", "复盘", "展示")
private val uncertaintyKeywords = listOf("无效", "无关", "未捕捉", "不确定", "疑点", "镜头跳转")

private fun parseEvidenceObject(json: String): JsonObject? {
    if (json.isBlank()) return null
    // Try JSON parsing first (legacy fact-packet format)
    runCatching {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()?.let { return it }
    // Fallback: Markdown narrative format — wrap in a synthetic JSON object
    // so downstream extraction can still find content via "narrative" key
    return runCatching {
        val syntheticJson = JsonObject().apply {
            addProperty("narrative", json)
            addProperty("evidenceFormat", "markdown_narrative")
        }
        syntheticJson
    }.getOrNull()
}

/** Returns true if the evidence is in the new Markdown narrative format (not legacy JSON fact packet). */
internal fun isNarrativeEvidence(evidenceJson: String): Boolean {
    if (evidenceJson.isBlank()) return false
    return runCatching {
        JsonParser.parseString(evidenceJson).isJsonObject
    }.getOrDefault(false).not()
}

private fun parseReportPayload(text: String): ReportPayload? {
    val raw = text.trim()
    if (raw.isBlank()) return null
    if (!looksLikeJsonishText(raw)) {
        if (isTechnicalFallbackText(raw)) return null
        return ReportPayload(markdownBody = raw)
    }

    val root = parseEvidenceObject(raw) ?: return null
    val structured = root.findObject(
        "structuredNotes",
        "structuredNote",
        "structured_note",
        "note",
        "notes"
    )
    val nestedMarkdown = root.firstString(
        "markdownNote",
        "markdown_note",
        "markdown",
        "noteMarkdown"
    )
    val markdownPayload = nestedMarkdown
        ?.takeIf { looksLikeJsonishText(it) }
        ?.let { parseReportPayload(it) }

    val outline = structured.extractList("outline", "agenda", "topics", "chapters")
        .ifEmpty { root.extractList("outline", "agenda", "topics", "chapters") }
    val keyPoints = structured.extractList("keyPoints", "knowledgePoints", "highlights")
        .ifEmpty { root.extractList("keyPoints", "knowledgePoints", "highlights") }
    val followUps = structured.extractList(
        "followUps",
        "reviewItems",
        "actionItems",
        "actions",
        "todos"
    ).ifEmpty {
        root.extractList("followUps", "reviewItems", "actionItems", "actions", "todos")
    }

    return ReportPayload(
        reportType = root.firstString("reportType", "report_type").orEmpty(),
        summary = firstUsefulText(
            root.firstString("briefSummary"),
            root.firstString("summary", "overview"),
            structured.firstString("overview", "summary"),
            markdownPayload?.summary
        ),
        conclusion = firstUsefulText(
            root.firstString("conclusion", "finalConclusion"),
            root.extractList("keyConclusions", "conclusions").joinToString("\n"),
            structured.firstString("conclusion"),
            markdownPayload?.conclusion
        ),
        markdownBody = firstUsefulText(
            nestedMarkdown?.takeIf { !looksLikeJsonishText(it) },
            markdownPayload?.markdownBody
        ),
        outline = outline.ifEmpty { markdownPayload?.outline.orEmpty() },
        keyPoints = keyPoints.ifEmpty {
            root.extractList("knowledgePoints").ifEmpty { markdownPayload?.keyPoints.orEmpty() }
        },
        followUps = followUps.ifEmpty {
            root.extractList("reviewOrActionItems").ifEmpty { markdownPayload?.followUps.orEmpty() }
        },
        evidenceHighlights = root.extractList("evidenceHighlights", "evidence", "highlights")
            .ifEmpty { markdownPayload?.evidenceHighlights.orEmpty() },
        coverageNotice = firstUsefulText(
            root.firstString("coverageNotice", "recordingNotes"),
            markdownPayload?.coverageNotice
        ),
        timelineEvents = root.extractList("timeline", "timelineFacts", "timelineEvents", "timeline_events", "events")
            .ifEmpty { markdownPayload?.timelineEvents.orEmpty() }
    )
}

private fun outlineFromMarkdown(markdown: String): List<VideoReportOutlineItem> {
    if (markdown.isBlank() || looksLikeStructuredPayload(markdown)) return emptyList()
    val outline = mutableListOf<VideoReportOutlineItem>()
    var currentTitle: String? = null
    var currentChildren = mutableListOf<String>()

    fun flush() {
        val title = currentTitle?.trim().orEmpty()
        if (title.isNotBlank()) {
            outline += VideoReportOutlineItem(title = title, children = currentChildren.take(8))
        }
        currentChildren = mutableListOf()
    }

    markdown.lineSequence()
        .map(String::trim)
        .forEach { line ->
            when {
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    val text = line.drop(level).trim()
                    if (level <= 3 && text.isNotBlank()) {
                        flush()
                        currentTitle = text
                    }
                }
                currentTitle != null && line.isMarkdownBullet() -> {
                    currentChildren += line.drop(1).trim()
                }
                currentTitle != null && line.matches(looseNumberedRegex) -> {
                    currentChildren += line.substringAfter(".").trim()
                }
            }
        }
    flush()
    return outline
}

private fun outlineFromStructuredJson(json: String): List<VideoReportOutlineItem> {
    val payload = parseReportPayload(json)
    if (payload != null && payload.outline.isNotEmpty()) {
        return payload.outline.take(12).map { VideoReportOutlineItem(normalizeOutlineText(it)) }
    }
    val root = parseEvidenceObject(json) ?: return emptyList()
    val direct = root.extractList(
        "outline",
        "topics",
        "keyPoints",
        "knowledgePoints",
        "agenda",
        "chapters"
    )
    if (direct.isNotEmpty()) {
        return direct.take(12).map { VideoReportOutlineItem(it) }
    }

    return root.entrySet()
        .filter { (key, value) ->
            key.contains("outline", ignoreCase = true) ||
                key.contains("topic", ignoreCase = true) ||
                key.contains("knowledge", ignoreCase = true) ||
                key.contains("chapter", ignoreCase = true) ||
                value.isJsonArray
        }
        .flatMap { (_, value) -> value.toReadableList() }
        .take(12)
        .map { VideoReportOutlineItem(it) }
}

private fun extractActionLinesFromMarkdown(markdown: String): List<String> {
    if (markdown.isBlank() || looksLikeStructuredPayload(markdown)) return emptyList()
    return markdown.lineSequence()
        .map { it.trim().removePrefix("-").removePrefix("*").trim() }
        .filter { line -> actionKeywords.any { line.contains(it, ignoreCase = true) } }
        .take(12)
        .toList()
}

private fun JsonObject?.firstString(vararg names: String): String? {
    val objectValue = this ?: return null
    return names.firstNotNullOfOrNull { name ->
        objectValue.findValue(name)?.let { element ->
            element.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf(String::isNotBlank)
        }
    }
}

private fun JsonObject?.extractList(vararg names: String): List<String> {
    val objectValue = this ?: return emptyList()
    return names.firstNotNullOfOrNull { name ->
        objectValue.findValue(name)?.toReadableList()?.takeIf { it.isNotEmpty() }
    }.orEmpty()
}

private fun JsonObject.findValue(name: String): JsonElement? {
    get(name)?.let { return it }
    return entrySet().firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

private fun JsonObject?.findObject(vararg names: String): JsonObject? {
    val objectValue = this ?: return null
    return names.firstNotNullOfOrNull { name ->
        objectValue.findValue(name)?.takeIf { it.isJsonObject }?.asJsonObject
    }
}

private fun JsonElement.toReadableList(maxDepth: Int = 10): List<String> {
    if (maxDepth <= 0) return emptyList()
    return when {
        isJsonNull -> emptyList()
        isJsonPrimitive -> listOf(asString.trim()).filter(String::isNotBlank)
        isJsonArray -> {
            val output = mutableListOf<String>()
            asJsonArray.forEach { output += it.toReadableList(maxDepth - 1) }
            output
        }
        isJsonObject -> listOfNotNull(asJsonObject.toReadableText())
        else -> emptyList()
    }
}

private fun JsonObject.toReadableText(): String? {
    val time = this.firstString(
        "timestampSeconds",
        "timestamp",
        "time",
        "timeLabel",
        "startTime",
        "mediaTime"
    )
    val speaker = this.firstString("speaker", "speakerHint", "role")
    val title = this.firstString("title", "topic", "label", "type")
    val text = this.firstString("text", "content", "detail", "description", "summary", "value")
    val body = listOfNotNull(title, text)
        .distinct()
        .joinToString("：")
        .trim()
    val prefix = listOfNotNull(time, speaker)
        .filter(String::isNotBlank)
        .joinToString(" · ")
    return when {
        prefix.isNotBlank() && body.isNotBlank() -> "$prefix：$body"
        body.isNotBlank() -> body
        else -> null
    }
}

private val looseNumberedRegex = Regex("""\d+\.\s*.*""")

private fun String.isMarkdownBullet(): Boolean {
    val trimmed = trimStart()
    return trimmed.length > 1 &&
        (trimmed[0] == '-' || trimmed[0] == '*') &&
        trimmed.getOrNull(1) != trimmed[0]
}

private fun String.containsAny(keywords: List<String>): Boolean {
    return keywords.any { contains(it, ignoreCase = true) }
}

private fun firstUsefulText(vararg candidates: String?): String {
    return candidates.firstOrNull { candidate ->
        val value = candidate?.trim().orEmpty()
        value.isNotBlank() &&
            !looksLikeJsonishText(value) &&
            !isTechnicalFallbackText(value)
    }?.trim().orEmpty()
}

private fun fallbackMarkdown(summary: String, conclusion: String): String {
    return buildString {
        if (summary.isNotBlank()) {
            append("## 摘要\n")
            append(summary).append("\n\n")
        }
        if (conclusion.isNotBlank()) {
            append("## 关键结论\n")
            append(conclusion).append("\n")
        }
    }.trim()
}

private fun cleanMarkdownBodyForReport(
    markdown: String,
    isObservationReport: Boolean,
    summary: String,
    conclusion: String
): String {
    val withoutDuplicateSections = removeDuplicatedReadingSections(markdown)
    val forcedTemplate = listOf("知识点", "复习", "行动项", "待办", "待跟进")
        .any { markdown.contains(it, ignoreCase = true) }
    return if (isObservationReport && forcedTemplate) {
        ""
    } else {
        withoutDuplicateSections
    }
}

private fun removeDuplicatedReadingSections(markdown: String): String {
    if (markdown.isBlank() || looksLikeStructuredPayload(markdown)) return markdown
    val lines = markdown.lineSequence().toList()
    val sections = mutableListOf<MarkdownSection>()
    var currentHeading: String? = null
    var currentLines = mutableListOf<String>()

    fun flush() {
        if (currentHeading != null || currentLines.any { it.isNotBlank() }) {
            sections += MarkdownSection(currentHeading, currentLines.toList())
        }
        currentHeading = null
        currentLines = mutableListOf()
    }

    lines.forEach { line ->
        if (line.trimStart().startsWith("#")) {
            flush()
            currentHeading = line
        } else {
            currentLines += line
        }
    }
    flush()

    val filtered = sections.filterNot { section ->
        val title = section.heading?.trim().orEmpty()
        val level = title.takeWhile { it == '#' }.length
        level == 1 || isDuplicateReportSectionTitle(title)
    }
    val rendered = filtered
        .flatMap { section ->
            buildList {
                section.heading?.let { add(it) }
                addAll(section.lines)
            }
        }
        .joinToString("\n")
        .trim()
    return rendered
}

private data class MarkdownSection(
    val heading: String?,
    val lines: List<String>
)

private fun isDuplicateReportSectionTitle(title: String): Boolean {
    val normalized = normalizedReportItemKey(title.removePrefix("#").trim())
    return listOf(
        "summary",
        "briefsummary",
        "reportsummary",
        "conclusion",
        "keyconclusions",
        "actionitems",
        "reviewitems",
        "followups",
        "coverage",
        "coveragenotice",
        "摘要",
        "报告摘要",
        "概览",
        "总结",
        "核心结论",
        "关键结论",
        "结论",
        "待办",
        "行动项",
        "复习",
        "后续建议",
        "录制说明"
    ).any { normalized.contains(it) }
}

private fun normalizeOutlineText(text: String): String {
    return text.trim()
        .replace(Regex("""^\s*(?:\d+|[一二三四五六七八九十]+)[\.\)、:：\s]+"""), "")
        .replace(Regex("""^\s*\d{2}(?=\S)"""), "")
        .trim()
}

private fun isPlaceholderReportItem(text: String): Boolean {
    val compact = text.trim()
        .replace(Regex("""^\s*(?:\d+|[一二三四五六七八九十]+)[\.\)、:：\s]+"""), "")
        .trim()
        .trim('-', '*', '_', '.', '、', ':', '：')
    if (compact.isBlank()) return true
    if (compact.matches(Regex("""\d{1,2}"""))) return true
    if (compact.matches(Regex("""[一二三四五六七八九十]{1,3}"""))) return true
    return compact.all { it == '-' || it == '_' || it == '—' || it == '–' }
}

private fun isCoverageOrQualityNote(text: String): Boolean {
    val value = text.trim()
    return listOf("收音", "语音", "音量", "画质", "清晰", "拍摄角度", "无法识别", "未覆盖", "设备", "底噪")
        .any { value.contains(it, ignoreCase = true) }
}

private fun markdownLeadParagraph(markdown: String): String {
    return markdown.lineSequence()
        .map { line ->
            line.trim()
                .removePrefix("#")
                .removePrefix("#")
                .removePrefix("#")
                .removePrefix("-")
                .removePrefix("*")
                .replace(Regex("""^\d+\.\s*"""), "")
                .trim()
        }
        .firstOrNull { it.isNotBlank() && !looksLikeJsonishText(it) }
        .orEmpty()
}

private fun List<VideoReportOutlineItem>.dedupeByTitle(): List<VideoReportOutlineItem> {
    val seen = mutableSetOf<String>()
    return filter { item ->
        val key = normalizedReportItemKey(item.title)
        key.isNotBlank() && seen.add(key)
    }
}

private fun List<String>.distinctReportItems(): List<String> {
    val seen = mutableSetOf<String>()
    return filter { item ->
        val key = normalizedReportItemKey(item)
        key.isNotBlank() && seen.add(key)
    }
}

private fun normalizedReportItemKey(text: String): String {
    return text
        .lowercase()
        .replace(Regex("""[\s\p{Punct}，。；：、（）【】《》“”‘’]+"""), "")
        .take(80)
}
