package com.example.watcher.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal sealed interface StreamingAsrEvent {
    data class Connecting(val connectId: String) : StreamingAsrEvent
    data class Connected(val connectId: String, val logId: String) : StreamingAsrEvent
    data class Ready(val connectId: String, val logId: String) : StreamingAsrEvent
    data class Utterance(val utterance: JSONObject, val logId: String, val sequence: Int?) : StreamingAsrEvent
    data class PartialText(val text: String, val logId: String, val sequence: Int?) : StreamingAsrEvent
    data class Error(val message: String, val logId: String = "", val retryable: Boolean = true) : StreamingAsrEvent
    data class Closed(val reason: String) : StreamingAsrEvent
}

internal class StreamingAsrClient(
    private val scope: CoroutineScope,
    private val credentials: VolcengineAsrCredentials,
    private val clientInfo: VolcengineAsrWireProtocol.ClientInfo,
    private val onEvent: (StreamingAsrEvent) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    @Volatile private var isActive = false
    @Volatile private var socketOpen = false
    @Volatile private var sessionReady = false
    @Volatile private var currentLogId = ""
    @Volatile private var submittedAudioPacketCount = 0L
    @Volatile private var receivedBinaryFrameCount = 0L
    @Volatile private var receivedUtteranceCount = 0L
    @Volatile private var receivedFinalUtteranceCount = 0L
    @Volatile private var receivedPartialCount = 0L

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var sampleRate = 16_000
    private var bitsPerSample = 16
    private var channelCount = 1

    fun start(sampleRate: Int, bitsPerSample: Int, channelCount: Int) {
        if (!credentials.isConfigured()) {
            onEvent(StreamingAsrEvent.Error("未配置火山流式 ASR 凭据。", retryable = false))
            return
        }
        this.sampleRate = sampleRate
        this.bitsPerSample = bitsPerSample
        this.channelCount = channelCount
        isActive = true
        submittedAudioPacketCount = 0L
        receivedBinaryFrameCount = 0L
        receivedUtteranceCount = 0L
        receivedFinalUtteranceCount = 0L
        receivedPartialCount = 0L
        ClassroomRealtimeDiagnostics.asr(
            "client_start sampleRate=$sampleRate bits=$bitsPerSample channels=$channelCount"
        )
        connect()
    }

    fun submitAudio(frame: ClassroomAudioFrame): Boolean {
        if (!isActive || !socketOpen) {
            if (frame.sequence <= 3L || frame.sequence % 50L == 0L) {
                ClassroomRealtimeDiagnostics.asrWarning(
                    "submit_rejected seq=${frame.sequence} active=$isActive socketOpen=$socketOpen ready=$sessionReady logId=${shortLogId(currentLogId)}"
                )
            }
            return false
        }
        val payload = runCatching {
            VolcengineAsrWireProtocol.encodeAudioRequest(frame.pcm, isLast = false)
                .toByteString()
        }.getOrElse { error ->
            onEvent(StreamingAsrEvent.Error(error.message ?: "ASR 音频包编码失败", currentLogId))
            return false
        }
        val sent = webSocket?.send(payload) == true
        if (sent) {
            submittedAudioPacketCount += 1
            if (submittedAudioPacketCount <= 3L || submittedAudioPacketCount % 50L == 0L) {
                ClassroomRealtimeDiagnostics.asr(
                    "audio_sent count=$submittedAudioPacketCount seq=${frame.sequence} bytes=${frame.pcm.size} durationMs=${frame.durationMs} ready=$sessionReady logId=${shortLogId(currentLogId)}"
                )
            }
        } else {
            ClassroomRealtimeDiagnostics.asrWarning(
                "audio_send_failed seq=${frame.sequence} bytes=${frame.pcm.size} active=$isActive socketOpen=$socketOpen ready=$sessionReady logId=${shortLogId(currentLogId)}"
            )
        }
        return sent
    }

    fun stop() {
        ClassroomRealtimeDiagnostics.asr(
            "client_stop sent=$submittedAudioPacketCount receivedFrames=$receivedBinaryFrameCount utterances=$receivedUtteranceCount finalUtterances=$receivedFinalUtteranceCount partials=$receivedPartialCount logId=${shortLogId(currentLogId)}"
        )
        isActive = false
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching {
            webSocket?.send(
                VolcengineAsrWireProtocol.encodeAudioRequest(ByteArray(0), isLast = true)
                    .toByteString()
            )
        }
        runCatching { webSocket?.close(1000, "classroom stopped") }
        webSocket = null
        socketOpen = false
        sessionReady = false
        onEvent(StreamingAsrEvent.Closed("stopped"))
    }

    fun release() {
        stop()
        runCatching { client.dispatcher.executorService.shutdown() }
        runCatching { client.connectionPool.evictAll() }
    }

    private fun connect() {
        val connectId = UUID.randomUUID().toString()
        val request = Request.Builder()
            .url(VOLCENGINE_ASR_WS_URL)
            .header("X-Api-App-Key", credentials.appKey)
            .header("X-Api-Access-Key", credentials.accessKey)
            .header("X-Api-Resource-Id", credentials.resourceId)
            .header("X-Api-Connect-Id", connectId)
            .build()
        ClassroomRealtimeDiagnostics.asr("connect_start connectId=$connectId")
        onEvent(StreamingAsrEvent.Connecting(connectId))
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                currentLogId = response.header("X-Tt-Logid").orEmpty()
                socketOpen = true
                sessionReady = false
                ClassroomRealtimeDiagnostics.asr(
                    "connect_open connectId=$connectId logId=${shortLogId(currentLogId)}"
                )
                onEvent(StreamingAsrEvent.Connected(connectId, currentLogId))
                val initFrame = VolcengineAsrWireProtocol.encodeInitRequest(
                    clientInfo = clientInfo,
                    sampleRate = sampleRate,
                    bitsPerSample = bitsPerSample,
                    channelCount = channelCount
                )
                if (!webSocket.send(initFrame.toByteString())) {
                    ClassroomRealtimeDiagnostics.asrWarning(
                        "init_send_failed connectId=$connectId logId=${shortLogId(currentLogId)}"
                    )
                    onEvent(StreamingAsrEvent.Error("火山 ASR 初始化请求发送失败。", currentLogId))
                } else {
                    ClassroomRealtimeDiagnostics.asr(
                        "init_sent connectId=$connectId sampleRate=$sampleRate bits=$bitsPerSample channels=$channelCount logId=${shortLogId(currentLogId)}"
                    )
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleBinaryMessage(bytes.toByteArray(), connectId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                ClassroomRealtimeDiagnostics.asrWarning("unexpected_text_frame text=${text.take(120)}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socketOpen = false
                sessionReady = false
                val logId = response?.header("X-Tt-Logid").orEmpty().ifBlank { currentLogId }
                ClassroomRealtimeDiagnostics.asrWarning(
                    "socket_failure connectId=$connectId logId=${shortLogId(logId)} message=${t.message.orEmpty().take(160)}"
                )
                onEvent(StreamingAsrEvent.Error(mapAsrNetworkError(t), logId, retryable = true))
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketOpen = false
                sessionReady = false
                ClassroomRealtimeDiagnostics.asr(
                    "socket_closed connectId=$connectId code=$code reason=${reason.take(120)} active=$isActive logId=${shortLogId(currentLogId)}"
                )
                onEvent(StreamingAsrEvent.Closed(reason.ifBlank { "code=$code" }))
                if (isActive) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isActive) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            ClassroomRealtimeDiagnostics.asr("reconnect_scheduled delayMs=1500 logId=${shortLogId(currentLogId)}")
            delay(1_500L)
            if (isActive) connect()
        }
    }

    private fun handleBinaryMessage(frameBytes: ByteArray, connectId: String) {
        val frame = runCatching { VolcengineAsrWireProtocol.decode(frameBytes) }
            .getOrElse { error ->
                ClassroomRealtimeDiagnostics.asrWarning(
                    "decode_failed connectId=$connectId bytes=${frameBytes.size} message=${error.message.orEmpty().take(160)}"
                )
                onEvent(StreamingAsrEvent.Error("火山 ASR 返回无法解析的数据包：${error.message}", currentLogId))
                return
            }
        receivedBinaryFrameCount += 1
        if (receivedBinaryFrameCount <= 3L || receivedBinaryFrameCount % 25L == 0L) {
            ClassroomRealtimeDiagnostics.asr(
                "binary_received count=$receivedBinaryFrameCount connectId=$connectId type=${frame.messageType} seq=${frame.sequence} payloadLength=${frame.payloadText.length} logId=${shortLogId(currentLogId)}"
            )
        }
        when (frame.messageType) {
            VolcengineAsrWireProtocol.MESSAGE_TYPE_FULL_SERVER_RESPONSE -> parseServerResponse(frame, connectId)
            VolcengineAsrWireProtocol.MESSAGE_TYPE_ERROR_RESPONSE -> {
                socketOpen = false
                sessionReady = false
                ClassroomRealtimeDiagnostics.asrWarning(
                    "server_error_frame connectId=$connectId code=${frame.errorCode ?: -1} payload=${frame.payloadText.take(160)} logId=${shortLogId(currentLogId)}"
                )
                onEvent(StreamingAsrEvent.Error(parseServerError(frame), currentLogId))
                if (isActive) webSocket?.cancel()
            }
        }
    }

    private fun parseServerResponse(frame: VolcengineAsrWireProtocol.DecodedFrame, connectId: String) {
        val payload = runCatching {
            VolcengineAsrWireProtocol.parseResponsePayload(frame.payloadText)
        }.getOrElse {
            ClassroomRealtimeDiagnostics.asrWarning("payload_not_json text=${frame.payloadText.take(160)}")
            return
        }
        if (!VolcengineAsrWireProtocol.isSuccessCode(payload.code)) {
            socketOpen = false
            sessionReady = false
            ClassroomRealtimeDiagnostics.asrWarning(
                "payload_error code=${payload.code} message=${VolcengineAsrWireProtocol.extractResponseMessage(payload, payload.code).take(160)} logId=${shortLogId(currentLogId)}"
            )
            onEvent(
                StreamingAsrEvent.Error(
                    VolcengineAsrWireProtocol.extractResponseMessage(payload, payload.code),
                    currentLogId
                )
            )
            if (isActive) webSocket?.cancel()
            return
        }
        if (!sessionReady) {
            sessionReady = true
            ClassroomRealtimeDiagnostics.asr("session_ready connectId=$connectId logId=${shortLogId(currentLogId)}")
            onEvent(StreamingAsrEvent.Ready(connectId, currentLogId))
        }
        val result = payload.result ?: return
        val utterances = result.optJSONArray("utterances")
        if (utterances != null) {
            var finalCount = 0
            var nonFinalCount = 0
            for (index in 0 until utterances.length()) {
                val utterance = utterances.optJSONObject(index) ?: continue
                if (utterance.optString("text").isNotBlank()) {
                    receivedUtteranceCount += 1
                    if (utterance.optBoolean("definite", false)) {
                        receivedFinalUtteranceCount += 1
                        finalCount += 1
                    } else {
                        nonFinalCount += 1
                    }
                    onEvent(StreamingAsrEvent.Utterance(utterance, currentLogId, frame.sequence))
                }
            }
            ClassroomRealtimeDiagnostics.asr(
                "utterances_received seq=${frame.sequence} count=${utterances.length()} final=$finalCount nonFinal=$nonFinalCount totalFinal=$receivedFinalUtteranceCount logId=${shortLogId(currentLogId)}"
            )
        } else {
            result.optString("text").trim().takeIf(String::isNotBlank)?.let { text ->
                receivedPartialCount += 1
                if (receivedPartialCount <= 3L || receivedPartialCount % 20L == 0L) {
                    ClassroomRealtimeDiagnostics.asr(
                        "partial_received count=$receivedPartialCount seq=${frame.sequence} chars=${text.length} logId=${shortLogId(currentLogId)} text=${text.take(80)}"
                    )
                }
                onEvent(StreamingAsrEvent.PartialText(text, currentLogId, frame.sequence))
            }
        }
    }

    private fun parseServerError(frame: VolcengineAsrWireProtocol.DecodedFrame): String {
        val parsed = runCatching {
            val payload = VolcengineAsrWireProtocol.parseResponsePayload(frame.payloadText)
            VolcengineAsrWireProtocol.extractResponseMessage(payload, frame.errorCode)
        }.getOrDefault(frame.payloadText)
        return "火山语音识别错误(${frame.errorCode ?: -1}): ${parsed.ifBlank { "未知错误" }}"
    }

    private fun shortLogId(logId: String): String {
        return logId.takeLast(12).ifBlank { "-" }
    }
}
