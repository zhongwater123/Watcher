package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessSmithMachineFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "smith_machine",
        displayName = "史密斯架",
        canonicalEquipment = "史密斯架",
        aliases = listOf(
            "史密斯架",
            "史密斯机",
            "史密斯",
            "史密斯深蹲",
            "史密斯卧推",
            "史密斯推举",
            "史密斯肩推",
            "smith",
            "smith machine",
            "smith squat",
            "smith bench press",
            "smith shoulder press"
        ),
        initialObserverFocus = "先判断史密斯架动作族，再看挂钩/安全限位、杠的位置、用户是否站稳或躺稳、以及是否有膝盖内扣或腰背失稳。",
    )
}
