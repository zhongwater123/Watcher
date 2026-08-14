package com.example.watcher.data.repository

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class VolcengineAsrWireProtocolTest {
    @Test
    fun encodesFullClientRequestWithJsonAndGzip() {
        val json = """{"request":{"model_name":"bigmodel"}}"""

        val decoded = VolcengineAsrWireProtocol.decode(
            VolcengineAsrWireProtocol.encodeFullClientRequest(json)
        )

        assertEquals(VolcengineAsrWireProtocol.MESSAGE_TYPE_FULL_CLIENT_REQUEST, decoded.messageType)
        assertEquals(VolcengineAsrWireProtocol.SERIALIZATION_JSON, decoded.serialization)
        assertEquals(VolcengineAsrWireProtocol.COMPRESSION_GZIP, decoded.compression)
        assertEquals(json, decoded.payloadText)
    }

    @Test
    fun encodesAudioRequestWithLastPacketFlag() {
        val audio = byteArrayOf(1, 2, 3, 4, 5)

        val decoded = VolcengineAsrWireProtocol.decode(
            VolcengineAsrWireProtocol.encodeAudioRequest(audio, isLast = true)
        )

        assertEquals(VolcengineAsrWireProtocol.MESSAGE_TYPE_AUDIO_ONLY_REQUEST, decoded.messageType)
        assertEquals(VolcengineAsrWireProtocol.FLAG_LAST_PACKET, decoded.flags)
        assertTrue(decoded.isLastPacket)
        assertArrayEquals(audio, decoded.payload)
    }

    @Test
    fun decodesServerResponseSequenceAndPayload() {
        val payload = """{"result":{"text":"你好，Watcher"}}"""

        val decoded = VolcengineAsrWireProtocol.decode(
            VolcengineAsrWireProtocol.encodeServerResponseForTest(
                payloadText = payload,
                sequence = 7,
                isLast = true
            )
        )

        assertEquals(VolcengineAsrWireProtocol.MESSAGE_TYPE_FULL_SERVER_RESPONSE, decoded.messageType)
        assertEquals(7, decoded.sequence)
        assertEquals(VolcengineAsrWireProtocol.FLAG_SEQUENCE_NEGATIVE, decoded.flags)
        assertTrue(decoded.isLastPacket)
        assertEquals(payload, decoded.payloadText)
    }

    @Test
    fun decodesServerResponseWithoutSequenceWhenFlagIsNone() {
        val payload = """{"code":0,"result":{"text":"ready"}}"""

        val decoded = VolcengineAsrWireProtocol.decode(
            VolcengineAsrWireProtocol.encodeServerResponseWithoutSequenceForTest(payload)
        )

        assertEquals(VolcengineAsrWireProtocol.MESSAGE_TYPE_FULL_SERVER_RESPONSE, decoded.messageType)
        assertEquals(VolcengineAsrWireProtocol.FLAG_NONE, decoded.flags)
        assertNull(decoded.sequence)
        assertEquals(payload, decoded.payloadText)
    }

    @Test
    fun decodesServerErrorFrame() {
        val decoded = VolcengineAsrWireProtocol.decode(
            VolcengineAsrWireProtocol.encodeErrorResponseForTest(
                errorCode = 45000081,
                payloadText = """{"message":"timeout"}"""
            )
        )

        assertEquals(VolcengineAsrWireProtocol.MESSAGE_TYPE_ERROR_RESPONSE, decoded.messageType)
        assertEquals(45000081, decoded.errorCode)
        assertEquals("""{"message":"timeout"}""", decoded.payloadText)
    }

    @Test
    fun encodesSharedInitRequestPayload() {
        val decoded = VolcengineAsrWireProtocol.decode(
            VolcengineAsrWireProtocol.encodeInitRequest(
                clientInfo = VolcengineAsrWireProtocol.ClientInfo(
                    uid = "com.example.watcher",
                    deviceId = "pixel",
                    platform = "Android 15",
                    appVersion = "1.0.4"
                ),
                sampleRate = 16000,
                bitsPerSample = 16,
                channelCount = 1
            )
        )

        val payload = JSONObject(decoded.payloadText)
        assertEquals(VolcengineAsrWireProtocol.MESSAGE_TYPE_FULL_CLIENT_REQUEST, decoded.messageType)
        assertEquals("com.example.watcher", payload.getJSONObject("user").getString("uid"))
        assertEquals("bigmodel", payload.getJSONObject("request").getString("model_name"))
        assertEquals(16000, payload.getJSONObject("audio").getInt("rate"))
    }

    @Test
    fun parsesSuccessfulResponsePayload() {
        val parsed = VolcengineAsrWireProtocol.parseResponsePayload(
            """{"code":0,"result":{"text":"hello"}}"""
        )

        assertTrue(VolcengineAsrWireProtocol.isSuccessCode(parsed.code))
        assertNotNull(parsed.result)
        assertEquals("hello", parsed.result?.getString("text"))
    }

    @Test
    fun extractsErrorMessageFromParsedPayload() {
        val parsed = VolcengineAsrWireProtocol.parseResponsePayload(
            """{"code":45000081,"message":"resource invalid"}"""
        )

        assertFalse(VolcengineAsrWireProtocol.isSuccessCode(parsed.code))
        assertEquals(
            "resource invalid",
            VolcengineAsrWireProtocol.extractResponseMessage(parsed, parsed.code)
        )
    }
}
