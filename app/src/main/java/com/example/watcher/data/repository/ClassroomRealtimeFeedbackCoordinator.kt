package com.example.watcher.data.repository

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.watcher.BuildConfig
import com.example.watcher.data.local.VideoSpeechTranscriptDao
import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeTree
import com.example.watcher.data.model.ClassroomKnowledgeTreeProcessingStatus
import com.example.watcher.data.model.ClassroomKnowledgeTreeProgress
import com.example.watcher.data.model.ClassroomSpeechProvider
import com.example.watcher.data.model.ClassroomSpeechRecognitionConfig
import com.example.watcher.data.model.VideoProcessTaskDraft
import com.example.watcher.data.remote.ContentItem
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException

internal class ClassroomRealtimeFeedbackCoordinator(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val transcriptDao: VideoSpeechTranscriptDao,
    private val apiService: DoubaoApiService,
    private val planningModel: String,
    private val apiKey: String,
    private val traceLogger: VideoAiTraceLogger,
    private val frameEvidenceCache: ClassroomFrameEvidenceCache? = null,
    private val astSourceSubtitleStore: ClassroomAstSourceSubtitleStore? = null,
    private val onUpdate: suspend (ClassroomRealtimeFeedbackState) -> Unit
) {
    private val queue = RealtimeAudioQueue(capacityFrames = REALTIME_QUEUE_CAPACITY)
    private val asrConfigRepository = AsrConfigRepository(appContext)
    private val astConfigRepository = AstConfigRepository(appContext)
    private var speechSession: ClassroomSpeechSessionManager? = null
    private var sendJob: Job? = null
    private var insightJob: Job? = null
    private var knowledgeTreeJob: Job? = null
    private var state = ClassroomRealtimeFeedbackState(enabled = true)
    private var runId: Long = 0L
    private var traceId: String = ""
    private var task: VideoProcessTaskDraft? = null
    private var segmentDurationMs: Long = 60_000L
    private var stableTranscript = StringBuilder()
    private val knowledgeTranscriptLines = mutableListOf<ClassroomKnowledgeTranscriptLine>()
    private var lastInsightTranscriptLength = 0
    private var lastInsightAtMs = 0L
    private var lastKnowledgeTreeConsumedLineIndex = 0
    private var lastKnowledgeTreeAtMs = 0L
    private var lastKnowledgeTreeSkipLogAtMs = 0L
    private var lastLoggedDroppedFrameCount = 0
    private var lastLoggedOfferedFrameCount = 0L
    private var sentAsrPacketCount = 0L
    private var partialEventCount = 0L
    private var utteranceEventCount = 0L
    private var finalUtteranceEventCount = 0L
    private var nonFinalUtteranceEventCount = 0L
    private var lastFinalUtteranceAtMs = 0L
    private var lastAsrActivityAtMs = 0L
    private var lastFinalStallLogAtMs = 0L
    private val knowledgeTreeUpdater = ClassroomKnowledgeTreeUpdater(
        apiService = apiService,
        model = planningModel,
        apiKey = apiKey,
        traceLogger = traceLogger
    )

    fun start(
        runId: Long,
        traceId: String,
        task: VideoProcessTaskDraft,
        sampleRate: Int,
        bitsPerSample: Int,
        channelCount: Int,
        speechConfig: ClassroomSpeechRecognitionConfig = ClassroomSpeechRecognitionConfig.Default
    ) {
        this.runId = runId
        this.traceId = traceId
        this.task = task
        this.segmentDurationMs = task.plannedSegmentDurationSeconds.coerceAtLeast(1) * 1_000L
        this.lastKnowledgeTreeAtMs = System.currentTimeMillis()
        this.lastKnowledgeTreeConsumedLineIndex = 0
        this.knowledgeTranscriptLines.clear()
        this.lastFinalUtteranceAtMs = System.currentTimeMillis()
        this.lastAsrActivityAtMs = System.currentTimeMillis()
        this.lastFinalStallLogAtMs = 0L
        val credentials = asrConfigRepository.resolveRuntimeCredentials(
            fallback = VolcengineAsrCredentials(
                appKey = BuildConfig.VOLCENGINE_ASR_APP_KEY,
                accessKey = BuildConfig.VOLCENGINE_ASR_ACCESS_KEY,
                resourceId = BuildConfig.VOLCENGINE_ASR_RESOURCE_ID
            )
        )
        val astCredentials = astConfigRepository.resolveRuntimeCredentials()
        if (!credentials.isConfigured() && !speechConfig.astEnabled) {
            updateState {
                it.copy(
                    enabled = false,
                    connectionState = ClassroomRealtimeConnectionState.Failed,
                    errorMessage = "未配置火山流式 ASR 凭据，实时字幕不可用。"
                )
            }
            return
        }
        logRealtimeTrace(
            phase = "speech_start",
            detail = "provider=${speechConfig.provider.value} fallback=${speechConfig.fallbackEnabled} sampleRate=$sampleRate bits=$bitsPerSample channels=$channelCount"
        )
        ClassroomRealtimeDiagnostics.speech(
            "coordinator_start run=$runId trace=$traceId provider=${speechConfig.provider.value} astConfigured=${astCredentials.isConfigured()} asrConfigured=${credentials.isConfigured()} sampleRate=$sampleRate bits=$bitsPerSample channels=$channelCount"
        )
        speechSession = ClassroomSpeechSessionManager(
            scope = scope,
            asrCredentials = credentials,
            astCredentials = astCredentials,
            clientInfo = buildClientInfo(),
            astSourceSubtitleStore = astSourceSubtitleStore,
            onEvent = ::handleSpeechEvent
        ).also {
            it.start(
                runId = runId,
                segmentDurationMs = segmentDurationMs,
                sampleRate = sampleRate,
                bitsPerSample = bitsPerSample,
                channelCount = channelCount,
                config = speechConfig
            )
        }
        sendJob = scope.launch(Dispatchers.IO) {
            val packetizer = RealtimeAudioPacketizer(targetDurationMs = ASR_PACKET_TARGET_DURATION_MS)
            while (true) {
                val frame = queue.poll(waitMs = 200L)
                if (frame == null) {
                    if (queue.snapshot().closed) break
                    continue
                }
                packetizer.add(frame).forEach(::sendAsrPacket)
            }
            packetizer.flush()?.let(::sendAsrPacket)
            ClassroomRealtimeDiagnostics.asr(
                "send_job_exit run=$runId sentPackets=$sentAsrPacketCount queueClosed=${queue.snapshot().closed} pending=${queue.snapshot().pendingFrameCount}"
            )
        }
    }

    fun offer(frame: ClassroomAudioFrame) {
        queue.offer(frame)
        val snapshot = queue.snapshot()
        if (
            snapshot.droppedFrameCount != lastLoggedDroppedFrameCount ||
            snapshot.offeredFrameCount - lastLoggedOfferedFrameCount >= 50
        ) {
            lastLoggedDroppedFrameCount = snapshot.droppedFrameCount
            lastLoggedOfferedFrameCount = snapshot.offeredFrameCount
            logRealtimeTrace(
                phase = "audio_queue",
                detail = "offered=${snapshot.offeredFrameCount} pending=${snapshot.pendingFrameCount} dropped=${snapshot.droppedFrameCount} frameSeq=${frame.sequence} frameDurationMs=${frame.durationMs}"
            )
        }
        updateState {
            it.copy(
                droppedFrameCount = snapshot.droppedFrameCount,
                pendingFrameCount = snapshot.pendingFrameCount,
                audioLagMs = (System.currentTimeMillis() - frame.capturedAtMs).coerceAtLeast(0L)
            )
        }
    }

    suspend fun stop() {
        queue.close()
        withTimeoutOrNull(1_500L) {
            sendJob?.join()
        }
        sendJob?.cancelAndJoin()
        sendJob = null
        speechSession?.stop()
        speechSession = null
        insightJob?.cancel()
        insightJob = null
        knowledgeTreeJob?.cancel()
        knowledgeTreeJob = null
        val snapshot = queue.snapshot()
        logRealtimeTrace(
            phase = "asr_stop",
            detail = "offered=${snapshot.offeredFrameCount} pending=${snapshot.pendingFrameCount} dropped=${snapshot.droppedFrameCount} stableChars=${stableTranscript.length}"
        )
        ClassroomRealtimeDiagnostics.asr(
            "coordinator_stop run=$runId offered=${snapshot.offeredFrameCount} pending=${snapshot.pendingFrameCount} dropped=${snapshot.droppedFrameCount} sentPackets=$sentAsrPacketCount partials=$partialEventCount utterances=$utteranceEventCount finalUtterances=$finalUtteranceEventCount nonFinalUtterances=$nonFinalUtteranceEventCount stableChars=${stableTranscript.length}"
        )
        updateState { it.copy(connectionState = ClassroomRealtimeConnectionState.Closed) }
    }

    suspend fun flushKnowledgeTree(reason: String = "stop_final"): Boolean {
        if (!ClassroomKnowledgeTreeUpdatePolicy.shouldFlushOnStop(
                currentTranscriptLength = knowledgeTranscriptLines.size,
                lastTranscriptLength = lastKnowledgeTreeConsumedLineIndex,
                hasKnowledgeTree = state.knowledgeTree != null
            )
        ) {
            ClassroomRealtimeDiagnostics.knowledgeTree(
                "final_update_skipped run=$runId reason=$reason inputLines=${knowledgeTranscriptLines.size} consumedLineIndex=$lastKnowledgeTreeConsumedLineIndex currentNodes=${ClassroomKnowledgeTreeParser.countNodes(state.knowledgeTree)}"
            )
            updateState {
                it.copy(
                    knowledgeTreeStatus = if (it.knowledgeTree != null) {
                        ClassroomKnowledgeTreeProcessingStatus.Completed
                    } else {
                        ClassroomKnowledgeTreeProcessingStatus.Waiting
                    }
                )
            }
            return false
        }
        knowledgeTreeJob?.cancelAndJoin()
        knowledgeTreeJob = null
        return withTimeoutOrNull(FINAL_KNOWLEDGE_TREE_TIMEOUT_MS) {
            var applied = false
            while (lastKnowledgeTreeConsumedLineIndex < knowledgeTranscriptLines.size) {
                val updated = runKnowledgeTreeUpdate(reason = reason)
                if (!updated) break
                applied = true
            }
            applied
        } ?: run {
            ClassroomRealtimeDiagnostics.knowledgeTreeWarning(
                "final_update_timeout run=$runId reason=$reason inputLines=${knowledgeTranscriptLines.size} consumedLineIndex=$lastKnowledgeTreeConsumedLineIndex timeoutMs=$FINAL_KNOWLEDGE_TREE_TIMEOUT_MS"
            )
            updateState { it.copy(knowledgeTreeStatus = ClassroomKnowledgeTreeProcessingStatus.Failed) }
            false
        }
    }

    fun latestState(): ClassroomRealtimeFeedbackState = state

    private fun sendAsrPacket(frame: ClassroomAudioFrame) {
        val sent = speechSession?.submitAudio(frame) == true
        if (!sent) {
            updateState {
                it.copy(
                    backfillSegmentCount = it.backfillSegmentCount + 1,
                    pendingFrameCount = queue.snapshot().pendingFrameCount
                )
            }
        } else {
            val snapshot = queue.snapshot()
            sentAsrPacketCount += 1
            if (sentAsrPacketCount <= 3 || sentAsrPacketCount % 25L == 0L) {
                logRealtimeTrace(
                    phase = "speech_packet",
                    detail = "sent=$sentAsrPacketCount durationMs=${frame.durationMs} bytes=${frame.pcm.size} pending=${snapshot.pendingFrameCount}"
                )
            }
            updateState {
                it.copy(
                    audioLagMs = (System.currentTimeMillis() - frame.capturedAtMs).coerceAtLeast(0L),
                    droppedFrameCount = snapshot.droppedFrameCount,
                    pendingFrameCount = snapshot.pendingFrameCount
                )
            }
        }
    }

    private fun handleSpeechEvent(event: ClassroomSpeechSessionEvent) {
        when (event) {
            is ClassroomSpeechSessionEvent.Connecting -> {
                logRealtimeTrace("speech_connecting", "provider=${event.provider.value} session=${event.sessionId}")
                ClassroomRealtimeDiagnostics.speech(
                    "event_connecting run=$runId provider=${event.provider.value} session=${event.sessionId}"
                )
                updateState {
                    it.copy(
                        connectionState = ClassroomRealtimeConnectionState.Connecting,
                        speechProvider = event.provider,
                        speechSessionId = event.sessionId,
                        asrLogId = event.logId,
                        errorMessage = null
                    )
                }
            }
            is ClassroomSpeechSessionEvent.Ready -> {
                logRealtimeTrace(
                    "speech_ready",
                    "provider=${event.provider.value} session=${event.sessionId} logId=${event.logId}"
                )
                ClassroomRealtimeDiagnostics.speech(
                    "event_ready run=$runId provider=${event.provider.value} session=${event.sessionId} logId=${shortAsrLogId(event.logId)}"
                )
                updateState {
                    it.copy(
                        connectionState = ClassroomRealtimeConnectionState.Connected,
                        speechProvider = event.provider,
                        speechSessionId = event.sessionId,
                        asrLogId = event.logId,
                        errorMessage = null
                    )
                }
            }
            is ClassroomSpeechSessionEvent.FinalTranscript -> handleFinalTranscript(event)
            is ClassroomSpeechSessionEvent.PartialText -> updateState {
                partialEventCount += 1
                lastAsrActivityAtMs = System.currentTimeMillis()
                if (partialEventCount <= 3L || partialEventCount % 20L == 0L) {
                    ClassroomRealtimeDiagnostics.speech(
                        "partial_event run=$runId provider=${event.provider.value} count=$partialEventCount seq=${event.sequence} chars=${event.text.length} logId=${shortAsrLogId(event.logId)} text=${event.text.take(80)}"
                    )
                }
                maybeLogAsrFinalStall(source = "partial", sequence = event.sequence, logId = event.logId)
                it.copy(
                    currentTranscript = event.text,
                    speechProvider = event.provider,
                    asrLogId = event.logId,
                    errorMessage = null
                )
            }
            is ClassroomSpeechSessionEvent.Error -> {
                logRealtimeTrace(
                    "speech_error",
                    "provider=${event.provider.value} retryable=${event.retryable} logId=${event.logId} message=${event.message.take(160)}"
                )
                ClassroomRealtimeDiagnostics.speechWarning(
                    "event_error run=$runId provider=${event.provider.value} retryable=${event.retryable} logId=${shortAsrLogId(event.logId)} message=${event.message.take(160)}"
                )
                updateState {
                    it.copy(
                        connectionState = if (event.retryable) {
                            ClassroomRealtimeConnectionState.Reconnecting
                        } else {
                            ClassroomRealtimeConnectionState.Failed
                        },
                        speechProvider = event.provider,
                        asrLogId = event.logId,
                        errorMessage = event.message
                    )
                }
            }
            is ClassroomSpeechSessionEvent.FallbackActivated -> {
                logRealtimeTrace(
                    "speech_fallback",
                    "from=${event.from.value} to=${event.to.value} reason=${event.reason.take(160)}"
                )
                updateState {
                    it.copy(
                        connectionState = ClassroomRealtimeConnectionState.Reconnecting,
                        speechProvider = event.to,
                        speechFallbackReason = event.reason,
                        errorMessage = "语音源已降级：${event.reason}"
                    )
                }
            }
            is ClassroomSpeechSessionEvent.Closed -> {
                logRealtimeTrace("speech_closed", "provider=${event.provider.value} reason=${event.reason.take(160)}")
                ClassroomRealtimeDiagnostics.speech(
                    "event_closed run=$runId provider=${event.provider.value} reason=${event.reason.take(160)}"
                )
                updateState {
                    it.copy(
                        connectionState = ClassroomRealtimeConnectionState.Closed,
                        speechProvider = event.provider
                    )
                }
            }
        }
    }

    private fun handleFinalTranscript(event: ClassroomSpeechSessionEvent.FinalTranscript) {
        val transcript = event.transcript
        val segmentIndex = transcript.segmentIndex ?: ((transcript.globalStartMs / segmentDurationMs).toInt() + 1)
        utteranceEventCount += 1
        lastAsrActivityAtMs = System.currentTimeMillis()
        if (transcript.isFinal) {
            finalUtteranceEventCount += 1
            lastFinalUtteranceAtMs = lastAsrActivityAtMs
        } else {
            nonFinalUtteranceEventCount += 1
        }
        ClassroomRealtimeDiagnostics.speech(
            "utterance_event run=$runId provider=${event.provider.value} count=$utteranceEventCount final=${transcript.isFinal} finalCount=$finalUtteranceEventCount nonFinalCount=$nonFinalUtteranceEventCount seq=${event.sequence} time=${transcript.globalStartMs}-${transcript.globalEndMs} chars=${transcript.text.length} logId=${shortAsrLogId(event.logId)} text=${transcript.text.take(80)}"
        )
        if (transcript.isFinal) {
            stableTranscript.appendLine(transcript.text)
            knowledgeTranscriptLines += ClassroomKnowledgeTranscriptLine(
                sequence = knowledgeTranscriptLines.size,
                startMs = transcript.globalStartMs,
                endMs = transcript.globalEndMs,
                text = transcript.text,
                source = transcript.source,
                asrLogId = transcript.asrLogId
            )
            scope.launch(Dispatchers.IO) {
                runCatching {
                    transcriptDao.insertAll(listOf(ClassroomAsrTranscriptMapper.toEntity(transcript)))
                }.onSuccess {
                    ClassroomRealtimeDiagnostics.speech(
                        "final_transcript_inserted run=$runId provider=${event.provider.value} segment=$segmentIndex time=${transcript.globalStartMs}-${transcript.globalEndMs} stableChars=${stableTranscript.length} logId=${shortAsrLogId(event.logId)}"
                    )
                }.onFailure { error ->
                    ClassroomRealtimeDiagnostics.speechWarning(
                        "final_transcript_insert_failed run=$runId provider=${event.provider.value} message=${error.message.orEmpty().take(160)} logId=${shortAsrLogId(event.logId)}"
                    )
                }
            }
            updateState {
                it.copy(
                    currentTranscript = transcript.text,
                    stableTranscript = stableTranscript.toString().trim(),
                    knowledgeTreeStatus = if (knowledgeTreeJob?.isActive == true) {
                        it.knowledgeTreeStatus
                    } else {
                        ClassroomKnowledgeTreeProcessingStatus.Waiting
                    },
                    lastDefiniteTimeMs = transcript.globalEndMs,
                    speechProvider = event.provider,
                    asrLogId = event.logId,
                    errorMessage = null
                )
            }
            maybeGenerateInsights()
            maybeUpdateKnowledgeTree()
        } else {
            if (nonFinalUtteranceEventCount <= 3L || nonFinalUtteranceEventCount % 20L == 0L) {
                ClassroomRealtimeDiagnostics.asrWarning(
                    "non_final_utterance_not_persisted run=$runId provider=${event.provider.value} nonFinalCount=$nonFinalUtteranceEventCount finalCount=$finalUtteranceEventCount seq=${event.sequence} logId=${shortAsrLogId(event.logId)}"
                )
            }
            maybeLogAsrFinalStall(source = "non_final_utterance", sequence = event.sequence, logId = event.logId)
            updateState {
                it.copy(
                    currentTranscript = transcript.text,
                    speechProvider = event.provider,
                    asrLogId = event.logId,
                    errorMessage = null
                )
            }
        }
    }

    private fun maybeLogAsrFinalStall(source: String, sequence: Int?, logId: String) {
        val now = System.currentTimeMillis()
        val noFinalForMs = now - lastFinalUtteranceAtMs
        if (noFinalForMs < ASR_FINAL_STALL_WARNING_MS) return
        if (now - lastFinalStallLogAtMs < ASR_FINAL_STALL_LOG_INTERVAL_MS) return
        lastFinalStallLogAtMs = now
        ClassroomRealtimeDiagnostics.asrWarning(
            "final_stall_detected run=$runId source=$source seq=${sequence ?: -1} noFinalForMs=$noFinalForMs lastActivityAgoMs=${now - lastAsrActivityAtMs} partials=$partialEventCount utterances=$utteranceEventCount finalUtterances=$finalUtteranceEventCount nonFinalUtterances=$nonFinalUtteranceEventCount stableChars=${stableTranscript.length} connection=${state.connectionState} logId=${shortAsrLogId(logId)}"
        )
    }

    private fun maybeUpdateKnowledgeTree() {
        val now = System.currentTimeMillis()
        val progress = buildKnowledgeTreeProgress(now)
        updateState { it.copy(knowledgeTreeProgress = progress) }
        if (!ClassroomKnowledgeTreeUpdatePolicy.shouldUpdate(
                nowMs = now,
                lastUpdateAtMs = lastKnowledgeTreeAtMs,
                currentTranscriptLength = progress.addedChars,
                lastTranscriptLength = 0,
                jobActive = progress.jobActive
            )
        ) {
            if (now - lastKnowledgeTreeSkipLogAtMs >= KNOWLEDGE_TREE_SKIP_LOG_INTERVAL_MS) {
                lastKnowledgeTreeSkipLogAtMs = now
                ClassroomRealtimeDiagnostics.knowledgeTree(
                    "update_skipped run=$runId unconsumedLines=${knowledgeTranscriptLines.size - lastKnowledgeTreeConsumedLineIndex} addedChars=${progress.addedChars} elapsedMs=${progress.elapsedMs} jobActive=${progress.jobActive} currentNodes=${ClassroomKnowledgeTreeParser.countNodes(state.knowledgeTree)}"
                )
            }
            return
        }
        knowledgeTreeJob = scope.launch(Dispatchers.IO) {
            runKnowledgeTreeUpdate(reason = "periodic")
        }
    }

    private suspend fun runKnowledgeTreeUpdate(reason: String): Boolean {
        val taskSnapshot = task ?: return false
        val finalFlush = reason != "periodic"
        val windowStartIndex = lastKnowledgeTreeConsumedLineIndex.coerceIn(0, knowledgeTranscriptLines.size)
        val transcriptWindow = knowledgeTranscriptLines.takeWindowFrom(
            startIndex = windowStartIndex,
            maxChars = ClassroomKnowledgeTreeUpdatePolicy.MAX_CONTEXT_CHARS
        )
        if (transcriptWindow.isBlank()) return false
        val consumedLineIndex = windowStartIndex + transcriptWindow.lines.size
        updateState {
            it.copy(
                knowledgeTreeStatus = ClassroomKnowledgeTreeProcessingStatus.Updating,
                knowledgeTreeProgress = buildKnowledgeTreeProgress(System.currentTimeMillis(), jobActiveOverride = true)
            )
        }
        ClassroomRealtimeDiagnostics.knowledgeTree(
            "update_scheduled run=$runId reason=$reason inputLines=${transcriptWindow.lines.size} windowMs=${transcriptWindow.startMs ?: -1}-${transcriptWindow.endMs ?: -1} consumedLineIndex=$lastKnowledgeTreeConsumedLineIndex unconsumedLines=${knowledgeTranscriptLines.size - lastKnowledgeTreeConsumedLineIndex} transcriptWindowChars=${transcriptWindow.charCount} currentNodes=${ClassroomKnowledgeTreeParser.countNodes(state.knowledgeTree)} insights=${state.liveInsights.size} finalFlush=$finalFlush"
        )
        ClassroomRealtimeDiagnostics.knowledgeTreeChunked(
            kind = if (reason == "periodic") "update_input" else "final_update_input",
            text = buildString {
                appendLine("run=$runId reason=$reason inputLines=${transcriptWindow.lines.size} windowMs=${transcriptWindow.startMs ?: -1}-${transcriptWindow.endMs ?: -1} consumedLineIndex=$lastKnowledgeTreeConsumedLineIndex")
                appendLine("recentInsights=${state.liveInsights.take(6).joinToString(" | ")}")
                appendLine(transcriptWindow.renderForPrompt())
            }
        )
        val update = runCatching {
            knowledgeTreeUpdater.update(
                traceId = traceId,
                runId = runId,
                task = taskSnapshot,
                currentTree = state.knowledgeTree,
                transcriptWindow = transcriptWindow,
                realtimeInsights = state.liveInsights,
                finalFlush = finalFlush
            )
        }.getOrElse { error ->
            ClassroomRealtimeDiagnostics.knowledgeTreeWarning(
                "update_failed run=$runId reason=$reason message=${error.message.orEmpty().take(160)} retryWillReuseLines=${knowledgeTranscriptLines.size - lastKnowledgeTreeConsumedLineIndex}"
            )
            updateState { it.copy(knowledgeTreeStatus = ClassroomKnowledgeTreeProcessingStatus.Failed) }
            return false
        }
        lastKnowledgeTreeAtMs = System.currentTimeMillis()
        if (update == null) {
            ClassroomRealtimeDiagnostics.knowledgeTreeWarning(
                "update_ignored run=$runId reason=$reason retryWillReuseLines=${knowledgeTranscriptLines.size - lastKnowledgeTreeConsumedLineIndex}"
            )
            updateState { it.copy(knowledgeTreeStatus = ClassroomKnowledgeTreeProcessingStatus.Failed) }
            return false
        }
        val changedIds = update.changedNodeIds
        val previousNodeIds = collectKnowledgeNodeIds(state.knowledgeTree)
        val newNodeIds = collectKnowledgeNodeIds(update.tree).filterNot(previousNodeIds::contains)
        lastKnowledgeTreeConsumedLineIndex = consumedLineIndex
        val frameRefs = frameEvidenceCache
            ?.representativeFramesForTree(runId = runId, tree = update.tree)
            .orEmpty()
        val representativeCandidateNodes = ClassroomFrameEvidencePolicy.collectRepresentativeCandidates(update.tree)
        val representativeCandidates = representativeCandidateNodes.size
        val representativeTimedNodes = representativeCandidateNodes.count {
            ClassroomFrameEvidencePolicy.representativeTargetMs(it) != null
        }
        val framesSkippedNoTime = (representativeCandidates - representativeTimedNodes).coerceAtLeast(0)
        ClassroomRealtimeDiagnostics.knowledgeTree(
            "update_applied run=$runId reason=$reason nodes=${ClassroomKnowledgeTreeParser.countNodes(update.tree)} active=${ClassroomKnowledgeTreeParser.countActiveNodes(update.tree)} changed=${changedIds.size} consumedLineIndex=$lastKnowledgeTreeConsumedLineIndex remainingLines=${knowledgeTranscriptLines.size - lastKnowledgeTreeConsumedLineIndex} validTimeNodes=${ClassroomKnowledgeTreeParser.countValidTimeNodes(update.tree)} zeroTimeNodes=${ClassroomKnowledgeTreeParser.countZeroTimeNodes(update.tree)} representativeCandidates=$representativeCandidates representativeTimedNodes=$representativeTimedNodes framesSkippedNoTime=$framesSkippedNoTime frames=${frameRefs.size} changedIds=${changedIds.joinToString(",").take(160)}"
        )
        updateState { current ->
            current.copy(
                knowledgeTree = update.tree,
                changedKnowledgeNodeIds = changedIds,
                newKnowledgeNodeIds = newNodeIds,
                knowledgeTreeStatus = ClassroomKnowledgeTreeProcessingStatus.Completed,
                knowledgeTreeProgress = buildKnowledgeTreeProgress(System.currentTimeMillis(), jobActiveOverride = false),
                knowledgeFrameRefs = frameRefs
            )
        }
        scheduleKnowledgeTreeHighlightClear(changedIds, newNodeIds)
        return true
    }

    private fun buildKnowledgeTreeProgress(
        nowMs: Long,
        jobActiveOverride: Boolean? = null
    ): ClassroomKnowledgeTreeProgress {
        val totalChars = knowledgeTranscriptLines.sumOf { it.text.length }
        val consumedChars = knowledgeTranscriptLines
            .take(lastKnowledgeTreeConsumedLineIndex.coerceIn(0, knowledgeTranscriptLines.size))
            .sumOf { it.text.length }
        return ClassroomKnowledgeTreeProgress(
            addedChars = (totalChars - consumedChars).coerceAtLeast(0),
            requiredChars = ClassroomKnowledgeTreeUpdatePolicy.MIN_ADDED_CHARS,
            elapsedMs = (nowMs - lastKnowledgeTreeAtMs).coerceAtLeast(0L),
            requiredIntervalMs = ClassroomKnowledgeTreeUpdatePolicy.INTERVAL_MS,
            jobActive = jobActiveOverride ?: (knowledgeTreeJob?.isActive == true)
        )
    }

    private fun collectKnowledgeNodeIds(tree: ClassroomKnowledgeTree?): Set<String> {
        fun collect(nodes: List<ClassroomKnowledgeNode>): Set<String> {
            return nodes.flatMap { node -> listOf(node.id) + collect(node.children) }.toSet()
        }
        return collect(tree?.nodes.orEmpty())
    }

    private fun scheduleKnowledgeTreeHighlightClear(changedIds: List<String>, newIds: List<String>) {
        if (changedIds.isEmpty() && newIds.isEmpty()) return
        scope.launch {
            delay(KNOWLEDGE_TREE_HIGHLIGHT_MS)
            updateState { current ->
                if (current.changedKnowledgeNodeIds == changedIds && current.newKnowledgeNodeIds == newIds) {
                    current.copy(
                        changedKnowledgeNodeIds = emptyList(),
                        newKnowledgeNodeIds = emptyList()
                    )
                } else {
                    current
                }
            }
        }
    }

    private fun maybeGenerateInsights() {
        val now = System.currentTimeMillis()
        val currentText = stableTranscript.toString().trim()
        if (currentText.length - lastInsightTranscriptLength < MIN_INSIGHT_CHARS) return
        if (now - lastInsightAtMs < INSIGHT_INTERVAL_MS) return
        if (insightJob?.isActive == true) return
        val taskSnapshot = task ?: return
        lastInsightAtMs = now
        lastInsightTranscriptLength = currentText.length
        insightJob = scope.launch(Dispatchers.IO) {
            val insights = runCatching {
                generateRealtimeInsights(taskSnapshot, currentText.takeLast(MAX_INSIGHT_CONTEXT_CHARS))
            }.getOrDefault(emptyList())
            if (insights.isNotEmpty()) {
                updateState { current ->
                    current.copy(liveInsights = (insights + current.liveInsights).distinct().take(MAX_LIVE_INSIGHTS))
                }
            }
        }
    }

    private suspend fun generateRealtimeInsights(
        task: VideoProcessTaskDraft,
        transcriptWindow: String
    ): List<String> {
        if (apiKey.isBlank()) return emptyList()
        val context = VideoAiTraceContext(
            traceId = traceId,
            runId = runId,
            taskId = task.taskId,
            node = "ClassroomRealtimeInsightGenerator",
            model = planningModel,
            requestKind = "classroom_realtime_insight"
        )
        val basePrompt = ClassroomPromptBuilder.realtimeInsightBasePrompt()
        val prompt = ClassroomPromptBuilder.realtimeInsightPrompt(task, transcriptWindow)
        val startedAt = System.currentTimeMillis()
        traceLogger.beginNode(context, aiTracePayload("transcriptLength" to transcriptWindow.length))
        traceLogger.logPrompt(context, basePrompt = basePrompt, renderedPrompt = prompt)
        traceLogger.logRequest(context, aiTracePayload("model" to planningModel, "promptLength" to prompt.length))
        return try {
            val rawText = retryRealtimeRemoteCall {
                apiService.analyzeIntent(
                    authorization = "Bearer $apiKey",
                    request = DoubaoRequest(
                        model = planningModel,
                        input = listOf(Message(role = "user", content = listOf(ContentItem(type = "input_text", text = prompt))))
                    )
                ).requireOutputText("classroom realtime insights")
            }
            val durationMs = System.currentTimeMillis() - startedAt
            traceLogger.logResponse(context, rawText, durationMs)
            val insights = parseInsights(rawText)
            traceLogger.logParsed(
                context = context,
                parsedSummary = insights.joinToString("；"),
                parsedJson = aiTracePayload("parseStatus" to "success", "insightCount" to insights.size),
                parseStatus = "success"
            )
            traceLogger.finishNode(context, durationMs)
            insights
        } catch (error: Throwable) {
            traceLogger.logError(context, error, System.currentTimeMillis() - startedAt)
            emptyList()
        }
    }

    private suspend fun <T> retryRealtimeRemoteCall(block: suspend () -> T): T {
        var lastError: Throwable? = null
        repeat(REMOTE_RETRY_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (error: Throwable) {
                if (error is CancellationException || !error.isRetryableRemoteFailure() || attempt == REMOTE_RETRY_ATTEMPTS - 1) {
                    throw error
                }
                lastError = error
                delay(REMOTE_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw lastError ?: IllegalStateException("Realtime insight call failed.")
    }

    private fun Throwable.isRetryableRemoteFailure(): Boolean {
        val text = message.orEmpty()
        return this is IOException ||
            text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true)
    }

    private fun parseInsights(rawText: String): List<String> {
        val jsonStart = rawText.indexOf('{')
        val jsonEnd = rawText.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val parsed = runCatching { JSONObject(rawText.substring(jsonStart, jsonEnd + 1)) }.getOrNull()
            val array = parsed?.optJSONArray("insights")
            if (array != null) {
                return buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index).trim().takeIf(String::isNotBlank)?.let { add(it) }
                    }
                }.take(MAX_LIVE_INSIGHTS)
            }
        }
        return rawText.lines()
            .map { line -> line.trim().trimStart('-', '•', '*', '1', '2', '3', '.', '、') }
            .filter { it.isNotBlank() }
            .take(MAX_LIVE_INSIGHTS)
    }

    private fun buildClientInfo(): VolcengineAsrWireProtocol.ClientInfo {
        val appVersion = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull().orEmpty()
        return VolcengineAsrWireProtocol.ClientInfo(
            uid = appContext.packageName,
            deviceId = Build.MODEL ?: "android",
            platform = "Android ${Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT}",
            appVersion = appVersion
        )
    }

    private fun updateState(transform: (ClassroomRealtimeFeedbackState) -> ClassroomRealtimeFeedbackState) {
        state = transform(state)
        scope.launch {
            onUpdate(state)
        }
    }

    private fun logRealtimeTrace(phase: String, detail: String) {
        if (traceId.isBlank()) return
        Log.d(
            VideoAiTraceFormatter.TAG,
            "trace=$traceId run=$runId node=ClassroomRealtimeAudio phase=$phase segment=0 chunk=0 kind=event model=speech durationMs=0 data=$detail"
        )
    }

    private fun shortAsrLogId(logId: String): String {
        return logId.takeLast(12).ifBlank { "-" }
    }

    private companion object {
        private const val REALTIME_QUEUE_CAPACITY = 80
        private const val INSIGHT_INTERVAL_MS = 25_000L
        private const val MIN_INSIGHT_CHARS = 80
        private const val MAX_INSIGHT_CONTEXT_CHARS = 1_200
        private const val MAX_LIVE_INSIGHTS = 6
        private const val KNOWLEDGE_TREE_HIGHLIGHT_MS = 8_000L
        private const val KNOWLEDGE_TREE_SKIP_LOG_INTERVAL_MS = 20_000L
        private const val FINAL_KNOWLEDGE_TREE_TIMEOUT_MS = 90_000L
        private const val ASR_PACKET_TARGET_DURATION_MS = 200L
        private const val ASR_FINAL_STALL_WARNING_MS = 60_000L
        private const val ASR_FINAL_STALL_LOG_INTERVAL_MS = 30_000L
        private const val REMOTE_RETRY_ATTEMPTS = 2
        private const val REMOTE_RETRY_DELAY_MS = 1_000L
    }
}
