package com.example.watcher.ui.screens

import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeNodeStatus
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeProcessingStatus
import com.example.watcher.data.model.ClassroomKnowledgeTreeProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ClassroomKnowledgeTreeExperienceTextTest {
    @Test
    fun progressTextShowsAccumulationWithoutChangingCadence() {
        val text = classroomKnowledgeProgressText(
            progress = ClassroomKnowledgeTreeProgress(
                addedChars = 280,
                requiredChars = 450,
                elapsedMs = 22_000,
                requiredIntervalMs = 60_000,
                jobActive = false
            ),
            status = ClassroomKnowledgeTreeProcessingStatus.Waiting
        )

        assertEquals("正在积累本段内容 · 已积累 280/450 字 · 约 38 秒后整理", text)
    }

    @Test
    fun progressTextShowsUpdatingWhenJobIsActive() {
        val text = classroomKnowledgeProgressText(
            progress = ClassroomKnowledgeTreeProgress(jobActive = true),
            status = ClassroomKnowledgeTreeProcessingStatus.Waiting
        )

        assertEquals("正在整理知识结构", text)
    }

    @Test
    fun progressTextAvoidsZeroSecondCountdownWhenOnlyCharsAreMissing() {
        val text = classroomKnowledgeProgressText(
            progress = ClassroomKnowledgeTreeProgress(
                addedChars = 120,
                requiredChars = 450,
                elapsedMs = 60_000,
                requiredIntervalMs = 60_000,
                jobActive = false
            ),
            status = ClassroomKnowledgeTreeProcessingStatus.Waiting
        )

        assertEquals("正在积累本段内容 · 已积累 120/450 字 · 继续听课后整理", text)
    }

    @Test
    fun structureLabelPrefersActiveNodeOverFreshChanges() {
        val tree = ClassroomKnowledgeTree(
            nodes = listOf(node("active", title = "补码的符号位含义", status = ClassroomKnowledgeNodeStatus.Active))
        )

        val label = classroomKnowledgeStructureLabel(
            tree = tree,
            changedNodeIds = setOf("active", "new"),
            newNodeIds = setOf("new")
        )

        assertEquals("正在讲：补码的符号位含义", label)
    }

    @Test
    fun structureLabelShowsActiveNodeWhenNoFreshChanges() {
        val tree = ClassroomKnowledgeTree(
            nodes = listOf(node("active", title = "补码的符号位含义", status = ClassroomKnowledgeNodeStatus.Active))
        )

        val label = classroomKnowledgeStructureLabel(tree = tree)

        assertEquals("正在讲：补码的符号位含义", label)
    }

    @Test
    fun structureLabelShowsCoverageWhenNoActiveNode() {
        val tree = ClassroomKnowledgeTree(
            nodes = listOf(
                node("a", start = 0, end = 2_000),
                node("b", start = 60_000, end = 80_000)
            )
        )

        val label = classroomKnowledgeStructureLabel(tree = tree)

        assertEquals("已整理 2 个知识点 · 覆盖 0:00-1:20", label)
    }

    @Test
    fun structureLabelShowsChangesWhenNoActiveOrCoverage() {
        val tree = ClassroomKnowledgeTree(
            nodes = listOf(node("new", start = 0, end = 0), node("updated", start = 0, end = 0))
        )

        val label = classroomKnowledgeStructureLabel(
            tree = tree,
            changedNodeIds = setOf("new", "updated"),
            newNodeIds = setOf("new")
        )

        assertEquals("新增 1 个 · 更新 1 个知识点", label)
    }

    private fun node(
        id: String,
        title: String = id,
        status: ClassroomKnowledgeNodeStatus = ClassroomKnowledgeNodeStatus.Completed,
        start: Long = 1_000,
        end: Long = 3_000
    ): ClassroomKnowledgeNode {
        return ClassroomKnowledgeNode(
            id = id,
            title = title,
            status = status,
            startMs = start,
            endMs = end
        )
    }
}
