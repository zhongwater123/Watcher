package com.example.watcher.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.util.Log
import com.example.watcher.data.model.ClassroomKnowledgeFrameRef
import com.example.watcher.data.model.ClassroomKnowledgeNode
import com.example.watcher.data.model.ClassroomKnowledgeTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val CLASSROOM_VISUAL_TAG = "Watcher.Classroom.Visual"

internal data class ClassroomFrameEvidenceIndex(
    val timestampMs: Long,
    val path: String,
    val width: Int = 0,
    val height: Int = 0,
    val byteLength: Long = 0L,
    val sha256: String = "",
    val source: String = "",
    val deleteOnPrune: Boolean = true
)

internal data class ClassroomFrameEvidenceOfferResult(
    val shortTerm: ClassroomInlineFrameEvidence? = null,
    val longTerm: ClassroomKnowledgeFrameRef? = null
)

internal data class ClassroomInlineFrameEvidence(
    val imageDataUri: String,
    val source: String,
    val frameTimestampMs: Long,
    val framePath: String,
    val width: Int,
    val height: Int,
    val byteLength: Long,
    val sha256: String,
    val status: String
)

internal object ClassroomFrameEvidencePolicy {
    const val SAMPLE_INTERVAL_MS = 3_000L
    const val LONG_TERM_SAMPLE_INTERVAL_MS = 6_000L
    const val MAX_NEAREST_FRAME_DISTANCE_MS = 5_000L
    const val MAX_REPRESENTATIVE_FRAME_DISTANCE_MS = 8_000L
    const val MAX_CACHE_AGE_MS = 5 * 60_000L
    const val MAX_CACHE_ENTRIES = 120

    fun shouldSample(lastSavedMs: Long?, candidateMs: Long): Boolean {
        return lastSavedMs == null || candidateMs - lastSavedMs >= SAMPLE_INTERVAL_MS
    }

    fun shouldSampleLongTerm(lastSavedMs: Long?, candidateMs: Long): Boolean {
        return lastSavedMs == null || candidateMs - lastSavedMs >= LONG_TERM_SAMPLE_INTERVAL_MS
    }

    fun nearestFrame(
        frames: List<ClassroomFrameEvidenceIndex>,
        targetMs: Long
    ): ClassroomFrameEvidenceIndex? {
        return frames
            .minByOrNull { kotlin.math.abs(it.timestampMs - targetMs) }
            ?.takeIf { kotlin.math.abs(it.timestampMs - targetMs) <= MAX_NEAREST_FRAME_DISTANCE_MS }
    }

    fun collectRepresentativeCandidates(tree: ClassroomKnowledgeTree): List<ClassroomKnowledgeNode> {
        return tree.nodes.flatMap(::collectRepresentativeCandidates)
    }

    fun representativeTargetMs(node: ClassroomKnowledgeNode): Long? {
        val start = node.startMs ?: return null
        val end = node.endMs
        if (start == 0L && end == 0L) return null
        if (start < 0L) return null
        return start
    }

    private fun collectRepresentativeCandidates(node: ClassroomKnowledgeNode): List<ClassroomKnowledgeNode> {
        return listOf(node) + node.children.flatMap(::collectRepresentativeCandidates)
    }
}

internal class ClassroomFrameEvidenceCache(
    private val appContext: Context
) {
    private val framesByRun = ConcurrentHashMap<Long, MutableList<ClassroomFrameEvidenceIndex>>()
    private val longTermFramesByRun = ConcurrentHashMap<Long, MutableList<ClassroomFrameEvidenceIndex>>()
    private val lastSavedByRun = ConcurrentHashMap<Long, Long>()
    private val lastLongTermSavedByRun = ConcurrentHashMap<Long, Long>()
    private val testVideoByRun = ConcurrentHashMap<Long, String>()

    fun offerFrameEvidence(
        runId: Long,
        mediaTimeMs: Long,
        bitmap: Bitmap,
        shortTermSource: String = "live_camera",
        longTermSource: String = "live_camera_archive"
    ): ClassroomFrameEvidenceOfferResult? {
        val safeTimeMs = mediaTimeMs.coerceAtLeast(0L)
        val shouldSaveShortTerm = ClassroomFrameEvidencePolicy.shouldSample(lastSavedByRun[runId], safeTimeMs)
        val shouldSaveLongTerm = ClassroomFrameEvidencePolicy.shouldSampleLongTerm(lastLongTermSavedByRun[runId], safeTimeMs)
        if (!shouldSaveShortTerm && !shouldSaveLongTerm) return null

        return runCatching {
            val evidence = saveBitmap(
                runId = runId,
                mediaTimeMs = safeTimeMs,
                bitmap = bitmap,
                source = if (shouldSaveLongTerm) longTermSource else shortTermSource,
                filePrefix = if (shouldSaveLongTerm) "archive_frame" else "frame",
                directoryName = if (shouldSaveLongTerm) {
                    longTermFrameDirectoryName(runId)
                } else {
                    shortFrameDirectoryName(runId)
                }
            )
            var shortEvidence: ClassroomInlineFrameEvidence? = null
            var longEvidence: ClassroomKnowledgeFrameRef? = null
            if (shouldSaveShortTerm) {
                lastSavedByRun[runId] = evidence.frameTimestampMs
                addShortTermIndex(
                    runId = runId,
                    evidence = evidence,
                    source = shortTermSource,
                    deleteOnPrune = !shouldSaveLongTerm
                )
                shortEvidence = evidence.copy(source = shortTermSource)
            }
            if (shouldSaveLongTerm) {
                lastLongTermSavedByRun[runId] = evidence.frameTimestampMs
                val index = addLongTermIndex(
                    runId = runId,
                    evidence = evidence,
                    source = longTermSource
                )
                longEvidence = index.toFrameRef(nodeId = "")
            }
            ClassroomFrameEvidenceOfferResult(
                shortTerm = shortEvidence,
                longTerm = longEvidence
            )
        }.getOrNull()
    }

    fun offerFrame(
        runId: Long,
        mediaTimeMs: Long,
        bitmap: Bitmap,
        source: String = "live_camera"
    ): ClassroomInlineFrameEvidence? {
        if (!ClassroomFrameEvidencePolicy.shouldSample(lastSavedByRun[runId], mediaTimeMs)) {
            return null
        }
        return runCatching {
            val evidence = saveBitmap(
                runId = runId,
                mediaTimeMs = mediaTimeMs.coerceAtLeast(0L),
                bitmap = bitmap,
                source = source,
                filePrefix = "frame"
            )
            lastSavedByRun[runId] = evidence.frameTimestampMs
            addShortTermIndex(runId, evidence, source = source, deleteOnPrune = true)
            evidence
        }.getOrNull()
    }

    fun offerLongTermFrame(
        runId: Long,
        mediaTimeMs: Long,
        bitmap: Bitmap,
        source: String = "live_camera_archive"
    ): ClassroomKnowledgeFrameRef? {
        if (!ClassroomFrameEvidencePolicy.shouldSampleLongTerm(lastLongTermSavedByRun[runId], mediaTimeMs)) {
            return null
        }
        return runCatching {
            val evidence = saveBitmap(
                runId = runId,
                mediaTimeMs = mediaTimeMs.coerceAtLeast(0L),
                bitmap = bitmap,
                source = source,
                filePrefix = "archive_frame",
                directoryName = longTermFrameDirectoryName(runId)
            )
            lastLongTermSavedByRun[runId] = evidence.frameTimestampMs
            val index = addLongTermIndex(runId, evidence, source = source)
            index.toFrameRef(nodeId = "")
        }.getOrNull()
    }

    fun findNearest(runId: Long, targetMs: Long): ClassroomInlineFrameEvidence? {
        val index = framesByRun[runId]?.let { list ->
            synchronized(list) {
                ClassroomFrameEvidencePolicy.nearestFrame(list.toList(), targetMs)
            }
        } ?: return null
        return evidenceFromFile(
            index = index,
            source = index.source.ifBlank { "frame_cache" },
            status = "cache_hit"
        )
    }

    fun registerTestVideo(runId: Long, videoFile: File) {
        if (videoFile.exists() && videoFile.length() > 0L) {
            testVideoByRun[runId] = videoFile.absolutePath
        }
    }

    suspend fun archiveTestVideoFrames(runId: Long, videoFile: File) = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() <= 0L) return@withContext
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            var timestampMs = 0L
            while (timestampMs <= durationMs) {
                val bitmap = retriever.getFrameAtTime(
                    timestampMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (bitmap != null) {
                    try {
                        saveLongTermExtractedFrame(
                            runId = runId,
                            mediaTimeMs = timestampMs,
                            bitmap = bitmap,
                            source = "test_video_archive"
                        )
                    } finally {
                        bitmap.recycle()
                    }
                }
                timestampMs += ClassroomFrameEvidencePolicy.LONG_TERM_SAMPLE_INTERVAL_MS
            }
        } catch (_: Throwable) {
        } finally {
            retriever.release()
        }
    }

    suspend fun extractTestVideoFrame(runId: Long, targetMs: Long): ClassroomInlineFrameEvidence? {
        val path = testVideoByRun[runId] ?: return null
        return extractFromTestVideo(runId = runId, videoFile = File(path), targetMs = targetMs)
    }

    suspend fun extractFromTestVideo(
        runId: Long,
        videoFile: File,
        targetMs: Long
    ): ClassroomInlineFrameEvidence? = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() <= 0L) return@withContext null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val bitmap = retriever.getFrameAtTime(
                targetMs.coerceAtLeast(0L) * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: return@withContext null
            try {
                saveBitmap(
                    runId = runId,
                    mediaTimeMs = targetMs.coerceAtLeast(0L),
                    bitmap = bitmap,
                    source = "test_video",
                    filePrefix = "test_frame",
                    directoryName = shortFrameDirectoryName(runId)
                ).copy(status = "test_video_extracted")
            } finally {
                bitmap.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            retriever.release()
        }
    }

    fun clearRun(runId: Long) {
        framesByRun.remove(runId)
        longTermFramesByRun.remove(runId)
        lastSavedByRun.remove(runId)
        lastLongTermSavedByRun.remove(runId)
        testVideoByRun.remove(runId)
        runCatching { frameDirectory(shortFrameDirectoryName(runId)).deleteRecursively() }
        runCatching { frameDirectory(longTermFrameDirectoryName(runId)).deleteRecursively() }
    }

    fun representativeFramesForTree(
        runId: Long,
        tree: ClassroomKnowledgeTree
    ): List<ClassroomKnowledgeFrameRef> {
        val candidates = ClassroomFrameEvidencePolicy.collectRepresentativeCandidates(tree)
        if (candidates.isEmpty()) return emptyList()
        val frames = longTermFrameIndexes(runId)
        if (frames.isEmpty()) return emptyList()
        return candidates.mapNotNull { node ->
            val targetMs = ClassroomFrameEvidencePolicy.representativeTargetMs(node) ?: return@mapNotNull null
            val nearest = frames
                .minByOrNull { kotlin.math.abs(it.timestampMs - targetMs) }
                ?.takeIf {
                    kotlin.math.abs(it.timestampMs - targetMs) <=
                        ClassroomFrameEvidencePolicy.MAX_REPRESENTATIVE_FRAME_DISTANCE_MS
                }
                ?: return@mapNotNull null
            Log.i(
                CLASSROOM_VISUAL_TAG,
                "knowledge frame run=$runId node=${node.id} targetMs=$targetMs source=${nearest.source} " +
                    "frameMs=${nearest.timestampMs} size=${nearest.width}x${nearest.height} hash=${nearest.sha256.take(12)}"
            )
            nearest.toFrameRef(nodeId = node.id, status = "matched")
        }
    }

    private fun saveBitmap(
        runId: Long,
        mediaTimeMs: Long,
        bitmap: Bitmap,
        source: String,
        filePrefix: String,
        directoryName: String = shortFrameDirectoryName(runId)
    ): ClassroomInlineFrameEvidence {
        val scaled = scaleForEvidence(bitmap)
        val bytes = ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            output.toByteArray()
        }
        val evidenceWidth = scaled.width
        val evidenceHeight = scaled.height
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        val dir = frameDirectory(directoryName).apply { mkdirs() }
        val file = File(dir, "${filePrefix}_${mediaTimeMs}.jpg")
        file.writeBytes(bytes)
        val hash = sha256(bytes)
        return ClassroomInlineFrameEvidence(
            imageDataUri = "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
            source = source,
            frameTimestampMs = mediaTimeMs,
            framePath = file.absolutePath,
            width = evidenceWidth,
            height = evidenceHeight,
            byteLength = bytes.size.toLong(),
            sha256 = hash,
            status = "saved"
        )
    }

    private fun saveLongTermExtractedFrame(
        runId: Long,
        mediaTimeMs: Long,
        bitmap: Bitmap,
        source: String
    ) {
        val evidence = saveBitmap(
            runId = runId,
            mediaTimeMs = mediaTimeMs,
            bitmap = bitmap,
            source = source,
            filePrefix = "archive_frame",
            directoryName = longTermFrameDirectoryName(runId)
        )
        lastLongTermSavedByRun[runId] = evidence.frameTimestampMs
        val index = evidence.toIndex(source = source, deleteOnPrune = false)
        val list = longTermFramesByRun.getOrPut(runId) { mutableListOf() }
        synchronized(list) {
            val existing = list.indexOfFirst { it.timestampMs == index.timestampMs }
            if (existing >= 0) {
                list[existing] = index
            } else {
                list.add(index)
            }
            list.sortBy { it.timestampMs }
        }
    }

    private fun addShortTermIndex(
        runId: Long,
        evidence: ClassroomInlineFrameEvidence,
        source: String,
        deleteOnPrune: Boolean
    ) {
        val list = framesByRun.getOrPut(runId) { mutableListOf() }
        synchronized(list) {
            list.add(evidence.toIndex(source = source, deleteOnPrune = deleteOnPrune))
            pruneLocked(runId, list, newestTimestampMs = evidence.frameTimestampMs)
        }
    }

    private fun addLongTermIndex(
        runId: Long,
        evidence: ClassroomInlineFrameEvidence,
        source: String
    ): ClassroomFrameEvidenceIndex {
        val index = evidence.toIndex(source = source, deleteOnPrune = false)
        val list = longTermFramesByRun.getOrPut(runId) { mutableListOf() }
        synchronized(list) {
            val existing = list.indexOfFirst { it.timestampMs == index.timestampMs }
            if (existing >= 0) {
                list[existing] = index
            } else {
                list.add(index)
            }
            list.sortBy { it.timestampMs }
        }
        return index
    }

    private fun ClassroomInlineFrameEvidence.toIndex(
        source: String,
        deleteOnPrune: Boolean
    ): ClassroomFrameEvidenceIndex {
        return ClassroomFrameEvidenceIndex(
            timestampMs = frameTimestampMs,
            path = framePath,
            width = width,
            height = height,
            byteLength = byteLength,
            sha256 = sha256,
            source = source,
            deleteOnPrune = deleteOnPrune
        )
    }

    private fun longTermFrameIndexes(runId: Long): List<ClassroomFrameEvidenceIndex> {
        val cached = longTermFramesByRun[runId]?.let { list ->
            synchronized(list) { list.toList() }
        }.orEmpty()
        if (cached.isNotEmpty()) return cached
        val dir = frameDirectory(longTermFrameDirectoryName(runId))
        if (!dir.exists()) return emptyList()
        val loaded = dir.listFiles { file -> file.isFile && file.extension.equals("jpg", ignoreCase = true) }
            .orEmpty()
            .mapNotNull(::frameIndexFromFile)
            .sortedBy { it.timestampMs }
        if (loaded.isNotEmpty()) {
            longTermFramesByRun[runId] = loaded.toMutableList()
            lastLongTermSavedByRun[runId] = loaded.last().timestampMs
        }
        return loaded
    }

    private fun frameIndexFromFile(file: File): ClassroomFrameEvidenceIndex? {
        val timestampMs = file.nameWithoutExtension.substringAfterLast("_").toLongOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val bytes = file.readBytes()
        return ClassroomFrameEvidenceIndex(
            timestampMs = timestampMs,
            path = file.absolutePath,
            width = bounds.outWidth.coerceAtLeast(0),
            height = bounds.outHeight.coerceAtLeast(0),
            byteLength = file.length(),
            sha256 = sha256(bytes),
            source = "archive_file",
            deleteOnPrune = false
        )
    }

    private fun ClassroomFrameEvidenceIndex.toFrameRef(
        nodeId: String,
        status: String = "saved"
    ): ClassroomKnowledgeFrameRef {
        return ClassroomKnowledgeFrameRef(
            nodeId = nodeId,
            frameTimestampMs = timestampMs,
            framePath = path,
            width = width,
            height = height,
            byteLength = byteLength,
            sha256 = sha256,
            source = source,
            status = status
        )
    }

    private fun frameDirectory(directoryName: String): File {
        return File(File(appContext.filesDir, "video_runs"), directoryName)
    }

    private fun shortFrameDirectoryName(runId: Long): String = "run_${runId}_frame_evidence"

    private fun longTermFrameDirectoryName(runId: Long): String = "run_${runId}_knowledge_frames"

    private fun evidenceFromFile(
        index: ClassroomFrameEvidenceIndex,
        source: String,
        status: String
    ): ClassroomInlineFrameEvidence? {
        val file = File(index.path)
        if (!file.exists() || file.length() <= 0L) return null
        val bytes = file.readBytes()
        val hash = index.sha256.ifBlank { sha256(bytes) }
        return ClassroomInlineFrameEvidence(
            imageDataUri = "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}",
            source = source,
            frameTimestampMs = index.timestampMs,
            framePath = file.absolutePath,
            width = index.width,
            height = index.height,
            byteLength = index.byteLength.takeIf { it > 0L } ?: bytes.size.toLong(),
            sha256 = hash,
            status = status
        )
    }

    private fun pruneLocked(
        runId: Long,
        frames: MutableList<ClassroomFrameEvidenceIndex>,
        newestTimestampMs: Long
    ) {
        val expiredBefore = newestTimestampMs - ClassroomFrameEvidencePolicy.MAX_CACHE_AGE_MS
        val removed = mutableListOf<ClassroomFrameEvidenceIndex>()
        val iterator = frames.iterator()
        while (iterator.hasNext()) {
            val frame = iterator.next()
            if (frame.timestampMs < expiredBefore) {
                removed += frame
                iterator.remove()
            }
        }
        while (frames.size > ClassroomFrameEvidencePolicy.MAX_CACHE_ENTRIES) {
            removed += frames.removeAt(0)
        }
        removed
            .filter { it.deleteOnPrune }
            .forEach { runCatching { File(it.path).delete() } }
        if (frames.isEmpty()) {
            framesByRun.remove(runId)
        }
    }

    private fun scaleForEvidence(bitmap: Bitmap): Bitmap {
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge <= MAX_LONG_EDGE_PX) return bitmap
        val scale = MAX_LONG_EDGE_PX.toFloat() / longEdge.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val MAX_LONG_EDGE_PX = 960
        private const val JPEG_QUALITY = 80
    }
}
