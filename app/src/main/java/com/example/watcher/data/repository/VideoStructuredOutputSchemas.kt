package com.example.watcher.data.repository

import com.example.watcher.data.remote.JsonSchemaResponseFormat
import com.example.watcher.data.remote.ResponseFormat
import com.example.watcher.data.remote.Thinking

internal object VideoStructuredOutputSchemas {
    val thinkingDisabled = Thinking(type = "disabled")

    val segmentFactPacketResponseFormat: ResponseFormat
        get() = ResponseFormat(
            type = "json_schema",
            jsonSchema = JsonSchemaResponseFormat(
                name = "video_segment_fact_packet_v1",
                strict = true,
                schema = segmentFactPacketSchema
            )
        )

    val finalReportResponseFormat: ResponseFormat
        get() = ResponseFormat(
            type = "json_schema",
            jsonSchema = JsonSchemaResponseFormat(
                name = "video_final_report_v1",
                strict = true,
                schema = finalReportSchema
            )
        )

    private val stringArraySchema = mapOf(
        "type" to "array",
        "items" to mapOf("type" to "string")
    )

    private val timelineFactSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "timestampSeconds" to mapOf("type" to "integer"),
            "title" to mapOf("type" to "string"),
            "detail" to mapOf("type" to "string"),
            "confidence" to mapOf("type" to "number")
        ),
        "required" to listOf("timestampSeconds", "title", "detail", "confidence"),
        "additionalProperties" to false
    )

    private val audioFactSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "timestampSeconds" to mapOf("type" to "integer"),
            "text" to mapOf("type" to "string"),
            "speakerHint" to mapOf("type" to "string"),
            "confidence" to mapOf("type" to "number"),
            "uncertain" to mapOf("type" to "boolean")
        ),
        "required" to listOf("timestampSeconds", "text", "speakerHint", "confidence", "uncertain"),
        "additionalProperties" to false
    )

    private val visualFactSchema = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "timestampSeconds" to mapOf("type" to "integer"),
            "content" to mapOf("type" to "string"),
            "relevance" to mapOf("type" to "string"),
            "confidence" to mapOf("type" to "number")
        ),
        "required" to listOf("timestampSeconds", "content", "relevance", "confidence"),
        "additionalProperties" to false
    )

    private val segmentFactPacketSchema: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "segmentTopic" to mapOf("type" to "string"),
            "audioFacts" to mapOf("type" to "array", "items" to audioFactSchema),
            "speechKeyPoints" to stringArraySchema,
            "visualFacts" to mapOf("type" to "array", "items" to visualFactSchema),
            "screenOrBoardFacts" to stringArraySchema,
            "demonstrationFacts" to stringArraySchema,
            "timelineFacts" to mapOf("type" to "array", "items" to timelineFactSchema),
            "uncertainties" to stringArraySchema,
            "quality" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "audioPresent" to mapOf("type" to "boolean"),
                    "speechClarity" to mapOf(
                        "type" to "string",
                        "enum" to listOf("high", "medium", "low", "none")
                    ),
                    "visualClarity" to mapOf(
                        "type" to "string",
                        "enum" to listOf("high", "medium", "low", "none")
                    ),
                    "contentDensity" to mapOf(
                        "type" to "string",
                        "enum" to listOf("high", "medium", "low", "none")
                    )
                ),
                "required" to listOf("audioPresent", "speechClarity", "visualClarity", "contentDensity"),
                "additionalProperties" to false
            )
        ),
        "required" to listOf(
            "segmentTopic",
            "audioFacts",
            "speechKeyPoints",
            "visualFacts",
            "screenOrBoardFacts",
            "demonstrationFacts",
            "timelineFacts",
            "uncertainties",
            "quality"
        ),
        "additionalProperties" to false
    )

    private val finalReportSchema: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "reportType" to mapOf(
                "type" to "string",
                "enum" to listOf(
                    "learning_notes",
                    "meeting_minutes",
                    "training_notes",
                    "interview_notes",
                    "scene_observation",
                    "general_record"
                )
            ),
            "title" to mapOf("type" to "string"),
            "briefSummary" to mapOf("type" to "string"),
            "keyConclusions" to stringArraySchema,
            "structuredNotes" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "overview" to mapOf("type" to "string"),
                    "sections" to mapOf(
                        "type" to "array",
                        "items" to mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "title" to mapOf("type" to "string"),
                                "items" to stringArraySchema
                            ),
                            "required" to listOf("title", "items"),
                            "additionalProperties" to false
                        )
                    )
                ),
                "required" to listOf("overview", "sections"),
                "additionalProperties" to false
            ),
            "outline" to stringArraySchema,
            "knowledgePoints" to stringArraySchema,
            "reviewOrActionItems" to stringArraySchema,
            "evidenceHighlights" to stringArraySchema,
            "coverageNotice" to mapOf("type" to "string"),
            "timeline" to mapOf("type" to "array", "items" to timelineFactSchema)
        ),
        "required" to listOf(
            "reportType",
            "title",
            "briefSummary",
            "keyConclusions",
            "structuredNotes",
            "outline",
            "knowledgePoints",
            "reviewOrActionItems",
            "evidenceHighlights",
            "coverageNotice",
            "timeline"
        ),
        "additionalProperties" to false
    )

}
