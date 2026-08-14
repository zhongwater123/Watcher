package com.example.watcher.ui.screens

import com.example.watcher.data.model.ClassroomTranscriptUiItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ClassroomRecordingTranscriptCardTest {
    @Test
    fun realtimeTranscriptScrollAnchorChangesWhenPartialTextChangesWithoutItemCountChange() {
        val item = ClassroomTranscriptUiItem(
            key = "transcript-1",
            runId = 10,
            transcriptId = 1,
            timestampLabel = "00:01",
            globalStartMs = 1_000,
            globalEndMs = 2_000,
            text = "stable text"
        )

        val firstAnchor = buildRealtimeTranscriptScrollAnchor(
            transcriptItems = listOf(item),
            partialText = "正在识别第一段"
        )
        val updatedAnchor = buildRealtimeTranscriptScrollAnchor(
            transcriptItems = listOf(item),
            partialText = "正在识别第一段更多内容"
        )

        assertNotEquals(firstAnchor, updatedAnchor)
    }

    @Test
    fun realtimeTranscriptScrollAnchorUsesLatestStableItemKey() {
        val first = ClassroomTranscriptUiItem(
            key = "transcript-1",
            runId = 10,
            transcriptId = 1,
            timestampLabel = "00:01",
            globalStartMs = 1_000,
            globalEndMs = 2_000,
            text = "first"
        )
        val second = ClassroomTranscriptUiItem(
            key = "transcript-2",
            runId = 10,
            transcriptId = 2,
            timestampLabel = "00:02",
            globalStartMs = 2_000,
            globalEndMs = 3_000,
            text = "second"
        )

        assertEquals(
            "2|transcript-2|",
            buildRealtimeTranscriptScrollAnchor(
                transcriptItems = listOf(first, second),
                partialText = ""
            )
        )
    }

    @Test
    fun realtimeTranscriptLatestTargetPointsToLastRenderedItem() {
        assertNull(realtimeTranscriptLatestTargetIndex(itemCount = 0))
        assertEquals(0, realtimeTranscriptLatestTargetIndex(itemCount = 1, RealtimeTranscriptDisplayOrder.Chronological))
        assertEquals(4, realtimeTranscriptLatestTargetIndex(itemCount = 5, RealtimeTranscriptDisplayOrder.Chronological))
        assertEquals(0, realtimeTranscriptLatestTargetIndex(itemCount = 5, RealtimeTranscriptDisplayOrder.Reverse))
    }

    @Test
    fun realtimeTranscriptLatestVisibilityFollowsDisplayOrder() {
        assertEquals(
            true,
            realtimeTranscriptIsLatestVisible(
                visibleIndices = listOf(3, 4),
                itemCount = 5,
                order = RealtimeTranscriptDisplayOrder.Chronological
            )
        )
        assertEquals(
            false,
            realtimeTranscriptIsLatestVisible(
                visibleIndices = listOf(0, 1),
                itemCount = 5,
                order = RealtimeTranscriptDisplayOrder.Chronological
            )
        )
        assertEquals(
            true,
            realtimeTranscriptIsLatestVisible(
                visibleIndices = listOf(0, 1),
                itemCount = 5,
                order = RealtimeTranscriptDisplayOrder.Reverse
            )
        )
    }

    @Test
    fun realtimeTranscriptReverseLatestRequiresTopAnchor() {
        assertEquals(
            false,
            realtimeTranscriptIsLatestVisible(
                visibleIndices = listOf(0, 1),
                itemCount = 5,
                order = RealtimeTranscriptDisplayOrder.Reverse,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 24
            )
        )
        assertEquals(
            true,
            realtimeTranscriptIsLatestVisible(
                visibleIndices = listOf(0, 1),
                itemCount = 5,
                order = RealtimeTranscriptDisplayOrder.Reverse,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = 0
            )
        )
    }

    @Test
    fun realtimeTranscriptItemsReverseForNewestFirstMode() {
        val first = ClassroomTranscriptUiItem(
            key = "transcript-1",
            runId = 10,
            transcriptId = 1,
            timestampLabel = "00:01",
            globalStartMs = 1_000,
            globalEndMs = 2_000,
            text = "first"
        )
        val second = ClassroomTranscriptUiItem(
            key = "transcript-2",
            runId = 10,
            transcriptId = 2,
            timestampLabel = "00:02",
            globalStartMs = 2_000,
            globalEndMs = 3_000,
            text = "second"
        )

        assertEquals(
            listOf("transcript-1", "transcript-2"),
            orderRealtimeTranscriptItems(listOf(first, second), RealtimeTranscriptDisplayOrder.Chronological)
                .map { it.key }
        )
        assertEquals(
            listOf("transcript-2", "transcript-1"),
            orderRealtimeTranscriptItems(listOf(first, second), RealtimeTranscriptDisplayOrder.Reverse)
                .map { it.key }
        )
    }
}
