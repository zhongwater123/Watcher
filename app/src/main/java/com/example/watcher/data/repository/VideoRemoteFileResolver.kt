package com.example.watcher.data.repository

import com.example.watcher.data.local.VideoRemoteFileBindingDao
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.remote.DoubaoApiService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.File
import java.io.IOException

class VideoRemoteFileResolver(
    private val apiService: DoubaoApiService,
    private val bindingDao: VideoRemoteFileBindingDao,
    private val apiKey: String,
    private val gson: Gson = Gson()
) {
    suspend fun recordLocalFileBinding(
        file: File,
        runId: Long,
        segmentRunId: Long?,
        assetKind: VideoRemoteAssetKind,
        mediaType: String
    ): VideoRemoteFileBindingEntity {
        val now = System.currentTimeMillis()
        val normalizedPath = file.absolutePath
        val existing = bindingDao.findForLocalFile(
            runId = runId,
            assetKind = assetKind.value,
            localPath = normalizedPath
        )
        val binding = (existing ?: VideoRemoteFileBindingEntity(
            runId = runId,
            segmentRunId = segmentRunId,
            assetKind = assetKind.value,
            localPath = normalizedPath
        )).copy(
            segmentRunId = segmentRunId ?: existing?.segmentRunId,
            lengthBytes = file.length(),
            lastModified = file.lastModified(),
            mediaType = mediaType,
            status = existing?.status ?: "local",
            updatedAt = now
        )
        val bindingId = bindingDao.upsert(binding)
        return if (binding.id == 0L) binding.copy(id = bindingId) else binding
    }

    suspend fun resolveVideoFile(
        file: File,
        runId: Long,
        segmentRunId: Long?,
        assetKind: VideoRemoteAssetKind,
        samplingFps: Int
    ): RemoteFileResolution {
        return resolveFile(
            file = file,
            runId = runId,
            segmentRunId = segmentRunId,
            assetKind = assetKind,
            mediaType = "video/mp4",
            samplingFps = samplingFps
        )
    }

    suspend fun resolveAudioFile(
        file: File,
        runId: Long,
        segmentRunId: Long?,
        assetKind: VideoRemoteAssetKind,
        mediaType: String = "audio/mp4"
    ): RemoteFileResolution {
        return resolveFile(
            file = file,
            runId = runId,
            segmentRunId = segmentRunId,
            assetKind = assetKind,
            mediaType = mediaType,
            samplingFps = null
        )
    }

    suspend fun resolveUserDataFile(
        file: File,
        runId: Long,
        assetKind: VideoRemoteAssetKind,
        mediaType: String
    ): RemoteFileResolution {
        return resolveFile(
            file = file,
            runId = runId,
            segmentRunId = null,
            assetKind = assetKind,
            mediaType = mediaType,
            samplingFps = null
        )
    }

    private suspend fun resolveFile(
        file: File,
        runId: Long,
        segmentRunId: Long?,
        assetKind: VideoRemoteAssetKind,
        mediaType: String,
        samplingFps: Int?
    ): RemoteFileResolution {
        val now = System.currentTimeMillis()
        val normalizedPath = file.absolutePath
        val fileLength = file.length()
        val fileModified = file.lastModified()
        val existing = bindingDao.findForLocalFile(
            runId = runId,
            assetKind = assetKind.value,
            localPath = normalizedPath
        )

        if (
            existing != null &&
            existing.arkFileId != null &&
            existing.lengthBytes == fileLength &&
            existing.lastModified == fileModified
        ) {
            val check = checkRemoteFile(existing.arkFileId)
            val checkedBinding = existing.copy(
                status = check.status,
                lastCheckedAt = now,
                diagnosticsJson = appendEvent(
                    existing.diagnosticsJson,
                    mapOf(
                        "event" to "check",
                        "at" to now,
                        "fileId" to existing.arkFileId,
                        "status" to check.status,
                        "message" to check.message
                    )
                ),
                updatedAt = now
            )
            bindingDao.upsert(checkedBinding)
            if (check.usable) {
                return RemoteFileResolution(
                    fileId = existing.arkFileId,
                    binding = checkedBinding,
                    resolutionType = RemoteFileResolutionType.Reused
                )
            }
        }

        val previousBinding = existing ?: VideoRemoteFileBindingEntity(
            runId = runId,
            segmentRunId = segmentRunId,
            assetKind = assetKind.value,
            localPath = normalizedPath,
            lengthBytes = fileLength,
            lastModified = fileModified
        )
        val reuploadReason = when {
            existing == null -> "no_binding"
            existing.arkFileId.isNullOrBlank() -> "missing_file_id"
            existing.lengthBytes != fileLength || existing.lastModified != fileModified -> "local_file_changed"
            else -> "remote_file_unavailable"
        }
        val attemptNumber = previousBinding.uploadAttemptCount + 1

        val uploadingBinding = previousBinding.copy(
            segmentRunId = segmentRunId ?: previousBinding.segmentRunId,
            lengthBytes = fileLength,
            lastModified = fileModified,
            mediaType = mediaType,
            status = "uploading",
            uploadAttemptCount = attemptNumber,
            diagnosticsJson = appendEvent(
                previousBinding.diagnosticsJson,
                mapOf(
                    "event" to "upload_start",
                    "at" to now,
                    "reason" to reuploadReason,
                    "oldFileId" to previousBinding.arkFileId,
                    "lengthBytes" to fileLength,
                    "lastModified" to fileModified,
                    "attempt" to attemptNumber
                )
            ),
            updatedAt = now
        )
        val bindingId = bindingDao.upsert(uploadingBinding)
        val bindingWithId = if (uploadingBinding.id == 0L) uploadingBinding.copy(id = bindingId) else uploadingBinding

        return try {
            val fileId = when {
                mediaType.startsWith("audio/") -> uploadAudioFile(file, mediaType)
                mediaType.startsWith("video/") -> uploadVideoFile(file, samplingFps ?: 1, mediaType)
                else -> uploadGenericFile(file, mediaType)
            }
            val uploadedAt = System.currentTimeMillis()
            val uploadedBinding = bindingWithId.copy(
                arkFileId = fileId,
                status = "uploaded",
                lastCheckedAt = uploadedAt,
                diagnosticsJson = appendEvent(
                    bindingWithId.diagnosticsJson,
                    mapOf(
                        "event" to "upload_success",
                        "at" to uploadedAt,
                        "reason" to reuploadReason,
                        "oldFileId" to previousBinding.arkFileId,
                        "newFileId" to fileId,
                        "lengthBytes" to fileLength,
                        "attempt" to attemptNumber
                    )
                ),
                updatedAt = uploadedAt
            )
            bindingDao.upsert(uploadedBinding)
            RemoteFileResolution(
                fileId = fileId,
                binding = uploadedBinding,
                resolutionType = if (existing == null) {
                    RemoteFileResolutionType.Uploaded
                } else {
                    RemoteFileResolutionType.Reuploaded
                }
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val failedAt = System.currentTimeMillis()
            bindingDao.upsert(
                bindingWithId.copy(
                    status = "upload_failed",
                    diagnosticsJson = appendEvent(
                        bindingWithId.diagnosticsJson,
                        mapOf(
                            "event" to "upload_failed",
                            "at" to failedAt,
                            "reason" to reuploadReason,
                            "oldFileId" to previousBinding.arkFileId,
                            "message" to (error.message ?: error::class.java.simpleName),
                            "attempt" to attemptNumber
                        )
                    ),
                    updatedAt = failedAt
                )
            )
            throw error
        }
    }

    suspend fun recordRemoteFileStatus(
        fileId: String,
        status: String,
        message: String = ""
    ) {
        val existing = bindingDao.findByFileId(fileId) ?: return
        val now = System.currentTimeMillis()
        bindingDao.upsert(
            existing.copy(
                status = status,
                lastCheckedAt = now,
                diagnosticsJson = appendEvent(
                    existing.diagnosticsJson,
                    mapOf(
                        "event" to "status",
                        "at" to now,
                        "fileId" to fileId,
                        "status" to status,
                        "message" to message
                    )
                ),
                updatedAt = now
            )
        )
    }

    /**
     * Polls the remote file status. Returns the lowercase status string (e.g. "active", "processing", "failed").
     */
    suspend fun pollFileStatus(fileId: String): String {
        return runCatching {
            val remoteFile = retryRemoteCall { apiService.getFile(bearerToken(), fileId) }
            remoteFile.status?.lowercase().orEmpty().ifBlank { "unknown" }
        }.getOrDefault("unknown")
    }

    private suspend fun checkRemoteFile(fileId: String): RemoteFileCheck {
        return runCatching {
            val remoteFile = retryRemoteCall { apiService.getFile(bearerToken(), fileId) }
            val status = remoteFile.status?.lowercase().orEmpty().ifBlank { "unknown" }
            RemoteFileCheck(
                usable = status in READY_FILE_STATUSES,
                status = status,
                message = remoteFile.filename ?: remoteFile.resolvedId().orEmpty()
            )
        }.getOrElse { error ->
            RemoteFileCheck(
                usable = false,
                status = "unavailable",
                message = error.message ?: error::class.java.simpleName
            )
        }
    }

    private suspend fun uploadVideoFile(file: File, samplingFps: Int, mediaType: String = "video/mp4"): String {
        val uploadSamplingFps = samplingFps.coerceIn(1, 5)
        val response = retryRemoteCall {
            apiService.uploadFile(
                authorization = bearerToken(),
                purpose = "user_data".toRequestBody("text/plain".toMediaType()),
                preprocessConfigs = mapOf(
                    "preprocess_configs[video][fps]" to uploadSamplingFps
                        .toString()
                        .toRequestBody("text/plain".toMediaType())
                ),
                file = MultipartBody.Part.createFormData(
                    name = "file",
                    filename = file.name,
                    body = file.asRequestBody(mediaType.toMediaType())
                )
            )
        }
        return response.resolvedId()
            ?: error("File upload succeeded but file_id was missing.")
    }

    private suspend fun uploadAudioFile(file: File, mediaType: String): String {
        val response = retryRemoteCall {
            apiService.uploadAudioFile(
                authorization = bearerToken(),
                purpose = "user_data".toRequestBody("text/plain".toMediaType()),
                file = MultipartBody.Part.createFormData(
                    name = "file",
                    filename = file.name,
                    body = file.asRequestBody(mediaType.toMediaType())
                )
            )
        }
        return response.resolvedId()
            ?: error("Audio file upload succeeded but file_id was missing.")
    }

    private suspend fun uploadGenericFile(file: File, mediaType: String): String {
        val response = retryRemoteCall {
            apiService.uploadFile(
                authorization = bearerToken(),
                purpose = "user_data".toRequestBody("text/plain".toMediaType()),
                preprocessConfigs = emptyMap(),
                file = MultipartBody.Part.createFormData(
                    name = "file",
                    filename = file.name,
                    body = file.asRequestBody(mediaType.toMediaType())
                )
            )
        }
        return response.resolvedId()
            ?: error("File upload succeeded but file_id was missing.")
    }

    private suspend fun <T> retryRemoteCall(block: suspend () -> T): T {
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
        throw lastError ?: IllegalStateException("Remote call failed.")
    }

    private fun Throwable.isRetryableRemoteFailure(): Boolean {
        val text = message.orEmpty()
        return this is IOException ||
            text.contains("Unable to resolve host", ignoreCase = true) ||
            text.contains("timeout", ignoreCase = true)
    }

    private fun appendEvent(existingJson: String, event: Map<String, Any?>): String {
        val events = parseEvents(existingJson).toMutableList()
        events += event
        return gson.toJson(events.takeLast(MAX_DIAGNOSTIC_EVENTS))
    }

    private fun parseEvents(json: String): List<Map<String, Any?>> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            gson.fromJson<List<Map<String, Any?>>>(json, type)
        }.getOrDefault(emptyList())
    }

    private fun bearerToken(): String = "Bearer $apiKey"

    private data class RemoteFileCheck(
        val usable: Boolean,
        val status: String,
        val message: String
    )

    private companion object {
        private const val REMOTE_RETRY_ATTEMPTS = 3
        private const val REMOTE_RETRY_DELAY_MS = 2_000L
        private const val MAX_DIAGNOSTIC_EVENTS = 30
        private val READY_FILE_STATUSES = setOf("active", "processed", "ready", "succeeded")
    }
}

data class RemoteFileResolution(
    val fileId: String,
    val binding: VideoRemoteFileBindingEntity,
    val resolutionType: RemoteFileResolutionType
)

enum class RemoteFileResolutionType {
    Uploaded,
    Reused,
    Reuploaded
}
