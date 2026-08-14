package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomRecordingDefaults
import com.example.watcher.data.model.ClassroomInlineQuestionType
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomPromptBuilderTest {
    @Test
    fun segmentPromptUsesClassroomFactPacketInsteadOfVideoNarrative() {
        val task = ClassroomRecordingDefaults.buildDraft("Java 多态", 600)

        val prompt = ClassroomPromptBuilder.segmentFactPrompt(
            task = task,
            segmentNumber = 1,
            segmentCount = 5,
            startOffsetSeconds = 0,
            durationSeconds = 120,
            inputMode = "merged_video"
        )

        assertTrue(prompt.contains("结构化事实包"))
        assertTrue(prompt.contains("segmentTopic"))
        assertTrue(prompt.contains("boardOrScreenEvidence"))
        assertTrue(prompt.contains("uncertainties"))
        assertTrue(prompt.contains("不是复现电影分镜"))
        assertTrue(!prompt.contains("电影分镜脚本"))
    }

    @Test
    fun noteSynthesisPromptContainsDualLayerClassroomSchema() {
        val task = ClassroomRecordingDefaults.buildDraft("Java 多态", 600)

        val prompt = ClassroomPromptBuilder.noteSynthesisPrompt(
            task = task,
            audioOutlineMarkdown = "# 音频课堂大纲",
            segmentFacts = emptyList(),
            coverageNotices = listOf("音频证据不足")
        )

        assertTrue(prompt.contains("双层课堂笔记"))
        assertTrue(prompt.contains("courseOverview"))
        assertTrue(prompt.contains("askableIndex"))
        assertTrue(prompt.contains("evidenceRefs"))
        assertTrue(prompt.contains("coverageNotice"))
    }

    @Test
    fun inlineQuestionPromptUsesAllAsrAndVisualEvidence() {
        val task = ClassroomRecordingDefaults.buildDraft("数字电子技术", 600)
        val prompt = ClassroomPromptBuilder.inlineQuestionPrompt(
            task = task,
            questionType = ClassroomInlineQuestionType.Why,
            selectedTranscripts = listOf(transcript(1, 274_000, 279_000, "计算机里面用到的是二进制")),
            allContextTranscripts = listOf(
                transcript(1, 274_000, 279_000, "计算机里面用到的是二进制"),
                transcript(2, 288_000, 296_000, "二进制只有0和1，逢二进一")
            ),
            realtimeInsights = listOf("十进制逢十进一，二进制逢二进一"),
            contextStartMs = 0,
            contextEndMs = 296_000,
            frameEvidence = ClassroomInlineFrameEvidence(
                imageDataUri = "data:image/jpeg;base64,abc",
                source = "test_video",
                frameTimestampMs = 277_000,
                framePath = "/tmp/frame.jpg",
                width = 960,
                height = 540,
                byteLength = 12,
                sha256 = "abc",
                status = "test_video_extracted"
            )
        )

        assertTrue(prompt.contains("全量已产出 ASR 上下文"))
        assertTrue(prompt.contains("可能存在错字、漏字、断句错误和同音词误识别"))
        assertTrue(prompt.contains("板书、PPT、代码、公式"))
        assertTrue(prompt.contains("visualFrameInstruction"))
        assertTrue(prompt.contains("test_video_extracted"))
    }

    @Test
    fun knowledgeTreePromptRequiresNestedLearningTree() {
        val task = ClassroomRecordingDefaults.buildDraft("数字电子技术", 600)

        val prompt = ClassroomPromptBuilder.knowledgeTreePrompt(
            task = task,
            currentTreeJson = "{}",
            transcriptWindow = ClassroomKnowledgeTranscriptWindow(
                listOf(
                    ClassroomKnowledgeTranscriptLine(
                        sequence = 0,
                        startMs = 30_000,
                        endMs = 42_000,
                        text = "老师正在讲十进制、二进制、十六进制和八进制。",
                        source = "live_asr",
                        asrLogId = null
                    )
                )
            ),
            realtimeInsights = listOf("进制表示是当前主题"),
            activePathSummary = "当前 active 路径：L1: 数字与码制"
        )

        assertEquals(1, Regex("\"status\"\\s*:\\s*\"active\"").findAll(prompt).count())
        assertTrue(prompt.contains("必须输出真正的嵌套树"))
        assertTrue(prompt.contains("子节点必须放在父节点的 children 数组里"))
        assertTrue(prompt.contains("root.nodes 只放一级知识模块"))
        assertTrue(prompt.contains("每一层节点都必须是知识点或学习要点"))
        assertTrue(prompt.contains("第 4 层仍是细粒度知识点"))
        assertTrue(prompt.contains("解释、规则、例子、步骤和易错点必须写在这些详情字段里"))
        assertTrue(prompt.contains("同一父主题下的新并列概念应作为 sibling"))
        assertTrue(prompt.contains("当前 active 路径"))
        assertTrue(prompt.contains("更新决策清单"))
        assertTrue(prompt.contains("[00:30.00-00:42.00] 老师正在讲十进制、二进制、十六进制和八进制。"))
        assertTrue(prompt.contains("startMs/endMs 必须来自支撑该节点的 ASR 行时间范围"))
        assertTrue(prompt.contains("新增 sibling"))
        assertTrue(prompt.contains("新增 child"))
        assertTrue(prompt.contains("新增一级模块"))
        assertTrue(prompt.contains("二进制的进位规则"))
        assertTrue(prompt.contains("二进制的位权含义"))
        assertTrue(prompt.contains("module_1_topic_1_concept_1_subpoint_1"))
        assertTrue(prompt.contains("module_1_topic_1_concept_1_subpoint_2"))
        assertFalse(prompt.contains("module_1_topic_1_concept_1_detail_1"))
        assertFalse(prompt.contains("module_1_topic_1_concept_1_detail_2"))
        assertFalse(prompt.contains("第 4 层只用于非常具体的规则、例题步骤、公式展开或易错点"))
        assertTrue(prompt.contains("不要把“步骤 1”“老师强调”“常见误解”“例题过程”当成树节点"))
        assertTrue(prompt.contains("仅作参考，最终结构必须以 ASR 为准"))
        assertTrue(prompt.contains("只是结构示例，不要复制示例里的课程主题、标题或 id"))
    }

    private fun transcript(id: Long, start: Long, end: Long, text: String): VideoSpeechTranscriptEntity {
        return VideoSpeechTranscriptEntity(
            id = id,
            runId = 7,
            timestamp = start,
            displayTimestamp = "${start / 1000}s",
            text = text,
            globalStartMs = start,
            globalEndMs = end
        )
    }
}
