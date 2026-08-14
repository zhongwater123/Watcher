package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessLatPulldownFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "lat_pulldown",
        displayName = "高位下拉器械",
        canonicalEquipment = "高位下拉",
        aliases = listOf(
            "高位下拉",
            "下拉器",
            "背阔下拉",
            "背部下拉",
            "坐姿下拉",
            "lat pulldown",
            "lat pull-down",
            "pulldown machine",
            "cable pulldown"
        ),
        initialObserverFocus = "先判断用户是否坐稳、大腿垫是否压住、做的是胸前下拉还是颈后/其他变式，并观察肩膀是否耸起、身体是否后仰借力。",
    )
}
