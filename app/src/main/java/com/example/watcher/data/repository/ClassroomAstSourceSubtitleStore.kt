package com.example.watcher.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.io.File

internal data class ClassroomAstSourceSubtitle(
    val runId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val isFinal: Boolean,
    val sequence: Int?,
    val logId: String,
    val createdAt: Long = System.currentTimeMillis()
)

internal class ClassroomAstSourceSubtitleStore(
    private val videoRunsDir: File
) {
    private val versionByRun = MutableStateFlow<Map<Long, Long>>(emptyMap())

    fun append(subtitle: ClassroomAstSourceSubtitle) {
        val normalized = subtitle.copy(
            endMs = subtitle.endMs.takeIf { it > subtitle.startMs } ?: (subtitle.startMs + 1_000L),
            text = subtitle.text.trim()
        )
        if (normalized.text.isBlank()) return
        val file = sourceFile(normalized.runId)
        synchronized(this) {
            file.parentFile?.mkdirs()
            file.appendText(normalized.toJson().toString() + "\n", Charsets.UTF_8)
            bumpRunVersionLocked(normalized.runId)
        }
    }

    fun observeVersion(runId: Long): Flow<Long> {
        return versionByRun
            .map { it[runId] ?: 0L }
            .distinctUntilChanged()
    }

    fun load(runId: Long): List<ClassroomAstSourceSubtitle> {
        val file = sourceFile(runId)
        if (!file.exists()) return emptyList()
        return synchronized(this) {
            file.readLines(Charsets.UTF_8)
                .mapNotNull { line ->
                    runCatching {
                        val json = JSONObject(line)
                        ClassroomAstSourceSubtitle(
                            runId = json.optLong("runId", runId),
                            startMs = json.optLong("startMs", 0L),
                            endMs = json.optLong("endMs", 0L),
                            text = json.optString("text").trim(),
                            isFinal = json.optBoolean("isFinal", true),
                            sequence = json.optInt("sequence").takeIf { json.has("sequence") },
                            logId = json.optString("logId"),
                            createdAt = json.optLong("createdAt", 0L)
                        )
                    }.getOrNull()
                }
                .filter { it.text.isNotBlank() }
                .sortedWith(compareBy<ClassroomAstSourceSubtitle> { it.startMs }.thenBy { it.sequence ?: Int.MAX_VALUE })
        }
    }

    fun findSourceTextFor(
        subtitles: List<ClassroomAstSourceSubtitle>,
        startMs: Long,
        endMs: Long
    ): String? {
        if (subtitles.isEmpty()) return null
        val safeEndMs = endMs.takeIf { it > startMs } ?: (startMs + 1_000L)
        val overlapping = subtitles
            .filter { overlapMs(it.startMs, it.endMs, startMs, safeEndMs) > 0L }
            .sortedBy { it.startMs }
        if (overlapping.isNotEmpty()) {
            return overlapping.joinTexts()
        }

        val targetMidpoint = (startMs + safeEndMs) / 2
        val nearest = subtitles.minByOrNull { subtitle ->
            kotlin.math.abs(((subtitle.startMs + subtitle.endMs) / 2) - targetMidpoint)
        } ?: return null
        val distanceMs = kotlin.math.abs(((nearest.startMs + nearest.endMs) / 2) - targetMidpoint)
        return nearest.text.takeIf { distanceMs <= NEAREST_SOURCE_TOLERANCE_MS }
    }

    fun clearRun(runId: Long) {
        synchronized(this) {
            runDir(runId).deleteRecursively()
            bumpRunVersionLocked(runId)
        }
    }

    private fun bumpRunVersionLocked(runId: Long) {
        val current = versionByRun.value
        versionByRun.value = current + (runId to ((current[runId] ?: 0L) + 1L))
    }

    private fun sourceFile(runId: Long): File {
        return File(runDir(runId), SOURCE_FILE_NAME)
    }

    private fun runDir(runId: Long): File {
        return File(videoRunsDir, "run_${runId}_ast_source_subtitles")
    }

    private fun ClassroomAstSourceSubtitle.toJson(): JSONObject {
        return JSONObject()
            .put("runId", runId)
            .put("startMs", startMs)
            .put("endMs", endMs)
            .put("text", text)
            .put("isFinal", isFinal)
            .put("logId", logId)
            .put("createdAt", createdAt)
            .also { json ->
                sequence?.let { json.put("sequence", it) }
            }
    }

    private fun List<ClassroomAstSourceSubtitle>.joinTexts(): String {
        val joined = map { it.text }
            .filter { it.isNotBlank() }
            .fold(mutableListOf<String>()) { acc, text ->
                if (acc.lastOrNull() != text) acc.add(text)
                acc
            }
            .joinToString(" ")
            .trim()
        return joined
    }

    private fun overlapMs(firstStart: Long, firstEnd: Long, secondStart: Long, secondEnd: Long): Long {
        return (minOf(firstEnd, secondEnd) - maxOf(firstStart, secondStart)).coerceAtLeast(0L)
    }

    private companion object {
        private const val SOURCE_FILE_NAME = "source_subtitles.jsonl"
        private const val NEAREST_SOURCE_TOLERANCE_MS = 3_000L
    }
}
