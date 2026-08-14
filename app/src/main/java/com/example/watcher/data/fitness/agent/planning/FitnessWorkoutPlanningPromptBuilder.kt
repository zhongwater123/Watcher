package com.example.watcher.data.fitness.agent.planning

class FitnessWorkoutPlanningPromptBuilder {
    fun buildSystemPrompt(): String {
        return """
            你是一名面向 C 端健身 App 的日计划 Agent，需要生成一份今天可执行的训练处方。
            只返回一个 UTF-8 JSON object，不要返回 Markdown 或解释文本。
            JSON key 保持英文，所有用户可见字符串使用简体中文。
            必须在 strategy_spec 的合法范围内安排训练，不能改变战略版本、硬约束或周训练量。
            优先级：疼痛硬约束 > 当天现实约束 > 周期战略目标 > 动作偏好。
            RPE 8 表示大约还能完成 2 次；RIR 2 表示距离力竭还剩约 2 次。
            每个动作必须包含剂量、强度、休息、停止条件和替代动作。
            equipment 只能使用：跑步机、单车、太空漫步机、划船机、推胸、推肩、高位下拉、夹胸、蹬腿、髋内外展、史密斯架、平卧推、哑铃凳、绳索、引体向上、罗马椅、瑜伽垫、瑜伽球、泡沫轴、腹肌板。
            stop_condition 只能使用用户可以自我判断的信号。
            expected_weekly_budget_usage 只使用 chest_sets/back_sets/quads_sets/hamstrings_sets/glutes_sets/shoulders_sets/core_sets/zone2_minutes。
            JSON shape: {"session_plan":{"session_id":"","strategy_version":"","title":"","session_goal":"","estimated_duration_min":45,"intensity_label":"","coach_notes":"","readiness_adjustment":{"decision":"","reason":""},"warmup":[{"name":"","duration_seconds":300,"cue":""}],"exercises":[{"category":"","equipment":"","exercise":"","movement_pattern":"","target_muscles":["chest"],"warmup_sets":[{"sets":1,"reps":"10","rir":4}],"work_sets":3,"rep_range":[8,12],"target_rir":2,"rest_seconds":[60,90],"tempo":"2-0-2","load_selection_rule":"","progression_rule":"","substitutions":[{"equipment":"","exercise":"","reason":""}],"stop_condition":"","priority":"main","notes":""}],"cooldown":[{"name":"","duration_seconds":300,"cue":""}],"expected_weekly_budget_usage":{"chest_sets":0,"back_sets":0,"quads_sets":0,"hamstrings_sets":0,"glutes_sets":0,"shoulders_sets":0,"core_sets":0,"zone2_minutes":0},"stop_rules":[{"condition":"","action":""}],"post_session_questions":[],"adjustments":[{"case":"","change":""}],"time_cut_order":[""],"strategy_escalation":{"required":false,"reason":"","suggested_action":""}}}
        """.trimIndent()
    }

    fun buildUserPrompt(input: FitnessWorkoutGenerationInput): String {
        return """
            用户资料：
            ${FitnessProfilePromptFormatter.format(input.profile)}

            strategy_spec：
            ${input.strategySpecJson}

            daily_context：
            ${input.dailyContextJson}

            remaining_weekly_budget：
            ${input.remainingWeeklyBudgetJson}

            可用器械：
            有氧：跑步机、单车、太空漫步机、划船机。
            固定力量：推胸、推肩、高位下拉、夹胸、蹬腿、髋内外展。
            半自由/自由力量：史密斯架、平卧推、哑铃凳、绳索、引体向上、罗马椅。
            拉伸/自重/恢复：瑜伽垫、瑜伽球、泡沫轴、腹肌板。

            质量要求：
            1. 生成 4-7 个训练动作，新手或低准备度优先 4-5 个。
            2. 至少一个热身和一个冷身，且不占用力量组数预算。
            3. 力量动作包含 work_sets、rep_range、target_rir、rest_seconds、load_selection_rule、stop_condition。
            4. 有氧动作包含 duration_seconds，并扣减 zone2_minutes。
            5. target_muscles 只使用 chest/back/quads/hamstrings/glutes/shoulders/core。
            6. expected_weekly_budget_usage 不得超过 remaining_weekly_budget。
            7. 文案专业、简短、可执行，不使用标签化表达。
            8. time_cut_order 按动作名列出时间不足时的删减顺序。
            9. substitutions.equipment 也必须来自固定器械清单。
            10. 停止条件使用刺痛、眩晕、胸闷、动作无法稳定等可判断信号。
            必须回写 strategy_version=${input.strategyVersion}。
            只返回 UTF-8 JSON。
        """.trimIndent()
    }
}
