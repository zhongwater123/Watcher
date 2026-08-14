package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessSeatedShoulderPressFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "seated_shoulder_press",
        displayName = "坐姿推肩器",
        canonicalEquipment = "推肩",
        aliases = listOf("坐姿推肩", "推肩器", "肩推", "推肩", "shoulder press", "machine shoulder"),
        initialObserverFocus = "先判断用户是否坐稳、背部贴住靠背、握把高度是否合适，并识别前握把/后握把。"
    )
}
