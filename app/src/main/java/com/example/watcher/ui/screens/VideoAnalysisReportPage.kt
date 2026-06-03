package com.example.watcher.ui.screens

import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.VideoHistoryDetail
import com.example.watcher.ui.components.EmptyHint
import java.io.File

private data class VideoReportUiModel(
    val title: String,
    val requirement: String,
    val scenarioLabel: String,
    val labels: VideoReportCopy,
    val recordedAt: String,
    val durationLabel: String,
    val statusLabel: String,
    val audioVisualLabel: String,
    val summary: String,
    val keyConclusions: List<String>,
    val recordingNote: String?,
    val actionItems: List<VideoReportActionItem>,
    val markdownBody: String,
    val outlineItems: List<VideoReportOutlineItem>,
    val transcriptEntries: List<VideoReportTranscriptEntry>,
    val evidenceGroups: List<VideoReportEvidenceGroup>,
    val masterMedia: VideoReportMasterMedia,
    val reportVersion: Int = 0,
    val isRefining: Boolean = false
)

private data class VideoReportCopy(
    val summaryTitle: String,
    val keySectionTitle: String,
    val actionSectionTitle: String,
    val actionSectionSubtitle: String,
    val markdownTitle: String,
    val markdownSubtitle: String,
    val outlineTitle: String,
    val outlineSubtitle: String,
    val recordingNoteTitle: String,
    val evidenceTitle: String,
    val chapters: List<String>,
    val emptyActionHint: String
)

private data class VideoReportEvidenceGroup(
    val title: String,
    val description: String,
    val items: List<String>
)

private sealed interface VideoReportMasterMedia {
    data class Full(val path: String, val status: String) : VideoReportMasterMedia
    data class Segments(val status: String, val segments: List<VideoReportEvidenceSegment>) : VideoReportMasterMedia
    data class Empty(val status: String) : VideoReportMasterMedia
}

@Composable
internal fun VideoAnalysisReportPage(
    detail: VideoHistoryDetail,
    onBack: () -> Unit
) {
    val report = remember(detail) { buildVideoReportUiModel(detail) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        VideoReportHeader(report = report, onBack = onBack)
        ReportChapterStrip(chapters = report.labels.chapters)
        if (report.isRefining) {
            ReportRefiningIndicator(reportVersion = report.reportVersion)
        }
        ReportSummaryHero(report = report)
        if (report.transcriptEntries.isNotEmpty()) {
            ReportTranscriptSection(entries = report.transcriptEntries)
        }
        if (report.markdownBody.isNotBlank()) {
            ReportMarkdownSection(report = report)
        }
        ReportOutlineSection(report = report)
        if (report.actionItems.isNotEmpty()) {
            ReportActionSection(report = report)
        }
        report.recordingNote?.let { ReportRecordingNoteSection(note = it, title = report.labels.recordingNoteTitle) }
        ReportEvidenceSection(groups = report.evidenceGroups, title = report.labels.evidenceTitle)
        ReportMasterMediaSection(masterMedia = report.masterMedia)
    }
}

@Composable
private fun VideoReportHeader(
    report: VideoReportUiModel,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Button(
            onClick = onBack,
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text("返回历史数据管理", color = MaterialTheme.colorScheme.onSurface)
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = report.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = report.requirement,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportMetaChip(report.scenarioLabel, Color(0xFF9A5B00))
                ReportMetaChip(report.statusLabel, MaterialTheme.colorScheme.secondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportMetaChip(report.durationLabel, MaterialTheme.colorScheme.primary)
                ReportMetaChip(report.audioVisualLabel, MaterialTheme.colorScheme.tertiary)
            }
            Text(
                text = report.recordedAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReportChapterStrip(chapters: List<String>) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chapters) { chapter ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                    text = chapter,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReportSummaryHero(report: VideoReportUiModel) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            ReportEyebrow(report.labels.summaryTitle)
            Text(
                text = report.summary,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (report.keyConclusions.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = report.labels.keySectionTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    report.keyConclusions.take(6).forEach {
                        ReportBulletLine(text = it, marker = "•")
                    }
                }
            }

        }
    }
}

@Composable
private fun ReportRecordingNoteSection(note: String, title: String) {
    ReportDocumentSection(title = title, subtitle = "影响报告完整性的录制状态与注意事项。") {
        Text(
            text = note,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReportRefiningIndicator(reportVersion: Int) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "报告正在丰富中...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "v$reportVersion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ReportMarkdownSection(report: VideoReportUiModel) {
    ReportDocumentSection(title = report.labels.markdownTitle, subtitle = report.labels.markdownSubtitle) {
        if (report.markdownBody.isBlank()) {
            EmptyHint("暂无结构化笔记。")
        } else {
            VideoReportMarkdown(markdown = report.markdownBody)
        }
    }
}

@Composable
private fun ReportOutlineSection(report: VideoReportUiModel) {
    ReportDocumentSection(title = report.labels.outlineTitle, subtitle = report.labels.outlineSubtitle) {
        if (report.outlineItems.isEmpty()) {
            EmptyHint("暂无可提取的大纲。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                report.outlineItems.forEachIndexed { index, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "%02d".format(index + 1),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                modifier = Modifier.weight(1f),
                                text = item.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        item.children.take(8).forEach { child ->
                            ReportIndentedLine(text = child)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportActionSection(report: VideoReportUiModel) {
    ReportDocumentSection(title = report.labels.actionSectionTitle, subtitle = report.labels.actionSectionSubtitle) {
        if (report.actionItems.isEmpty()) {
            EmptyHint(report.labels.emptyActionHint)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                report.actionItems.forEachIndexed { index, item ->
                    ReportNumberedLine(number = index + 1, text = item.text)
                }
            }
        }
    }
}

@Composable
private fun ReportEvidenceSection(groups: List<VideoReportEvidenceGroup>, title: String) {
    ReportDocumentSection(title = title, subtitle = "保留可回看的音轨理解、画面、板书、演示和疑点。") {
        Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
            groups.forEach { group ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = group.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (group.items.isEmpty()) {
                        EmptyHint("暂无可展示内容。")
                    } else {
                        group.items.take(12).forEachIndexed { index, item ->
                            ReportEvidenceLine(marker = "%02d".format(index + 1), text = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportMasterMediaSection(masterMedia: VideoReportMasterMedia) {
    ReportDocumentSection(title = "母带回看", subtitle = "视频仅作为复核证据入口，不作为报告首屏核心。") {
        when (masterMedia) {
            is VideoReportMasterMedia.Full -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = masterMedia.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReportVideoPreview(path = masterMedia.path)
                MediaFileLine(path = masterMedia.path)
            }
            is VideoReportMasterMedia.Segments -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = masterMedia.status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                masterMedia.segments.forEach { segment ->
                    SegmentMediaEntry(segment = segment)
                }
            }
            is VideoReportMasterMedia.Empty -> EmptyHint(masterMedia.status)
        }
    }
}

@Composable
private fun ReportDocumentSection(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        content()
    }
}

@Composable
private fun ReportMetaChip(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accent.copy(alpha = 0.12f)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = accent
        )
    }
}

@Composable
private fun ReportEyebrow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ReportBulletLine(text: String, marker: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = marker,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ReportIndentedLine(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 9.dp)
                .width(18.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReportNumberedLine(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                text = "%02d".format(number),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReportEvidenceLine(marker: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = marker,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            modifier = Modifier.weight(1f),
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReportTranscriptSection(entries: List<VideoReportTranscriptEntry>) {
    val speakerColors = listOf(
        Color(0xFF1976D2),
        Color(0xFF388E3C),
        Color(0xFF7B1FA2),
        Color(0xFFE64A19),
        Color(0xFF00838F)
    )
    val speakerColorMap = remember(entries) {
        entries.map { it.speakerHint }.filter(String::isNotBlank).distinct()
            .mapIndexed { i, name -> name to speakerColors[i % speakerColors.size] }
            .toMap()
    }

    ReportDocumentSection(title = "语音记录", subtitle = "基于音轨的逐字理解与说话人标注。") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { entry ->
                TranscriptLine(
                    entry = entry,
                    speakerColor = speakerColorMap[entry.speakerHint]
                        ?: MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TranscriptLine(entry: VideoReportTranscriptEntry, speakerColor: Color) {
    val alpha = if (entry.uncertain || entry.confidence < 0.6f) 0.55f else 1f
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = formatTimelineSeconds(entry.timestampSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            modifier = Modifier.width(48.dp)
        )
        if (entry.speakerHint.isNotBlank()) {
            Text(
                text = entry.speakerHint,
                style = MaterialTheme.typography.labelSmall,
                color = speakerColor.copy(alpha = alpha),
                maxLines = 1
            )
        }
        Text(
            text = entry.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SegmentMediaEntry(segment: VideoReportEvidenceSegment) {
    val path = segment.localFilePath ?: return
    var expanded by rememberSaveable(segment.segmentIndex, path) { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第 ${segment.segmentIndex} 段",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${segment.durationSeconds}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = segment.summary.ifBlank { "点击回看该分片。" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            MediaFileLine(path = path)
            if (expanded) {
                ReportVideoPreview(path = path)
            }
        }
    }
}

@Composable
private fun MediaFileLine(path: String) {
    Text(
        text = File(path).name.ifBlank { path },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ReportVideoPreview(path: String) {
    val file = remember(path) { File(path) }
    if (!file.exists()) {
        EmptyHint("视频文件不存在或无法读取。")
        return
    }
    var videoView by remember(path) { mutableStateOf<VideoView?>(null) }
    DisposableEffect(path) {
        onDispose {
            videoView?.stopPlayback()
            videoView = null
        }
    }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
        factory = { context ->
            VideoView(context).apply {
                videoView = this
                val controller = MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                tag = path
                setOnPreparedListener { player ->
                    player.isLooping = false
                }
                setVideoURI(Uri.fromFile(file))
            }
        },
        update = { view ->
            videoView = view
            if (view.tag != path) {
                view.stopPlayback()
                view.tag = path
                view.setVideoURI(Uri.fromFile(file))
            }
        }
    )
}

private fun buildVideoReportUiModel(detail: VideoHistoryDetail): VideoReportUiModel {
    val scenario = RecordingScenario.fromValue(detail.run.recordingScenario)
    val evidenceSegments = parseVideoReportEvidence(detail.segments)
    val transcriptEntries = parseStructuredAudioFacts(detail.segments)
    val reportContent = buildVideoReportContent(detail, evidenceSegments)
    val labels = videoReportCopy(scenario = scenario, reportType = reportContent.reportType)
    val keyConclusions = (splitReportLines(reportContent.conclusion) + reportContent.keyPoints)
        .filter(String::isNotBlank)
        .distinctBy { normalizedReportItemKey(it) }
        .ifEmpty { reportContent.outlineItems.firstOrNull()?.let { listOf(it.title) }.orEmpty() }
    return VideoReportUiModel(
        title = detail.title,
        requirement = detail.requirement.ifBlank { scenario.outputFocus },
        scenarioLabel = scenario.label,
        labels = labels,
        recordedAt = "记录 ${formatDateTime(detail.startedAt)} · 更新 ${formatDateTime(detail.updatedAt)}",
        durationLabel = reportDurationLabel(detail),
        statusLabel = detail.statusLabel,
        audioVisualLabel = readableReportAudioVisualStatus(detail),
        summary = reportContent.summary,
        keyConclusions = keyConclusions,
        recordingNote = buildRecordingNote(detail, reportContent.coverageNotice),
        actionItems = reportContent.actionItems,
        markdownBody = reportContent.markdownBody,
        outlineItems = reportContent.outlineItems,
        transcriptEntries = transcriptEntries,
        evidenceGroups = buildEvidenceGroups(detail, evidenceSegments, reportContent),
        masterMedia = buildMasterMedia(detail, evidenceSegments),
        reportVersion = detail.run.reportVersion,
        isRefining = detail.run.reportVersion > 0 &&
            detail.run.status != com.example.watcher.data.model.VideoRunStatus.Completed &&
            detail.run.status != com.example.watcher.data.model.VideoRunStatus.CompletedDegraded
    )
}

private fun videoReportCopy(
    scenario: RecordingScenario,
    reportType: String
): VideoReportCopy {
    val observation = reportType == "scene_observation" ||
        reportType == "general_record" ||
        scenario == RecordingScenario.General
    return when {
        observation -> VideoReportCopy(
            summaryTitle = "观察摘要",
            keySectionTitle = "关键发现",
            actionSectionTitle = "后续建议",
            actionSectionSubtitle = "仅展示确有价值的补充记录、复核或处理建议。",
            markdownTitle = "观察记录",
            markdownSubtitle = "按记录内容整理出的可阅读观察结果。",
            outlineTitle = "事件脉络",
            outlineSubtitle = "从事实片段中提取的时间顺序和行为线索。",
            recordingNoteTitle = "录制说明",
            evidenceTitle = "证据回看",
            chapters = listOf("摘要", "观察", "脉络", "说明", "证据", "母带"),
            emptyActionHint = "暂无明确后续建议。"
        )
        scenario == RecordingScenario.Meeting -> VideoReportCopy(
            summaryTitle = "会议摘要",
            keySectionTitle = "关键结论与决策",
            actionSectionTitle = "行动项",
            actionSectionSubtitle = "会议后需要推进、确认或跟进的事项。",
            markdownTitle = "会议纪要",
            markdownSubtitle = "按议题整理的会议记录正文。",
            outlineTitle = "议题大纲",
            outlineSubtitle = "从讨论内容中提取的议题结构。",
            recordingNoteTitle = "录制说明",
            evidenceTitle = "关键证据",
            chapters = listOf("摘要", "纪要", "议题", "行动", "证据", "母带"),
            emptyActionHint = "暂无明确行动项。"
        )
        scenario == RecordingScenario.Interview -> VideoReportCopy(
            summaryTitle = "访谈摘要",
            keySectionTitle = "关键观点",
            actionSectionTitle = "追问建议",
            actionSectionSubtitle = "适合后续追问、复核或整理的事项。",
            markdownTitle = "访谈笔记",
            markdownSubtitle = "按观点和问答脉络整理的记录正文。",
            outlineTitle = "问答脉络",
            outlineSubtitle = "从访谈内容中提取的主题与观点线索。",
            recordingNoteTitle = "录制说明",
            evidenceTitle = "关键证据",
            chapters = listOf("摘要", "笔记", "脉络", "追问", "证据", "母带"),
            emptyActionHint = "暂无明确追问建议。"
        )
        scenario == RecordingScenario.Training -> VideoReportCopy(
            summaryTitle = "培训摘要",
            keySectionTitle = "关键步骤与注意事项",
            actionSectionTitle = "练习 / 行动项",
            actionSectionSubtitle = "适合复盘、练习或落地执行的事项。",
            markdownTitle = "培训笔记",
            markdownSubtitle = "按流程和要点整理的培训正文。",
            outlineTitle = "流程大纲",
            outlineSubtitle = "从培训内容中提取的步骤和知识结构。",
            recordingNoteTitle = "录制说明",
            evidenceTitle = "关键证据",
            chapters = listOf("摘要", "笔记", "流程", "练习", "证据", "母带"),
            emptyActionHint = "暂无练习或行动项。"
        )
        else -> VideoReportCopy(
            summaryTitle = "报告摘要",
            keySectionTitle = "核心结论与知识点",
            actionSectionTitle = "复习 / 行动项",
            actionSectionSubtitle = "可用于复习、行动或课后处理的事项。",
            markdownTitle = "结构化笔记",
            markdownSubtitle = "按记录内容整理的完整笔记正文。",
            outlineTitle = "知识点 / 议题大纲",
            outlineSubtitle = "从笔记和证据中提取的层级化阅读线索。",
            recordingNoteTitle = "录制说明",
            evidenceTitle = "关键证据",
            chapters = listOf("摘要", "笔记", "大纲", "行动", "证据", "母带"),
            emptyActionHint = "暂无待办、复习或行动项。"
        )
    }
}

private fun buildEvidenceGroups(
    detail: VideoHistoryDetail,
    evidenceSegments: List<VideoReportEvidenceSegment>,
    reportContent: VideoReportContent
): List<VideoReportEvidenceGroup> {
    return listOf(
        VideoReportEvidenceGroup(
            title = "关键语音摘录",
            description = "模型从音轨理解出的关键摘录，不等同于逐字 ASR。",
            items = (evidenceSegments.flatMap { it.transcriptExtracts + it.speechKeyPoints })
                .distinctBy { normalizedReportItemKey(it) }
        ),
        VideoReportEvidenceGroup(
            title = "画面 / PPT / 板书",
            description = "画面中可复核的板书、投屏、幻灯片、演示内容和现场变化。",
            items = (reportContent.evidenceHighlights + evidenceSegments
                .flatMap { it.visualEvidence + it.screenOrBoardContent + it.demonstrations }
                ).distinctBy { normalizedReportItemKey(it) }
        ),
        VideoReportEvidenceGroup(
            title = "时间线",
            description = "关键时序点，便于回到母带定位上下文。",
            items = (detail.events.map { "${formatTimelineSeconds(it.timestampSeconds)} ${it.title}：${it.detail}" } +
                reportContent.timelineEvents).distinctBy { normalizedReportItemKey(it) }
        ),
        VideoReportEvidenceGroup(
            title = "疑点",
            description = "模型明确标记的不确定内容，适合回看母带或分片复核。",
            items = evidenceSegments.flatMap { it.uncertainties }
                .distinctBy { normalizedReportItemKey(it) }
        )
    )
}

private fun buildRecordingNote(detail: VideoHistoryDetail, coverageNotice: String): String? {
    val notes = mutableListOf<String>()
    coverageNotice.takeIf(String::isNotBlank)?.let { notes += it }
    detail.run.degradedReason?.takeIf(String::isNotBlank)?.let { notes += it }
    detail.run.errorMessage?.takeIf(String::isNotBlank)?.let { notes += "分析错误：$it" }
    if (detail.run.segmentCount > 0 && detail.segments.size < detail.run.segmentCount) {
        notes += "计划分片 ${detail.run.segmentCount} 个，当前保留 ${detail.segments.size} 个已记录分片。"
    }
    return notes.distinct().joinToString("\n").takeIf(String::isNotBlank)
}

private fun buildMasterMedia(
    detail: VideoHistoryDetail,
    evidenceSegments: List<VideoReportEvidenceSegment>
): VideoReportMasterMedia {
    val primaryVideoPath = detail.fullMediaPath ?: detail.mergedVideoPath
    val status = readableReportMediaStatus(detail)
    if (!primaryVideoPath.isNullOrBlank()) {
        return VideoReportMasterMedia.Full(path = primaryVideoPath, status = status)
    }
    val playableSegments = evidenceSegments.filter { !it.localFilePath.isNullOrBlank() }
    return if (playableSegments.isNotEmpty()) {
        VideoReportMasterMedia.Segments(
            status = "完整母带暂不可用，可回看以下视频分片。$status",
            segments = playableSegments
        )
    } else {
        VideoReportMasterMedia.Empty("暂无可回看的完整母带或视频分片。")
    }
}

private fun splitReportLines(text: String): List<String> {
    return text
        .takeIf { !looksLikeJsonishText(it) && !isTechnicalFallbackText(it) }
        .orEmpty()
        .lineSequence()
        .flatMap { line -> line.split("；", ";").asSequence() }
        .map { line ->
            line.trim()
                .removePrefix("-")
                .removePrefix("*")
                .replace(Regex("""^\d+\.\s*"""), "")
                .trim()
        }
        .filter(String::isNotBlank)
        .take(6)
        .toList()
}

private fun normalizedReportItemKey(text: String): String {
    return text
        .lowercase()
        .replace(Regex("""[\s\p{Punct}，。；：、（）【】《》“”‘’]+"""), "")
        .take(80)
}

private fun reportDurationLabel(detail: VideoHistoryDetail): String {
    val durationSeconds = when {
        detail.run.fullMediaDurationMs > 0L -> (detail.run.fullMediaDurationMs / 1000L).toInt()
        detail.run.totalDurationSeconds > 0 -> detail.run.totalDurationSeconds
        else -> detail.segments.sumOf { it.durationSeconds }
    }
    if (durationSeconds <= 0) return "时长未知"
    val hours = durationSeconds / 3600
    val minutes = durationSeconds % 3600 / 60
    val seconds = durationSeconds % 60
    return when {
        hours > 0 -> "${hours}时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}

private fun readableReportAudioVisualStatus(detail: VideoHistoryDetail): String {
    return if (detail.run.fullMediaHasAudio) "音画完整" else "画面记录"
}

private fun readableReportMediaStatus(detail: VideoHistoryDetail): String {
    val audio = if (detail.run.fullMediaHasAudio) "包含音轨" else "未检测到音轨"
    val source = detail.run.fullMediaVideoSource.ifBlank { "本地记录" }
    return "$source · $audio"
}
