package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessCableMachineFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "cable_machine",
        displayName = "多功能绳索力量器械",
        canonicalEquipment = "绳索",
        aliases = listOf(
            "绳索",
            "绳索器械",
            "多功能绳索",
            "龙门架",
            "绳索力量",
            "滑轮",
            "绳索夹胸",
            "绳索飞鸟",
            "绳索下压",
            "绳索弯举",
            "绳索划船",
            "绳索面拉",
            "直臂下拉",
            "cable",
            "cable machine",
            "functional trainer",
            "cable crossover",
            "cable fly",
            "triceps pushdown",
            "cable curl",
            "cable row",
            "face pull",
            "straight arm pulldown"
        ),
        initialObserverFocus = "先判断当前绳索动作族、滑轮高度和拉线方向，再看用户是否站稳、是否用身体甩动借力、回程是否受控。",
    )
}
