package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessLegPressFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "leg_press",
        displayName = "蹬腿机",
        canonicalEquipment = "蹬腿",
        aliases = listOf(
            "蹬腿",
            "蹬腿机",
            "腿举",
            "腿举机",
            "坐姿蹬腿",
            "坐姿腿举",
            "倒蹬",
            "倒蹬机",
            "leg press",
            "seated leg press",
            "horizontal leg press",
            "incline leg press",
            "45 degree leg press"
        ),
        initialObserverFocus = "先判断蹬腿机变式，再看用户背臀是否贴垫、脚跟是否踩实、膝盖是否对准脚尖，以及顶部有没有锁膝。",
    )
}
