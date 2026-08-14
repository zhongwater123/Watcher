package com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.packs

import com.example.watcher.data.fitness.agent.feedback.legacy.knowledge.FitnessFeedbackKnowledgeDefinition
object FitnessFlatBenchPressMachineFeedbackKnowledge {
    val pack = FitnessFeedbackKnowledgeDefinition(
        id = "flat_bench_press_machine",
        displayName = "手握平卧推训练器",
        canonicalEquipment = "平卧推",
        aliases = listOf(
            "平卧推",
            "平卧推训练器",
            "手握平卧推",
            "平板卧推训练器",
            "卧推训练器",
            "平板卧推",
            "flat bench press",
            "flat bench press machine",
            "bench press machine",
            "horizontal bench press",
            "plate loaded bench press"
        ),
        initialObserverFocus = "先判断是否为仰卧手握平卧推训练器，再看用户是否躺稳、脚踩稳、肩胛稳定、手腕是否放直，以及下放是否受控。",
    )
}
