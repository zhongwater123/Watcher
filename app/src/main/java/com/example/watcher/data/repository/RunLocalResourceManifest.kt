package com.example.watcher.data.repository

import com.example.watcher.data.model.MonitorEventEntity
import com.example.watcher.data.model.MonitorMediaEntity
import com.example.watcher.data.model.MonitorRun
import com.example.watcher.data.model.VideoAudioAssetEntity
import com.example.watcher.data.model.VideoProcessRun
import com.example.watcher.data.model.VideoRemoteFileBindingEntity
import com.example.watcher.data.model.VideoSegmentRun
import java.io.File

internal data class RunLocalResourceManifest(
    val runId: Long,
    val files: List<File> = emptyList(),
    val directories: List<File> = emptyList()
) {
    fun managedBy(roots: List<File>): RunLocalResourceManifest {
        val safeRoots = roots.mapNotNull { root ->
            runCatching { root.canonicalFile }.getOrNull()
        }
        return copy(
            files = files.filter { it.isInsideAny(safeRoots) },
            directories = directories.filter { it.isInsideAny(safeRoots) }
        )
    }
}

internal object RunLocalResourceCollector {
    fun collectVideoRunResources(
        videoRunsDir: File,
        run: VideoProcessRun,
        segments: List<VideoSegmentRun>,
        audioAssets: List<VideoAudioAssetEntity>,
        remoteFileBindings: List<VideoRemoteFileBindingEntity>
    ): RunLocalResourceManifest {
        val explicitFiles = buildList {
            addPath(run.fullMediaPath)
            addPath(run.mergedVideoPath)
            addPath(run.continuousAudioPath)
            segments.forEach { segment -> addPath(segment.localFilePath) }
            audioAssets.forEach { asset -> addPath(asset.localFilePath) }
            remoteFileBindings.forEach { binding -> addPath(binding.localPath) }
        }

        val prefix = "run_${run.id}_"
        val generatedEntries = videoRunsDir
            .listFiles()
            .orEmpty()
            .filter { it.name.startsWith(prefix) }

        return RunLocalResourceManifest(
            runId = run.id,
            files = (explicitFiles + generatedEntries.filter(File::isFile)).distinctByCanonicalPath(),
            directories = generatedEntries.filter(File::isDirectory).distinctByCanonicalPath()
        )
    }

    fun collectMonitorRunResources(
        run: MonitorRun,
        events: List<MonitorEventEntity>,
        media: List<MonitorMediaEntity>
    ): RunLocalResourceManifest {
        val files = buildList {
            addPath(run.baselineImagePath)
            addPath(run.sessionVideoPath)
            events.forEach { event -> addPath(event.frameImagePath) }
            media.forEach { item -> addPath(item.localFilePath) }
        }
        return RunLocalResourceManifest(
            runId = run.id,
            files = files.distinctByCanonicalPath()
        )
    }
}

private fun MutableList<File>.addPath(path: String?) {
    if (!path.isNullOrBlank()) {
        add(File(path))
    }
}

private fun List<File>.distinctByCanonicalPath(): List<File> {
    return distinctBy { file ->
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    }
}

private fun File.isInsideAny(roots: List<File>): Boolean {
    val target = runCatching { canonicalFile }.getOrElse { absoluteFile }
    return roots.any { root ->
        target.path == root.path || target.path.startsWith(root.path + File.separator)
    }
}
