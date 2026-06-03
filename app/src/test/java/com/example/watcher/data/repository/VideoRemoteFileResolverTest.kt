package com.example.watcher.data.repository

import com.example.watcher.data.local.VideoRemoteFileBindingDao
import com.example.watcher.data.model.VideoRemoteAssetKind
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.remote.ArkFileResponse
import com.example.watcher.data.remote.DoubaoApiService
import com.example.watcher.data.remote.DoubaoImageRequest
import com.example.watcher.data.remote.DoubaoRequest
import com.example.watcher.data.remote.DoubaoResponse
import com.example.watcher.data.remote.DoubaoVideoRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class VideoRemoteFileResolverTest {
    @Test
    fun reusesUsableExistingFileId() = runBlocking {
        val file = createTempFileWithBytes(16)
        val dao = FakeBindingDao()
        dao.upsert(file.binding(fileId = "file-existing"))
        val api = FakeDoubaoApiService(
            getFileResult = ArkFileResponse(id = "file-existing", status = "active")
        )
        val resolver = VideoRemoteFileResolver(api, dao, apiKey = "test")

        val result = resolver.resolveVideoFile(
            file = file,
            runId = RUN_ID,
            segmentRunId = SEGMENT_ID,
            assetKind = VideoRemoteAssetKind.SegmentVideo,
            samplingFps = 1
        )

        assertEquals(RemoteFileResolutionType.Reused, result.resolutionType)
        assertEquals("file-existing", result.fileId)
        assertEquals(0, api.uploadCalls)
        file.delete()
    }

    @Test
    fun reusesLegacyReadyFileIdForBackwardCompatibility() = runBlocking {
        val file = createTempFileWithBytes(16)
        val dao = FakeBindingDao()
        dao.upsert(file.binding(fileId = "file-existing"))
        val api = FakeDoubaoApiService(
            getFileResult = ArkFileResponse(id = "file-existing", status = "processed")
        )
        val resolver = VideoRemoteFileResolver(api, dao, apiKey = "test")

        val result = resolver.resolveVideoFile(
            file = file,
            runId = RUN_ID,
            segmentRunId = SEGMENT_ID,
            assetKind = VideoRemoteAssetKind.SegmentVideo,
            samplingFps = 1
        )

        assertEquals(RemoteFileResolutionType.Reused, result.resolutionType)
        assertEquals("file-existing", result.fileId)
        assertEquals(0, api.uploadCalls)
        file.delete()
    }

    @Test
    fun reuploadsWhenExistingFileIsStillProcessing() = runBlocking {
        val file = createTempFileWithBytes(16)
        val dao = FakeBindingDao()
        dao.upsert(file.binding(fileId = "file-processing"))
        val api = FakeDoubaoApiService(
            getFileResult = ArkFileResponse(id = "file-processing", status = "processing"),
            uploadResult = ArkFileResponse(id = "file-new", status = "uploaded")
        )
        val resolver = VideoRemoteFileResolver(api, dao, apiKey = "test")

        val result = resolver.resolveVideoFile(
            file = file,
            runId = RUN_ID,
            segmentRunId = SEGMENT_ID,
            assetKind = VideoRemoteAssetKind.SegmentVideo,
            samplingFps = 1
        )

        assertEquals(RemoteFileResolutionType.Reuploaded, result.resolutionType)
        assertEquals("file-new", result.fileId)
        assertEquals(1, api.uploadCalls)
        file.delete()
    }

    @Test
    fun reuploadsWhenExistingFileStatusIsUnknown() = runBlocking {
        val file = createTempFileWithBytes(16)
        val dao = FakeBindingDao()
        dao.upsert(file.binding(fileId = "file-unknown"))
        val api = FakeDoubaoApiService(
            getFileResult = ArkFileResponse(id = "file-unknown", status = null),
            uploadResult = ArkFileResponse(id = "file-new", status = "uploaded")
        )
        val resolver = VideoRemoteFileResolver(api, dao, apiKey = "test")

        val result = resolver.resolveVideoFile(
            file = file,
            runId = RUN_ID,
            segmentRunId = SEGMENT_ID,
            assetKind = VideoRemoteAssetKind.SegmentVideo,
            samplingFps = 1
        )

        assertEquals(RemoteFileResolutionType.Reuploaded, result.resolutionType)
        assertEquals("file-new", result.fileId)
        assertEquals(1, api.uploadCalls)
        file.delete()
    }

    @Test
    fun reuploadsWhenExistingFileStatusIsExplicitlyUnknown() = runBlocking {
        val file = createTempFileWithBytes(16)
        val dao = FakeBindingDao()
        dao.upsert(file.binding(fileId = "file-unknown-text"))
        val api = FakeDoubaoApiService(
            getFileResult = ArkFileResponse(id = "file-unknown-text", status = "unknown"),
            uploadResult = ArkFileResponse(id = "file-new", status = "uploaded")
        )
        val resolver = VideoRemoteFileResolver(api, dao, apiKey = "test")

        val result = resolver.resolveVideoFile(
            file = file,
            runId = RUN_ID,
            segmentRunId = SEGMENT_ID,
            assetKind = VideoRemoteAssetKind.SegmentVideo,
            samplingFps = 1
        )

        assertEquals(RemoteFileResolutionType.Reuploaded, result.resolutionType)
        assertEquals("file-new", result.fileId)
        assertEquals(1, api.uploadCalls)
        file.delete()
    }

    @Test
    fun reuploadsWhenExistingFileStatusFailed() = runBlocking {
        val file = createTempFileWithBytes(16)
        val dao = FakeBindingDao()
        dao.upsert(file.binding(fileId = "file-failed"))
        val api = FakeDoubaoApiService(
            getFileResult = ArkFileResponse(id = "file-failed", status = "failed"),
            uploadResult = ArkFileResponse(id = "file-new", status = "uploaded")
        )
        val resolver = VideoRemoteFileResolver(api, dao, apiKey = "test")

        val result = resolver.resolveVideoFile(
            file = file,
            runId = RUN_ID,
            segmentRunId = SEGMENT_ID,
            assetKind = VideoRemoteAssetKind.SegmentVideo,
            samplingFps = 1
        )

        assertEquals(RemoteFileResolutionType.Reuploaded, result.resolutionType)
        assertEquals("file-new", result.fileId)
        assertEquals(1, api.uploadCalls)
        file.delete()
    }

    @Test
    fun reuploadsWhenExistingFileIdIsUnavailable() = runBlocking {
        val file = createTempFileWithBytes(16)
        val dao = FakeBindingDao()
        dao.upsert(file.binding(fileId = "file-old"))
        val api = FakeDoubaoApiService(
            getFileError = IOException("not found"),
            uploadResult = ArkFileResponse(id = "file-new", status = "uploaded")
        )
        val resolver = VideoRemoteFileResolver(api, dao, apiKey = "test")

        val result = resolver.resolveVideoFile(
            file = file,
            runId = RUN_ID,
            segmentRunId = SEGMENT_ID,
            assetKind = VideoRemoteAssetKind.SegmentVideo,
            samplingFps = 1
        )

        assertEquals(RemoteFileResolutionType.Reuploaded, result.resolutionType)
        assertEquals("file-new", result.fileId)
        assertEquals(1, api.uploadCalls)
        val updated = dao.findForLocalFile(RUN_ID, VideoRemoteAssetKind.SegmentVideo.value, file.absolutePath)
        assertTrue(updated?.diagnosticsJson.orEmpty().contains("file-old"))
        assertTrue(updated?.diagnosticsJson.orEmpty().contains("file-new"))
        file.delete()
    }

    private fun createTempFileWithBytes(sizeBytes: Int): File {
        return File.createTempFile("watcher-remote-file", ".mp4").apply {
            writeBytes(ByteArray(sizeBytes) { 7 })
        }
    }

    private fun File.binding(fileId: String): VideoRemoteFileBindingEntity {
        return VideoRemoteFileBindingEntity(
            runId = RUN_ID,
            segmentRunId = SEGMENT_ID,
            assetKind = VideoRemoteAssetKind.SegmentVideo.value,
            localPath = absolutePath,
            lengthBytes = length(),
            lastModified = lastModified(),
            arkFileId = fileId,
            status = "processed"
        )
    }

    private class FakeBindingDao : VideoRemoteFileBindingDao {
        private val bindings = mutableListOf<VideoRemoteFileBindingEntity>()
        private var nextId = 1L

        override fun observeForRun(runId: Long): Flow<List<VideoRemoteFileBindingEntity>> {
            return flowOf(bindings.filter { it.runId == runId })
        }

        override suspend fun getForRun(runId: Long): List<VideoRemoteFileBindingEntity> {
            return bindings.filter { it.runId == runId }
        }

        override suspend fun findForLocalFile(
            runId: Long,
            assetKind: String,
            localPath: String
        ): VideoRemoteFileBindingEntity? {
            return bindings.firstOrNull {
                it.runId == runId && it.assetKind == assetKind && it.localPath == localPath
            }
        }

        override suspend fun findByFileId(fileId: String): VideoRemoteFileBindingEntity? {
            return bindings.firstOrNull { it.arkFileId == fileId }
        }

        override suspend fun insert(binding: VideoRemoteFileBindingEntity): Long {
            val entity = binding.copy(id = nextId++)
            bindings += entity
            return entity.id
        }

        override suspend fun update(binding: VideoRemoteFileBindingEntity) {
            val index = bindings.indexOfFirst { it.id == binding.id }
            if (index >= 0) bindings[index] = binding
        }

        override suspend fun upsert(binding: VideoRemoteFileBindingEntity): Long {
            if (binding.id == 0L) {
                val existing = findForLocalFile(binding.runId, binding.assetKind, binding.localPath)
                if (existing != null) {
                    update(binding.copy(id = existing.id))
                    return existing.id
                }
                return insert(binding)
            }
            update(binding)
            return binding.id
        }
    }

    private class FakeDoubaoApiService(
        private val getFileResult: ArkFileResponse? = null,
        private val getFileError: Throwable? = null,
        private val uploadResult: ArkFileResponse = ArkFileResponse(id = "file-uploaded", status = "uploaded")
    ) : DoubaoApiService {
        var uploadCalls = 0

        override suspend fun analyzeIntent(
            authorization: String,
            contentType: String,
            request: DoubaoRequest
        ): DoubaoResponse = DoubaoResponse()

        override suspend fun analyzeImage(
            authorization: String,
            contentType: String,
            request: DoubaoImageRequest
        ): DoubaoResponse = DoubaoResponse()

        override suspend fun uploadFile(
            authorization: String,
            purpose: RequestBody,
            preprocessConfigs: Map<String, @JvmSuppressWildcards RequestBody>,
            file: MultipartBody.Part
        ): ArkFileResponse {
            uploadCalls++
            return uploadResult
        }

        override suspend fun uploadAudioFile(
            authorization: String,
            purpose: RequestBody,
            file: MultipartBody.Part
        ): ArkFileResponse = uploadResult

        override suspend fun getFile(
            authorization: String,
            fileId: String
        ): ArkFileResponse {
            getFileError?.let { throw it }
            return getFileResult ?: ArkFileResponse(id = fileId, status = "processed")
        }

        override suspend fun analyzeVideo(
            authorization: String,
            contentType: String,
            request: DoubaoVideoRequest
        ): DoubaoResponse = DoubaoResponse()
    }

    private companion object {
        private const val RUN_ID = 7L
        private const val SEGMENT_ID = 9L
    }
}
