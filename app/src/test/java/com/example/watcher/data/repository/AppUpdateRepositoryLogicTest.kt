package com.example.watcher.data.repository

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class AppUpdateRepositoryLogicTest {
    @Test
    fun prefersVersionCodeWhenPresent() {
        val result = isAppUpdateAvailable(
            currentVersion = "1.0.99",
            currentVersionCode = 4,
            latestVersion = "1.0.1",
            latestVersionCode = 5
        )

        assertTrue(result)
    }

    @Test
    fun doesNotUpdateWhenLatestVersionCodeIsNotHigher() {
        val result = isAppUpdateAvailable(
            currentVersion = "1.0.4",
            currentVersionCode = 4,
            latestVersion = "9.9.9",
            latestVersionCode = 4
        )

        assertFalse(result)
    }

    @Test
    fun fallsBackToSemanticTokensWhenVersionCodeMissing() {
        val result = isAppUpdateAvailable(
            currentVersion = "1.0.4",
            currentVersionCode = 4,
            latestVersion = "1.0.5",
            latestVersionCode = null
        )

        assertTrue(result)
    }

    @Test
    fun comparesVersionTokensBySegment() {
        assertEquals(
            1,
            compareVersionTokensForUpdate(listOf(1, 0, 10), listOf(1, 0, 4))
        )
    }

    @Test
    fun parsesVerifiedSignedPayload() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val payload = """
            {
              "versionName":"1.0.8",
              "versionCode":8,
              "apkUrl":"http://www.shokz-watcher.cn/download/app/watcher-v1.0.8-8-release.apk",
              "apkSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              "publishedAt":"2026-05-06T12:00:00Z",
              "minSupportedVersionCode":1,
              "releaseNotes":"Bug fixes"
            }
        """.trimIndent()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(payload.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val envelope = """
            {
              "payload": ${Gson().toJson(payload)},
              "signature": "$signature",
              "algorithm": "SHA256withRSA"
            }
        """.trimIndent()
        val publicKeyPem = buildPublicPem(keyPair.public.encoded)

        val verified = parseVerifiedAppUpdatePayload(envelope, publicKeyPem)

        assertNotNull(verified)
        assertEquals(8L, verified?.versionCode)
        assertEquals("1.0.8", verified?.versionName)
        assertEquals("Bug fixes", verified?.releaseNotes)
    }

    @Test
    fun rejectsTamperedSignedPayload() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val originalPayload = """
            {"versionName":"1.0.8","versionCode":8,"apkUrl":"http://www.shokz-watcher.cn/download/app/a.apk","apkSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","publishedAt":"2026-05-06T12:00:00Z"}
        """.trimIndent()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(originalPayload.toByteArray(Charsets.UTF_8))
            Base64.getEncoder().encodeToString(sign())
        }
        val tamperedEnvelope = """
            {
              "payload": "{\"versionName\":\"9.9.9\",\"versionCode\":999,\"apkUrl\":\"http://www.shokz-watcher.cn/download/app/a.apk\",\"apkSha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"publishedAt\":\"2026-05-06T12:00:00Z\"}",
              "signature": "$signature",
              "algorithm": "SHA256withRSA"
            }
        """.trimIndent()
        val publicKeyPem = buildPublicPem(keyPair.public.encoded)

        val verified = parseVerifiedAppUpdatePayload(tamperedEnvelope, publicKeyPem)

        assertNull(verified)
    }

    @Test
    fun parsesIsoPublishedAt() {
        val epochMillis = parsePublishedAtEpochMillis("2026-05-06T12:00:00Z")

        assertEquals(1778068800000L, epochMillis)
    }

    private fun buildPublicPem(encoded: ByteArray): String {
        val base64 = Base64.getEncoder().encodeToString(encoded)
        return "-----BEGIN PUBLIC KEY-----\n$base64\n-----END PUBLIC KEY-----"
    }
}
