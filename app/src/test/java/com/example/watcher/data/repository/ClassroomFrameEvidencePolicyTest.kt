package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClassroomFrameEvidencePolicyTest {
    @Test
    fun samplingAllowsOneFrameEveryThreeSeconds() {
        assertEquals(true, ClassroomFrameEvidencePolicy.shouldSample(lastSavedMs = null, candidateMs = 0))
        assertEquals(false, ClassroomFrameEvidencePolicy.shouldSample(lastSavedMs = 0, candidateMs = 2_999))
        assertEquals(true, ClassroomFrameEvidencePolicy.shouldSample(lastSavedMs = 0, candidateMs = 3_000))
    }

    @Test
    fun nearestFrameMustBeWithinFiveSeconds() {
        val frames = listOf(
            ClassroomFrameEvidenceIndex(timestampMs = 9_000, path = "a.jpg"),
            ClassroomFrameEvidenceIndex(timestampMs = 14_000, path = "b.jpg"),
            ClassroomFrameEvidenceIndex(timestampMs = 20_500, path = "c.jpg")
        )

        assertEquals("b.jpg", ClassroomFrameEvidencePolicy.nearestFrame(frames, targetMs = 12_000)?.path)
        assertNull(ClassroomFrameEvidencePolicy.nearestFrame(frames, targetMs = 30_000))
    }

    @Test
    fun representativeCandidatesIncludeEveryKnowledgeTreeDepth() {
        val tree = ClassroomKnowledgeTree(
            nodes = listOf(
                node(
                    id = "level_1",
                    start = 1_000,
                    children = listOf(
                        node(
                            id = "level_2",
                            start = 2_000,
                            children = listOf(
                                node(
                                    id = "level_3",
                                    start = 3_000,
                                    children = listOf(node(id = "level_4", start = 4_000))
                                )
                            )
                        )
                    )
                )
            )
        )

        val candidates = ClassroomFrameEvidencePolicy.collectRepresentativeCandidates(tree)

        assertEquals(listOf("level_1", "level_2", "level_3", "level_4"), candidates.map { it.id })
    }

    @Test
    fun representativeTargetUsesNodeStartTime() {
        val node = node(id = "concept", start = 10_000, end = 70_000)

        assertEquals(10_000L, ClassroomFrameEvidencePolicy.representativeTargetMs(node))
    }

    @Test
    fun representativeTargetSkipsZeroZeroTime() {
        assertNull(ClassroomFrameEvidencePolicy.representativeTargetMs(node(id = "bad", start = 0, end = 0)))
    }

    private fun node(
        id: String,
        start: Long,
        end: Long? = start + 1_000,
        children: List<ClassroomKnowledgeNode> = emptyList()
    ): ClassroomKnowledgeNode {
        return ClassroomKnowledgeNode(
            id = id,
            title = id,
            startMs = start,
            endMs = end,
            children = children
        )
    }
}
