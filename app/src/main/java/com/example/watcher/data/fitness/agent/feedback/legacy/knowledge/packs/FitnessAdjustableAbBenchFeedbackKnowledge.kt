package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessAdjustableAbBenchFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "adjustable_ab_bench",
        displayName = "可调腹肌板",
        canonicalEquipment = "腹肌板",
        aliases = listOf(
            "腹肌板",
            "可调腹肌板",
            "仰卧起坐板",
            "下斜腹肌板",
            "下斜仰卧起坐",
            "下斜卷腹",
            "仰卧卷腹",
            "反向卷腹",
            "腹肌训练板",
            "ab bench",
            "adjustable ab bench",
            "decline bench",
            "decline sit up",
            "decline situp",
            "decline crunch",
            "reverse crunch",
            "ab board"
        ),
        initialObserverFocus = "先判断腹肌板角度和当前动作族，再看脚踝是否卡稳、用户是否拉脖子、下降是否受控、腰背是否明显反弓。",
    )
}
