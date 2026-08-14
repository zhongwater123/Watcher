package com.example.watcher.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassroomCompletionStateMergeTest {
    @Test
    fun emptyClassroomDraftUpdateDoesNotEraseExistingDraft() {
        assertEquals(
            "# 已有初稿",
            mergeClassroomCompletionDraftBuffer(
                previous = "# 已有初稿",
                incoming = ""
            )
        )
    }

    @Test
    fun nonEmptyClassroomDraftUpdateReplacesPreviousDraft() {
        assertEquals(
            "# 新初稿",
            mergeClassroomCompletionDraftBuffer(
                previous = "# 旧初稿",
                incoming = "# 新初稿"
            )
        )
    }

    @Test
    fun absentClassroomDraftUpdateKeepsPreviousDraft() {
        assertEquals(
            "# 已有初稿",
            mergeClassroomCompletionDraftBuffer(
                previous = "# 已有初稿",
                incoming = null
            )
        )
    }
}
