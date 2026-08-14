package com.example.watcher.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomNoteResultParserTest {
    @Test
    fun parseClassroomSchemaBuildsNoteResult() {
        val raw = """
{
  "courseOverview": {"title": "Java 多态", "summary": "讲解成员变量与成员方法的调用规则。"},
  "learningObjectives": [{"noteBlockId": "obj_1", "text": "理解多态调用规则", "evidenceIds": ["seg1_audio_1"]}],
  "askableIndex": [{"noteBlockId": "obj_1", "topic": "多态调用规则", "evidenceIds": ["seg1_audio_1"]}],
  "evidenceRefs": [{"evidenceId": "seg1_audio_1", "segmentIndex": 1, "timeRange": "0-120", "source": "audio", "summary": "老师讲解调用规则"}],
  "coverageNotice": "",
  "markdownNote": "# Java 多态\n\n## 学习目标\n- 理解多态调用规则"
}
""".trimIndent()

        val result = ClassroomNoteResultParser.parse(raw)

        assertEquals(ClassroomParseStatus.Success, result.parseStatus)
        assertTrue(result.markdownNote.contains("Java 多态"))
        assertEquals("讲解成员变量与成员方法的调用规则。", result.summary)
        assertEquals("seg1_audio_1", result.evidenceRefs.single().evidenceId)
        assertEquals("多态调用规则", result.askableIndex.single().topic)
    }

    @Test
    fun invalidJsonFallsBackToMarkdownText() {
        val result = ClassroomNoteResultParser.parse("# 课堂笔记\n\n这是一段模型直接返回的 Markdown。")

        assertEquals(ClassroomParseStatus.Fallback, result.parseStatus)
        assertTrue(result.markdownNote.contains("模型直接返回"))
        assertEquals("课堂笔记", result.summary)
    }

    @Test
    fun blankResponseIsFailed() {
        val result = ClassroomNoteResultParser.parse("   ")

        assertEquals(ClassroomParseStatus.Failed, result.parseStatus)
        assertTrue(result.markdownNote.isBlank())
    }
}
