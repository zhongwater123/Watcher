package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessFoamRollerFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "foam_roller",
        displayName = "泡沫轴",
        canonicalEquipment = "泡沫轴",
        aliases = listOf(
            "泡沫轴",
            "泡沫滚轴",
            "筋膜轴",
            "筋膜放松轴",
            "滚筒放松",
            "泡沫轴放松",
            "foam roller",
            "foam rolling",
            "myofascial release",
            "roller"
        ),
        initialObserverFocus = "先判断泡沫轴压在哪个身体区域，再看是否压到关节/腰颈、速度是否太快、用户是否出现疼痛或失控。",
    )
}
