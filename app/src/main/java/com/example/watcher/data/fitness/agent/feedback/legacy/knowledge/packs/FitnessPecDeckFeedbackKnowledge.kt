package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessPecDeckFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "pec_deck",
        displayName = "夹胸器",
        canonicalEquipment = "夹胸",
        aliases = listOf(
            "夹胸",
            "夹胸器",
            "蝴蝶机",
            "坐姿夹胸",
            "器械夹胸",
            "胸飞鸟",
            "器械飞鸟",
            "pec deck",
            "chest fly machine",
            "machine chest fly",
            "pec fly",
            "fly machine"
        ),
        initialObserverFocus = "先判断用户做的是夹胸还是推胸，再看座椅高度、背部是否贴垫、肘部高度是否合适，以及回程有没有被重量拉得过深。",
    )
}
