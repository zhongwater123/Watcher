package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessRomanChairFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "roman_chair",
        displayName = "罗马椅",
        canonicalEquipment = "罗马椅",
        aliases = listOf(
            "罗马椅",
            "山羊挺身",
            "背伸",
            "背部伸展",
            "挺身椅",
            "45度背伸",
            "45度罗马椅",
            "俯身挺背",
            "罗马椅抬腿",
            "垂直举腿",
            "悬垂举腿",
            "roman chair",
            "roman chair back extension",
            "back extension",
            "hyperextension",
            "45 degree back extension",
            "captain chair",
            "captain's chair",
            "captains chair",
            "roman chair leg raise"
        ),
        initialObserverFocus = "先判断用户做的是背伸还是罗马椅抬腿，再看髋垫/脚踝或扶手/靠背设置是否稳定，以及是否出现圆背、过伸或甩腿。",
    )
}
