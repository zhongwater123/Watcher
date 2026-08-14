package com.example.watcher.data.repository

import data.speech.ast.AstService
import data.speech.common.Rpcmeta
import data.speech.event.Events
import data.speech.understanding.AuBase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

internal sealed interface StreamingAstEvent {
    data class Connecting(val connectId: String, val sessionId: String) : StreamingAstEvent
    data class Connected(val connectId: String, val sessionId: String, val logId: String) : StreamingAstEvent
    data class Ready(val connectId: String, val sessionId: String, val logId: String) : StreamingAstEvent
    data class Subtitle(
        val text: String,
        val startMs: Long,
        val endMs: Long,
        val translated: Boolean,
        val isFinal: Boolean,
        val logId: String,
        val sequence: Int?
    ) : StreamingAstEvent
    data class AudioMuted(val mutedDurationMs: Int, val logId: String) : StreamingAstEvent
    data class Usage(val message: String, val logId: String) : StreamingAstEvent
    data class Error(val message: String, val logId: String = "", val retryable: Boolean = true) : StreamingAstEvent
    data class Closed(val reason: String) : StreamingAstEvent
}

internal class StreamingAstClient(
    private val scope: CoroutineScope,
    private val credentials: VolcengineAstCredentials,
    private val preset: String,
    private val clientInfo: VolcengineAsrWireProtocol.ClientInfo,
    private val onEvent: (StreamingAstEvent) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    @Volatile private var isActive = false
    @Volatile private var socketOpen = false
    @Volatile private var sessionReady = false
    @Volatile private var currentLogId = ""
    @Volatile private var submittedAudioPacketCount = 0L
    @Volatile private var receivedFrameCount = 0L

    private val connectId = UUID.randomUUID().toString()
    private val sessionId = UUID.randomUUID().toString()
    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var sequence = 0

    fun start() {
        if (!credentials.isConfigured()) {
            onEvent(StreamingAstEvent.Error("未配置火山 AST 凭据。", retryable = false))
            return
        }
        isActive = true
        socketOpen = false
        sessionReady = false
        submittedAudioPacketCount = 0L
        receivedFrameCount = 0L
        ClassroomRealtimeDiagnostics.ast(
            "client_start connectId=$connectId sessionId=$sessionId auth=${credentials.authMode.value} preset=$preset"
        )
        connect()
    }

    fun submitAudio(frame: ClassroomAudioFrame): Boolean {
        if (!isActive || !socketOpen || !sessionReady) {
            if (frame.sequence <= 3L || frame.sequence % 50L == 0L) {
                ClassroomRealtimeDiagnostics.astWarning(
                    "submit_rejected seq=${frame.sequence} active=$isActive socketOpen=$socketOpen ready=$sessionReady logId=${shortLogId(currentLogId)}"
                )
            }
            return false
        }
        val astFrame = ClassroomPcmAudioConverter.toAst16kMono(frame)
        val request = AstService.TranslateRequest.newBuilder()
            .setRequestMeta(requestMeta(nextSequence()))
            .setEvent(Events.Type.TaskRequest)
            .setSourceAudio(
                AuBase.Audio.newBuilder()
                    .setBinaryData(com.google.protobuf.ByteString.copyFrom(astFrame.pcm))
                    .build()
            )
            .build()
        val sent = webSocket?.send(request.toByteArray().toByteString()) == true
        if (sent) {
            submittedAudioPacketCount += 1
            if (submittedAudioPacketCount <= 3L || submittedAudioPacketCount % 50L == 0L) {
                ClassroomRealtimeDiagnostics.ast(
                    "audio_sent count=$submittedAudioPacketCount seq=${frame.sequence} bytes=${astFrame.pcm.size} durationMs=${astFrame.durationMs} logId=${shortLogId(currentLogId)}"
                )
            }
        } else {
            ClassroomRealtimeDiagnostics.astWarning(
                "audio_send_failed seq=${frame.sequence} bytes=${astFrame.pcm.size} active=$isActive socketOpen=$socketOpen ready=$sessionReady logId=${shortLogId(currentLogId)}"
            )
        }
        return sent
    }

    fun stop() {
        ClassroomRealtimeDiagnostics.ast(
            "client_stop sent=$submittedAudioPacketCount received=$receivedFrameCount ready=$sessionReady logId=${shortLogId(currentLogId)}"
        )
        isActive = false
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching {
            webSocket?.send(
                AstService.TranslateRequest.newBuilder()
                    .setRequestMeta(requestMeta(nextSequence()))
                    .setEvent(Events.Type.FinishSession)
                    .build()
                    .toByteArray()
                    .toByteString()
            )
        }
        runCatching { webSocket?.close(1000, "classroom stopped") }
        webSocket = null
        socketOpen = false
        sessionReady = false
        onEvent(StreamingAstEvent.Closed("stopped"))
    }

    fun release() {
        stop()
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private fun connect() {
        val requestBuilder = Request.Builder()
            .url(VOLCENGINE_AST_WS_URL)
            .header("X-Api-Resource-Id", credentials.resourceId)
            .header("X-Api-Connect-Id", connectId)
        when (credentials.authMode) {
            VolcengineAstAuthMode.ApiKey -> requestBuilder.header("X-Api-Key", credentials.apiKey)
            VolcengineAstAuthMode.AppKeyAccessKey -> requestBuilder
                .header("X-Api-App-Key", credentials.appKey)
                .header("X-Api-Access-Key", credentials.accessKey)
        }
        ClassroomRealtimeDiagnostics.ast("connect_start connectId=$connectId sessionId=$sessionId")
        onEvent(StreamingAstEvent.Connecting(connectId, sessionId))
        webSocket = client.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                currentLogId = response.header("X-Tt-Logid").orEmpty()
                socketOpen = true
                sessionReady = false
                ClassroomRealtimeDiagnostics.ast(
                    "connect_open connectId=$connectId sessionId=$sessionId logId=${shortLogId(currentLogId)}"
                )
                onEvent(StreamingAstEvent.Connected(connectId, sessionId, currentLogId))
                val startRequest = AstService.TranslateRequest.newBuilder()
                    .setRequestMeta(requestMeta(nextSequence()))
                    .setEvent(Events.Type.StartSession)
                    .setUser(
                        AuBase.User.newBuilder()
                            .setUid(clientInfo.uid)
                            .setDid(clientInfo.deviceId)
                            .setPlatform(clientInfo.platform)
                            .setAppVersion(clientInfo.appVersion)
                            .build()
                    )
                    .setSourceAudio(
                        AuBase.Audio.newBuilder()
                            .setFormat("wav")
                            .setCodec("raw")
                            .setRate(AST_SAMPLE_RATE)
                            .setBits(16)
                            .setChannel(1)
                            .build()
                    )
                    .setRequest(
                        AstService.ReqParams.newBuilder()
                            .setMode("s2t")
                            .setSourceLanguage(preset)
                            .setTargetLanguage(preset)
                            .setEnableSourceLanguageDetect(true)
                            .build()
                    )
                    .build()
                if (!webSocket.send(startRequest.toByteArray().toByteString())) {
                    ClassroomRealtimeDiagnostics.astWarning(
                        "start_session_send_failed connectId=$connectId sessionId=$sessionId logId=${shortLogId(currentLogId)}"
                    )
                    onEvent(StreamingAstEvent.Error("AST StartSession 发送失败。", currentLogId))
                } else {
                    ClassroomRealtimeDiagnostics.ast(
                        "start_session_sent connectId=$connectId sessionId=$sessionId preset=$preset logId=${shortLogId(currentLogId)}"
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                ClassroomRealtimeDiagnostics.astWarning("unexpected_text_frame text=${text.take(120)}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketOpen = false
                sessionReady = false
                val logId = response?.header("X-Tt-Logid").orEmpty().ifBlank { currentLogId }
                ClassroomRealtimeDiagnostics.astWarning(
                    "socket_failure connectId=$connectId sessionId=$sessionId logId=${shortLogId(logId)} message=${t.message.orEmpty().take(160)}"
                )
                onEvent(StreamingAstEvent.Error(mapAsrNetworkError(t), logId, retryable = true))
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketOpen = false
                sessionReady = false
                ClassroomRealtimeDiagnostics.ast(
                    "socket_closed connectId=$connectId sessionId=$sessionId code=$code reason=${reason.take(120)} active=$isActive logId=${shortLogId(currentLogId)}"
                )
                onEvent(StreamingAstEvent.Closed(reason.ifBlank { "code=$code" }))
                if (isActive) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isActive) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            ClassroomRealtimeDiagnostics.ast("reconnect_scheduled delayMs=1500 logId=${shortLogId(currentLogId)}")
            delay(1_500L)
            if (isActive) connect()
        }
    }

    private fun handleBinaryMessage(frameBytes: ByteArray) {
        val response = runCatching { AstService.TranslateResponse.parseFrom(frameBytes) }
            .getOrElse { error ->
                ClassroomRealtimeDiagnostics.astWarning(
                    "decode_failed bytes=${frameBytes.size} message=${error.message.orEmpty().take(160)}"
                )
                onEvent(StreamingAstEvent.Error("AST 返回无法解析的数据包：${error.message}", currentLogId))
                return
            }
        receivedFrameCount += 1
        val meta = if (response.hasResponseMeta()) response.responseMeta else null
        val sequence = meta?.sequence
        val message = meta?.message.orEmpty()
        if (receivedFrameCount <= 3L || receivedFrameCount % 25L == 0L) {
            ClassroomRealtimeDiagnostics.ast(
                "frame_received count=$receivedFrameCount event=${response.event} seq=${sequence ?: -1} status=${meta?.statusCode ?: 0} chars=${response.text.length} logId=${shortLogId(currentLogId)}"
            )
        }
        when (response.event) {
            Events.Type.SessionStarted -> {
                sessionReady = true
                ClassroomRealtimeDiagnostics.ast(
                    "session_ready connectId=$connectId sessionId=$sessionId logId=${shortLogId(currentLogId)}"
                )
                onEvent(StreamingAstEvent.Ready(connectId, sessionId, currentLogId))
            }
            Events.Type.SessionFailed -> {
                socketOpen = false
                sessionReady = false
                val detail = message.ifBlank { "AST SessionFailed status=${meta?.statusCode ?: -1}" }
                ClassroomRealtimeDiagnostics.astWarning(
                    "session_failed sessionId=$sessionId status=${meta?.statusCode ?: -1} message=${detail.take(160)} logId=${shortLogId(currentLogId)}"
                )
                onEvent(StreamingAstEvent.Error("AST 会话失败：$detail", currentLogId, retryable = true))
                if (isActive) webSocket?.cancel()
            }
            Events.Type.SessionCanceled,
            Events.Type.SessionFinished -> {
                socketOpen = false
                sessionReady = false
                onEvent(StreamingAstEvent.Closed(response.event.name))
            }
            Events.Type.AudioMuted -> {
                onEvent(StreamingAstEvent.AudioMuted(response.mutedDurationMs, currentLogId))
            }
            Events.Type.UsageResponse -> {
                onEvent(StreamingAstEvent.Usage(response.toString().take(240), currentLogId))
            }
            Events.Type.TranslationSubtitleResponse,
            Events.Type.SourceSubtitleResponse -> {
                val text = response.text.trim()
                if (text.isNotBlank()) {
                    onEvent(
                        StreamingAstEvent.Subtitle(
                            text = text,
                            startMs = response.startTime.toLong().coerceAtLeast(0L),
                            endMs = response.endTime.toLong().coerceAtLeast(response.startTime.toLong().coerceAtLeast(0L)),
                            translated = response.event == Events.Type.TranslationSubtitleResponse,
                            isFinal = false,
                            logId = currentLogId,
                            sequence = sequence
                        )
                    )
                }
            }
            Events.Type.TranslationSubtitleEnd,
            Events.Type.SourceSubtitleEnd -> {
                val text = response.text.trim()
                if (text.isNotBlank()) {
                    onEvent(
                        StreamingAstEvent.Subtitle(
                            text = text,
                            startMs = response.startTime.toLong().coerceAtLeast(0L),
                            endMs = response.endTime.toLong().coerceAtLeast(response.startTime.toLong().coerceAtLeast(0L)),
                            translated = response.event == Events.Type.TranslationSubtitleEnd,
                            isFinal = true,
                            logId = currentLogId,
                            sequence = sequence
                        )
                    )
                }
            }
            else -> Unit
        }
    }

    private fun requestMeta(sequence: Int): Rpcmeta.RequestMeta {
        return Rpcmeta.RequestMeta.newBuilder()
            .setSessionID(sessionId)
            .setConnectionID(connectId)
            .setResourceID(credentials.resourceId)
            .setSequence(sequence)
            .build()
    }

    private fun nextSequence(): Int {
        sequence += 1
        return sequence
    }

    private fun shortLogId(logId: String): String {
        return logId.takeLast(12).ifBlank { "-" }
    }

    companion object {
        const val AST_SAMPLE_RATE = 16_000

        suspend fun testCredentials(credentials: VolcengineAstCredentials): String {
            val ready = CompletableDeferred<String>()
            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
            var astClient: StreamingAstClient? = null
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            astClient = StreamingAstClient(
                scope = scope,
                credentials = credentials,
                preset = com.example.watcher.data.model.ClassroomSpeechRecognitionConfig.AST_PRESET_ZH_EN_MIXED,
                clientInfo = VolcengineAsrWireProtocol.ClientInfo(
                    uid = "watcher_ast_test",
                    deviceId = "watcher_ast_test",
                    platform = "Android",
                    appVersion = "test"
                ),
                onEvent = { event ->
                    when (event) {
                        is StreamingAstEvent.Ready -> ready.complete(event.logId)
                        is StreamingAstEvent.Error -> ready.completeExceptionally(IllegalStateException(event.message))
                        else -> Unit
                    }
                },
                client = client
            )
            return try {
                astClient.start()
                val logId = withTimeout(8_000L) { ready.await() }
                "AST 连通性测试成功，logId=${logId.takeLast(12).ifBlank { "-" }}"
            } finally {
                runCatching { astClient?.stop() }
                runCatching { client.dispatcher.executorService.shutdown() }
                runCatching { client.connectionPool.evictAll() }
            }
        }
    }
}

internal object ClassroomPcmAudioConverter {
    fun toAst16kMono(frame: ClassroomAudioFrame): ClassroomAudioFrame {
        if (frame.sampleRate == StreamingAstClient.AST_SAMPLE_RATE &&
            frame.channelCount == 1 &&
            frame.bitsPerSample == 16
        ) {
            return frame
        }
        val converted = resample16BitPcm(
            source = frame.pcm,
            sourceRate = frame.sampleRate.coerceAtLeast(1),
            sourceChannels = frame.channelCount.coerceAtLeast(1),
            targetRate = StreamingAstClient.AST_SAMPLE_RATE
        )
        return frame.copy(
            pcm = converted,
            sampleRate = StreamingAstClient.AST_SAMPLE_RATE,
            channelCount = 1,
            bitsPerSample = 16
        )
    }

    private fun resample16BitPcm(
        source: ByteArray,
        sourceRate: Int,
        sourceChannels: Int,
        targetRate: Int
    ): ByteArray {
        if (source.isEmpty()) return source
        val bytesPerSample = 2
        val sourceFrameCount = source.size / (bytesPerSample * sourceChannels)
        if (sourceFrameCount <= 0) return ByteArray(0)
        val targetFrameCount = ((sourceFrameCount.toLong() * targetRate) / sourceRate).toInt().coerceAtLeast(1)
        val out = ByteArray(targetFrameCount * bytesPerSample)
        for (targetIndex in 0 until targetFrameCount) {
            val sourceIndex = ((targetIndex.toLong() * sourceRate) / targetRate)
                .toInt()
                .coerceIn(0, sourceFrameCount - 1)
            val sourceByteIndex = sourceIndex * sourceChannels * bytesPerSample
            val outByteIndex = targetIndex * bytesPerSample
            out[outByteIndex] = source.getOrElse(sourceByteIndex) { 0 }
            out[outByteIndex + 1] = source.getOrElse(sourceByteIndex + 1) { 0 }
        }
        return out
    }
}
