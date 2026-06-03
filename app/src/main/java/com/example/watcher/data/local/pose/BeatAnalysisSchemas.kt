package com.example.watcher.data.local.pose

import com.example.watcher.data.remote.JsonSchemaResponseFormat
import com.example.watcher.data.remote.ResponseFormat

/**
 * JSON Schema definitions for LLM structured output of beat analysis.
 * Used with Doubao Responses API (strict: true).
 */
internal object BeatAnalysisSchemas {

    val beatAnalysisResponseFormat by lazy {
        ResponseFormat(
            type = "json_schema",
            jsonSchema = JsonSchemaResponseFormat(
                name = "beat_analysis_v1",
                schema = beatAnalysisSchema,
                strict = true
            )
        )
    }

    private val beatAnalysisSchema: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "correctedBpm" to mapOf("type" to "number"),
            "firstBeatMs" to mapOf("type" to "integer"),
            "timeSignature" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "numerator" to mapOf("type" to "integer"),
                    "denominator" to mapOf("type" to "integer")
                ),
                "required" to listOf("numerator", "denominator"),
                "additionalProperties" to false
            ),
            "tempoChanges" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "timestampMs" to mapOf("type" to "integer"),
                        "newBpm" to mapOf("type" to "number")
                    ),
                    "required" to listOf("timestampMs", "newBpm"),
                    "additionalProperties" to false
                )
            ),
            "accents" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "timestampMs" to mapOf("type" to "integer"),
                        "type" to mapOf("type" to "string", "enum" to listOf("accent", "fill", "break")),
                        "intensity" to mapOf("type" to "number")
                    ),
                    "required" to listOf("timestampMs", "intensity"),
                    "additionalProperties" to false
                )
            ),
            "segments" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "startMs" to mapOf("type" to "integer"),
                        "endMs" to mapOf("type" to "integer"),
                        "type" to mapOf("type" to "string", "enum" to listOf("intro", "verse", "chorus", "bridge", "outro", "break")),
                        "energyLevel" to mapOf("type" to "number")
                    ),
                    "required" to listOf("startMs", "endMs", "type", "energyLevel"),
                    "additionalProperties" to false
                )
            ),
            "phrases" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "startMs" to mapOf("type" to "integer"),
                        "endMs" to mapOf("type" to "integer"),
                        "beatCount" to mapOf("type" to "integer"),
                        "phraseType" to mapOf("type" to "string", "enum" to listOf("8-count", "4-count", "custom")),
                        "difficulty" to mapOf("type" to "number")
                    ),
                    "required" to listOf("startMs", "endMs", "beatCount", "phraseType", "difficulty"),
                    "additionalProperties" to false
                )
            )
        ),
        "required" to listOf("correctedBpm", "firstBeatMs", "timeSignature", "tempoChanges", "accents", "segments", "phrases"),
        "additionalProperties" to false
    )

    fun buildBeatAnalysisPrompt(
        dspBpm: Float,
        onsetCount: Int,
        durationMs: Long,
        onsetSamples: List<Long>
    ): String {
        val onsetStr = onsetSamples.take(30).joinToString(", ")
        return """你是专业的音乐分析师和舞蹈教学专家。请结合视频画面中的舞蹈动作和音频节奏，分析节拍结构。

本地信号分析参考数据:
- 估计 BPM: ${"%.1f".format(dspBpm)}
- 检测到 ${onsetCount} 个onset事件
- 音频时长: ${durationMs}ms
- onset时间戳采样(ms): [$onsetStr]

请结合视觉（舞蹈动作变化）和听觉（节拍、旋律）完成以下分析:

1. **BPM**: 确定准确的全局 BPM
2. **第一拍时间**: 音乐第一个强拍的精确时间戳(ms)
3. **拍号**: 4/4, 3/4 等
4. **变速段**(如有): 某些段落 BPM 发生变化时标注
5. **重音/特殊节拍**: 只标注有特殊重音、变化、或舞蹈动作突变的时间点（不需要列举每一拍，普通拍由 BPM 网格自动生成）
6. **音乐结构段落**: intro/verse/chorus/bridge/outro/break 的起止时间
7. **舞蹈学习短语**: 优先8拍一组，根据动作复杂度评估难度(0-1)

请严格按以下JSON格式输出（不要输出任何其他内容）:
{"correctedBpm":数字,"firstBeatMs":整数,"timeSignature":{"numerator":整数,"denominator":整数},"tempoChanges":[{"timestampMs":整数,"newBpm":数字}],"accents":[{"timestampMs":整数,"type":"accent或fill或break","intensity":0-1}],"segments":[{"startMs":整数,"endMs":整数,"type":"intro或verse或chorus或bridge或outro或break","energyLevel":0-1}],"phrases":[{"startMs":整数,"endMs":整数,"beatCount":整数,"phraseType":"8-count或4-count或custom","difficulty":0-1}]}"""
    }
}
