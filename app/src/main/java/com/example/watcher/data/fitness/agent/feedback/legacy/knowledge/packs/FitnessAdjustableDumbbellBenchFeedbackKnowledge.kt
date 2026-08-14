package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessAdjustableDumbbellBenchFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "adjustable_dumbbell_bench",
        displayName = "可调哑铃凳 + 哑铃",
        canonicalEquipment = "哑铃凳",
        aliases = listOf(
            "哑铃凳",
            "可调哑铃凳",
            "可调训练凳",
            "可调凳",
            "训练凳",
            "哑铃卧推",
            "哑铃上斜卧推",
            "哑铃肩推",
            "哑铃飞鸟",
            "俯身哑铃划船",
            "单臂哑铃划船",
            "哑铃凳划船",
            "adjustable bench",
            "dumbbell bench",
            "dumbbell bench press",
            "incline dumbbell press",
            "dumbbell shoulder press",
            "dumbbell fly",
            "one arm dumbbell row"
        ),
        initialObserverFocus = "先判断凳子角度和当前哑铃动作族，再看用户上下凳是否安全、脚是否踩稳、手腕是否放直、两侧哑铃是否同步受控。",
    )
}
