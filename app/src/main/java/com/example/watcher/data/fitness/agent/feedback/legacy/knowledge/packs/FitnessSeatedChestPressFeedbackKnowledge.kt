package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessSeatedChestPressFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "seated_chest_press",
        displayName = "坐姿推胸器",
        canonicalEquipment = "推胸",
        aliases = listOf(
            "坐姿推胸",
            "推胸器",
            "推胸",
            "胸推",
            "chest press",
            "machine chest press",
            "seated chest press"
        ),
        initialObserverFocus = "先判断用户是否坐深坐稳、背部贴住靠背、握把是否在胸部中线、肩膀是否放下，并观察是否在轻重量试推。"
    )
}
