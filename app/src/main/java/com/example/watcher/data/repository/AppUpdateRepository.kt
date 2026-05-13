package com.example.watcher.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.watcher.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.concurrent.TimeUnit

data class AppUpdatePrompt(
    val currentVersion: String,
    val latestVersion: String,
    val downloadPageUrl: String,
    val downloadUrl: String?,
    val updatedAt: String?,
    val apkSha256: String,
    val releaseNotes: String?,
    val isVerified: Boolean
)

private data class SignedAppUpdateEnvelope(
    val payload: String? = null,
    val signature: String? = null,
    val algorithm: String? = null
)

private data class SignedAppUpdatePayload(
    val versionName: String? = null,
    val versionCode: Long? = null,
    val apkUrl: String? = null,
    val apkSha256: String? = null,
    val publishedAt: String? = null,
    val minSupportedVersionCode: Long? = null,
    val releaseNotes: String? = null
)

internal data class VerifiedAppUpdatePayload(
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val apkSha256: String,
    val publishedAt: String,
    val publishedAtEpochMillis: Long,
    val minSupportedVersionCode: Long?,
    val releaseNotes: String?
)

internal fun isAppUpdateAvailable(
    currentVersion: String,
    currentVersionCode: Long,
    latestVersion: String,
    latestVersionCode: Long?
): Boolean {
    if (latestVersionCode != null) {
        return latestVersionCode > currentVersionCode
    }

    val currentTokens = extractVersionTokensForUpdate(currentVersion)
    val latestTokens = extractVersionTokensForUpdate(latestVersion)

    if (currentTokens.isNotEmpty() && latestTokens.isNotEmpty()) {
        return compareVersionTokensForUpdate(latestTokens, currentTokens) > 0
    }

    return false
}

internal fun extractVersionTokensForUpdate(value: String): List<Int> {
    return VERSION_NUMBER_REGEX_FOR_UPDATE.findAll(value)
        .mapNotNull { it.value.toIntOrNull() }
        .toList()
}

internal fun compareVersionTokensForUpdate(left: List<Int>, right: List<Int>): Int {
    val maxSize = maxOf(left.size, right.size)
    repeat(maxSize) { index ->
        val leftValue = left.getOrElse(index) { 0 }
        val rightValue = right.getOrElse(index) { 0 }
        if (leftValue != rightValue) {
            return leftValue.compareTo(rightValue)
        }
    }
    return 0
}

class AppUpdateRepository(
    private val context: Context,
    private val gson: Gson = Gson()
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(): Result<AppUpdatePrompt?> = withContext(Dispatchers.IO) {
        runCatching {
            val publicKeyPem = BuildConfig.APP_UPDATE_PUBLIC_KEY_PEM.trim()
            if (publicKeyPem.isBlank()) {
                return@runCatching null
            }

            val request = Request.Builder()
                .url(METADATA_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Update metadata request failed: HTTP ${response.code}")
                }

                val body = response.body?.string().orEmpty()
                val metadata = parseVerifiedAppUpdatePayload(
                    rawJson = body,
                    publicKeyPem = publicKeyPem,
                    gson = gson
                ) ?: return@use null

                if (isReplayOrDowngrade(metadata)) {
                    return@use null
                }

                val currentVersion = currentVersionName()
                val updateAvailable = isUpdateAvailable(
                    currentVersion = currentVersion,
                    currentVersionCode = currentVersionCode(),
                    latestVersion = metadata.versionName,
                    latestVersionCode = metadata.versionCode
                )

                if (!updateAvailable) {
                    return@use null
                }

                rememberTrustedMetadata(metadata)

                AppUpdatePrompt(
                    currentVersion = currentVersion,
                    latestVersion = normalizeVersionLabel(metadata.versionName),
                    downloadPageUrl = DOWNLOAD_PAGE_URL,
                    downloadUrl = resolveAbsoluteUrl(metadata.apkUrl),
                    updatedAt = metadata.publishedAt,
                    apkSha256 = metadata.apkSha256,
                    releaseNotes = metadata.releaseNotes?.trim()?.takeIf { it.isNotBlank() },
                    isVerified = true
                )
            }
        }
    }

    private fun isUpdateAvailable(
        currentVersion: String,
        currentVersionCode: Long,
        latestVersion: String,
        latestVersionCode: Long?
    ): Boolean {
        return isAppUpdateAvailable(
            currentVersion = currentVersion,
            currentVersionCode = currentVersionCode,
            latestVersion = latestVersion,
            latestVersionCode = latestVersionCode
        )
    }

    private fun currentVersionName(): String {
        return packageInfo().versionName?.trim().takeUnless { it.isNullOrBlank() } ?: "0"
    }

    private fun currentVersionCode(): Long {
        val packageInfo = packageInfo()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    private fun packageInfo() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.PackageInfoFlags.of(0)
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    private fun resolveAbsoluteUrl(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return null
        }
        return runCatching { URI(DOWNLOAD_PAGE_URL).resolve(trimmed).toString() }
            .getOrDefault(trimmed)
    }

    private fun normalizeVersionLabel(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
    }

    private fun isReplayOrDowngrade(metadata: VerifiedAppUpdatePayload): Boolean {
        val seenVersionCode = preferences.getLong(KEY_HIGHEST_TRUSTED_VERSION_CODE, 0L)
        val seenPublishedAt = preferences.getLong(KEY_HIGHEST_TRUSTED_PUBLISHED_AT, 0L)
        return when {
            metadata.versionCode < seenVersionCode -> true
            metadata.versionCode == seenVersionCode && metadata.publishedAtEpochMillis < seenPublishedAt -> true
            else -> false
        }
    }

    private fun rememberTrustedMetadata(metadata: VerifiedAppUpdatePayload) {
        val seenVersionCode = preferences.getLong(KEY_HIGHEST_TRUSTED_VERSION_CODE, 0L)
        val seenPublishedAt = preferences.getLong(KEY_HIGHEST_TRUSTED_PUBLISHED_AT, 0L)
        if (
            metadata.versionCode > seenVersionCode ||
            (metadata.versionCode == seenVersionCode && metadata.publishedAtEpochMillis > seenPublishedAt)
        ) {
            preferences.edit()
                .putLong(KEY_HIGHEST_TRUSTED_VERSION_CODE, metadata.versionCode)
                .putLong(KEY_HIGHEST_TRUSTED_PUBLISHED_AT, metadata.publishedAtEpochMillis)
                .apply()
        }
    }

    private companion object {
        const val DOWNLOAD_PAGE_URL = "http://www.shokz-watcher.cn/app/"
        const val METADATA_URL = "http://www.shokz-watcher.cn/app/latest.json"
        const val PREFS_NAME = "watcher_app_updates"
        const val KEY_HIGHEST_TRUSTED_VERSION_CODE = "highest_trusted_version_code"
        const val KEY_HIGHEST_TRUSTED_PUBLISHED_AT = "highest_trusted_published_at"
    }
}

private val VERSION_NUMBER_REGEX_FOR_UPDATE = Regex("\\d+")
private val SHA256_HEX_REGEX_FOR_UPDATE = Regex("^[0-9a-f]{64}$")
private val SUPPORTED_SIGNATURE_ALGORITHMS = setOf("SHA256withRSA", "SHA256withECDSA")

internal fun parseVerifiedAppUpdatePayload(
    rawJson: String,
    publicKeyPem: String,
    gson: Gson = Gson()
): VerifiedAppUpdatePayload? {
    if (publicKeyPem.isBlank()) {
        return null
    }

    val envelope = try {
        gson.fromJson(rawJson, SignedAppUpdateEnvelope::class.java)
    } catch (_: JsonSyntaxException) {
        return null
    } ?: return null

    val payloadJson = envelope.payload?.takeUnless { it.isBlank() } ?: return null
    val signatureBase64 = envelope.signature?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val algorithm = envelope.algorithm?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    if (algorithm !in SUPPORTED_SIGNATURE_ALGORITHMS) {
        return null
    }

    val publicKey = parseAppUpdatePublicKey(publicKeyPem, algorithm) ?: return null
    if (!verifyAppUpdateSignature(payloadJson, signatureBase64, algorithm, publicKey)) {
        return null
    }

    val payload = try {
        gson.fromJson(payloadJson, SignedAppUpdatePayload::class.java)
    } catch (_: JsonSyntaxException) {
        return null
    } ?: return null

    val versionCode = payload.versionCode?.takeIf { it > 0 } ?: return null
    val versionName = payload.versionName?.trim().takeUnless { it.isNullOrBlank() }
        ?: versionCode.toString()
    val apkUrl = payload.apkUrl?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val apkSha256 = payload.apkSha256
        ?.trim()
        ?.lowercase()
        ?.takeIf { SHA256_HEX_REGEX_FOR_UPDATE.matches(it) }
        ?: return null
    val publishedAt = payload.publishedAt?.trim().takeUnless { it.isNullOrBlank() } ?: return null
    val publishedAtEpochMillis = parsePublishedAtEpochMillis(publishedAt) ?: return null
    val minSupportedVersionCode = payload.minSupportedVersionCode?.takeIf { it >= 0 }

    return VerifiedAppUpdatePayload(
        versionName = versionName,
        versionCode = versionCode,
        apkUrl = apkUrl,
        apkSha256 = apkSha256,
        publishedAt = publishedAt,
        publishedAtEpochMillis = publishedAtEpochMillis,
        minSupportedVersionCode = minSupportedVersionCode,
        releaseNotes = payload.releaseNotes
    )
}

internal fun parsePublishedAtEpochMillis(value: String): Long? {
    value.toLongOrNull()?.let { numeric ->
        return if (numeric < 10_000_000_000L) numeric * 1000L else numeric
    }
    return try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

internal fun parseAppUpdatePublicKey(publicKeyPem: String, signatureAlgorithm: String): PublicKey? {
    val keyAlgorithm = when {
        signatureAlgorithm.endsWith("RSA") -> "RSA"
        signatureAlgorithm.endsWith("ECDSA") -> "EC"
        else -> return null
    }
    val normalized = publicKeyPem
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("-----BEGIN PUBLIC KEY-----", "")
        .replace("-----END PUBLIC KEY-----", "")
        .replace("\\s".toRegex(), "")
    val keyBytes = runCatching { Base64.getDecoder().decode(normalized) }.getOrNull() ?: return null
    return runCatching {
        KeyFactory.getInstance(keyAlgorithm).generatePublic(X509EncodedKeySpec(keyBytes))
    }.getOrNull()
}

internal fun verifyAppUpdateSignature(
    payloadJson: String,
    signatureBase64: String,
    signatureAlgorithm: String,
    publicKey: PublicKey
): Boolean {
    val signatureBytes = runCatching { Base64.getDecoder().decode(signatureBase64) }.getOrNull()
        ?: return false
    return runCatching {
        Signature.getInstance(signatureAlgorithm).apply {
            initVerify(publicKey)
            update(payloadJson.toByteArray(Charsets.UTF_8))
        }.verify(signatureBytes)
    }.getOrDefault(false)
}
