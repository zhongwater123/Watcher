package com.example.watcher.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONObject

internal object VolcengineAsrWireProtocol {
    const val MESSAGE_TYPE_FULL_CLIENT_REQUEST = 0x1
    const val MESSAGE_TYPE_AUDIO_ONLY_REQUEST = 0x2
    const val MESSAGE_TYPE_FULL_SERVER_RESPONSE = 0x9
    const val MESSAGE_TYPE_ERROR_RESPONSE = 0xF

    const val FLAG_NONE = 0x0
    const val FLAG_SEQUENCE_POSITIVE = 0x1
    const val FLAG_LAST_PACKET = 0x2
    const val FLAG_SEQUENCE_NEGATIVE = 0x3

    const val SERIALIZATION_NONE = 0x0
    const val SERIALIZATION_JSON = 0x1

    const val COMPRESSION_NONE = 0x0
    const val COMPRESSION_GZIP = 0x1

    private const val PROTOCOL_VERSION = 0x1
    private const val HEADER_SIZE_WORDS = 0x1
    private const val HEADER_SIZE_BYTES = HEADER_SIZE_WORDS * 4

    data class DecodedFrame(
        val messageType: Int,
        val flags: Int,
        val serialization: Int,
        val compression: Int,
        val sequence: Int?,
        val errorCode: Int?,
        val payload: ByteArray
    ) {
        val payloadText: String
            get() = payload.toString(Charsets.UTF_8)

        val isLastPacket: Boolean
            get() = flags == FLAG_LAST_PACKET || flags == FLAG_SEQUENCE_NEGATIVE
    }

    data class ClientInfo(
        val uid: String,
        val deviceId: String,
        val platform: String,
        val appVersion: String,
        val sdkVersion: String = "watcher-volcengine-asr-v1"
    )

    data class ParsedResponsePayload(
        val code: Int,
        val message: String?,
        val error: String?,
        val result: JSONObject?
    )

    fun encodeFullClientRequest(jsonPayload: String): ByteArray {
        val payload = gzip(jsonPayload.toByteArray(Charsets.UTF_8))
        return encodeFrame(
            messageType = MESSAGE_TYPE_FULL_CLIENT_REQUEST,
            flags = FLAG_NONE,
            serialization = SERIALIZATION_JSON,
            compression = COMPRESSION_GZIP,
            sequence = null,
            payload = payload
        )
    }

    fun encodeInitRequest(
        clientInfo: ClientInfo,
        sampleRate: Int,
        bitsPerSample: Int,
        channelCount: Int
    ): ByteArray {
        return encodeFullClientRequest(
            buildInitPayload(
                clientInfo = clientInfo,
                sampleRate = sampleRate,
                bitsPerSample = bitsPerSample,
                channelCount = channelCount
            )
        )
    }

    fun encodeAudioRequest(audioPayload: ByteArray, isLast: Boolean): ByteArray {
        val payload = gzip(audioPayload)
        return encodeFrame(
            messageType = MESSAGE_TYPE_AUDIO_ONLY_REQUEST,
            flags = if (isLast) FLAG_LAST_PACKET else FLAG_NONE,
            serialization = SERIALIZATION_NONE,
            compression = COMPRESSION_GZIP,
            sequence = null,
            payload = payload
        )
    }

    fun decode(frame: ByteArray): DecodedFrame {
        require(frame.size >= HEADER_SIZE_BYTES) { "Frame is too short to contain protocol header." }

        val version = (frame[0].toInt() ushr 4) and 0x0F
        require(version == PROTOCOL_VERSION) { "Unsupported protocol version: $version" }

        val headerSizeBytes = (frame[0].toInt() and 0x0F) * 4
        require(frame.size >= headerSizeBytes) { "Frame is shorter than declared header size." }

        val messageType = (frame[1].toInt() ushr 4) and 0x0F
        val flags = frame[1].toInt() and 0x0F
        val serialization = (frame[2].toInt() ushr 4) and 0x0F
        val compression = frame[2].toInt() and 0x0F

        var offset = headerSizeBytes
        var sequence: Int? = null
        var errorCode: Int? = null

        if (messageType == MESSAGE_TYPE_FULL_SERVER_RESPONSE && flags.hasSequenceNumber()) {
            sequence = readInt(frame, offset)
            offset += 4
        } else if (messageType == MESSAGE_TYPE_ERROR_RESPONSE) {
            errorCode = readInt(frame, offset)
            offset += 4
        }

        val payloadSize = readInt(frame, offset)
        offset += 4
        require(offset + payloadSize <= frame.size) { "Frame payload size exceeds available bytes." }
        val encodedPayload = frame.copyOfRange(offset, offset + payloadSize)
        val payload = when (compression) {
            COMPRESSION_GZIP -> gunzip(encodedPayload)
            COMPRESSION_NONE -> encodedPayload
            else -> error("Unsupported payload compression: $compression")
        }

        return DecodedFrame(
            messageType = messageType,
            flags = flags,
            serialization = serialization,
            compression = compression,
            sequence = sequence,
            errorCode = errorCode,
            payload = payload
        )
    }

    fun encodeServerResponseForTest(
        payloadText: String,
        sequence: Int,
        isLast: Boolean = false
    ): ByteArray {
        val payload = gzip(payloadText.toByteArray(Charsets.UTF_8))
        return encodeFrame(
            messageType = MESSAGE_TYPE_FULL_SERVER_RESPONSE,
            flags = if (isLast) FLAG_SEQUENCE_NEGATIVE else FLAG_SEQUENCE_POSITIVE,
            serialization = SERIALIZATION_JSON,
            compression = COMPRESSION_GZIP,
            sequence = sequence,
            payload = payload
        )
    }

    fun encodeServerResponseWithoutSequenceForTest(payloadText: String): ByteArray {
        val payload = gzip(payloadText.toByteArray(Charsets.UTF_8))
        return encodeFrame(
            messageType = MESSAGE_TYPE_FULL_SERVER_RESPONSE,
            flags = FLAG_NONE,
            serialization = SERIALIZATION_JSON,
            compression = COMPRESSION_GZIP,
            sequence = null,
            payload = payload
        )
    }

    fun encodeErrorResponseForTest(errorCode: Int, payloadText: String): ByteArray {
        val payload = payloadText.toByteArray(Charsets.UTF_8)
        return encodeFrame(
            messageType = MESSAGE_TYPE_ERROR_RESPONSE,
            flags = FLAG_NONE,
            serialization = SERIALIZATION_JSON,
            compression = COMPRESSION_NONE,
            sequence = errorCode,
            payload = payload
        )
    }

    fun buildInitPayload(
        clientInfo: ClientInfo,
        sampleRate: Int,
        bitsPerSample: Int,
        channelCount: Int
    ): String {
        return JSONObject().apply {
            put("user", JSONObject().apply {
                put("uid", clientInfo.uid)
                put("did", clientInfo.deviceId)
                put("platform", clientInfo.platform)
                put("sdk_version", clientInfo.sdkVersion)
                put("app_version", clientInfo.appVersion)
            })
            put("audio", JSONObject().apply {
                put("format", "pcm")
                put("codec", "raw")
                put("rate", sampleRate)
                put("bits", bitsPerSample)
                put("channel", channelCount)
                put("language", "zh-CN")
            })
            put("request", JSONObject().apply {
                put("model_name", "bigmodel")
                put("enable_nonstream", true)
                put("enable_itn", true)
                put("enable_punc", true)
                put("enable_ddc", false)
                put("show_utterances", true)
                put("result_type", "single")
                put("end_window_size", 800)
            })
        }.toString()
    }

    fun parseResponsePayload(payloadText: String): ParsedResponsePayload {
        val payload = JSONObject(payloadText)
        return ParsedResponsePayload(
            code = payload.optInt("code", 20000000),
            message = payload.optString("message").takeIf { it.isNotBlank() },
            error = payload.optString("error").takeIf { it.isNotBlank() },
            result = payload.optJSONObject("result")
                ?: payload.optJSONArray("result")?.optJSONObject(0)
        )
    }

    fun isSuccessCode(code: Int): Boolean = code == 0 || code == 20000000

    fun extractResponseMessage(
        payload: ParsedResponsePayload,
        fallbackCode: Int? = null
    ): String {
        return payload.message
            ?: payload.error
            ?: fallbackCode?.let { "火山语音识别失败，错误码: $it" }
            ?: "火山语音识别失败"
    }

    private fun encodeFrame(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
        sequence: Int?,
        payload: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(buildHeader(messageType, flags, serialization, compression))
        if (
            (messageType == MESSAGE_TYPE_FULL_SERVER_RESPONSE && flags.hasSequenceNumber()) ||
            messageType == MESSAGE_TYPE_ERROR_RESPONSE
        ) {
            out.write(intToBytes(sequence ?: 0))
        }
        out.write(intToBytes(payload.size))
        out.write(payload)
        return out.toByteArray()
    }

    private fun buildHeader(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int
    ): ByteArray {
        return byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or HEADER_SIZE_WORDS).toByte(),
            ((messageType shl 4) or flags).toByte(),
            ((serialization shl 4) or compression).toByte(),
            0x00
        )
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        require(offset + 4 <= bytes.size) { "Not enough bytes to decode int at offset $offset." }
        return ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
    }

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(value)
            .array()
    }

    private fun Int.hasSequenceNumber(): Boolean {
        return this == FLAG_SEQUENCE_POSITIVE || this == FLAG_SEQUENCE_NEGATIVE
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gzip ->
            gzip.write(data)
        }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        if (data.isEmpty()) {
            return data
        }
        return GZIPInputStream(ByteArrayInputStream(data)).use { gzip ->
            gzip.readBytes()
        }
    }
}
