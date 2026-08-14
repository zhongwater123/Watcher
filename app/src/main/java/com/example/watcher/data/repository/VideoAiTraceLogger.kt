package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.data.local.VideoAiTraceDao
import com.example.watcher.data.model.VideoAiTraceEventEntity
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal data class VideoAiTraceContext(
    val traceId: String,
    val runId: Long? = null,
    val taskId: Long? = null,
    val node: String,
    val segmentIndex: Int? = null,
    val chunkIndex: Int? = null,
    val model: String = "",
    val requestKind: String = ""
)

internal interface VideoAiTraceLogSink {
    fun log(tag: String, message: String)
    fun error(tag: String, message: String, throwable: Throwable?)
}

internal class AndroidVideoAiTraceLogSink : VideoAiTraceLogSink {
    override fun log(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
}

internal object VideoAiTraceFormatter {
    const val TAG = "Watcher.Video.AITrace"
    const val DEFAULT_CHUNK_SIZE = 900

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun splitText(text: String, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<String> {
        if (text.isEmpty()) return listOf("")
        return text.chunked(chunkSize)
    }

    fun formatLines(
        context: VideoAiTraceContext,
        phase: String,
        kind: String,
        text: String,
        durationMs: Long = 0L,
        chunkSize: Int = DEFAULT_CHUNK_SIZE
    ): List<String> {
        val chunks = splitText(text, chunkSize)
        val hash = sha256(text)
        val byteLength = text.toByteArray(Charsets.UTF_8).size
        return chunks.mapIndexed { index, chunk ->
            "${prefix(context, phase)} part=${index + 1}/${chunks.size} kind=$kind " +
                "sha256=$hash length=${text.length} bytes=$byteLength model=${context.model} " +
                "durationMs=$durationMs data=${chunk.escapeLogcatValue()}"
        }
    }

    fun prefix(context: VideoAiTraceContext, phase: String): String {
        return "trace=${context.traceId} " +
            "run=${context.runId ?: 0} " +
            "node=${context.node} " +
            "phase=$phase " +
            "segment=${context.segmentIndex ?: 0} " +
            "chunk=${context.chunkIndex ?: 0}"
    }
}

private fun String.escapeLogcatValue(): String {
    return buildString(length) {
        this@escapeLogcatValue.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}

internal class VideoAiTraceLogger(
    private val dao: VideoAiTraceDao,
    private val logSink: VideoAiTraceLogSink = AndroidVideoAiTraceLogSink(),
    private val sequence: AtomicLong = AtomicLong(0L)
) {
    fun newTraceId(): String = "video-${UUID.randomUUID()}"

    suspend fun beginNode(
        context: VideoAiTraceContext,
        requestPayloadJson: String = ""
    ) {
        record(context = context, phase = "begin", requestPayloadJson = requestPayloadJson)
    }

    suspend fun logPrompt(
        context: VideoAiTraceContext,
        basePrompt: String,
        renderedPrompt: String
    ) {
        val payload = buildJsonObject(
            "basePromptLength" to basePrompt.length.toString(),
            "renderedPromptLength" to renderedPrompt.length.toString(),
            "basePromptHash" to VideoAiTraceFormatter.sha256(basePrompt),
            "renderedPromptHash" to VideoAiTraceFormatter.sha256(renderedPrompt),
            "basePrompt" to basePrompt
        )
        record(
            context = context,
            phase = "prompt",
            promptText = renderedPrompt,
            requestPayloadJson = payload
        )
    }

    suspend fun logRequest(
        context: VideoAiTraceContext,
        requestPayloadJson: String,
        promptText: String = ""
    ) {
        record(
            context = context,
            phase = "request",
            promptText = promptText,
            requestPayloadJson = requestPayloadJson
        )
    }

    suspend fun logResponse(
        context: VideoAiTraceContext,
        rawResponseText: String,
        durationMs: Long
    ) {
        record(
            context = context,
            phase = "response",
            rawResponseText = rawResponseText,
            durationMs = durationMs
        )
    }

    suspend fun logParsed(
        context: VideoAiTraceContext,
        parsedSummary: String,
        parsedJson: String,
        parseStatus: String
    ) {
        record(
            context = context,
            phase = "parsed",
            parsedSummary = parsedSummary,
            parsedJson = parsedJson,
            requestPayloadJson = """{"parseStatus":"${parseStatus.escapeJson()}"}"""
        )
    }

    suspend fun logError(
        context: VideoAiTraceContext,
        error: Throwable,
        durationMs: Long = 0L,
        rawResponseText: String = ""
    ) {
        record(
            context = context,
            phase = "error",
            rawResponseText = rawResponseText,
            errorMessage = error.toUserMessage("AI trace node failed."),
            durationMs = durationMs
        )
    }

    suspend fun finishNode(
        context: VideoAiTraceContext,
        durationMs: Long = 0L
    ) {
        record(context = context, phase = "finish", durationMs = durationMs)
    }

    private suspend fun record(
        context: VideoAiTraceContext,
        phase: String,
        promptText: String = "",
        requestPayloadJson: String = "",
        rawResponseText: String = "",
        parsedSummary: String = "",
        parsedJson: String = "",
        errorMessage: String = "",
        durationMs: Long = 0L
    ) {
        val content = listOf(
            promptText,
            requestPayloadJson,
            rawResponseText,
            parsedSummary,
            parsedJson,
            errorMessage
        ).joinToString("\n")
        val event = VideoAiTraceEventEntity(
            traceId = context.traceId,
            runId = context.runId,
            taskId = context.taskId,
            node = context.node,
            phase = phase,
            segmentIndex = context.segmentIndex,
            chunkIndex = context.chunkIndex,
            model = context.model,
            requestKind = context.requestKind,
            promptText = promptText,
            requestPayloadJson = requestPayloadJson,
            rawResponseText = rawResponseText,
            parsedSummary = parsedSummary,
            parsedJson = parsedJson,
            errorMessage = errorMessage,
            durationMs = durationMs,
            sequence = sequence.incrementAndGet(),
            contentHash = VideoAiTraceFormatter.sha256(content)
        )

        runCatching { dao.insert(event) }
            .onFailure { logSink.error(VideoAiTraceFormatter.TAG, "Failed to persist ${VideoAiTraceFormatter.prefix(context, phase)}", it) }

        emitLogcat(context, phase, "prompt", promptText, durationMs)
        emitLogcat(context, phase, "payload", requestPayloadJson, durationMs)
        emitLogcat(context, phase, "response", rawResponseText, durationMs)
        emitLogcat(context, phase, "parsed", parsedJson.ifBlank { parsedSummary }, durationMs)
        emitLogcat(context, phase, "error", errorMessage, durationMs)
        if (
            promptText.isBlank() &&
            requestPayloadJson.isBlank() &&
            rawResponseText.isBlank() &&
            parsedSummary.isBlank() &&
            parsedJson.isBlank() &&
            errorMessage.isBlank()
        ) {
            logSink.log(
                VideoAiTraceFormatter.TAG,
                "${VideoAiTraceFormatter.prefix(context, phase)} kind=event sha256=${event.contentHash} length=0 model=${context.model} durationMs=$durationMs"
            )
        }
    }

    private fun emitLogcat(
        context: VideoAiTraceContext,
        phase: String,
        kind: String,
        text: String,
        durationMs: Long
    ) {
        if (text.isBlank()) return
        VideoAiTraceFormatter.formatLines(
            context = context,
            phase = phase,
            kind = kind,
            text = text,
            durationMs = durationMs
        ).forEach { line ->
            logSink.log(VideoAiTraceFormatter.TAG, line)
        }
    }
}

internal fun aiTracePayload(vararg pairs: Pair<String, Any?>): String {
    return pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        val rendered = when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            else -> "\"${value.toString().escapeJson()}\""
        }
        "\"${key.escapeJson()}\":$rendered"
    }
}

private fun buildJsonObject(vararg pairs: Pair<String, String>): String {
    return pairs.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${key.escapeJson()}\":\"${value.escapeJson()}\""
    }
}

private fun String.escapeJson(): String {
    return buildString(length) {
        this@escapeJson.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }
}
