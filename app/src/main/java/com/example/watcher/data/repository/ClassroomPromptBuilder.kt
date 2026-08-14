package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomNoteFollowupSourceRef
import com.example.watcher.data.model.RecordingScenario
import com.example.watcher.data.model.ClassroomInlineQuestionType
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.model.VideoSpeechTranscriptEntity

internal object ClassroomPromptBuilder {
    fun audioOutlineBasePrompt(): String = """
你是课堂记录专用的听课助教。你的任务不是写通用视频报告，而是从完整课堂音频中恢复知识讲解主线。

请重点抽取：
1. 课程主题、授课对象、前后课程承接关系。
2. 老师的讲解顺序、概念定义、规则、步骤、例子、代码或演示说明。
3. 老师强调、容易混淆点、复习要求。
4. 学生提问、互动回答、课堂练习。
5. 听不清或证据不足的片段，必须显式标注，不要编造。

输出 Markdown，使用简体中文，按时间顺序组织，并保留可用于后续核对的时间线线索。
""".trimIndent()

    fun audioOutlinePrompt(
        task: VideoProcessTaskDraft,
        durationSeconds: Int
    ): String {
        val scenario = RecordingScenario.fromValue(task.recordingScenario)
        return buildString {
            appendLine(audioOutlineBasePrompt())
            appendLine()
            appendLine("## 任务上下文")
            appendLine("- 课程名/任务标题：${task.title}")
            appendLine("- 用户目标：${task.userRequirement}")
            appendLine("- 场景参考：${task.sceneContext}")
            appendLine("- 录制场景：${scenario.label}")
            appendLine("- 完整音频时长：${durationSeconds} 秒")
            appendLine()
            appendLine("## 输出要求")
            appendLine("- 标题使用 `# 音频课堂大纲`。")
            appendLine("- 必须包含：课程概览、时间线、概念/定义、例子/演示、老师强调、学生问答、听不清/证据不足。")
            appendLine("- 所有不确定内容写入“听不清/证据不足”，不要推断成事实。")
        }
    }

    fun segmentFactBasePrompt(): String = """
你是课堂记录专用的分片事实提取器。你的任务不是复现电影分镜，也不是写泛色视频总结，而是把本段课堂中可验证的知识事实整理成结构化事实包。

优先级：
1. 语音中的概念、定义、规则、步骤、例题、老师强调。
2. 屏幕、PPT、板书、代码、IDE、演示操作中承载知识的信息。
3. 学生提问和老师回答。
4. 证据不足时明确写 uncertainties，不要补全。

请只输出 JSON，不要输出 Markdown。
""".trimIndent()

    fun segmentFactPrompt(
        task: VideoProcessTaskDraft,
        segmentNumber: Int,
        segmentCount: Int,
        startOffsetSeconds: Int,
        durationSeconds: Int,
        inputMode: String
    ): String {
        return buildString {
            appendLine(segmentFactBasePrompt())
            appendLine()
            appendLine("## 任务上下文")
            appendLine("- 课程名/任务标题：${task.title}")
            appendLine("- 用户目标：${task.userRequirement}")
            appendLine("- 场景参考：${task.sceneContext}")
            appendLine("- 当前分片：$segmentNumber/$segmentCount")
            appendLine("- 分片时间范围：${startOffsetSeconds}-${startOffsetSeconds + durationSeconds} 秒")
            appendLine("- 输入模式：$inputMode")
            appendLine()
            appendLine("## 输出 JSON schema")
            appendLine(
                """
{
  "segmentId": "segment_$segmentNumber",
  "segmentIndex": $segmentNumber,
  "timeRange": {"startSeconds": $startOffsetSeconds, "endSeconds": ${startOffsetSeconds + durationSeconds}},
  "inputMode": "$inputMode",
  "segmentTopic": "",
  "speechKeyPoints": [{"noteBlockId": "seg${segmentNumber}_speech_1", "text": "", "evidenceId": "seg${segmentNumber}_audio_1"}],
  "concepts": [{"name": "", "definition": "", "explanation": "", "evidenceId": ""}],
  "examplesAndDemos": [{"title": "", "steps": [], "evidenceId": ""}],
  "boardOrScreenEvidence": [{"evidenceId": "seg${segmentNumber}_visual_1", "source": "ppt|board|code|screen|unknown", "timeRange": "", "text": "", "description": ""}],
  "teacherEmphasis": [{"text": "", "evidenceId": ""}],
  "studentQuestions": [{"question": "", "answer": "", "evidenceId": ""}],
  "uncertainties": [{"timeRange": "", "reason": "", "impact": ""}],
  "coverageNotice": ""
}
""".trimIndent()
            )
        }
    }

    fun visualEvidenceBasePrompt(): String = """
你是课堂记录专用的视觉证据补充器。你的任务只是在课堂分片事实不足时补充可核验的视觉信息。

只关注：PPT、板书、代码、IDE、屏幕文字、演示步骤、图表和可见操作。
不要推断语音内容，不要补写老师没有明确展示的信息。
证据不足时写入 uncertainties。
只输出 JSON，不要输出 Markdown。
""".trimIndent()

    fun visualEvidencePrompt(
        task: VideoProcessTaskDraft,
        segmentNumber: Int,
        segmentCount: Int,
        timeRange: String
    ): String {
        return buildString {
            appendLine(visualEvidenceBasePrompt())
            appendLine()
            appendLine("## 任务上下文")
            appendLine("- 课程名/任务标题：${task.title}")
            appendLine("- 用户目标：${task.userRequirement}")
            appendLine("- 场景参考：${task.sceneContext}")
            appendLine("- 当前分片：$segmentNumber/$segmentCount")
            appendLine("- 分片时间范围：$timeRange")
            appendLine()
            appendLine("## 输出 JSON schema")
            appendLine(
                """
{
  "segmentIndex": $segmentNumber,
  "visualEvidence": [
    {
      "evidenceId": "seg${segmentNumber}_visual_supplement_1",
      "source": "ppt|board|code|screen|demo|unknown",
      "timeRange": "",
      "text": "",
      "description": "",
      "confidence": 0.0
    }
  ],
  "uncertainties": [{"timeRange": "", "reason": "", "impact": ""}],
  "coverageNotice": ""
}
""".trimIndent()
            )
        }
    }

    fun noteSynthesisBasePrompt(): String = """
你是课堂笔记合成器。请基于完整音频大纲和各分片事实包，生成“双层课堂笔记”：

第一层：给学习者阅读的完整课堂学习笔记，结构清晰、可复习、可直接回看。
第二层：给调试和后续追问使用的证据索引，包含稳定 noteBlockId、evidenceId、来源分片和时间线。

不要使用通用视频分析报告结构。
不要编造音频或视觉证据。证据不足时写 coverageNotice。
只输出 JSON，不要输出 Markdown 代码块。
面向用户的 markdownNote 必须使用简体中文。
""".trimIndent()

    fun noteSynthesisPrompt(
        task: VideoProcessTaskDraft,
        audioOutlineMarkdown: String,
        segmentFacts: List<SegmentExecutionResult>,
        realtimeTranscript: String = "",
        coverageNotices: List<String>,
        visualEvidence: List<ClassroomVisualEvidenceResult> = emptyList()
    ): String {
        val factsPayload = segmentFacts
            .sortedBy { it.segment.segmentIndex }
            .joinToString(separator = "\n\n") { result ->
                """
### segment_${result.segment.segmentIndex}
- status: ${result.segment.status}
- inputMode: ${result.analysisInputMode}
- hasAudio: ${result.hasAudio}
- coverageLimitation: ${result.coverageLimitation.orEmpty()}
- summary: ${result.analysisResult.summary}
- rawFacts:
${result.analysisResult.rawResponse.ifBlank { result.analysisResult.evidenceJson }}
""".trimIndent()
            }
        val visualPayload = visualEvidence
            .sortedBy { it.segmentIndex }
            .joinToString(separator = "\n\n") { result ->
                """
### segment_${result.segmentIndex}_visual_supplement
- parseStatus: ${result.parseStatus}
- summary: ${result.summary}
- rawVisualEvidence:
${result.rawJson}
""".trimIndent()
            }
        return buildString {
            appendLine(noteSynthesisBasePrompt())
            appendLine()
            appendLine("## 任务上下文")
            appendLine("- 课程名/任务标题：${task.title}")
            appendLine("- 用户目标：${task.userRequirement}")
            appendLine("- 场景参考：${task.sceneContext}")
            appendLine("- 计划时长：${task.plannedDurationSeconds} 秒")
            appendLine("- 分片时长：${task.plannedSegmentDurationSeconds} 秒")
            appendLine()
            appendLine("## AUDIO_OUTLINE")
            appendLine(audioOutlineMarkdown.ifBlank { "无完整音频大纲；请仅依据分片事实生成，并在 coverageNotice 中说明。" })
            appendLine()
            if (realtimeTranscript.isNotBlank()) {
                appendLine("## REALTIME_TRANSCRIPT_EVIDENCE")
                appendLine("以下为录制中实时 ASR 稳定转写，可用于补充讲解顺序、老师原话和时间定位；若与更高置信证据冲突，请以音频大纲和分片事实为准。")
                appendLine(realtimeTranscript)
                appendLine()
            }
            appendLine("## SEGMENT_CLASSROOM_FACTS")
            appendLine(factsPayload.ifBlank { "无可用分片事实。" })
            if (visualPayload.isNotBlank()) {
                appendLine()
                appendLine("## SUPPLEMENTAL_VISUAL_EVIDENCE")
                appendLine(visualPayload)
            }
            if (coverageNotices.isNotEmpty()) {
                appendLine()
                appendLine("## COVERAGE_NOTICES")
                coverageNotices.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("## 输出 JSON schema")
            appendLine(
                """
{
  "courseOverview": {"title": "", "summary": "", "durationSeconds": ${task.plannedDurationSeconds}, "audience": "", "prerequisites": []},
  "learningObjectives": [{"noteBlockId": "obj_1", "text": "", "evidenceIds": []}],
  "orderedOutline": [{"noteBlockId": "outline_1", "timeRange": "", "title": "", "keyPoints": [], "evidenceIds": []}],
  "definitions": [{"noteBlockId": "def_1", "name": "", "definition": "", "explanation": "", "evidenceIds": []}],
  "examplesAndDemos": [{"noteBlockId": "demo_1", "title": "", "steps": [], "takeaway": "", "evidenceIds": []}],
  "commonMisunderstandings": [{"noteBlockId": "mis_1", "text": "", "correction": "", "evidenceIds": []}],
  "reviewChecklist": [{"noteBlockId": "review_1", "text": "", "evidenceIds": []}],
  "selfTestQuestions": [{"noteBlockId": "quiz_1", "question": "", "answer": "", "evidenceIds": []}],
  "askableIndex": [{"noteBlockId": "outline_1", "topic": "", "evidenceIds": []}],
  "evidenceRefs": [{"evidenceId": "seg1_audio_1", "segmentIndex": 1, "timeRange": "", "source": "audio|ppt|board|code|screen|unknown", "summary": ""}],
  "coverageNotice": "",
  "summary": "",
  "markdownNote": "# 课堂笔记\n..."
}
""".trimIndent()
            )
        }
    }

    fun realtimeInsightBasePrompt(): String = """
你是课堂记录的实时助教。请只基于最近一段稳定转写，提炼 3 条以内正在讲解的课堂要点。

要求：简短、确定、不编造；不要输出完整笔记；每条不超过 32 个中文字符。
实时 ASR 可能存在错字、漏字、同音词误识别，请结合上下文修正理解，不要逐字照搬明显错误。
""".trimIndent()

    fun realtimeInsightPrompt(
        task: VideoProcessTaskDraft,
        transcriptWindow: String
    ): String = buildString {
        appendLine(realtimeInsightBasePrompt())
        appendLine("课程：${task.title.ifBlank { task.userRequirement }}")
        appendLine()
        appendLine("最近稳定转写：")
        appendLine(transcriptWindow)
        appendLine()
        appendLine("请用 JSON 输出：{\"insights\":[\"要点1\",\"要点2\"]}")
    }

    fun knowledgeTreeBasePrompt(): String = """
你是课堂记录的知识结构维护器。你需要把课堂稳定 ASR 增量整理成一棵纵向、由浅入深的学习知识树。

要求：
- 只输出 JSON，不输出 Markdown 或解释。
- 知识树最多 4 层，每一层节点都必须是知识点或学习要点：课程阶段/大知识模块、模块内主题、主题下的核心知识点、核心知识点下的细粒度知识点或子要点。
- 必须输出真正的嵌套树：子节点必须放在父节点的 children 数组里；不要只写 parentId 后把子节点平铺在 root.nodes 同级。
- 尽量保留已有节点 id 和顺序，不要每轮重排整棵树。
- 只有课堂明显进入新知识模块时，才新增一级模块。
- 如果老师仍在同一知识模块内深入讲解不同小节，例如十进制、二进制、十六进制、八进制、进制转换，应新增或更新该模块 children 中的知识点节点，而不是把所有内容塞进一个 active 节点。
- 第 4 层仍然只能是可命名、可追问的细粒度知识点，例如“二进制的进位规则”“补码的符号位含义”；不要把“步骤 1”“老师强调”“常见误解”“例题过程”当成树节点。
- 整棵树最多只能有 1 个 status=active 的节点；如果出现新的 active，旧 active 必须改为 completed 或 draft。
- 不要把课程名、教材名、章节名当作唯一知识节点；节点标题必须是具体知识点、学习要点或课堂正在解释的问题。
- 不要生成只有标题、没有学习内容的空节点；每个节点至少填写 oneLineTakeaway，能确定时再补 teacherEmphasis、examples、misunderstandings。
- 单个节点内容必须克制：teacherEmphasis 最多 3 条，examples 最多 3 条，misunderstandings 最多 2 条；如果超过这个量，说明需要拆成 children。
- 展开内容要像学习笔记，优先填 oneLineTakeaway、teacherEmphasis、examples、misunderstandings；解释、规则、例子、步骤和易错点必须写在这些详情字段里，不要拆成树节点。
- 只有当内容可以被命名为独立知识点、学习要点或可追问概念时，才创建 child 节点。
- 如果 ASR 片段只是在寒暄、点名、重复上一句或过渡，优先更新已有节点，不要新建模块。
- ASR 可能有错字、漏字、断句错误和同音词误识别，请结合上下文修正理解，不要逐字照搬明显错误。
- 输出 JSON schema 只是结构示例，不要复制示例里的课程主题、标题或 id 到真实课堂。
""".trimIndent()

    fun knowledgeTreePrompt(
        task: VideoProcessTaskDraft,
        currentTreeJson: String,
        transcriptWindow: ClassroomKnowledgeTranscriptWindow,
        realtimeInsights: List<String>,
        activePathSummary: String = ""
    ): String = buildString {
        appendLine(knowledgeTreeBasePrompt())
        appendLine()
        appendLine("## 任务上下文")
        appendLine("- 课程：${task.title.ifBlank { task.userRequirement }}")
        appendLine("- 输出语言：简体中文")
        appendLine("- 时间字段单位：毫秒")
        appendLine("- 本轮 ASR 时间范围：${transcriptWindow.describeRange()}")
        appendLine("- 本轮 ASR 行数：${transcriptWindow.lines.size}")
        appendLine()
        appendLine("## 当前 active 路径摘要")
        appendLine(activePathSummary.ifBlank { "当前还没有稳定 active 路径，请根据 ASR 建立第一个知识模块。" })
        appendLine()
        appendLine("## 当前已有知识树 JSON")
        appendLine(currentTreeJson.ifBlank { "{}" })
        appendLine()
        appendLine("## 近期稳定 ASR 上下文（每行格式：[开始-结束] 文本）")
        appendLine(transcriptWindow.renderForPrompt())
        if (realtimeInsights.isNotEmpty()) {
            appendLine()
            appendLine("## 当前滚动课堂要点（仅作参考，最终结构必须以 ASR 为准）")
            realtimeInsights.take(6).forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## 结构化规则")
        appendLine("- root.nodes 只放一级知识模块。")
        appendLine("- 二级/三级/四级知识点必须嵌入父节点 children，不要平铺。")
        appendLine("- 第 4 层仍是细粒度知识点或子要点，不是规则解释、例题步骤、老师强调或易错点容器。")
        appendLine("- 当一个节点开始堆积多个可命名的并列知识点时，拆成 children；解释、规则、例子、步骤、易错点留在详情字段。")
        appendLine("- 同一父主题下的新并列概念应作为 sibling，不要挂到前一个 sibling 的 children。")
        appendLine("- startMs/endMs 必须来自支撑该节点的 ASR 行时间范围，不要臆造，不要整棵树填 0。")
        appendLine("- 叶子节点取覆盖该知识点的字幕范围；父节点取自身内容与 children 的最小 startMs、最大 endMs。")
        appendLine("- 不要提前创建老师只是预告、还没有实际讲解的未来节点。draft 只表示当前已经讲到但尚未完全展开的知识点。")
        appendLine("- changedNodeIds 只包含本轮新增或实质更新的节点 id。")
        appendLine()
        appendLine("## 更新决策清单")
        appendLine("- 如果新 ASR 是解释、规则、例题步骤、易错点：更新当前节点的 oneLineTakeaway、teacherEmphasis、examples 或 misunderstandings。")
        appendLine("- 如果新 ASR 是同一父级下的并列知识点：新增 sibling。")
        appendLine("- 如果新 ASR 是当前知识点下可命名、可追问的细粒度知识点：新增 child。")
        appendLine("- 如果课堂明显进入新知识模块：新增一级模块。")
        appendLine("- 如果新 ASR 是寒暄、重复、点名或过渡语：不要新增节点。")
        appendLine("- active 只能给当前正在讲解的最深知识点；它的祖先不要同时标记 active。")
        appendLine()
        appendLine("## 输出 JSON schema")
        appendLine(
            """
{
  "tree": {
    "rootTitle": "课程知识树",
    "nodes": [
      {
        "id": "module_1",
        "parentId": null,
        "title": "数字与码制",
        "oneLineTakeaway": "学习数字系统和编码方式如何表达信息。",
        "teacherEmphasis": ["区分数值本身和编码表示"],
        "examples": [],
        "misunderstandings": [],
        "startMs": 0,
        "endMs": 60000,
        "status": "completed",
        "updatedAtMs": 0,
        "children": [
          {
            "id": "module_1_topic_1",
            "parentId": "module_1",
            "title": "数制表示",
            "oneLineTakeaway": "不同数制用不同基数和权值表示数量。",
            "teacherEmphasis": ["同一数值可以有多种进制写法"],
            "examples": [],
            "misunderstandings": [],
            "startMs": 60000,
            "endMs": 120000,
            "status": "completed",
            "updatedAtMs": 0,
            "children": [
              {
                "id": "module_1_topic_1_concept_1",
                "parentId": "module_1_topic_1",
                "title": "二进制数表示",
                "oneLineTakeaway": "二进制只使用 0 和 1，并按逢二进一计数。",
                "teacherEmphasis": ["位权是 2 的各次幂"],
                "examples": ["1011₂ 可按位权展开理解"],
                "misunderstandings": [],
                "startMs": 120000,
                "endMs": 180000,
                "status": "completed",
                "updatedAtMs": 0,
                "children": [
                  {
                    "id": "module_1_topic_1_concept_1_subpoint_1",
                    "parentId": "module_1_topic_1_concept_1",
                    "title": "二进制的进位规则",
                    "oneLineTakeaway": "当前位满 2 时向高一位进 1。",
                    "teacherEmphasis": ["规则说明写在详情里，不把“步骤 1”作为节点"],
                    "examples": ["1 + 1 = 10₂"],
                    "misunderstandings": ["不要按十进制的逢十进一理解二进制"],
                    "startMs": 180000,
                    "endMs": 210000,
                    "status": "active",
                    "updatedAtMs": 0,
                    "children": []
                  },
                  {
                    "id": "module_1_topic_1_concept_1_subpoint_2",
                    "parentId": "module_1_topic_1_concept_1",
                    "title": "二进制的位权含义",
                    "oneLineTakeaway": "每一位代表 2 的不同次幂。",
                    "teacherEmphasis": [],
                    "examples": ["101₂ = 1×2² + 0×2¹ + 1×2⁰"],
                    "misunderstandings": [],
                    "startMs": 210000,
                    "endMs": 240000,
                    "status": "draft",
                    "updatedAtMs": 0,
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
  "changedNodeIds": ["module_1_topic_1_concept_1_subpoint_1"]
}
""".trimIndent()
        )
    }

    fun inlineQuestionBasePrompt(): String = """
你是课堂实时助教。你需要根据用户在实时字幕中点选的核心问题字幕、全量已产出的 ASR 上下文、实时课堂要点，以及可选的核心字幕附近画面，快速回答学生正在听课时的疑惑。

要求：
- 只输出 JSON：{"answer":"..."}。
- answer 使用简体中文，控制在 120-180 字。
- 直接解释当前课堂语境，不写长篇笔记。
- 回答必须围绕第 1 条 core 字幕；第 2-3 条 important 字幕用于还原老师正在讲的对象；context 字幕和全量 ASR 只用于纠错、补充背景和避免误解。
- 可以使用通用知识辅助解释，但不要声称已经联网搜索。
- ASR 是实时识别结果，可能存在错字、漏字、断句错误和同音词误识别；请结合全量 ASR 上下文纠错理解，不要逐字照搬明显错误。
- 如果提供了图片，必须先判断画面里是否有板书、PPT、代码、公式、图表、演示界面或屏幕文字。图片可读且相关时，answer 必须明确写出一个画面证据，例如“画面里正在演示.../板书上写着.../代码区显示...”，再结合 core 字幕解释。
- 如果图片可读且与问题相关，不允许只根据 ASR 和通用知识回答；视觉证据要优先帮助解释老师当前讲到的对象、规则、例子或代码状态。
- 如果图片看不清、与字幕无关或与 ASR 冲突，不要编造图片内容；请简短说明判断依据，并以 ASR 上下文为主。
- 按问题类型调整答案：解释这段=先讲概念再落到课堂对象；举个例子=给一个贴近当前课堂材料的简短例子；为什么这样=说明规则来源、因果关系或执行机制。
- 禁止复述用户问题、泛泛鼓励、给学习建议凑字数；每句话都要推进理解。
- 不确定时说明“不确定”，不要编造课堂中没有出现的信息。
""".trimIndent()

    fun inlineQuestionPrompt(
        task: VideoProcessTaskDraft,
        questionType: ClassroomInlineQuestionType,
        selectedTranscripts: List<VideoSpeechTranscriptEntity>,
        allContextTranscripts: List<VideoSpeechTranscriptEntity>,
        realtimeInsights: List<String>,
        contextStartMs: Long,
        contextEndMs: Long,
        frameEvidence: ClassroomInlineFrameEvidence?
    ): String = buildString {
        appendLine(inlineQuestionBasePrompt())
        appendLine()
        appendLine("## 任务上下文")
        appendLine("- 课程：${task.title.ifBlank { task.userRequirement }}")
        appendLine("- 问题类型：${questionType.label}")
        appendLine("- externalKnowledgeAllowed: true")
        appendLine("- ASR 上下文范围：${formatMs(contextStartMs)}-${formatMs(contextEndMs)}")
        appendLine("- ASR 上下文策略：使用当前任务已产出的全部稳定 ASR，而不是只截取局部窗口。")
        appendLine("- ASR 可靠性提示：可能有错字、漏字、断句错误、同音词误识别，请根据上下文修正。")
        appendLine("- 回答聚焦策略：core 字幕是用户真正卡住的问题，important 字幕辅助定位课堂对象，全量 ASR 只做纠错和补背景。")
        appendLine("- visualFrameStatus: ${frameEvidence?.status ?: "unavailable"}")
        frameEvidence?.let { frame ->
            appendLine("- visualFrameTime: ${formatMs(frame.frameTimestampMs)}")
            appendLine("- visualFrameSource: ${frame.source}")
            appendLine("- visualFrameSize: ${frame.width}x${frame.height}")
            appendLine("- visualFrameInstruction: 先识别图片中的板书/PPT/代码/公式/屏幕文字；如果图片与 core 字幕相关，回答中必须融合一个具体可见线索。")
        }
        appendLine()
        appendLine("## 用户点选字幕（按权重排序）")
        selectedTranscripts.forEachIndexed { index, transcript ->
            val weight = ClassroomTranscriptSelectionPolicy.weightForOrder(index + 1).value
            appendLine("- weight=$weight time=${formatMs(transcript.globalStartMs)}-${formatMs(transcript.globalEndMs)} text=${transcript.text}")
        }
        appendLine()
        appendLine("## 全量已产出 ASR 上下文")
        allContextTranscripts.forEach { transcript ->
            appendLine("- ${formatMs(transcript.globalStartMs)}-${formatMs(transcript.globalEndMs)} ${transcript.text}")
        }
        if (realtimeInsights.isNotEmpty()) {
            appendLine()
            appendLine("## 当前课堂要点")
            realtimeInsights.take(6).forEach { appendLine("- $it") }
        }
    }

    fun noteFollowupPrompt(
        question: String,
        task: VideoProcessTaskDraft,
        context: ClassroomNoteFollowupContext
    ): String = buildString {
        appendLine("你是课后笔记追问助教。请优先基于本节课已经产出的笔记、字幕、知识树和证据回答用户自由提问。")
        appendLine()
        appendLine("要求：")
        appendLine("- 只输出 JSON，不要输出 Markdown 代码块。")
        appendLine("- JSON 结构固定为 {\"answer\":\"...\",\"courseEvidence\":[{\"type\":\"transcript|timeline|segment|knowledge|note\",\"text\":\"...\",\"startMs\":0,\"endMs\":0,\"refId\":\"...\"}],\"supplement\":\"...\"}。")
        appendLine("- answer 使用简体中文，直接回答用户问题。")
        appendLine("- courseEvidence 只能写本节课材料中实际出现的依据，并尽量保留时间点。")
        appendLine("- 如果本节课材料中未找到直接依据，courseEvidence 填空数组，并在 answer 中写“本节课材料中未找到直接依据”。")
        appendLine("- 可以补充通用知识，但 supplement 必须以“补充解释：”开头；没有补充就留空字符串。")
        appendLine("- 不要把通用知识伪装成本节课内容。")
        appendLine()
        appendLine("## 课程上下文")
        appendLine("- 课程：${task.title.ifBlank { task.userRequirement }}")
        appendLine("- 用户目标：${task.userRequirement}")
        appendLine("- 当前材料阶段：${context.stage.label} (${context.stage.value})")
        appendLine()
        appendLine("## 用户问题")
        appendLine(question)
        appendLine()
        appendLine("## 课堂笔记/草稿")
        appendLine(context.noteText.ifBlank { "暂无课堂笔记或临时草稿。" })
        appendLine()
        appendLine("## 课堂总结")
        appendLine(context.summaryText.ifBlank { "暂无课堂总结。" })
        appendLine()
        appendLine("## 知识树")
        appendLine(context.knowledgeTreeText.ifBlank { "暂无知识树。" })
        appendLine()
        appendLine("## 可引用依据")
        if (context.evidenceRefs.isEmpty()) {
            appendLine("本节课材料中未找到直接依据。")
        } else {
            context.evidenceRefs.forEach { ref ->
                appendLine("- ${formatFollowupRef(ref)}")
            }
        }
        if (context.conversationTurns.isNotEmpty()) {
            appendLine()
            appendLine("## 最近追问上下文")
            context.conversationTurns.forEach { turn ->
                appendLine("- Q#${turn.id}: ${turn.question}")
                appendLine("  A#${turn.id}: ${turn.answer}")
            }
        }
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun formatFollowupRef(ref: ClassroomNoteFollowupSourceRef): String {
        val time = when {
            ref.startMs != null && ref.endMs != null ->
                "[${formatFollowupMs(ref.startMs)}-${formatFollowupMs(ref.endMs)}] "
            ref.startMs != null ->
                "[${formatFollowupMs(ref.startMs)}] "
            else -> ""
        }
        val id = ref.refId.takeIf(String::isNotBlank)?.let { " id=$it" }.orEmpty()
        return "$time${ref.text}$id"
    }

    private fun formatFollowupMs(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val totalSeconds = safeMs / 1_000L
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val centiseconds = (safeMs % 1_000L) / 10L
        return "%02d:%02d.%02d".format(minutes, seconds, centiseconds)
    }
}
