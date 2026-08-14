package com.example.watcher.data.model

data class MonitorStatus(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val lastCheckTime: Long = 0,
    val lastResult: CheckResult = CheckResult.NONE,
    val lastSummary: String = "",
    val lastReason: String = "",
    val lastRemark: String = "",
    val lastConfidence: Float? = null,
    val alertCount: Int = 0,
    val warningCount: Int = 0,
    val unknownCount: Int = 0,
    val normalCount: Int = 0,
    val totalCheckCount: Int = 0,
    val skippedCount: Int = 0,
    val failureCount: Int = 0,
    val nextCheckTime: Long = 0,
    val effectiveBaselineImagePath: String? = null,
    val lastAnalyzedImagePath: String? = null
)

enum class CheckResult {
    NONE,
    ALERT,
    WARNING,
    NORMAL,
    UNKNOWN
}

data class MonitorLogEntry(
    val id: Long = System.currentTimeMillis(),
    val timestamp: Long = System.currentTimeMillis(),
    val result: CheckResult,
    val message: String,
    val action: MonitorLogAction = MonitorLogAction.RESULT,
    val confidence: Float? = null,
    val imagePath: String? = null
)

enum class MonitorLogAction {
    RESULT,
    SKIP,
    ERROR,
    TASK
}

data class MonitorDecision(
    val result: CheckResult,
    val summary: String,
    val reason: String = "",
    val remark: String = "",
    val confidence: Float? = null,
    val rawResponse: String = ""
)
