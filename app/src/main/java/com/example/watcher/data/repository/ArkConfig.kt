package com.example.watcher.data.repository

import com.example.watcher.BuildConfig

internal object ArkConfig {
    val apiKey: String = BuildConfig.API_KEY
    const val intentModel: String = "doubao-seed-2-0-mini-260215"
    const val monitorModel: String = "doubao-seed-2-0-lite-260215"
    const val videoPlanningModel: String = "doubao-seed-2-0-mini-260215"
    const val videoAnalysisModel: String = "doubao-seed-2-0-lite-260428"
}
