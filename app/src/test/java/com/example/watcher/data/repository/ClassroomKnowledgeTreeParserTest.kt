package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeNodeStatus
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeUpdate
import com.example.watcher.data.model.VideoProcessTaskDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomKnowledgeTreeParserTest {
    @Test
    fun parseUpdateBuildsLearningTree() {
        val raw = """
{
  "tree": {
    "rootTitle": "Java 多态",
    "nodes": [
      {
        "id": "module_1",
        "title": "成员变量访问",
        "oneLineTakeaway": "成员变量看引用类型。",
        "teacherEmphasis": ["编译看左边，运行也看左边"],
        "examples": ["Animal a = new Cat(); a.name"],
        "misunderstandings": ["不要套用成员方法的动态绑定规则"],
        "startMs": 10000,
        "endMs": 60000,
        "status": "active",
        "children": [
          {"id": "concept_1", "title": "引用类型", "status": "draft", "children": []}
        ]
      }
    ]
  },
  "changedNodeIds": ["module_1"]
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw, nowMs = 99_000)

        assertNotNull(update)
        val tree = update!!.tree
        assertEquals("Java 多态", tree.rootTitle)
        assertEquals(listOf("module_1"), update.changedNodeIds)
        val node = tree.nodes.single()
        assertEquals("成员变量访问", node.title)
        assertEquals(ClassroomKnowledgeNodeStatus.Active, node.status)
        assertEquals("成员变量看引用类型。", node.oneLineTakeaway)
        assertEquals(listOf("编译看左边，运行也看左边"), node.teacherEmphasis)
        assertEquals(listOf("concept_1"), node.children.map { it.id })
    }

    @Test
    fun parseUpdateTrimsNodesDeeperThanFourLevels() {
        val raw = """
{
  "tree": {
    "rootTitle": "深度测试",
    "nodes": [
      {
        "id": "level_1",
        "title": "第一层",
        "children": [
          {
            "id": "level_2",
            "title": "第二层",
            "children": [
              {
                "id": "level_3",
                "title": "第三层",
                "children": [
                  {
                    "id": "level_4",
                    "title": "第四层",
                    "children": [
                      {"id": "level_5", "title": "第五层不应出现", "children": []}
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ]
  },
  "changedNodeIds": []
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw)

        val level4 = update!!.tree.nodes.single().children.single().children.single().children.single()
        assertEquals("第四层", level4.title)
        assertTrue(level4.children.isEmpty())
    }

    @Test
    fun parseUpdateMergesOverflowChildrenIntoFourthLevelDetails() {
        val raw = """
{
  "tree": {
    "rootTitle": "深度测试",
    "nodes": [
      {
        "id": "level_1",
        "title": "第一层",
        "children": [
          {
            "id": "level_2",
            "title": "第二层",
            "children": [
              {
                "id": "level_3",
                "title": "第三层",
                "children": [
                  {
                    "id": "level_4",
                    "title": "第四层",
                    "teacherEmphasis": ["已有强调"],
                    "children": [
                      {
                        "id": "level_5",
                        "title": "第五层说明不应成为节点",
                        "oneLineTakeaway": "这只是第四层知识点的补充说明。",
                        "examples": ["补充例子"],
                        "misunderstandings": ["补充易错点"],
                        "children": []
                      }
                    ]
                  }
                ]
              }
            ]
          }
        ]
      }
    ]
  },
  "changedNodeIds": []
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw)

        val level4 = update!!.tree.nodes.single().children.single().children.single().children.single()
        assertTrue(level4.children.isEmpty())
        assertTrue(level4.teacherEmphasis.any { it.contains("第五层说明不应成为节点") })
        assertEquals(listOf("补充例子"), level4.examples)
        assertEquals(listOf("补充易错点"), level4.misunderstandings)
    }

    @Test
    fun parseUpdateMergesFlatOverflowChildrenIntoFourthLevelDetails() {
        val raw = """
{
  "tree": {
    "rootTitle": "深度测试",
    "nodes": [
      {"id": "level_1", "title": "第一层", "children": []},
      {"id": "level_2", "parentId": "level_1", "title": "第二层", "children": []},
      {"id": "level_3", "parentId": "level_2", "title": "第三层", "children": []},
      {"id": "level_4", "parentId": "level_3", "title": "第四层", "children": []},
      {
        "id": "level_5",
        "parentId": "level_4",
        "title": "第五层平铺说明",
        "oneLineTakeaway": "应该合并进第四层详情。",
        "examples": ["平铺例子"],
        "children": []
      }
    ]
  },
  "changedNodeIds": []
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw)

        val level4 = update!!.tree.nodes.single().children.single().children.single().children.single()
        assertTrue(level4.children.isEmpty())
        assertTrue(level4.teacherEmphasis.any { it.contains("第五层平铺说明") })
        assertEquals(listOf("平铺例子"), level4.examples)
    }

    @Test
    fun parseUpdateNormalizesMultipleActiveNodesToDeepestLastActive() {
        val raw = """
{
  "tree": {
    "rootTitle": "数字电子技术",
    "nodes": [
      {
        "id": "module_1",
        "title": "数字与码制",
        "status": "active",
        "children": [
          {
            "id": "topic_1",
            "parentId": "module_1",
            "title": "数制表示",
            "status": "active",
            "children": []
          }
        ]
      }
    ]
  },
  "changedNodeIds": ["topic_1"]
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw)

        val module = update!!.tree.nodes.single()
        val topic = module.children.single()
        assertEquals(ClassroomKnowledgeNodeStatus.Completed, module.status)
        assertEquals(ClassroomKnowledgeNodeStatus.Active, topic.status)
        assertEquals(1, ClassroomKnowledgeTreeParser.countActiveNodes(update.tree))
    }

    @Test
    fun renderActivePathSummaryShowsCurrentLearningPath() {
        val raw = """
{
  "tree": {
    "rootTitle": "数字电子技术",
    "nodes": [
      {
        "id": "module_1",
        "title": "数字与码制",
        "status": "completed",
        "children": [
          {
            "id": "topic_1",
            "parentId": "module_1",
            "title": "数制表示",
            "status": "active",
            "oneLineTakeaway": "不同数制用不同基数表示数量。",
            "children": []
          }
        ]
      }
    ]
  },
  "changedNodeIds": ["topic_1"]
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw)

        val summary = ClassroomKnowledgeTreeParser.renderActivePathSummary(update!!.tree)
        assertTrue(summary.contains("L1: [completed] 数字与码制 id=module_1"))
        assertTrue(summary.contains("L2: [active] 数制表示 id=topic_1"))
        assertTrue(summary.contains("当前节点一句话：不同数制用不同基数表示数量。"))
    }

    @Test
    fun parseUpdateKeepsSpecificConceptsAtFourthLevel() {
        val raw = """
{
  "tree": {
    "rootTitle": "电工与电子技术基础课程知识树",
    "nodes": [
      {
        "id": "module_1",
        "title": "数字电子技术",
        "children": [
          {
            "id": "module_1_1",
            "parentId": "module_1",
            "title": "数字与码字",
            "children": [
              {
                "id": "module_1_1_1",
                "parentId": "module_1_1",
                "title": "数的数值表示形式",
                "children": [
                  {
                    "id": "module_1_1_1_1",
                    "parentId": "module_1_1_1",
                    "title": "十进制数表示",
                    "oneLineTakeaway": "逢十进一的计数进制，使用0~9十个数字。",
                    "teacherEmphasis": ["权值为10的各次幂"],
                    "examples": ["325 = 3×10² + 2×10¹ + 5×10⁰"],
                    "children": []
                  }
                ]
              }
            ]
          }
        ]
      }
    ]
  },
  "changedNodeIds": ["module_1_1_1_1"]
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw)

        assertEquals(4, ClassroomKnowledgeTreeParser.countNodes(update!!.tree))
        val level4 = update.tree.nodes.single().children.single().children.single().children.single()
        assertEquals("十进制数表示", level4.title)
        assertEquals(listOf("权值为10的各次幂"), level4.teacherEmphasis)
        assertEquals(listOf("325 = 3×10² + 2×10¹ + 5×10⁰"), level4.examples)
    }

    @Test
    fun parseUpdatePreservesSiblingConceptsAtFourthLevel() {
        val raw = """
{
  "tree": {
    "rootTitle": "数字电子技术",
    "nodes": [
      {
        "id": "module_1",
        "title": "数字与码制",
        "children": [
          {
            "id": "topic_1",
            "parentId": "module_1",
            "title": "数制表示",
            "children": [
              {
                "id": "concept_1",
                "parentId": "topic_1",
                "title": "二进制数表示",
                "children": [
                  {
                    "id": "detail_1",
                    "parentId": "concept_1",
                    "title": "二进制的进位规则",
                    "oneLineTakeaway": "满二向高位进一。",
                    "examples": ["1 + 1 = 10₂"],
                    "children": []
                  },
                  {
                    "id": "detail_2",
                    "parentId": "concept_1",
                    "title": "二进制的位权含义",
                    "oneLineTakeaway": "每位代表2的不同次幂。",
                    "teacherEmphasis": ["位权解释放在详情字段"],
                    "children": []
                  }
                ]
              }
            ]
          }
        ]
      }
    ]
  },
  "changedNodeIds": ["detail_1", "detail_2"]
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw)

        val level4Nodes = update!!.tree.nodes.single()
            .children.single()
            .children.single()
            .children
        assertEquals(listOf("detail_1", "detail_2"), level4Nodes.map { it.id })
        assertEquals("二进制的进位规则", level4Nodes[0].title)
        assertEquals("二进制的位权含义", level4Nodes[1].title)
        assertTrue(level4Nodes.all { it.children.isEmpty() })
        assertEquals(listOf("位权解释放在详情字段"), level4Nodes[1].teacherEmphasis)
    }

    @Test
    fun parseUpdateNestsFlatParentIdNodes() {
        val raw = """
{
  "tree": {
    "rootTitle": "数字电子技术",
    "nodes": [
      {"id": "module_1", "parentId": "null", "title": "数字与码字", "oneLineTakeaway": "区分数值和编码。", "children": []},
      {"id": "module_1_1", "parentId": "module_1", "title": "十进制", "oneLineTakeaway": "逢十进一。", "children": []},
      {"id": "module_1_2", "parentId": "module_1", "title": "二进制", "oneLineTakeaway": "逢二进一。", "children": []},
      {"id": "module_1_2_1", "parentId": "module_1_2", "title": "二进制物理状态", "oneLineTakeaway": "0 和 1 对应两种稳定状态。", "children": []}
    ]
  },
  "changedNodeIds": ["module_1_2"]
}
""".trimIndent()

        val update = ClassroomKnowledgeTreeParser.parseUpdate(raw)

        val module = update!!.tree.nodes.single()
        assertNull(module.parentId)
        assertEquals(listOf("module_1_1", "module_1_2"), module.children.map { it.id })
        assertEquals(listOf("module_1_2_1"), module.children[1].children.map { it.id })
    }

    @Test
    fun parseUpdateCapsVerboseNodeDetails() {
        val raw = """
{
  "tree": {
    "rootTitle": "数字电子技术",
    "nodes": [
      {
        "id": "module_1",
        "title": "进制表示",
        "oneLineTakeaway": "不同进制有不同基数。",
        "teacherEmphasis": ["1", "2", "3", "4", "5"],
        "examples": ["a", "b", "c", "d", "e"],
        "misunderstandings": ["x", "y", "z", "w"],
        "children": []
      }
    ]
  },
  "changedNodeIds": ["module_1"]
}
""".trimIndent()

        val node = ClassroomKnowledgeTreeParser.parseUpdate(raw)!!.tree.nodes.single()

        assertEquals(4, node.teacherEmphasis.size)
        assertEquals(4, node.examples.size)
        assertEquals(3, node.misunderstandings.size)
    }

    @Test
    fun parseUpdateReturnsNullForInvalidJson() {
        assertNull(ClassroomKnowledgeTreeParser.parseUpdate("not a json response"))
    }

    @Test
    fun updatePolicyRequiresEnoughCharsEnoughTimeAndIdleJob() {
        assertTrue(
            ClassroomKnowledgeTreeUpdatePolicy.shouldUpdate(
                nowMs = 61_000,
                lastUpdateAtMs = 0,
                currentTranscriptLength = 451,
                lastTranscriptLength = 0,
                jobActive = false
            )
        )
        assertEquals(
            false,
            ClassroomKnowledgeTreeUpdatePolicy.shouldUpdate(
                nowMs = 61_000,
                lastUpdateAtMs = 0,
                currentTranscriptLength = 449,
                lastTranscriptLength = 0,
                jobActive = false
            )
        )
        assertEquals(
            false,
            ClassroomKnowledgeTreeUpdatePolicy.shouldUpdate(
                nowMs = 59_000,
                lastUpdateAtMs = 0,
                currentTranscriptLength = 600,
                lastTranscriptLength = 0,
                jobActive = false
            )
        )
        assertEquals(
            false,
            ClassroomKnowledgeTreeUpdatePolicy.shouldUpdate(
                nowMs = 61_000,
                lastUpdateAtMs = 0,
                currentTranscriptLength = 600,
                lastTranscriptLength = 0,
                jobActive = true
            )
        )
    }

    @Test
    fun updatePolicyFlushesOnStopOnlyWhenTreeMayMissStableAsr() {
        assertTrue(
            ClassroomKnowledgeTreeUpdatePolicy.shouldFlushOnStop(
                currentTranscriptLength = 7_104,
                lastTranscriptLength = 5_521,
                hasKnowledgeTree = true
            )
        )
        assertTrue(
            ClassroomKnowledgeTreeUpdatePolicy.shouldFlushOnStop(
                currentTranscriptLength = 900,
                lastTranscriptLength = 900,
                hasKnowledgeTree = false
            )
        )
        assertEquals(
            false,
            ClassroomKnowledgeTreeUpdatePolicy.shouldFlushOnStop(
                currentTranscriptLength = 7_104,
                lastTranscriptLength = 7_104,
                hasKnowledgeTree = true
            )
        )
        assertEquals(
            false,
            ClassroomKnowledgeTreeUpdatePolicy.shouldFlushOnStop(
                currentTranscriptLength = 0,
                lastTranscriptLength = 0,
                hasKnowledgeTree = false
            )
        )
    }

    @Test
    fun transcriptWindowRendersTimestampedAsrLines() {
        val window = ClassroomKnowledgeTranscriptWindow(
            listOf(
                ClassroomKnowledgeTranscriptLine(
                    sequence = 0,
                    startMs = 135_200,
                    endMs = 140_800,
                    text = "这里开始讲生产环境中的模型监控。",
                    source = "live_asr",
                    asrLogId = "log-1"
                )
            )
        )

        val rendered = window.renderForPrompt()

        assertTrue(rendered.contains("[02:15.20-02:20.80] 这里开始讲生产环境中的模型监控。"))
        assertEquals("02:15.20-02:20.80", window.describeRange())
    }

    @Test
    fun knowledgeTreePromptRequiresTimesFromAsrLines() {
        val prompt = ClassroomPromptBuilder.knowledgeTreePrompt(
            task = VideoProcessTaskDraft(title = "机器学习系统设计"),
            currentTreeJson = "{}",
            transcriptWindow = ClassroomKnowledgeTranscriptWindow(
                listOf(
                    ClassroomKnowledgeTranscriptLine(
                        sequence = 0,
                        startMs = 10_000,
                        endMs = 15_000,
                        text = "生产环境还要考虑数据漂移。",
                        source = "live_asr",
                        asrLogId = null
                    )
                )
            ),
            realtimeInsights = emptyList()
        )

        assertTrue(prompt.contains("[00:10.00-00:15.00] 生产环境还要考虑数据漂移。"))
        assertTrue(prompt.contains("startMs/endMs 必须来自支撑该节点的 ASR 行时间范围"))
        assertTrue(prompt.contains("不要整棵树填 0"))
    }

    @Test
    fun mergePolicyKeepsOldNodesAndIgnoresReparenting() {
        val current = ClassroomKnowledgeTree(
            rootTitle = "课程知识树",
            nodes = listOf(
                ClassroomKnowledgeNode(
                    id = "module_1",
                    title = "生产机器学习",
                    startMs = 10_000,
                    endMs = 60_000,
                    children = listOf(
                        ClassroomKnowledgeNode(
                            id = "topic_1",
                            parentId = "module_1",
                            title = "数据差异",
                            startMs = 20_000,
                            endMs = 40_000
                        )
                    )
                )
            )
        )
        val candidate = ClassroomKnowledgeTreeUpdate(
            tree = ClassroomKnowledgeTree(
                nodes = listOf(
                    ClassroomKnowledgeNode(
                        id = "module_2",
                        title = "错误的新父级",
                        children = listOf(
                            ClassroomKnowledgeNode(
                                id = "topic_1",
                                parentId = "module_2",
                                title = "数据差异与漂移",
                                startMs = 0,
                                endMs = 0
                            )
                        )
                    )
                )
            ),
            changedNodeIds = listOf("topic_1")
        )
        val window = ClassroomKnowledgeTranscriptWindow(
            listOf(
                ClassroomKnowledgeTranscriptLine(
                    sequence = 0,
                    startMs = 120_000,
                    endMs = 130_000,
                    text = "这里继续说明数据差异与漂移。",
                    source = "live_asr",
                    asrLogId = null
                )
            )
        )

        val result = ClassroomKnowledgeTreeMergePolicy.merge(
            currentTree = current,
            candidateUpdate = candidate,
            window = window,
            finalFlush = false
        )

        val oldTopic = result.update.tree.nodes.first { it.id == "module_1" }.children.single()
        assertEquals("module_1", oldTopic.parentId)
        assertEquals("数据差异与漂移", oldTopic.title)
        assertEquals(20_000L, oldTopic.startMs)
        assertTrue(result.stats.ignoredReparents >= 1)
    }

    @Test
    fun mergePolicyFallsBackZeroTimeNewNodesToInputWindow() {
        val candidate = ClassroomKnowledgeTreeUpdate(
            tree = ClassroomKnowledgeTree(
                nodes = listOf(
                    ClassroomKnowledgeNode(
                        id = "module_1",
                        title = "生产模型监控",
                        startMs = 0,
                        endMs = 0
                    )
                )
            ),
            changedNodeIds = listOf("module_1")
        )
        val window = ClassroomKnowledgeTranscriptWindow(
            listOf(
                ClassroomKnowledgeTranscriptLine(
                    sequence = 0,
                    startMs = 180_000,
                    endMs = 190_000,
                    text = "生产模型上线后需要监控。",
                    source = "live_asr",
                    asrLogId = null
                )
            )
        )

        val result = ClassroomKnowledgeTreeMergePolicy.merge(
            currentTree = null,
            candidateUpdate = candidate,
            window = window,
            finalFlush = false
        )

        val node = result.update.tree.nodes.single()
        assertEquals(180_000L, node.startMs)
        assertEquals(190_000L, node.endMs)
        assertEquals(1, ClassroomKnowledgeTreeParser.countValidTimeNodes(result.update.tree))
    }
}
