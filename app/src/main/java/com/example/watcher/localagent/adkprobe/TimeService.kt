package com.example.watcher.localagent.adkprobe

import com.google.adk.kt.annotations.Param
import com.google.adk.kt.annotations.Tool

class TimeService {
    @Tool
    fun getCurrentTime(
        @Param("Name of the city to get the time for") city: String
    ): Map<String, String> {
        return mapOf(
            "city" to city,
            "time" to "The time is 10:30am."
        )
    }
}
