package com.example.watcher.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "video_ai_trace_events",
    indices = [
        Index("traceId"),
        Index("runId"),
        Index("taskId"),
        Index("node"),
        Index("createdAt")
    ]
)
data class VideoAiTraceEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val traceId: String,
    val runId: Long? = null,
    val taskId: Long? = null,
    val node: String,
    val phase: String,
    val segmentIndex: Int? = null,
    val chunkIndex: Int? = null,
    val model: String = "",
    val requestKind: String = "",
    val promptText: String = "",
    val requestPayloadJson: String = "",
    val rawResponseText: String = "",
    val parsedSummary: String = "",
    val parsedJson: String = "",
    val errorMessage: String = "",
    val durationMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val sequence: Long = 0L,
    val contentHash: String = ""
)
