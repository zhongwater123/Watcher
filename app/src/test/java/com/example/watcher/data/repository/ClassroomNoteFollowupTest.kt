package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomNoteFollowupContextStage
import com.example.watcher.data.model.ClassroomNoteFollowupSourceRef
import com.example.watcher.data.model.ClassroomRecordingDefaults
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoRunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomNoteFollowupTest {
    @Test
    fun parserExtractsAnswerEvidenceSupplementAndTimeRefs() {
        val raw = """
            {
              "answer": "二进制只使用 0 和 1 表示数值。",
              "courseEvidence": [
                {
                  "type": "transcript",
                  "text": "老师提到二进制逢二进一",
                  "startMs": 120000,
                  "endMs": 126000,
                  "refId": "asr-1"
                }
              ],
              "supplement": "补充解释：这也是数字电路中高低电平抽象的基础。"
            }
        """.trimIndent()

        val result = ClassroomNoteFollowupResultParser.parse(raw)

        assertEquals("二进制只使用 0 和 1 表示数值。", result.answer)
        assertEquals("补充解释：这也是数字电路中高低电平抽象的基础。", result.supplement)
        assertEquals(1, result.sourceRefs.size)
        assertEquals("transcript", result.sourceRefs.single().type)
        assertEquals(120000L, result.sourceRefs.single().startMs)
        assertEquals(126000L, result.sourceRefs.single().endMs)
    }

    @Test
    fun promptRequiresEvidenceAndExplicitSupplementLabel() {
        val task = ClassroomRecordingDefaults.buildDraft("数字电路", 600)
        val prompt = ClassroomPromptBuilder.noteFollowupPrompt(
            question = "二进制为什么适合计算机？",
            task = task,
            context = ClassroomNoteFollowupContext(
                stage = ClassroomNoteFollowupContextStage.Final,
                noteText = "# 数字电路\n二进制逢二进一。",
                summaryText = "本节课介绍进制。",
                knowledgeTreeText = "数字与码制 > 二进制",
                evidenceRefs = listOf(
                    ClassroomNoteFollowupSourceRef(
                        type = "transcript",
                        text = "二进制只有 0 和 1",
                        startMs = 90_000,
                        endMs = 98_000,
                        refId = "asr-8"
                    )
                ),
                conversationTurns = emptyList()
            )
        )

        assertTrue(prompt.contains("只输出 JSON"))
        assertTrue(prompt.contains("answer"))
        assertTrue(prompt.contains("courseEvidence"))
        assertTrue(prompt.contains("supplement"))
        assertTrue(prompt.contains("补充解释"))
        assertTrue(prompt.contains("本节课材料中未找到直接依据"))
        assertTrue(prompt.contains("[01:30.00-01:38.00] 二进制只有 0 和 1"))
    }

    @Test
    fun contextStagePrefersFinalNoteOverDraft() {
        val finalRun = run(markdownNote = "# 最终笔记", status = VideoRunStatus.Completed)
        val draftRun = run(markdownNote = "", outlineMarkdown = "# 音频大纲", status = VideoRunStatus.Summarizing)

        assertEquals(
            ClassroomNoteFollowupContextStage.Final,
            ClassroomNoteFollowupContextFactory.resolveStage(finalRun, streamingBuffer = "临时草稿")
        )
        assertEquals(
            ClassroomNoteFollowupContextStage.Outline,
            ClassroomNoteFollowupContextFactory.resolveStage(draftRun, streamingBuffer = "临时草稿")
        )
        assertEquals(
            ClassroomNoteFollowupContextStage.Draft,
            ClassroomNoteFollowupContextFactory.resolveStage(
                draftRun.copy(outlineMarkdown = ""),
                streamingBuffer = "临时草稿"
            )
        )
    }

    private fun run(
        markdownNote: String,
        outlineMarkdown: String = "",
        status: VideoRunStatus
    ): VideoProcessRun {
        return VideoProcessRun(
            id = 12,
            taskId = 3,
            taskTitle = "数字电路",
            taskRequirement = "解释课堂重点",
            status = status,
            markdownNote = markdownNote,
            outlineMarkdown = outlineMarkdown
        )
    }
}
