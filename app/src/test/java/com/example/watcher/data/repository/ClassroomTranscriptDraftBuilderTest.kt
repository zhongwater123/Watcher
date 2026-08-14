package com.example.watcher.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomTranscriptDraftBuilderTest {
    @Test
    fun buildClassroomTranscriptDraftIncludesInsightsAndStableTranscript() {
        val draft = buildClassroomTranscriptDraft(
            title = "Java 多态",
            stableTranscript = "成员变量看左边。\n成员方法看右边。",
            realtimeInsights = listOf("多态变量访问规则", "方法调用看运行类型")
        )

        assertTrue(draft.contains("# 临时课堂草稿：Java 多态"))
        assertTrue(draft.contains("## 课堂要点"))
        assertTrue(draft.contains("- 多态变量访问规则"))
        assertTrue(draft.contains("## 稳定转写"))
        assertTrue(draft.contains("成员变量看左边。"))
    }

    @Test
    fun buildClassroomTranscriptDraftFallsBackWhenNoRealtimeTextExists() {
        val draft = buildClassroomTranscriptDraft(
            title = "",
            stableTranscript = "",
            realtimeInsights = emptyList()
        )

        assertTrue(draft.contains("# 临时课堂草稿"))
        assertTrue(draft.contains("正在收尾实时转写"))
        assertFalse(draft.contains("null"))
    }
}
