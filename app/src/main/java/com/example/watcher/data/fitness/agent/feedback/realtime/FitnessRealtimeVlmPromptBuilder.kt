package com.example.watcher.data.fitness.agent.feedback.realtime

import com.google.gson.Gson

internal object FitnessRealtimeVlmPromptBuilder {
    private val gson = Gson()

    fun build(context: FitnessVlmPromptContext): String {
        val requestContext = mapOf(
            "exercise" to mapOf(
                "name" to context.exercise.exerciseName,
                "movement" to context.exercise.movementPattern
            ),
            "recent_facts" to context.rollingFacts.map { fact ->
                mapOf(
                    "id" to fact.factId,
                    "text" to fact.observation,
                    "visibility" to fact.observability.name.lowercase(),
                    "confidence" to fact.confidence
                )
            },
            "probes" to context.activeProbes.map { probe ->
                mapOf(
                    "id" to probe.probeId,
                    "question" to probe.question
                )
            }
        )

        return """
            你是力量训练视觉观察器，只分析当前图片。
            - 输出1至4条当前可见事实。办公室或没有器械也要描述人体姿态，不能仅因器械缺失判定不可观察。
            - 关键身体区域不在画面或被遮挡才用not_observable；可见但证据有限用partial或insufficient_evidence。
            - 单帧不判断持续、节奏、轨迹或上一下；历史事实只帮助理解，不能替代当前证据。
            - 回答输入probes时，result只能是supported、refuted、not_observable；前两者必须引用当前fact，后者fact为null。
            - 可提出1个短期可证伪probe，只能基于当前clear或partial fact；否则probe为null。
            - 有具体可执行建议就输出coach，客户端会自行验证；没有则为null。
            - 不计数，不判断组次，不诊断伤病，不输出无视觉依据的通用建议。

            输入：
            ${gson.toJson(requestContext)}

            只输出JSON：
            {
              "facts":[{"ref":"C1","text":"","visibility":"clear|partial|not_observable|insufficient_evidence","confidence":0.0}],
              "probe_results":[{"id":"Q1","result":"supported|refuted|not_observable","fact":"C1","confidence":0.0}],
              "probe":{"question":"","source":"C1","finding":""},
              "coach":{"text":"","facts":["C1"],"probe":null,"confidence":0.0}
            }
            没有probe或coach时填null；没有待回答probe时probe_results填[]。
        """.trimIndent()
    }
}
