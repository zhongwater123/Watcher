package com.example.watcher.data.repository

import com.example.watcher.data.model.ClassroomKnowledgeFrameRef
import com.example.watcher.data.model.ClassroomKnowledgeTreeProgress
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeProcessingStatus
import com.example.watcher.data.model.ClassroomSpeechProvider
import com.example.watcher.data.model.VideoSpeechTranscriptEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class ClassroomAudioFrame(
    val sequence: Long,
    val pcm: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
    val bitsPerSample: Int,
    val capturedAtMs: Long,
    val relativeStartMs: Long,
    val durationMs: Long
)

internal data class RealtimeAudioQueueSnapshot(
    val pendingFrameCount: Int,
    val offeredFrameCount: Long,
    val droppedFrameCount: Int,
    val closed: Boolean
)

internal class RealtimeAudioQueue(
    private val capacityFrames: Int
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val frames = ArrayDeque<ClassroomAudioFrame>()
    private var offeredFrameCount = 0L
    private var droppedFrameCount = 0
    private var closed = false

    init {
        require(capacityFrames > 0) { "capacityFrames must be positive." }
    }

    fun offer(frame: ClassroomAudioFrame): Boolean {
        lock.withLock {
            if (closed) return false
            offeredFrameCount += 1
            val acceptedWithoutDrop = frames.size < capacityFrames
            if (!acceptedWithoutDrop) {
                frames.removeFirst()
                droppedFrameCount += 1
            }
            frames.addLast(frame)
            notEmpty.signalAll()
            return acceptedWithoutDrop
        }
    }

    fun poll(waitMs: Long = 0L): ClassroomAudioFrame? {
        lock.withLock {
            if (frames.isEmpty() && !closed && waitMs > 0L) {
                notEmpty.await(waitMs, TimeUnit.MILLISECONDS)
            }
            return frames.pollFirst()
        }
    }

    fun drain(maxFrames: Int): List<ClassroomAudioFrame> {
        if (maxFrames <= 0) return emptyList()
        lock.withLock {
            val drained = ArrayList<ClassroomAudioFrame>(maxFrames)
            while (drained.size < maxFrames && frames.isNotEmpty()) {
                drained += frames.removeFirst()
            }
            return drained
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            notEmpty.signalAll()
        }
    }

    fun snapshot(): RealtimeAudioQueueSnapshot {
        lock.withLock {
            return RealtimeAudioQueueSnapshot(
                pendingFrameCount = frames.size,
                offeredFrameCount = offeredFrameCount,
                droppedFrameCount = droppedFrameCount,
                closed = closed
            )
        }
    }
}

internal class AudioTee(
    private val consumers: List<(ClassroomAudioFrame) -> Unit>
) {
    fun emit(frame: ClassroomAudioFrame) {
        consumers.forEach { consumer ->
            runCatching { consumer(frame) }
        }
    }
}

internal class RealtimeAudioPacketizer(
    private val targetDurationMs: Long = 200L
) {
    private val buffer = ByteArrayOutputStream()
    private var sequence = 0L
    private var sampleRate = 0
    private var channelCount = 0
    private var bitsPerSample = 0
    private var capturedAtMs = 0L
    private var relativeStartMs = 0L
    private var durationMs = 0L

    fun add(frame: ClassroomAudioFrame): List<ClassroomAudioFrame> {
        val emitted = mutableListOf<ClassroomAudioFrame>()
        if (buffer.size() > 0 && !frame.matchesCurrentFormat()) {
            flush()?.let(emitted::add)
        }
        if (buffer.size() == 0) {
            capturedAtMs = frame.capturedAtMs
            relativeStartMs = frame.relativeStartMs
            sampleRate = frame.sampleRate
            channelCount = frame.channelCount
            bitsPerSample = frame.bitsPerSample
        }
        sequence = frame.sequence
        buffer.write(frame.pcm)
        durationMs += frame.durationMs
        if (durationMs >= targetDurationMs) {
            flush()?.let(emitted::add)
        }
        return emitted
    }

    fun flush(): ClassroomAudioFrame? {
        val bytes = buffer.toByteArray()
        if (bytes.isEmpty()) return null
        buffer.reset()
        val packet = ClassroomAudioFrame(
            sequence = sequence,
            pcm = bytes,
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitsPerSample = bitsPerSample,
            capturedAtMs = capturedAtMs,
            relativeStartMs = relativeStartMs,
            durationMs = durationMs.coerceAtLeast(1L)
        )
        durationMs = 0L
        return packet
    }

    private fun ClassroomAudioFrame.matchesCurrentFormat(): Boolean {
        return sampleRate == this.sampleRate &&
            channelCount == this.channelCount &&
            bitsPerSample == this.bitsPerSample
    }
}

internal data class ClassroomAsrWord(
    val text: String,
    val globalStartMs: Long,
    val globalEndMs: Long
)

internal data class ClassroomAsrTranscript(
    val runId: Long,
    val segmentIndex: Int?,
    val globalStartMs: Long,
    val globalEndMs: Long,
    val text: String,
    val isFinal: Boolean,
    val words: List<ClassroomAsrWord>,
    val source: String,
    val asrLogId: String?
)

internal object ClassroomAsrTranscriptMapper {
    fun fromUtteranceJson(
        runId: Long,
        segmentIndex: Int?,
        utterance: JSONObject,
        globalOffsetMs: Long,
        source: String,
        asrLogId: String?
    ): ClassroomAsrTranscript {
        val text = utterance.optString("text").trim()
        val startMs = utterance.optLong("start_time", 0L).coerceAtLeast(0L)
        val endMs = utterance.optLong("end_time", startMs).coerceAtLeast(startMs)
        val words = utterance.optJSONArray("words")
            ?.toWordList(globalOffsetMs)
            .orEmpty()
        return ClassroomAsrTranscript(
            runId = runId,
            segmentIndex = segmentIndex,
            globalStartMs = globalOffsetMs + startMs,
            globalEndMs = globalOffsetMs + endMs,
            text = text,
            isFinal = utterance.optBoolean("definite", false),
            words = words,
            source = source,
            asrLogId = asrLogId
        )
    }

    fun toEntity(transcript: ClassroomAsrTranscript): VideoSpeechTranscriptEntity {
        return VideoSpeechTranscriptEntity(
            runId = transcript.runId,
            segmentIndex = transcript.segmentIndex,
            timestamp = transcript.globalStartMs,
            displayTimestamp = formatDisplayTimestamp(transcript.globalStartMs),
            text = transcript.text,
            isFinal = transcript.isFinal,
            globalStartMs = transcript.globalStartMs,
            globalEndMs = transcript.globalEndMs,
            definite = transcript.isFinal,
            wordsJson = transcript.words.toJsonArray().toString(),
            source = transcript.source,
            asrLogId = transcript.asrLogId.orEmpty()
        )
    }

    private fun JSONArray.toWordList(globalOffsetMs: Long): List<ClassroomAsrWord> {
        val result = ArrayList<ClassroomAsrWord>(length())
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val text = item.optString("text").ifBlank {
                item.optString("word")
            }
            if (text.isBlank()) continue
            val startMs = item.optLong("start_time", 0L).coerceAtLeast(0L)
            val endMs = item.optLong("end_time", startMs).coerceAtLeast(startMs)
            result += ClassroomAsrWord(
                text = text,
                globalStartMs = globalOffsetMs + startMs,
                globalEndMs = globalOffsetMs + endMs
            )
        }
        return result
    }

    private fun List<ClassroomAsrWord>.toJsonArray(): JSONArray {
        val array = JSONArray()
        forEach { word ->
            array.put(JSONObject().apply {
                put("text", word.text)
                put("startMs", word.globalStartMs)
                put("endMs", word.globalEndMs)
            })
        }
        return array
    }

    private fun formatDisplayTimestamp(timestampMs: Long): String {
        val totalSeconds = timestampMs / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d".format(minutes, seconds)
    }
}

internal enum class ClassroomRealtimeConnectionState {
    Idle,
    Connecting,
    Connected,
    Reconnecting,
    Failed,
    Closed
}

internal data class ClassroomRealtimeFeedbackState(
    val enabled: Boolean = false,
    val connectionState: ClassroomRealtimeConnectionState = ClassroomRealtimeConnectionState.Idle,
    val currentTranscript: String = "",
    val stableTranscript: String = "",
    val liveInsights: List<String> = emptyList(),
    val knowledgeTree: ClassroomKnowledgeTree? = null,
    val changedKnowledgeNodeIds: List<String> = emptyList(),
    val newKnowledgeNodeIds: List<String> = emptyList(),
    val knowledgeTreeStatus: ClassroomKnowledgeTreeProcessingStatus = ClassroomKnowledgeTreeProcessingStatus.Waiting,
    val knowledgeTreeProgress: ClassroomKnowledgeTreeProgress = ClassroomKnowledgeTreeProgress(),
    val knowledgeFrameRefs: List<ClassroomKnowledgeFrameRef> = emptyList(),
    val lastDefiniteTimeMs: Long = 0L,
    val audioLagMs: Long = 0L,
    val droppedFrameCount: Int = 0,
    val backfillSegmentCount: Int = 0,
    val pendingFrameCount: Int = 0,
    val asrLogId: String = "",
    val speechProvider: ClassroomSpeechProvider = ClassroomSpeechProvider.ASR,
    val speechFallbackReason: String? = null,
    val speechSessionId: String = "",
    val errorMessage: String? = null
)
