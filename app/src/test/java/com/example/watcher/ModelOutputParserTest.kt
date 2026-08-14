package com.example.watcher

import com.example.watcher.data.model.BaselineSource
import com.example.watcher.data.model.CheckResult
import com.example.watcher.data.model.MonitorMode
import com.example.watcher.data.model.TargetTrigger
import com.example.watcher.data.model.VideoTaskCategory
import com.example.watcher.data.repository.ModelOutputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOutputParserTest {
    @Test
    fun `intent parsing clamps interval and keeps baseline`() {
        val raw = """
            {
              "title": "Desk Watch",
              "userRequirement": "Alert me when the laptop leaves the desk",
              "originalSceneDescription": "A laptop sits on a wooden desk",
              "checkIntervalSeconds": 999,
              "promptTemplate": "Return JSON only."
            }
        """.trimIndent()

        val result = ModelOutputParser.parseIntentResult(
            rawText = raw,
            userInput = "Watch the desk",
            baseFrameBase64 = "baseline",
            baselineSource = BaselineSource.CapturedFrame,
            hasImage = true
        )

        assertEquals("Desk Watch", result.title)
        assertEquals(300, result.checkInterval)
        assertEquals("baseline", result.baseFrameBase64)
        assertEquals(MonitorMode.SceneBaseline, result.monitorMode)
    }

    @Test
    fun `intent parsing fills fallbacks when fields are missing`() {
        val result = ModelOutputParser.parseIntentResult(
            rawText = """{"title":"", "checkIntervalSeconds": 1}""",
            userInput = "Watch the hallway",
            baseFrameBase64 = null,
            baselineSource = BaselineSource.CapturedFrame,
            hasImage = false
        )

        assertEquals("Watch the hallway", result.userRequirement)
        assertEquals(2, result.checkInterval)
        assertTrue(result.promptTemplate.contains("Return JSON only"))
        assertEquals(BaselineSource.CapturedFrame, result.baselineSource)
    }

    @Test
    fun `intent parsing supports chinese keys`() {
        val raw = """
            {
              "任务标题": "Water Bucket",
              "用户需求": "Watch the bucket",
              "原始场景描述": "A bucket sits below a tap",
              "打点频率": 12,
              "每次提示词": "Return JSON only."
            }
        """.trimIndent()

        val result = ModelOutputParser.parseIntentResult(
            rawText = raw,
            userInput = "Watch the bucket",
            baseFrameBase64 = null,
            baselineSource = BaselineSource.UploadedImage,
            hasImage = true
        )

        assertEquals("Water Bucket", result.title)
        assertEquals(12, result.checkInterval)
    }

    @Test
    fun `intent parsing keeps reference target mode for uploaded image`() {
        val raw = """
            {
              "title": "Find Person",
              "userRequirement": "Alert me when this person appears",
              "originalSceneDescription": "An adult wearing a dark coat and carrying a backpack",
              "monitorMode": "ReferenceTarget",
              "targetTrigger": "OnAppear",
              "baselineSource": "UploadedImage"
            }
        """.trimIndent()

        val result = ModelOutputParser.parseIntentResult(
            rawText = raw,
            userInput = "Alert me when this person appears",
            baseFrameBase64 = "baseline",
            baselineSource = BaselineSource.UploadedImage,
            hasImage = true
        )

        assertEquals(MonitorMode.ReferenceTarget, result.monitorMode)
        assertEquals(TargetTrigger.OnAppear, result.targetTrigger)
        assertEquals(BaselineSource.UploadedImage, result.baselineSource)
    }

    @Test
    fun `monitor decision parsing accepts strict json`() {
        val raw = """
            {
              "status": "WARNING",
              "summary": "Someone is near the doorway",
              "reason": "A person stands in the monitored area",
              "confidence": 0.83
            }
        """.trimIndent()

        val decision = ModelOutputParser.parseMonitorDecision(raw)

        assertEquals(CheckResult.WARNING, decision.result)
        assertEquals("Someone is near the doorway", decision.summary)
        assertEquals(0.83f, decision.confidence ?: 0f, 0.0001f)
    }

    @Test
    fun `monitor decision parsing keeps cyber poetic remark`() {
        val raw = """
            {
              "status": "ALERT",
              "summary": "Target appeared",
              "reason": "The reference target is visible in the frame",
              "confidence": 0.91,
              "remark": "警戒线亮起，沉睡的镜头已拔出光剑。"
            }
        """.trimIndent()

        val decision = ModelOutputParser.parseMonitorDecision(raw)

        assertEquals(CheckResult.ALERT, decision.result)
        assertEquals("Target appeared", decision.summary)
        assertEquals("警戒线亮起，沉睡的镜头已拔出光剑。", decision.remark)
    }

    @Test
    fun `monitor decision parsing accepts remark aliases`() {
        val raw = """
            {
              "status": "NORMAL",
              "summary": "Scene is stable",
              "comment": "命运的像素仍在原地发光。"
            }
        """.trimIndent()

        val decision = ModelOutputParser.parseMonitorDecision(raw)

        assertEquals(CheckResult.NORMAL, decision.result)
        assertEquals("命运的像素仍在原地发光。", decision.remark)
    }

    @Test
    fun `monitor decision parsing treats message as remark when summary is explicit`() {
        val raw = """
            {
              "status": "NORMAL",
              "summary": "Scene is stable",
              "message": "观测线安静燃烧，世界暂未偏航。"
            }
        """.trimIndent()

        val decision = ModelOutputParser.parseMonitorDecision(raw)

        assertEquals(CheckResult.NORMAL, decision.result)
        assertEquals("Scene is stable", decision.summary)
        assertEquals("观测线安静燃烧，世界暂未偏航。", decision.remark)
    }

    @Test
    fun `extract json handles reasoning text around payload`() {
        val raw = """
            The model thought about the scene first.
            {
              "status": "NORMAL",
              "summary": "The cup is still present"
            }
            Additional commentary should be ignored.
        """.trimIndent()

        val decision = ModelOutputParser.parseMonitorDecision(raw)

        assertEquals(CheckResult.NORMAL, decision.result)
        assertEquals("The cup is still present", decision.summary)
    }

    @Test
    fun `monitor decision parsing falls back to unknown for malformed output`() {
        val decision = ModelOutputParser.parseMonitorDecision("I am not sure what happened.")

        assertEquals(CheckResult.UNKNOWN, decision.result)
        assertEquals("", decision.remark)
        assertTrue(decision.reason.contains("JSON"))
    }

    @Test
    fun `monitor decision parsing supports chinese keys`() {
        val raw = """
            {
              "状态": "ALERT",
              "摘要": "Water level is high",
              "原因": "The bucket is nearly full",
              "confidence": 0.91
            }
        """.trimIndent()

        val decision = ModelOutputParser.parseMonitorDecision(raw)

        assertEquals(CheckResult.ALERT, decision.result)
        assertEquals("Water level is high", decision.summary)
    }

    @Test
    fun `monitor decision parsing accepts textual confidence`() {
        val raw = """
            {
              "status": "WARNING",
              "summary": "Someone is near the doorway",
              "reason": "Movement detected",
              "confidence": "高"
            }
        """.trimIndent()

        val decision = ModelOutputParser.parseMonitorDecision(raw)

        assertEquals(CheckResult.WARNING, decision.result)
        assertEquals(0.85f, decision.confidence ?: 0f, 0.0001f)
    }

    @Test
    fun `video plan parsing normalizes segmentation for long tasks`() {
        val raw = """
            {
              "title": "Door Review",
              "userRequirement": "Review whether anyone entered the doorway",
              "sceneContext": "A doorway and a storage shelf are visible",
              "recordingDurationSeconds": 900,
              "samplingFps": 3,
              "segmentDurationSeconds": 120,
              "segmentCount": 1,
              "analysisPrompt": "Return JSON only.",
              "confirmationNotes": "Record long enough to cover a full delivery cycle."
            }
        """.trimIndent()

        val plan = ModelOutputParser.parseVideoTaskPlan(raw, "Review the doorway")

        assertEquals("Door Review", plan.title)
        assertEquals(900, plan.recordingDurationSeconds)
        assertTrue(plan.segmentCount > 1)
    }

    @Test
    fun `video plan parsing keeps split prompts from payload`() {
        val raw = """
            {
              "title": "中文提示词任务",
              "userRequirement": "请观察门口发生了什么",
              "sceneContext": "门口和前台都在画面中",
              "segmentAnalysisPrompt": "请分析当前片段，字段值使用简体中文。",
              "finalSummaryPrompt": "请汇总全部片段，字段值使用简体中文。"
            }
        """.trimIndent()

        val plan = ModelOutputParser.parseVideoTaskPlan(raw, "观察门口")

        assertEquals("请分析当前片段，字段值使用简体中文。", plan.segmentAnalysisPrompt)
        assertEquals("请汇总全部片段，字段值使用简体中文。", plan.finalSummaryPrompt)
    }

    @Test
    fun `video plan parsing supports legacy analysis prompt fallback`() {
        val raw = """
            {
              "title": "Legacy Prompt",
              "userRequirement": "Review the hallway",
              "sceneContext": "A hallway is visible",
              "analysisPrompt": "Return per-segment JSON only."
            }
        """.trimIndent()

        val plan = ModelOutputParser.parseVideoTaskPlan(raw, "Review the hallway")

        assertEquals("Return per-segment JSON only.", plan.segmentAnalysisPrompt)
        assertTrue(plan.finalSummaryPrompt.isNotBlank())
    }

    @Test
    fun `video plan parsing infers short dense strategy from one minute request`() {
        val raw = """
            {
              "title": "Quick Review",
              "userRequirement": "看看这一分钟会发生什么",
              "sceneContext": "A service desk is visible",
              "analysisPrompt": "Return JSON only."
            }
        """.trimIndent()

        val plan = ModelOutputParser.parseVideoTaskPlan(raw, "看看这一分钟会发生什么")

        assertEquals(VideoTaskCategory.ShortBurstDense.value, plan.taskCategory)
        assertEquals(60, plan.recordingDurationSeconds)
        assertEquals(6, plan.segmentDurationSeconds)
        assertEquals(6, plan.captureIntervalSeconds)
        assertEquals(10, plan.segmentCount)
    }

    @Test
    fun `video plan parsing keeps model suggestion but respects explicit duration`() {
        val raw = """
            {
              "taskCategory": "long_horizon_summary",
              "strategyReason": "Model recommends sparse sampling.",
              "title": "Child Review",
              "userRequirement": "看这个小孩这两个小时干了什么",
              "sceneContext": "A living room is visible",
              "recordingDurationSeconds": 30,
              "samplingFps": 3,
              "segmentDurationSeconds": 10,
              "captureIntervalSeconds": 60,
              "analysisPrompt": "Return JSON only."
            }
        """.trimIndent()

        val plan = ModelOutputParser.parseVideoTaskPlan(raw, "看这个小孩这两个小时干了什么")

        assertEquals(VideoTaskCategory.LongHorizonSummary.value, plan.taskCategory)
        assertEquals(7200, plan.recordingDurationSeconds)
        assertEquals(10, plan.segmentDurationSeconds)
        assertEquals(60, plan.captureIntervalSeconds)
        assertEquals("Model recommends sparse sampling.", plan.strategyReason)
    }

    @Test
    fun `video analysis parsing extracts timeline events`() {
        val raw = """
            {
              "summary": "A person entered and left with a package.",
              "conclusion": "Delivery completed successfully.",
              "timelineEvents": [
                {
                  "timestampSeconds": 12,
                  "title": "Person enters",
                  "detail": "A courier enters the frame carrying a box.",
                  "confidence": 0.88
                }
              ]
            }
        """.trimIndent()

        val result = ModelOutputParser.parseVideoAnalysis(raw)

        assertEquals("Delivery completed successfully.", result.conclusion)
        assertEquals(1, result.timelineEvents.size)
        assertEquals(12, result.timelineEvents.first().timestampSeconds)
    }

    @Test
    fun `video analysis parsing accepts textual confidence`() {
        val raw = """
            {
              "summary": "检测完成。",
              "conclusion": "发现一条高置信度事件。",
              "timelineEvents": [
                {
                  "timestampSeconds": 6,
                  "title": "有人经过",
                  "detail": "一名人员快速经过画面中心。",
                  "confidence": "高"
                }
              ]
            }
        """.trimIndent()

        val result = ModelOutputParser.parseVideoAnalysis(raw)

        assertEquals(1, result.timelineEvents.size)
        assertEquals(0.85f, result.timelineEvents.first().confidence ?: 0f, 0.0001f)
    }

    @Test
    fun `video analysis parsing keeps event when confidence is invalid`() {
        val raw = """
            {
              "summary": "检测完成。",
              "conclusion": "事件已保留。",
              "timelineEvents": [
                {
                  "timestampSeconds": 9,
                  "title": "门口有人停留",
                  "detail": "目标在门口短暂停留。",
                  "confidence": "未知"
                }
              ]
            }
        """.trimIndent()

        val result = ModelOutputParser.parseVideoAnalysis(raw)

        assertEquals(1, result.timelineEvents.size)
        assertEquals("门口有人停留", result.timelineEvents.first().title)
        assertEquals(null, result.timelineEvents.first().confidence)
    }

    @Test
    fun `video analysis parsing accepts range timestamps without falling back to raw json`() {
        val raw = """
            {
              "summary": "本段讲解客户细分概念。",
              "conclusion": "完成了客户细分模块的基础铺垫。",
              "timelineEvents": [
                {
                  "timestampSeconds": "11-25",
                  "title": "展示客户细分概念PPT页",
                  "detail": "PPT页面展示客户细分定义。",
                  "confidence": 0.98
                }
              ],
              "structuredNote": {
                "overview": "围绕客户细分展开。",
                "outline": ["客户细分概念", "五类市场分类"]
              },
              "markdownNote": "#客户细分讲座\n-核心主题：客户细分"
            }
        """.trimIndent()

        val result = ModelOutputParser.parseVideoAnalysis(raw)

        assertEquals("本段讲解客户细分概念。", result.summary)
        assertEquals("完成了客户细分模块的基础铺垫。", result.conclusion)
        assertEquals(1, result.timelineEvents.size)
        assertEquals(11, result.timelineEvents.first().timestampSeconds)
        assertEquals("#客户细分讲座\n-核心主题：客户细分", result.markdownNote)
        assertTrue(result.structuredNoteJson.contains("客户细分概念"))
        assertTrue(!result.evidenceJson.contains("\"summary\""))
        assertTrue(!result.evidenceJson.contains("\"conclusion\""))
    }

    @Test
    fun `video analysis parsing returns empty user fields for malformed json`() {
        val raw = """{ "summary": "截断的模型输出","""

        val result = ModelOutputParser.parseVideoAnalysis(raw)

        assertEquals("", result.summary)
        assertEquals("", result.conclusion)
        assertEquals("", result.structuredNoteJson)
        assertEquals("", result.markdownNote)
        assertEquals(raw, result.rawResponse)
    }
    @Test
    fun `video segment fact packet keeps facts in evidence without final report fields`() {
        val raw = """
            {
              "segmentTopic": "Customer segmentation intro",
              "audioFacts": [
                {
                  "timestampSeconds": 18,
                  "text": "The speaker explains that customer segments describe groups served by a business.",
                  "speakerHint": "lecturer",
                  "confidence": 0.86,
                  "uncertain": false
                }
              ],
              "speechKeyPoints": ["Customer segments are a core business model block."],
              "visualFacts": [
                {
                  "timestampSeconds": 22,
                  "content": "Slide shows five market types.",
                  "confidence": 0.9
                }
              ],
              "screenOrBoardFacts": ["PPT lists mass, niche, segmented, diversified and multi-sided markets."],
              "demonstrationFacts": [],
              "timelineFacts": [
                {
                  "timestampSeconds": 22,
                  "title": "Slide with market types",
                  "detail": "The segment shows a classification slide.",
                  "confidence": 0.9
                }
              ],
              "uncertainties": [],
              "quality": {
                "audioPresent": true,
                "speechClarity": "medium",
                "visualClarity": "high",
                "contentDensity": "high"
              }
            }
        """.trimIndent()

        val result = ModelOutputParser.parseVideoAnalysis(raw)

        assertEquals("Customer segmentation intro", result.summary)
        assertEquals("", result.conclusion)
        assertEquals(1, result.timelineEvents.size)
        assertEquals(22, result.timelineEvents.first().timestampSeconds)
        assertTrue(result.evidenceJson.contains("\"schemaVersion\":\"video_segment_fact_packet_v1\""))
        assertTrue(result.evidenceJson.contains("\"audioFacts\""))
        assertTrue(!result.evidenceJson.contains("\"markdownNote\""))
        assertTrue(!result.evidenceJson.contains("\"structuredNote\""))
    }

    @Test
    fun `video final report packet becomes final user fields`() {
        val raw = """
            {
              "reportType": "learning_notes",
              "title": "Business model lecture",
              "briefSummary": "The lecture explains customer segmentation in the business model canvas.",
              "keyConclusions": [
                "Customer segmentation is the foundation for choosing markets.",
                "The speaker used education brands as examples."
              ],
              "structuredNotes": {
                "overview": "A lecture about market segmentation and education industry cases.",
                "sections": [
                  {
                    "title": "Market types",
                    "items": ["Mass market", "Niche market", "Multi-sided market"]
                  }
                ]
              },
              "outline": ["Concept intro", "Five market types", "Education examples"],
              "knowledgePoints": ["Business model canvas", "Customer segmentation"],
              "reviewOrActionItems": ["Review the five market categories."],
              "evidenceHighlights": ["PPT slide lists five market types."],
              "coverageNotice": "The recording stopped early but contains enough lecture evidence.",
              "timeline": [
                {
                  "timestampSeconds": 120,
                  "title": "Education examples",
                  "detail": "The speaker compares several education brands.",
                  "confidence": 0.88
                }
              ]
            }
        """.trimIndent()

        val result = ModelOutputParser.parseVideoAnalysis(raw)

        assertEquals("The lecture explains customer segmentation in the business model canvas.", result.summary)
        assertTrue(result.conclusion.contains("Customer segmentation is the foundation"))
        assertEquals(1, result.timelineEvents.size)
        assertEquals(120, result.timelineEvents.first().timestampSeconds)
        assertTrue(result.structuredNoteJson.contains("\"schemaVersion\":\"video_final_report_v1\""))
        assertTrue(result.markdownNote.contains("# Business model lecture"))
        assertTrue(result.markdownNote.contains("## Key Conclusions"))
    }

    @Test
    fun `scene observation final report does not force knowledge or action sections`() {
        val raw = """
            {
              "reportType": "scene_observation",
              "title": "Workspace observation",
              "briefSummary": "A short video records a person adjusting posture in a shared workspace.",
              "keyConclusions": ["The activity is ordinary and no abnormal event is visible."],
              "structuredNotes": {
                "overview": "A general scene observation.",
                "sections": []
              },
              "outline": ["01 场景概览", "02人物动作复盘"],
              "knowledgePoints": ["本次记录未采集到清晰有效语音"],
              "reviewOrActionItems": ["01", "02", "若需完整对话内容，可更换高灵敏度收音设备补充记录"],
              "evidenceHighlights": ["The camera shows desks, ceiling pipes, and seated people."],
              "coverageNotice": "Only light ambient noise is audible; no clear speech is captured.",
              "timeline": []
            }
        """.trimIndent()

        val result = ModelOutputParser.parseVideoAnalysis(raw)

        assertTrue(result.markdownNote.contains("## Event Flow"))
        assertTrue(!result.markdownNote.contains("## Knowledge Points"))
        assertTrue(!result.markdownNote.contains("## Follow-ups"))
        assertTrue(!result.markdownNote.contains("01"))
        assertTrue(result.markdownNote.contains("人物动作复盘"))
    }
}
