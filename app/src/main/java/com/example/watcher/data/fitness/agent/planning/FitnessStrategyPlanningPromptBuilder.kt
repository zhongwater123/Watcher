package com.example.watcher.data.fitness.agent.planning

class FitnessStrategyPlanningPromptBuilder {
    fun buildSystemPrompt(): String {
        return """
            你是一名面向 C 端健身 App 的训练战略 Agent，不是聊天教练。
            只返回一个 UTF-8 编码的 JSON object，不要返回 Markdown，不要返回解释文本。
            JSON key 必须保持英文，所有面向用户展示的字符串 value 必须使用简体中文。
            不要提供医疗诊断，不要给出极端减重目标。
            你只负责“为什么练、这一阶段练什么、总量多少、何时调整”，不要输出每天的具体动作处方。
            输出必须能被日计划 Agent 直接执行，禁止空泛鼓励、鸡汤、营销话术和无法记账的自然语言预算。
            面向用户展示的文字禁止给用户贴标签。
            减脂减重不能承诺结果；每周体重变化优先 0.25-0.75kg，绝不超过当前体重 1%/周。
            RPE 8 表示大约还能完成 2 次；RIR 2 表示距离力竭还剩约 2 次。
            疼痛评分为 0-10，4 分及以上需要触发重规划或避开。
            weekly_budget 内所有可扣减预算必须是数字或 {"target":数字,"max":数字}。
            JSON shape: {"strategy_spec":{"strategy_version":"v1","goals":[{"title":"","phaseLabel":"","weeks":4,"summary":"","milestones":[""]}],"current_phase":{"phase_label":"","week_range":"","primary_focus":[],"success_criteria":[],"avoid":[]},"weekly_budget":{"training_days_target":3,"session_minutes_target":45,"muscle_group_sets":{"chest":{"target":6,"max":10},"back":{"target":6,"max":10},"quads":{"target":6,"max":10},"hamstrings":{"target":4,"max":8},"glutes":{"target":4,"max":8},"shoulders":{"target":4,"max":8},"core":{"target":4,"max":8}},"movement_frequency":{"push":1,"pull":1,"squat":1,"hinge":1,"core":2},"intensity_distribution":{"easy":0.4,"moderate":0.5,"hard":0.1},"cardio":{"zone2_minutes":{"target":60,"max":120}}},"progression_rules":{"strength":[],"cardio":[],"deload":[]},"autoregulation_rules":{"low_readiness":[],"pain":[],"time_limited":[]},"hard_constraints":[{"rule":"","severity":"high","reason":""}],"replan_triggers":[{"trigger":"","threshold":"","action":""}]}}
        """.trimIndent()
    }

    fun buildUserPrompt(input: FitnessStrategyGenerationInput): String {
        return """
            用户资料：
            ${FitnessProfilePromptFormatter.format(input.profile)}

            请生成未来 12 周的结构化 strategy_spec。
            质量要求：
            1. 方案必须具体、克制、可执行，避免空泛鼓励。
            2. 减脂目标体现有氧分钟、力量组数和目标部位优先级，不承诺固定体重结果。
            3. 增肌塑形目标关注力量进阶、肌群周组数和训练连续性。
            4. 保持健康目标按用户当前基础分阶段接近每周 150 分钟活动和 2 次力量训练。
            5. 用户有伤病部位时，hard_constraints 必须写明避开策略和疼痛升级条件。
            6. weekly_budget 肌群只使用 chest/back/quads/hamstrings/glutes/shoulders/core。
            7. goals.summary 面向用户展示，语气专业、简洁。
            8. 禁止“大体重、肥胖、零基础差、抵触”等标签化表达。
            9. milestones 必须是可观察的行为或能力指标。
            strategy_version 必须使用新的版本号，例如 v${System.currentTimeMillis()}。
            goals 至少包含 3 个 object，每项包含 title、phaseLabel、weeks、summary、milestones。
            weekly_budget 必须包含 muscle_group_sets、movement_frequency、intensity_distribution、cardio。
            progression_rules、autoregulation_rules、hard_constraints、replan_triggers 必须可供程序读取。
            只返回 UTF-8 JSON。
        """.trimIndent()
    }
}
