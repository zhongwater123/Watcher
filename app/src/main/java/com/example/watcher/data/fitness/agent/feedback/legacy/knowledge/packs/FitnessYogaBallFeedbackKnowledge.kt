package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessYogaBallFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "yoga_ball",
        displayName = "瑜伽球",
        canonicalEquipment = "瑜伽球",
        aliases = listOf(
            "瑜伽球",
            "健身球",
            "稳定球",
            "瑞士球",
            "平衡球",
            "抗力球",
            "yoga ball",
            "fitness ball",
            "stability ball",
            "swiss ball",
            "exercise ball"
        ),
        initialObserverFocus = "先判断瑜伽球动作族，再看球是否稳定、脚或手是否撑稳、腰背是否塌陷或反弓、动作是否过快。",
    )
}
