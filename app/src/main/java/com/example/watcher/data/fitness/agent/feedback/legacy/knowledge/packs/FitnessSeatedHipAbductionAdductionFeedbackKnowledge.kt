package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessSeatedHipAbductionAdductionFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "seated_hip_abduction_adduction",
        displayName = "坐姿髋内外展器",
        canonicalEquipment = "髋内外展",
        aliases = listOf(
            "髋内外展",
            "髋内外展器",
            "髋外展",
            "髋外展器",
            "髋内收",
            "髋内收器",
            "髋内展",
            "髋外展机",
            "髋内收机",
            "大腿外展",
            "大腿内收",
            "hip abduction",
            "hip adduction",
            "abductor machine",
            "adductor machine",
            "hip abductor",
            "hip adductor"
        ),
        initialObserverFocus = "先判断用户做的是髋外展还是髋内收，再看是否坐稳、背臀贴垫、脚踩稳、两侧腿是否同步慢速移动。",
    )
}
