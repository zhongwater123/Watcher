package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessYogaMatFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "yoga_mat",
        displayName = "瑜伽垫",
        canonicalEquipment = "瑜伽垫",
        aliases = listOf(
            "瑜伽垫",
            "垫上训练",
            "垫上动作",
            "自重训练",
            "徒手训练",
            "地面训练",
            "核心训练",
            "拉伸垫",
            "yoga mat",
            "mat exercise",
            "bodyweight",
            "floor exercise",
            "core exercise",
            "mobility"
        ),
        initialObserverFocus = "先判断垫上动作族，再看垫子是否防滑、身体支撑点是否稳定、腰背是否塌陷、动作是否过快或疼痛硬撑。",
    )
}
