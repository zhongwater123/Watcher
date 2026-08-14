package com.example.watcher.data.repository

import android.util.Log
import com.example.watcher.data.model.ClassroomSpeechProvider
import com.example.watcher.data.model.ClassroomSpeechRecognitionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

internal sealed interface ClassroomSpeechSessionEvent {
    data class Connecting(
        val provider: ClassroomSpeechProvider,
        val sessionId: String,
        val logId: String = ""
    ) : ClassroomSpeechSessionEvent

    data class Ready(
        val provider: ClassroomSpeechProvider,
        val sessionId: String,
        val logId: String
    ) : ClassroomSpeechSessionEvent

    data class PartialText(
        val provider: ClassroomSpeechProvider,
        val text: String,
        val logId: String,
        val sequence: Int?
    ) : ClassroomSpeechSessionEvent

    data class FinalTranscript(
        val provider: ClassroomSpeechProvider,
        val transcript: ClassroomAsrTranscript,
        val logId: String,
        val sequence: Int?
    ) : ClassroomSpeechSessionEvent

    data class Error(
        val provider: ClassroomSpeechProvider,
        val message: String,
        val logId: String = "",
        val retryable: Boolean = true
    ) : ClassroomSpeechSessionEvent

    data class FallbackActivated(
        val from: ClassroomSpeechProvider,
        val to: ClassroomSpeechProvider,
        val reason: String
    ) : ClassroomSpeechSessionEvent

    data class Closed(
        val provider: ClassroomSpeechProvider,
        val reason: String
    ) : ClassroomSpeechSessionEvent
}

internal class ClassroomSpeechSessionManager(
    private val scope: CoroutineScope,
    private val asrCredentials: VolcengineAsrCredentials,
    private val astCredentials: VolcengineAstCredentials,
    private val clientInfo: VolcengineAsrWireProtocol.ClientInfo,
    private val astSourceSubtitleStore: ClassroomAstSourceSubtitleStore? = null,
    private val onEvent: (ClassroomSpeechSessionEvent) -> Unit
) {
    private val preReadyFrames = ArrayDeque<ClassroomAudioFrame>()
    private var asrClient: StreamingAsrClient? = null
    private var astClient: StreamingAstClient? = null
    private var watchdogJob: Job? = null
    private var active = false
    private var ready = false
    private var sessionId = ""
    private var currentProvider = ClassroomSpeechProvider.ASR
    private var config = ClassroomSpeechRecognitionConfig.Default
    private var runId = 0L
    private var segmentDurationMs = 60_000L
    private var sampleRate = 16_000
    private var bitsPerSample = 16
    private var channelCount = 1
    private var startedAtMs = 0L
    private var lastActivityAtMs = 0L
    private var lastFinalAtMs = 0L
    private var lastLogId = ""
    private var fallbackReason: String? = null
    private var sentPacketCount = 0L
    private var bufferedDropCount = 0
    private var lastAstSubtitleKey = ""
    private var lastAstSourceSubtitleKey = ""
    private var suppressClientClosedEvents = false

    // AST subtitle accumulation buffer
    private val astBuffer = StringBuilder()
    private var astBufferStartMs = 0L
    private var astBufferEndMs = 0L
    private var astBufferSegmentIndex = 1
    private var astBufferLogId = ""
    private var astBufferSequence: Int? = null
    private var astFlushJob: Job? = null

    fun start(
        runId: Long,
        segmentDurationMs: Long,
        sampleRate: Int,
        bitsPerSample: Int,
        channelCount: Int,
        config: ClassroomSpeechRecognitionConfig
    ) {
        this.runId = runId
        this.segmentDurationMs = segmentDurationMs.coerceAtLeast(1L)
        this.sampleRate = sampleRate
        this.bitsPerSample = bitsPerSample
        this.channelCount = channelCount
        this.config = config
        this.fallbackReason = null
        lastAstSubtitleKey = ""
        lastAstSourceSubtitleKey = ""
        astBuffer.clear()
        astFlushJob?.cancel()
        astFlushJob = null
        active = true
        startProvider(config.provider)
        startWatchdog()
    }

    fun submitAudio(frame: ClassroomAudioFrame): Boolean {
        if (!active) return false
        if (!ready) {
            bufferPreReadyFrame(frame)
            return true
        }
        val sent = when (currentProvider) {
            ClassroomSpeechProvider.ASR -> asrClient?.submitAudio(frame) == true
            ClassroomSpeechProvider.AST -> astClient?.submitAudio(frame) == true
        }
        if (sent) {
            sentPacketCount += 1
        } else {
            bufferPreReadyFrame(frame)
        }
        return sent
    }

    fun stop() {
        active = false
        watchdogJob?.cancel()
        watchdogJob = null
        flushAstBuffer()
        asrClient?.stop()
        astClient?.stop()
        asrClient = null
        astClient = null
        ready = false
        preReadyFrames.clear()
        ClassroomRealtimeDiagnostics.speech(
            "manager_stop provider=${currentProvider.value} session=$sessionId sent=$sentPacketCount bufferedDrops=$bufferedDropCount fallback=${fallbackReason.orEmpty()}"
        )
    }

    fun currentProvider(): ClassroomSpeechProvider = currentProvider

    fun currentFallbackReason(): String? = fallbackReason

    fun currentSessionId(): String = sessionId

    private fun startProvider(provider: ClassroomSpeechProvider) {
        stopCurrentClientOnly()
        currentProvider = provider
        ready = false
        sentPacketCount = 0L
        startedAtMs = System.currentTimeMillis()
        lastActivityAtMs = startedAtMs
        lastFinalAtMs = startedAtMs
        lastLogId = ""
        sessionId = "${provider.value}-${UUID.randomUUID()}"
        ClassroomRealtimeDiagnostics.speech(
            "provider_start provider=${provider.value} session=$sessionId sampleRate=$sampleRate bits=$bitsPerSample channels=$channelCount fallback=${fallbackReason.orEmpty()}"
        )
        onEvent(ClassroomSpeechSessionEvent.Connecting(provider, sessionId))
        when (provider) {
            ClassroomSpeechProvider.ASR -> startAsrClient()
            ClassroomSpeechProvider.AST -> startAstClientOrFallback()
        }
    }

    private fun startAsrClient() {
        if (!asrCredentials.isConfigured()) {
            handleProviderFailure(
                provider = ClassroomSpeechProvider.ASR,
                message = "未配置火山流式 ASR 凭据。",
                logId = "",
                retryable = false
            )
            return
        }
        asrClient = StreamingAsrClient(
            scope = scope,
            credentials = asrCredentials,
            clientInfo = clientInfo,
            onEvent = ::handleAsrEvent
        ).also { it.start(sampleRate, bitsPerSample, channelCount) }
    }

    private fun startAstClientOrFallback() {
        if (!astCredentials.isConfigured()) {
            handleProviderFailure(
                provider = ClassroomSpeechProvider.AST,
                message = "未配置火山 AST 凭据。",
                logId = "",
                retryable = false
            )
            return
        }
        astClient = StreamingAstClient(
            scope = scope,
            credentials = astCredentials,
            preset = config.astPreset,
            clientInfo = clientInfo,
            onEvent = ::handleAstEvent
        ).also { it.start() }
    }

    private fun handleAsrEvent(event: StreamingAsrEvent) {
        when (event) {
            is StreamingAsrEvent.Connecting -> {
                onEvent(ClassroomSpeechSessionEvent.Connecting(ClassroomSpeechProvider.ASR, sessionId))
            }
            is StreamingAsrEvent.Connected -> {
                lastLogId = event.logId
                markActivity()
            }
            is StreamingAsrEvent.Ready -> {
                lastLogId = event.logId
                markReady(ClassroomSpeechProvider.ASR, event.logId)
            }
            is StreamingAsrEvent.PartialText -> {
                lastLogId = event.logId
                markActivity()
                onEvent(
                    ClassroomSpeechSessionEvent.PartialText(
                        provider = ClassroomSpeechProvider.ASR,
                        text = event.text,
                        logId = event.logId,
                        sequence = event.sequence
                    )
                )
            }
            is StreamingAsrEvent.Utterance -> handleAsrUtterance(event)
            is StreamingAsrEvent.Error -> {
                handleProviderFailure(ClassroomSpeechProvider.ASR, event.message, event.logId, event.retryable)
            }
            is StreamingAsrEvent.Closed -> {
                if (!suppressClientClosedEvents) {
                    onEvent(ClassroomSpeechSessionEvent.Closed(ClassroomSpeechProvider.ASR, event.reason))
                }
            }
        }
    }

    private fun handleAsrUtterance(event: StreamingAsrEvent.Utterance) {
        val text = event.utterance.optString("text").trim()
        if (text.isBlank()) return
        lastLogId = event.logId
        markActivity()
        val startMs = event.utterance.optLong("start_time", 0L).coerceAtLeast(0L)
        val segmentIndex = ((startMs / segmentDurationMs).toInt() + 1).coerceAtLeast(1)
        val transcript = ClassroomAsrTranscriptMapper.fromUtteranceJson(
            runId = runId,
            segmentIndex = segmentIndex,
            utterance = event.utterance,
            globalOffsetMs = 0L,
            source = "live_asr",
            asrLogId = event.logId
        )
        if (transcript.isFinal) {
            lastFinalAtMs = System.currentTimeMillis()
            onEvent(
                ClassroomSpeechSessionEvent.FinalTranscript(
                    provider = ClassroomSpeechProvider.ASR,
                    transcript = transcript,
                    logId = event.logId,
                    sequence = event.sequence
                )
            )
        } else {
            onEvent(
                ClassroomSpeechSessionEvent.PartialText(
                    provider = ClassroomSpeechProvider.ASR,
                    text = text,
                    logId = event.logId,
                    sequence = event.sequence
                )
            )
        }
    }

    private fun handleAstEvent(event: StreamingAstEvent) {
        when (event) {
            is StreamingAstEvent.Connecting -> {
                onEvent(ClassroomSpeechSessionEvent.Connecting(ClassroomSpeechProvider.AST, event.sessionId))
            }
            is StreamingAstEvent.Connected -> {
                lastLogId = event.logId
                markActivity()
            }
            is StreamingAstEvent.Ready -> {
                lastLogId = event.logId
                sessionId = event.sessionId
                markReady(ClassroomSpeechProvider.AST, event.logId)
            }
            is StreamingAstEvent.Subtitle -> handleAstSubtitle(event)
            is StreamingAstEvent.AudioMuted -> {
                lastLogId = event.logId
                markActivity()
                ClassroomRealtimeDiagnostics.ast(
                    "audio_muted session=$sessionId mutedDurationMs=${event.mutedDurationMs} logId=${shortLogId(event.logId)}"
                )
            }
            is StreamingAstEvent.Usage -> {
                ClassroomRealtimeDiagnostics.ast("usage session=$sessionId ${event.message.take(160)}")
            }
            is StreamingAstEvent.Error -> {
                handleProviderFailure(ClassroomSpeechProvider.AST, event.message, event.logId, event.retryable)
            }
            is StreamingAstEvent.Closed -> {
                if (!suppressClientClosedEvents) {
                    onEvent(ClassroomSpeechSessionEvent.Closed(ClassroomSpeechProvider.AST, event.reason))
                }
            }
        }
    }

    private fun handleAstSubtitle(event: StreamingAstEvent.Subtitle) {
        if (!event.translated) {
            handleAstSourceSubtitle(event)
            return
        }
        val key = "translated:${event.startMs}:${event.endMs}:${event.text}:${event.isFinal}"
        if (key == lastAstSubtitleKey) return
        lastAstSubtitleKey = key
        lastLogId = event.logId
        markActivity()

        val endMs = event.endMs.takeIf { it > event.startMs }
            ?: (event.startMs + 1_000L)

        if (event.isFinal) {
            // TranslationSubtitleEnd: complete translation — flush buffer and use this text directly
            lastFinalAtMs = System.currentTimeMillis()
            astFlushJob?.cancel()
            astFlushJob = null
            astBuffer.clear()
            val segmentIndex = ((event.startMs / segmentDurationMs).toInt() + 1).coerceAtLeast(1)
            val transcript = ClassroomAsrTranscript(
                runId = runId,
                segmentIndex = segmentIndex,
                globalStartMs = event.startMs,
                globalEndMs = endMs,
                text = event.text,
                isFinal = true,
                words = emptyList(),
                source = "live_ast",
                asrLogId = event.logId
            )
            onEvent(
                ClassroomSpeechSessionEvent.FinalTranscript(
                    provider = ClassroomSpeechProvider.AST,
                    transcript = transcript,
                    logId = event.logId,
                    sequence = event.sequence
                )
            )
        } else {
            // TranslationSubtitleResponse: incremental fragment — accumulate and show as partial
            if (astBuffer.isEmpty()) {
                astBufferStartMs = event.startMs
                astBufferSegmentIndex = ((event.startMs / segmentDurationMs).toInt() + 1).coerceAtLeast(1)
            }
            astBuffer.append(event.text)
            astBufferEndMs = endMs
            astBufferLogId = event.logId
            astBufferSequence = event.sequence

            // Show accumulating buffer as partial text
            onEvent(
                ClassroomSpeechSessionEvent.PartialText(
                    provider = ClassroomSpeechProvider.AST,
                    text = astBuffer.toString(),
                    logId = event.logId,
                    sequence = event.sequence
                )
            )

            // Schedule delayed flush in case TranslationSubtitleEnd never arrives
            astFlushJob?.cancel()
            astFlushJob = scope.launch {
                delay(AST_BUFFER_FLUSH_DELAY_MS)
                flushAstBuffer()
            }
        }
    }

    private fun handleAstSourceSubtitle(event: StreamingAstEvent.Subtitle) {
        val key = "source:${event.startMs}:${event.endMs}:${event.text}:${event.isFinal}"
        if (key == lastAstSourceSubtitleKey) return
        lastAstSourceSubtitleKey = key
        lastLogId = event.logId
        markActivity()
        if (!event.isFinal) return
        val endMs = event.endMs.takeIf { it > event.startMs }
            ?: (event.startMs + 1_000L)
        val store = astSourceSubtitleStore ?: return
        store.append(
            ClassroomAstSourceSubtitle(
                runId = runId,
                startMs = event.startMs,
                endMs = endMs,
                text = event.text,
                isFinal = true,
                sequence = event.sequence,
                logId = event.logId
            )
        )
        ClassroomRealtimeDiagnostics.ast(
            "source_subtitle_saved run=$runId time=${event.startMs}-$endMs chars=${event.text.length} seq=${event.sequence ?: -1} logId=${shortLogId(event.logId)}"
        )
    }

    private fun flushAstBuffer() {
        if (astBuffer.isEmpty()) return
        val text = astBuffer.toString()
        astBuffer.clear()
        astFlushJob?.cancel()
        astFlushJob = null

        val transcript = ClassroomAsrTranscript(
            runId = runId,
            segmentIndex = astBufferSegmentIndex,
            globalStartMs = astBufferStartMs,
            globalEndMs = astBufferEndMs,
            text = text,
            isFinal = true,
            words = emptyList(),
            source = "live_ast",
            asrLogId = astBufferLogId
        )
        onEvent(
            ClassroomSpeechSessionEvent.FinalTranscript(
                provider = ClassroomSpeechProvider.AST,
                transcript = transcript,
                logId = astBufferLogId,
                sequence = astBufferSequence
            )
        )
    }

    private fun markReady(provider: ClassroomSpeechProvider, logId: String) {
        if (provider != currentProvider || !active) return
        ready = true
        markActivity()
        ClassroomRealtimeDiagnostics.speech(
            "provider_ready provider=${provider.value} session=$sessionId logId=${shortLogId(logId)} buffered=${preReadyFrames.size}"
        )
        onEvent(ClassroomSpeechSessionEvent.Ready(provider, sessionId, logId))
        flushPreReadyFrames()
    }

    private fun markActivity() {
        lastActivityAtMs = System.currentTimeMillis()
    }

    private fun bufferPreReadyFrame(frame: ClassroomAudioFrame) {
        if (preReadyFrames.size >= PRE_READY_BUFFER_CAPACITY) {
            preReadyFrames.removeFirst()
            bufferedDropCount += 1
        }
        preReadyFrames.addLast(frame)
        if (preReadyFrames.size <= 3 || preReadyFrames.size % 25 == 0) {
            ClassroomRealtimeDiagnostics.speech(
                "audio_buffered provider=${currentProvider.value} session=$sessionId buffered=${preReadyFrames.size} dropped=$bufferedDropCount frameSeq=${frame.sequence}"
            )
        }
    }

    private fun flushPreReadyFrames() {
        while (ready && preReadyFrames.isNotEmpty()) {
            val frame = preReadyFrames.removeFirst()
            val sent = when (currentProvider) {
                ClassroomSpeechProvider.ASR -> asrClient?.submitAudio(frame) == true
                ClassroomSpeechProvider.AST -> astClient?.submitAudio(frame) == true
            }
            if (!sent) {
                bufferPreReadyFrame(frame)
                break
            }
            sentPacketCount += 1
        }
    }

    private fun handleProviderFailure(
        provider: ClassroomSpeechProvider,
        message: String,
        logId: String,
        retryable: Boolean
    ) {
        ClassroomRealtimeDiagnostics.speechWarning(
            "provider_failure provider=${provider.value} session=$sessionId retryable=$retryable logId=${shortLogId(logId)} message=${message.take(160)}"
        )
        onEvent(ClassroomSpeechSessionEvent.Error(provider, message, logId, retryable))
        if (!active) return
        if (provider == ClassroomSpeechProvider.AST && config.fallbackEnabled) {
            activateFallback("AST 失败：${message.take(80)}")
        } else if (retryable) {
            restartCurrentProvider("provider_error:${message.take(80)}")
        }
    }

    private fun activateFallback(reason: String) {
        if (currentProvider == ClassroomSpeechProvider.ASR) return
        flushAstBuffer()
        fallbackReason = reason
        ClassroomRealtimeDiagnostics.speechWarning(
            "fallback_activated from=ast to=asr reason=${reason.take(160)} buffered=${preReadyFrames.size}"
        )
        onEvent(
            ClassroomSpeechSessionEvent.FallbackActivated(
                from = ClassroomSpeechProvider.AST,
                to = ClassroomSpeechProvider.ASR,
                reason = reason
            )
        )
        startProvider(ClassroomSpeechProvider.ASR)
    }

    private fun restartCurrentProvider(reason: String) {
        ClassroomRealtimeDiagnostics.speechWarning(
            "provider_restart provider=${currentProvider.value} session=$sessionId reason=${reason.take(120)} buffered=${preReadyFrames.size}"
        )
        startProvider(currentProvider)
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (active) {
                delay(WATCHDOG_INTERVAL_MS)
                val now = System.currentTimeMillis()
                if (!ready && now - startedAtMs >= READY_TIMEOUT_MS) {
                    Log.w(
                        ClassroomRealtimeDiagnostics.SPEECH_TAG,
                        "ready_timeout provider=${currentProvider.value} session=$sessionId elapsedMs=${now - startedAtMs}"
                    )
                    if (currentProvider == ClassroomSpeechProvider.AST && config.fallbackEnabled) {
                        activateFallback("AST ready 超时")
                    } else {
                        restartCurrentProvider("ready_timeout")
                    }
                    continue
                }
                if (ready && now - lastActivityAtMs >= NO_ACTIVITY_TIMEOUT_MS) {
                    ClassroomRealtimeDiagnostics.speechWarning(
                        "activity_timeout provider=${currentProvider.value} session=$sessionId elapsedMs=${now - lastActivityAtMs} logId=${shortLogId(lastLogId)}"
                    )
                    if (currentProvider == ClassroomSpeechProvider.AST && config.fallbackEnabled) {
                        activateFallback("AST 长时间无字幕事件")
                    } else {
                        restartCurrentProvider("activity_timeout")
                    }
                    continue
                }
                if (ready && now - lastFinalAtMs >= NO_FINAL_TIMEOUT_MS) {
                    ClassroomRealtimeDiagnostics.speechWarning(
                        "final_timeout provider=${currentProvider.value} session=$sessionId elapsedMs=${now - lastFinalAtMs} logId=${shortLogId(lastLogId)}"
                    )
                    if (currentProvider == ClassroomSpeechProvider.AST && config.fallbackEnabled) {
                        activateFallback("AST 长时间无稳定字幕")
                    } else {
                        restartCurrentProvider("final_timeout")
                    }
                }
            }
        }
    }

    private fun stopCurrentClientOnly() {
        suppressClientClosedEvents = true
        try {
            asrClient?.stop()
            astClient?.stop()
            asrClient = null
            astClient = null
        } finally {
            suppressClientClosedEvents = false
        }
    }

    private fun shortLogId(logId: String): String {
        return logId.takeLast(12).ifBlank { "-" }
    }

    private companion object {
        private const val PRE_READY_BUFFER_CAPACITY = 100
        private const val WATCHDOG_INTERVAL_MS = 2_000L
        private const val READY_TIMEOUT_MS = 12_000L
        private const val NO_ACTIVITY_TIMEOUT_MS = 45_000L
        private const val NO_FINAL_TIMEOUT_MS = 75_000L
        private const val AST_BUFFER_FLUSH_DELAY_MS = 800L
        private const val AST_BUFFER_LENGTH_THRESHOLD = 60
        private val AST_SENTENCE_ENDINGS = charArrayOf('。', '！', '？', '；', '.', '!', '?')
    }
}
