package com.example.watcher.data.repository

import com.example.watcher.data.model.BaselineSource
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorDecision
import com.example.watcher.data.model.MonitorMode
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.TargetTrigger
import com.example.watcher.data.model.VideoAnalysisResult
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoTaskCategory
import com.example.watcher.data.model.VideoTaskPlan
import com.example.watcher.data.model.VideoTimelineEvent
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import kotlin.math.roundToInt

object ModelOutputParser {
    private val gson = Gson()
    private val codeBlockRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")
    private val arabicDurationRegex = Regex("""(\d+(?:\.\d+)?)\s*(小时|分钟|分|秒)""")
    private val chineseDurationRegex = Regex("""([零一二两三四五六七八九十百半]+)\s*(小时|分钟|分|秒)""")

    fun extractJson(text: String): String {
        codeBlockRegex.find(text)?.let { return it.groupValues[1].trim() }

        val objectStart = text.indexOf('{')
        val objectEnd = text.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            return text.substring(objectStart, objectEnd + 1).trim()
        }

        return text.trim()
    }

    fun parseIntentResult(
        rawText: String,
        userInput: String,
        baseFrameBase64: String?,
        baselineSource: BaselineSource,
        hasImage: Boolean
    ): IntentResult {
        val payload = tryParse<IntentPayload>(rawText)

        val requirement = payload?.userRequirement?.trim().takeUnless { it.isNullOrBlank() }
            ?: userInput.trim().ifBlank { "监看当前画面" }
        val sceneDescription = payload?.originalSceneDescription?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: "暂无场景描述。"
        val interval = (payload?.checkIntervalSeconds ?: IntentResult.DEFAULT_INTERVAL_SECONDS)
            .coerceIn(IntentResult.MIN_INTERVAL_SECONDS, IntentResult.MAX_INTERVAL_SECONDS)
        val title = payload?.title?.trim().takeUnless { it.isNullOrBlank() }
            ?: requirement.take(IntentResult.MAX_TITLE_LENGTH)
        val monitorMode = payload?.monitorMode.toMonitorMode(
            baselineSource = baselineSource,
            userInput = userInput,
            hasImage = hasImage
        )
        val targetTrigger = payload?.targetTrigger.toTargetTrigger(
            userInput = userInput,
            monitorMode = monitorMode
        )
        val effectiveBaselineSource = payload?.baselineSource.toBaselineSource(
            default = baselineSource,
            hasImage = hasImage
        )
        val promptTemplate = payload?.promptTemplate?.trim().takeUnless { it.isNullOrBlank() }
            ?: IntentResult.buildFallbackPrompt(
                requirement = requirement,
                sceneDescription = sceneDescription,
                monitorMode = monitorMode,
                targetTrigger = targetTrigger
            )

        return IntentResult(
            title = title,
            userInput = userInput,
            userRequirement = requirement,
            originalSceneDescription = sceneDescription,
            checkInterval = interval,
            promptTemplate = promptTemplate,
            baseFrameBase64 = baseFrameBase64,
            monitorMode = monitorMode,
            targetTrigger = targetTrigger,
            baselineSource = effectiveBaselineSource
        ).normalized()
    }

    fun parseMonitorDecision(rawText: String): MonitorDecision {
        val payload = tryParse<DetectionPayload>(rawText)

        if (payload != null) {
            val result = payload.status.toCheckResult()
            val summary = payload.summary?.trim().takeUnless { it.isNullOrBlank() }
                ?: payload.reason?.trim().takeUnless { it.isNullOrBlank() }
                ?: "模型返回结果：${result.toDisplayLabel()}"

            return MonitorDecision(
                result = result,
                summary = summary,
                reason = payload.reason?.trim().orEmpty(),
                confidence = parseConfidenceValue(payload.confidence),
                rawResponse = rawText
            )
        }

        val fallbackResult = when {
            rawText.contains("alert", ignoreCase = true) -> CheckResult.ALERT
            rawText.contains("warning", ignoreCase = true) -> CheckResult.WARNING
            rawText.contains("normal", ignoreCase = true) -> CheckResult.NORMAL
            else -> CheckResult.UNKNOWN
        }

        return MonitorDecision(
            result = fallbackResult,
            summary = rawText.trim().take(120).ifBlank { "模型响应无法解析" },
            reason = if (fallbackResult == CheckResult.UNKNOWN) {
                "响应内容不符合要求的 JSON 结构。"
            } else {
                ""
            },
            rawResponse = rawText
        )
    }

    fun parseVideoTaskPlan(rawText: String, userInput: String): VideoTaskPlan {
        val payload = tryParse<VideoPlanPayload>(rawText)
        val explicitDurationSeconds = extractRequestedDurationSeconds(userInput)
        val modelCategory = VideoTaskCategory.fromValue(payload?.taskCategory)
        val effectiveCategory = modelCategory ?: inferVideoTaskCategory(
            userInput = userInput,
            durationSeconds = explicitDurationSeconds ?: payload?.recordingDurationSeconds
        )
        val baseline = baselineFor(
            category = effectiveCategory,
            durationSeconds = explicitDurationSeconds ?: payload?.recordingDurationSeconds
        )
        val durationSeconds = explicitDurationSeconds
            ?: payload?.recordingDurationSeconds
            ?: baseline.recordingDurationSeconds
        val segmentDurationSeconds = payload?.segmentDurationSeconds ?: baseline.segmentDurationSeconds
        val captureIntervalSeconds = segmentDurationSeconds
        val samplingFps = payload?.samplingFps ?: baseline.samplingFps

        val draft = VideoProcessTaskDraft(
            taskCategory = effectiveCategory.value,
            strategyReason = payload?.strategyReason?.trim().orEmpty().ifBlank {
                defaultStrategyReason(
                    category = effectiveCategory,
                    durationSeconds = durationSeconds,
                    segmentDurationSeconds = segmentDurationSeconds,
                    captureIntervalSeconds = captureIntervalSeconds
                )
            },
            title = payload?.title.orEmpty(),
            userInput = userInput,
            userRequirement = payload?.userRequirement.orEmpty(),
            sceneContext = payload?.sceneContext.orEmpty(),
            segmentAnalysisPrompt = payload?.segmentAnalysisPrompt.orEmpty(),
            finalSummaryPrompt = payload?.finalSummaryPrompt.orEmpty(),
            recordingScenario = inferRecordingScenario(
                modelValue = payload?.recordingScenario,
                userInput = userInput,
                userRequirement = payload?.userRequirement
            ).value,
            speechInputEnabled = payload?.speechInputEnabled ?: false,
            plannedDurationSeconds = durationSeconds,
            plannedSamplingFps = samplingFps,
            plannedSegmentDurationSeconds = segmentDurationSeconds,
            captureIntervalSeconds = captureIntervalSeconds,
            plannedSegmentCount = payload?.segmentCount ?: 0,
            autoStartStreamingOutput = payload?.autoStartStreamingOutput ?: false,
            finalSummaryEnabled = payload?.finalSummaryEnabled ?: true,
            confirmationNotes = payload?.confirmationNotes?.trim().orEmpty().ifBlank {
                defaultConfirmationNotes(
                    category = effectiveCategory,
                    durationSeconds = durationSeconds,
                    segmentDurationSeconds = segmentDurationSeconds,
                    captureIntervalSeconds = captureIntervalSeconds
                )
            }
        ).normalized()

        return VideoTaskPlan(
            templateId = draft.templateId,
            templateLabel = draft.templateLabel,
            taskCategory = draft.taskCategory,
            strategyReason = draft.strategyReason,
            title = draft.title,
            userRequirement = draft.userRequirement,
            sceneContext = draft.sceneContext,
            recordingDurationSeconds = draft.plannedDurationSeconds,
            samplingFps = draft.plannedSamplingFps,
            segmentDurationSeconds = draft.plannedSegmentDurationSeconds,
            captureIntervalSeconds = draft.captureIntervalSeconds,
            segmentCount = draft.plannedSegmentCount,
            segmentAnalysisPrompt = draft.segmentAnalysisPrompt,
            finalSummaryPrompt = draft.finalSummaryPrompt,
            recordingScenario = draft.recordingScenario,
            speechInputEnabled = draft.speechInputEnabled,
            autoStartStreamingOutput = draft.autoStartStreamingOutput,
            finalSummaryEnabled = draft.finalSummaryEnabled,
            confirmationNotes = draft.confirmationNotes
        )
    }

    fun parseVideoAnalysis(rawText: String): VideoAnalysisResult {
        parseVideoAnalysisObject(rawText)?.let { root ->
            if (root.hasAny(FINAL_REPORT_KEYS)) {
                return parseFinalVideoReport(root, rawText)
            }
            if (root.hasAny(SEGMENT_FACT_KEYS)) {
                return parseSegmentFactPacket(root, rawText)
            }
        }

        val payload = tryParse<VideoAnalysisPayload>(rawText)

        if (payload != null) {
            val summary = payload.summary?.trim().takeUnless { it.isNullOrBlank() }
                ?: "已完成视频分析。"
            val conclusion = payload.conclusion?.trim().takeUnless { it.isNullOrBlank() }
                ?: summary
            val timeline = payload.timelineEvents.orEmpty()
                .mapNotNull { event ->
                    val title = event.title?.trim().takeUnless { it.isNullOrBlank() }
                        ?: return@mapNotNull null
                    VideoTimelineEvent(
                        timestampSeconds = parseTimestampSeconds(event.timestampSeconds),
                        title = title,
                        detail = event.detail?.trim().orEmpty(),
                        confidence = parseConfidenceValue(event.confidence)
                    )
                }
                .sortedBy { it.timestampSeconds }

            return VideoAnalysisResult(
                summary = summary,
                conclusion = conclusion,
                timelineEvents = timeline,
                rawResponse = rawText,
                structuredNoteJson = payload.structuredNote?.let { gson.toJson(it) }.orEmpty(),
                markdownNote = payload.markdownNote?.trim().orEmpty(),
                evidenceJson = gson.toJson(payload.toEvidencePackage())
            )
        }

        return VideoAnalysisResult(
            summary = "",
            conclusion = "",
            timelineEvents = emptyList(),
            rawResponse = rawText
        )
    }

    private fun parseVideoAnalysisObject(rawText: String): JsonObject? {
        return runCatching {
            gson.fromJson(extractJson(rawText), JsonObject::class.java)
        }.getOrNull()
    }

    private fun parseSegmentFactPacket(root: JsonObject, rawText: String): VideoAnalysisResult {
        val timeline = extractTimeline(root, "timelineFacts", "timelineEvents")
        val segmentTopic = root.firstString("segmentTopic", "topic")
        val fallbackSummary = timeline.firstOrNull()?.title
            ?: root.extractTextList("speechKeyPoints").firstOrNull()
            ?: root.extractTextList("audioFacts").firstOrNull()
            ?: root.extractTextList("visualFacts").firstOrNull()
        val evidencePackage = root.deepCopy().asJsonObject.apply {
            addProperty("schemaVersion", SEGMENT_FACT_SCHEMA_VERSION)
        }

        return VideoAnalysisResult(
            summary = firstUsefulText(segmentTopic, fallbackSummary),
            conclusion = "",
            timelineEvents = timeline,
            rawResponse = rawText,
            evidenceJson = gson.toJson(evidencePackage)
        )
    }

    private fun parseFinalVideoReport(root: JsonObject, rawText: String): VideoAnalysisResult {
        val keyConclusions = root.extractTextList("keyConclusions", "conclusions")
        val summary = firstUsefulText(
            root.firstString("briefSummary", "summary", "overview"),
            root.findObject("structuredNotes", "structuredNote")?.firstString("overview", "summary")
        )
        val conclusion = firstUsefulText(
            root.firstString("conclusion", "finalConclusion"),
            keyConclusions.joinToString("\n")
        )
        val reportPackage = root.deepCopy().asJsonObject.apply {
            addProperty("schemaVersion", FINAL_REPORT_SCHEMA_VERSION)
        }

        return VideoAnalysisResult(
            summary = summary,
            conclusion = conclusion,
            timelineEvents = extractTimeline(root, "timeline", "timelineEvents"),
            rawResponse = rawText,
            structuredNoteJson = gson.toJson(reportPackage),
            markdownNote = buildFinalReportMarkdown(root)
        )
    }

    private fun buildFinalReportMarkdown(root: JsonObject): String {
        val title = root.firstString("title").orEmpty().ifBlank { "Video Analysis Report" }
        val reportType = root.firstString("reportType").orEmpty()
        val isObservationReport = reportType == "scene_observation" || reportType == "general_record"
        val summary = firstUsefulText(
            root.firstString("briefSummary", "summary", "overview"),
            root.findObject("structuredNotes", "structuredNote")?.firstString("overview", "summary")
        )
        val keyConclusions = root.extractTextList("keyConclusions", "conclusions")
        val outline = root.extractTextList("outline").map(::normalizeReportListItem).filterNot(::isPlaceholderReportItem)
        val knowledgePoints = if (isObservationReport) {
            emptyList()
        } else {
            root.extractTextList("knowledgePoints", "keyPoints")
                .map(::normalizeReportListItem)
                .filterNot(::isPlaceholderReportItem)
                .filterNot(::isCoverageOrQualityNote)
        }
        val reviewItems = root.extractTextList("reviewOrActionItems", "actionItems", "followUps")
            .map(::normalizeReportListItem)
            .filterNot(::isPlaceholderReportItem)
            .filterNot(::isCoverageOrQualityNote)
        val evidence = root.extractTextList("evidenceHighlights")
        val coverageNotice = root.firstString("coverageNotice")

        return buildString {
            appendLine("# $title")
            if (summary.isNotBlank()) {
                appendLine()
                appendLine(summary)
            }
            appendMarkdownList(if (isObservationReport) "Key Findings" else "Key Conclusions", keyConclusions)
            appendMarkdownList(if (isObservationReport) "Event Flow" else "Structured Outline", outline)
            if (!isObservationReport) {
                appendMarkdownList("Knowledge Points", knowledgePoints)
            }
            appendMarkdownList(if (isObservationReport) "Follow-ups" else "Review / Action Items", reviewItems)
            appendMarkdownList("Evidence Highlights", evidence)
            coverageNotice?.takeIf(String::isNotBlank)?.let {
                appendLine()
                appendLine(if (isObservationReport) "## Recording Notes" else "## Coverage Notice")
                appendLine(it)
            }
        }.trim()
    }

    private fun StringBuilder.appendMarkdownList(title: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine()
        appendLine("## $title")
        items.forEach { item -> appendLine("- $item") }
    }

    private fun normalizeReportListItem(text: String): String {
        return text.trim()
            .replace(Regex("""^\s*(?:\d+|[一二三四五六七八九十]+)[\.\)、:：\s]+"""), "")
            .replace(Regex("""^\s*\d{2}(?=\S)"""), "")
            .trim()
    }

    private fun isPlaceholderReportItem(text: String): Boolean {
        val compact = normalizeReportListItem(text).trim().trim('-', '*', '_', '.', '、', ':', '：')
        if (compact.isBlank()) return true
        if (compact.matches(Regex("""\d{1,2}"""))) return true
        return compact.matches(Regex("""[一二三四五六七八九十]{1,3}"""))
    }

    private fun isCoverageOrQualityNote(text: String): Boolean {
        val value = text.trim()
        return listOf("收音", "语音", "音量", "画质", "清晰", "拍摄角度", "无法识别", "未覆盖", "设备", "底噪")
            .any { value.contains(it, ignoreCase = true) }
    }

    private fun firstUsefulText(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun JsonObject.hasAny(keys: Set<String>): Boolean {
        return keys.any(::has)
    }

    private fun JsonObject.firstString(vararg keys: String): String? {
        for (key in keys) {
            val value = get(key) ?: continue
            if (value.isJsonPrimitive) {
                val text = runCatching { value.asString }.getOrNull()?.trim()
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    private fun JsonObject.findObject(vararg keys: String): JsonObject? {
        for (key in keys) {
            val value = get(key) ?: continue
            if (value.isJsonObject) return value.asJsonObject
        }
        return null
    }

    private fun JsonObject.extractTextList(vararg keys: String): List<String> {
        val values = keys.firstNotNullOfOrNull { key ->
            get(key)?.takeIf { !it.isJsonNull }
        } ?: return emptyList()
        return when {
            values.isJsonArray -> values.asJsonArray.mapNotNull { item ->
                when {
                    item.isJsonPrimitive -> runCatching { item.asString }.getOrNull()
                    item.isJsonObject -> item.asJsonObject.firstString(
                        "text",
                        "content",
                        "title",
                        "detail",
                        "relevance"
                    ) ?: gson.toJson(item)
                    else -> null
                }?.trim()?.takeIf(String::isNotBlank)
            }
            values.isJsonPrimitive -> listOfNotNull(runCatching { values.asString }.getOrNull()?.trim())
                .filter(String::isNotBlank)
            values.isJsonObject -> listOf(gson.toJson(values))
            else -> emptyList()
        }
    }

    private fun extractTimeline(root: JsonObject, vararg keys: String): List<VideoTimelineEvent> {
        val array = keys.firstNotNullOfOrNull { key ->
            root.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        } ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val title = obj.firstString("title", "event", "text", "content") ?: return@mapNotNull null
            VideoTimelineEvent(
                timestampSeconds = parseTimestampSeconds(
                    obj.get("timestampSeconds") ?: obj.get("time") ?: obj.get("startSeconds")
                ),
                title = title,
                detail = obj.firstString("detail", "description", "text", "content").orEmpty(),
                confidence = parseConfidenceValue(obj.get("confidence"))
            )
        }.sortedBy { it.timestampSeconds }
    }

    fun fractionToPercent(value: Float): Int {
        return (value.coerceIn(0f, 1f) * 100f).roundToInt()
    }

    private fun parseConfidenceValue(raw: JsonElement?): Float? {
        if (raw == null || raw.isJsonNull) {
            return null
        }

        val text = runCatching { raw.asString }.getOrNull()?.trim().orEmpty()
        if (text.isBlank()) {
            return null
        }

        text.toDoubleOrNull()?.let { numeric ->
            return normalizeConfidenceValue(numeric)
        }

        if (text.endsWith("%")) {
            text.removeSuffix("%").trim().toDoubleOrNull()?.let { numeric ->
                return normalizeConfidenceValue(numeric / 100.0)
            }
        }

        val normalized = text.lowercase()
        return when {
            "high" in normalized || "高" in text -> 0.85f
            "medium" in normalized || "中" in text -> 0.60f
            "low" in normalized || "低" in text -> 0.30f
            else -> null
        }
    }

    private fun normalizeConfidenceValue(value: Double): Float? {
        if (!value.isFinite() || value < 0.0) {
            return null
        }

        val normalized = when {
            value <= 1.0 -> value
            value <= 100.0 -> value / 100.0
            else -> return null
        }
        return normalized.toFloat().coerceIn(0f, 1f)
    }

    private fun parseTimestampSeconds(raw: JsonElement?): Int {
        if (raw == null || raw.isJsonNull) return 0
        val text = runCatching { raw.asString }.getOrNull()?.trim().orEmpty()
        if (text.isBlank()) return 0
        text.toIntOrNull()?.let { return it.coerceAtLeast(0) }

        val firstNumber = Regex("""\d+""").find(text)?.value?.toIntOrNull()
        return firstNumber?.coerceAtLeast(0) ?: 0
    }

    private inline fun <reified T> tryParse(rawText: String): T? {
        return try {
            gson.fromJson(extractJson(rawText), T::class.java)
        } catch (_: JsonSyntaxException) {
            null
        }
    }

    private fun extractRequestedDurationSeconds(userInput: String): Int? {
        val candidates = mutableListOf<Int>()

        arabicDurationRegex.findAll(userInput).forEach { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return@forEach
            convertToSeconds(value, match.groupValues[2])?.let(candidates::add)
        }
        chineseDurationRegex.findAll(userInput).forEach { match ->
            val value = parseChineseNumber(match.groupValues[1]) ?: return@forEach
            convertToSeconds(value, match.groupValues[2])?.let(candidates::add)
        }

        return candidates.maxOrNull()
    }

    private fun inferVideoTaskCategory(
        userInput: String,
        durationSeconds: Int?
    ): VideoTaskCategory {
        val hasSummaryIntent = SUMMARY_KEYWORDS.any(userInput::contains)
        val hasAlertIntent = ALERT_KEYWORDS.any(userInput::contains)

        return when {
            durationSeconds != null && durationSeconds <= 90 -> VideoTaskCategory.ShortBurstDense
            durationSeconds != null && durationSeconds >= 3_600 -> {
                if (hasAlertIntent) {
                    VideoTaskCategory.ContinuousWatch
                } else {
                    VideoTaskCategory.LongHorizonSummary
                }
            }

            hasSummaryIntent && (durationSeconds ?: 0) >= 1_800 -> VideoTaskCategory.LongHorizonSummary
            hasAlertIntent -> VideoTaskCategory.ContinuousWatch
            durationSeconds != null && durationSeconds <= 600 -> VideoTaskCategory.ContinuousWatch
            else -> VideoTaskCategory.LongHorizonSummary
        }
    }

    private fun baselineFor(
        category: VideoTaskCategory,
        durationSeconds: Int?
    ): VideoPlanningBaseline {
        val safeDuration = durationSeconds ?: VideoProcessTaskDraft.DEFAULT_DURATION_SECONDS
        return when (category) {
            VideoTaskCategory.ShortBurstDense -> {
                val targetSegments = 10
                val segmentDuration = (safeDuration.toDouble() / targetSegments.toDouble())
                    .roundToInt()
                    .coerceIn(VideoProcessTaskDraft.MIN_SEGMENT_DURATION_SECONDS, 12)
                VideoPlanningBaseline(
                    recordingDurationSeconds = safeDuration,
                    samplingFps = 2,
                    segmentDurationSeconds = segmentDuration,
                    captureIntervalSeconds = segmentDuration
                )
            }

            VideoTaskCategory.ContinuousWatch -> VideoPlanningBaseline(
                recordingDurationSeconds = safeDuration,
                samplingFps = if (safeDuration <= 300) 2 else 1,
                segmentDurationSeconds = safeDuration.coerceAtMost(60),
                captureIntervalSeconds = safeDuration.coerceAtMost(60)
            )

            VideoTaskCategory.LongHorizonSummary -> VideoPlanningBaseline(
                recordingDurationSeconds = safeDuration,
                samplingFps = 1,
                segmentDurationSeconds = safeDuration.coerceAtMost(60),
                captureIntervalSeconds = safeDuration.coerceAtMost(60)
            )
        }
    }

    private fun defaultStrategyReason(
        category: VideoTaskCategory,
        durationSeconds: Int,
        segmentDurationSeconds: Int,
        captureIntervalSeconds: Int
    ): String {
        val rhythm = "每隔 ${captureIntervalSeconds} 秒录制 ${segmentDurationSeconds} 秒"
        return when (category) {
            VideoTaskCategory.LongHorizonSummary ->
                "这是长时段回顾任务，建议用较低频率采样保留趋势变化，当前节奏为 $rhythm。"

            VideoTaskCategory.ContinuousWatch ->
                "这是连续观察任务，建议尽量覆盖全过程，当前节奏为 $rhythm。"

            VideoTaskCategory.ShortBurstDense ->
                "这是短时高密度观察任务，建议提高切片密度以尽快给出反馈，当前节奏为 $rhythm。"
        } + " 总观察时长约 ${durationSeconds} 秒。"
    }

    private fun inferRecordingScenario(
        modelValue: String?,
        userInput: String,
        userRequirement: String?
    ): RecordingScenario {
        val modelScenario = RecordingScenario.fromValue(modelValue)
        if (modelScenario != RecordingScenario.General) return modelScenario

        val combined = "$userInput ${userRequirement.orEmpty()}".lowercase()
        return when {
            combined.containsAny("访谈", "采访", "interview", "座谈", "对话", "问答") ->
                RecordingScenario.Interview
            combined.containsAny("课", "讲座", "讲课", "lecture", "class", "授课", "上课") ->
                RecordingScenario.ClassLecture
            combined.containsAny("会议", "meeting", "例会", "周会", "开会", "汇报") ->
                RecordingScenario.Meeting
            combined.containsAny("培训", "training", "教学", "操作演示") ->
                RecordingScenario.Training
            else -> RecordingScenario.General
        }
    }

    private fun String.containsAny(vararg keywords: String): Boolean =
        keywords.any { contains(it, ignoreCase = true) }

    private fun defaultConfirmationNotes(
        category: VideoTaskCategory,
        durationSeconds: Int,
        segmentDurationSeconds: Int,
        captureIntervalSeconds: Int
    ): String {
        val categoryLabel = when (category) {
            VideoTaskCategory.LongHorizonSummary -> "长时段回顾"
            VideoTaskCategory.ContinuousWatch -> "连续观察"
            VideoTaskCategory.ShortBurstDense -> "短时高密度观察"
        }
        return "$categoryLabel：总时长 ${durationSeconds} 秒，每隔 ${captureIntervalSeconds} 秒录制 ${segmentDurationSeconds} 秒。"
    }

    private fun parseChineseNumber(raw: String): Double? {
        if (raw == "半") {
            return 0.5
        }

        var text = raw
        var suffixHalf = false
        if (text.endsWith("半")) {
            suffixHalf = true
            text = text.dropLast(1)
        }

        CHINESE_DIGIT_MAP[text]?.let { direct ->
            return direct + if (suffixHalf) 0.5 else 0.0
        }

        var result = 0
        var current = 0
        text.forEach { char ->
            when (char) {
                '十' -> {
                    result += (if (current == 0) 1 else current) * 10
                    current = 0
                }

                '百' -> {
                    result += (if (current == 0) 1 else current) * 100
                    current = 0
                }

                else -> {
                    val digit = CHINESE_DIGIT_MAP[char.toString()]?.toInt() ?: return null
                    current = digit
                }
            }
        }
        result += current

        if (result == 0 && text.isNotBlank()) {
            return null
        }
        return result + if (suffixHalf) 0.5 else 0.0
    }

    private fun convertToSeconds(value: Double, unit: String): Int? {
        val seconds = when (unit) {
            "小时" -> value * 3_600
            "分钟", "分" -> value * 60
            "秒" -> value
            else -> return null
        }
        return seconds.roundToInt().takeIf { it > 0 }
    }

    private fun String?.toCheckResult(): CheckResult {
        return when (this?.trim()?.uppercase()) {
            "ALERT" -> CheckResult.ALERT
            "WARNING" -> CheckResult.WARNING
            "NORMAL" -> CheckResult.NORMAL
            "UNKNOWN" -> CheckResult.UNKNOWN
            else -> CheckResult.UNKNOWN
        }
    }

    private fun CheckResult.toDisplayLabel(): String {
        return when (this) {
            CheckResult.NONE -> "未开始"
            CheckResult.ALERT -> "告警"
            CheckResult.WARNING -> "预警"
            CheckResult.NORMAL -> "正常"
            CheckResult.UNKNOWN -> "未知"
        }
    }

    private fun String?.toMonitorMode(
        baselineSource: BaselineSource,
        userInput: String,
        hasImage: Boolean
    ): MonitorMode {
        if (!hasImage || baselineSource == BaselineSource.CapturedFrame) {
            return MonitorMode.SceneBaseline
        }
        return when (this?.trim()?.lowercase()) {
            "referencetarget", "reference_target", "target", "target_presence", "目标检测", "参考目标检测" ->
                MonitorMode.ReferenceTarget
            "scenebaseline", "scene_baseline", "baseline", "场景基线", "场景基线比较" ->
                MonitorMode.SceneBaseline
            else -> inferMonitorModeFromText(userInput)
        }
    }

    private fun String?.toTargetTrigger(
        userInput: String,
        monitorMode: MonitorMode
    ): TargetTrigger {
        if (monitorMode != MonitorMode.ReferenceTarget) {
            return TargetTrigger.OnAppear
        }
        return when (this?.trim()?.lowercase()) {
            "ondisappear", "on_disappear", "disappear", "absence", "missing" ->
                TargetTrigger.OnDisappear
            "onappear", "on_appear", "appear", "presence" ->
                TargetTrigger.OnAppear
            else -> inferTargetTriggerFromText(userInput)
        }
    }

    private fun String?.toBaselineSource(default: BaselineSource, hasImage: Boolean): BaselineSource {
        if (!hasImage) {
            return BaselineSource.CapturedFrame
        }
        return when (this?.trim()?.lowercase()) {
            "uploadedimage", "uploaded_image", "upload" -> BaselineSource.UploadedImage
            "capturedframe", "captured_frame", "capture" -> BaselineSource.CapturedFrame
            else -> default
        }
    }

    private fun inferMonitorModeFromText(userInput: String): MonitorMode {
        val normalized = userInput.lowercase()
        return if (
            listOf(
                "this person",
                "this object",
                "this item",
                "look for",
                "recognize",
                "identify",
                "person in the photo",
                "object in the image"
            ).any(normalized::contains)
        ) {
            MonitorMode.ReferenceTarget
        } else {
            MonitorMode.SceneBaseline
        }
    }

    private fun inferTargetTriggerFromText(userInput: String): TargetTrigger {
        val normalized = userInput.lowercase()
        return if (
            listOf("leave", "missing", "gone", "disappear", "not appear", "absent")
                .any(normalized::contains)
        ) {
            TargetTrigger.OnDisappear
        } else {
            TargetTrigger.OnAppear
        }
    }

    private data class IntentPayload(
        @SerializedName(value = "title", alternate = ["taskTitle", "任务标题"])
        val title: String? = null,
        @SerializedName(value = "userRequirement", alternate = ["requirement", "用户需求"])
        val userRequirement: String? = null,
        @SerializedName(
            value = "originalSceneDescription",
            alternate = ["sceneDescription", "原始场景描述"]
        )
        val originalSceneDescription: String? = null,
        @SerializedName(
            value = "checkIntervalSeconds",
            alternate = ["checkInterval", "检查间隔秒数", "打点频率"]
        )
        val checkIntervalSeconds: Int? = null,
        @SerializedName(
            value = "promptTemplate",
            alternate = ["monitorPrompt", "提示词模板", "每次提示词"]
        )
        val promptTemplate: String? = null,
        @SerializedName(
            value = "monitorMode",
            alternate = ["mode", "baselineInterpretation", "monitor_type"]
        )
        val monitorMode: String? = null,
        @SerializedName(
            value = "targetTrigger",
            alternate = ["trigger", "referenceTrigger", "triggerMode"]
        )
        val targetTrigger: String? = null,
        @SerializedName(
            value = "baselineSource",
            alternate = ["imageSource", "referenceImageSource"]
        )
        val baselineSource: String? = null
    )

    private data class DetectionPayload(
        @SerializedName(value = "status", alternate = ["result", "状态"])
        val status: String? = null,
        @SerializedName(value = "summary", alternate = ["message", "摘要"])
        val summary: String? = null,
        @SerializedName(value = "reason", alternate = ["detail", "原因"])
        val reason: String? = null,
        val confidence: JsonElement? = null
    )

    private data class VideoPlanPayload(
        @SerializedName(value = "taskCategory", alternate = ["observationMode", "任务类别"])
        val taskCategory: String? = null,
        @SerializedName(value = "strategyReason", alternate = ["reason", "策略原因"])
        val strategyReason: String? = null,
        @SerializedName(value = "title", alternate = ["taskTitle", "任务标题"])
        val title: String? = null,
        @SerializedName(value = "userRequirement", alternate = ["requirement", "用户需求"])
        val userRequirement: String? = null,
        @SerializedName(value = "sceneContext", alternate = ["sceneDescription", "场景参考"])
        val sceneContext: String? = null,
        @SerializedName(
            value = "recordingDurationSeconds",
            alternate = ["durationSeconds", "录制时长秒数"]
        )
        val recordingDurationSeconds: Int? = null,
        @SerializedName(value = "samplingFps", alternate = ["sampleFps", "抽帧密度"])
        val samplingFps: Int? = null,
        @SerializedName(
            value = "segmentDurationSeconds",
            alternate = ["perSegmentDurationSeconds", "片段时长秒数"]
        )
        val segmentDurationSeconds: Int? = null,
        @SerializedName(
            value = "captureIntervalSeconds",
            alternate = ["intervalSeconds", "采样间隔秒数"]
        )
        val captureIntervalSeconds: Int? = null,
        @SerializedName(value = "segmentCount", alternate = ["segments", "片段数"])
        val segmentCount: Int? = null,
        @SerializedName(
            value = "segmentAnalysisPrompt",
            alternate = [
                "analysisPrompt",
                "segmentPrompt",
                "promptTemplate",
                "分片分析提示词",
                "分析提示词"
            ]
        )
        val segmentAnalysisPrompt: String? = null,
        @SerializedName(
            value = "finalSummaryPrompt",
            alternate = [
                "summaryPrompt",
                "finalPrompt",
                "分片汇总提示词",
                "最终汇总提示词"
            ]
        )
        val finalSummaryPrompt: String? = null,
        @SerializedName(value = "confirmationNotes", alternate = ["notes", "确认说明"])
        val confirmationNotes: String? = null,
        @SerializedName(value = "autoStartStreamingOutput", alternate = ["streamingEnabled"])
        val autoStartStreamingOutput: Boolean? = null,
        @SerializedName(value = "finalSummaryEnabled")
        val finalSummaryEnabled: Boolean? = null,
        @SerializedName(value = "recordingScenario", alternate = ["scenario", "recording_scene"])
        val recordingScenario: String? = null,
        @SerializedName(value = "speechInputEnabled", alternate = ["speechEnabled", "asrEnabled"])
        val speechInputEnabled: Boolean? = null
    )

    private data class VideoAnalysisPayload(
        @SerializedName(value = "summary", alternate = ["摘要"])
        val summary: String? = null,
        @SerializedName(value = "conclusion", alternate = ["结论"])
        val conclusion: String? = null,
        @SerializedName(value = "timelineEvents", alternate = ["时间线事件"])
        val timelineEvents: List<VideoAnalysisEventPayload>? = null,
        @SerializedName(value = "structuredNote", alternate = ["note", "recordNote", "结构化记录"])
        val structuredNote: JsonElement? = null,
        val markdownNote: String? = null,
        val segmentTopic: String? = null,
        val audioTranscriptExtracts: JsonElement? = null,
        val speechKeyPoints: JsonElement? = null,
        val visualEvidence: JsonElement? = null,
        val screenOrBoardContent: JsonElement? = null,
        val demonstrations: JsonElement? = null,
        val uncertainties: JsonElement? = null
    )

    private fun VideoAnalysisPayload.toEvidencePackage(): Map<String, JsonElement?> {
        return mapOf(
            "segmentTopic" to segmentTopic?.let { gson.toJsonTree(it) },
            "audioTranscriptExtracts" to audioTranscriptExtracts,
            "speechKeyPoints" to speechKeyPoints,
            "visualEvidence" to visualEvidence,
            "screenOrBoardContent" to screenOrBoardContent,
            "demonstrations" to demonstrations,
            "timelineEvents" to timelineEvents?.let { gson.toJsonTree(it) },
            "uncertainties" to uncertainties
        ).filterValues { it != null }
    }

    private data class VideoAnalysisEventPayload(
        @SerializedName(value = "timestampSeconds", alternate = ["time", "时间戳秒数"])
        val timestampSeconds: JsonElement? = null,
        @SerializedName(value = "title", alternate = ["事件"])
        val title: String? = null,
        @SerializedName(value = "detail", alternate = ["说明", "detailText"])
        val detail: String? = null,
        @SerializedName(value = "confidence", alternate = ["置信度"])
        val confidence: JsonElement? = null
    )

    private data class VideoPlanningBaseline(
        val recordingDurationSeconds: Int,
        val samplingFps: Int,
        val segmentDurationSeconds: Int,
        val captureIntervalSeconds: Int
    )

    private val SUMMARY_KEYWORDS = listOf("干了什么", "都做了什么", "总结", "回顾", "复盘")
    private val ALERT_KEYWORDS = listOf("异常", "危险", "告警", "报警", "问题", "摔倒")
    private const val SEGMENT_FACT_SCHEMA_VERSION = "video_segment_fact_packet_v1"
    private const val FINAL_REPORT_SCHEMA_VERSION = "video_final_report_v1"
    private val SEGMENT_FACT_KEYS = setOf(
        "segmentTopic",
        "audioFacts",
        "visualFacts",
        "screenOrBoardFacts",
        "demonstrationFacts",
        "timelineFacts",
        "quality"
    )
    private val FINAL_REPORT_KEYS = setOf(
        "briefSummary",
        "reportType",
        "keyConclusions",
        "structuredNotes",
        "knowledgePoints",
        "reviewOrActionItems",
        "evidenceHighlights",
        "coverageNotice"
    )
    private val CHINESE_DIGIT_MAP = mapOf(
        "零" to 0.0,
        "一" to 1.0,
        "二" to 2.0,
        "两" to 2.0,
        "三" to 3.0,
        "四" to 4.0,
        "五" to 5.0,
        "六" to 6.0,
        "七" to 7.0,
        "八" to 8.0,
        "九" to 9.0
    )
}
