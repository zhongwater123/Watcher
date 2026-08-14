package com.example.watcher.data.model

sealed interface ClassroomRecordingInput {
    val sourceId: String
    val displayLabel: String
    val visualLogLabel: String get() = sourceId

    object LiveCamera : ClassroomRecordingInput {
        override val sourceId: String = "live_camera"
        override val displayLabel: String = "Live camera"
    }

    data class RemoteMjpegStream(
        val streamUrl: String,
        val sourceLabel: String
    ) : ClassroomRecordingInput {
        override val sourceId: String = "remote_mjpeg"
        override val displayLabel: String = sourceLabel.ifBlank { "Embedded video stream" }
        override val visualLogLabel: String = listOf(sourceId, streamUrl)
            .filter(String::isNotBlank)
            .joinToString(":")
    }

    data class PhoneCameraFallback(
        val lens: ClassroomPhoneCameraLens,
        val sourceLabel: String
    ) : ClassroomRecordingInput {
        override val sourceId: String = when (lens) {
            ClassroomPhoneCameraLens.Front -> "phone_front_camera_fallback"
            ClassroomPhoneCameraLens.Back -> "phone_back_camera_fallback"
        }
        override val displayLabel: String = sourceLabel.ifBlank {
            when (lens) {
                ClassroomPhoneCameraLens.Front -> "Phone front camera"
                ClassroomPhoneCameraLens.Back -> "Phone back camera"
            }
        }
    }

    data class TestVideo(
        val localPath: String,
        val displayName: String,
        val durationMs: Long
    ) : ClassroomRecordingInput {
        override val sourceId: String = "test_video"
        override val displayLabel: String = displayName.ifBlank { "Test video" }
    }
}

enum class ClassroomPhoneCameraLens {
    Front,
    Back
}

val ClassroomRecordingInput.isTestVideoInput: Boolean
    get() = this is ClassroomRecordingInput.TestVideo

val ClassroomRecordingInput.usesLiveFrameProvider: Boolean
    get() = !isTestVideoInput

val ClassroomRecordingInput.usesLiveAudioCapture: Boolean
    get() = !isTestVideoInput

val ClassroomRecordingInput.shortTermFrameSource: String
    get() = when (this) {
        ClassroomRecordingInput.LiveCamera -> "live_camera"
        is ClassroomRecordingInput.RemoteMjpegStream -> "remote_mjpeg"
        is ClassroomRecordingInput.PhoneCameraFallback -> sourceId
        is ClassroomRecordingInput.TestVideo -> "test_video"
    }

val ClassroomRecordingInput.longTermFrameSource: String
    get() = when (this) {
        ClassroomRecordingInput.LiveCamera -> "live_camera_archive"
        is ClassroomRecordingInput.RemoteMjpegStream -> "remote_mjpeg_archive"
        is ClassroomRecordingInput.PhoneCameraFallback -> "${sourceId}_archive"
        is ClassroomRecordingInput.TestVideo -> "test_video_archive"
    }

fun ClassroomRecordingInput.acceptsPreviewFrameSource(sourceId: String): Boolean {
    if (!usesLiveFrameProvider) return true
    return when (this) {
        ClassroomRecordingInput.LiveCamera -> sourceId == "live_camera"
        is ClassroomRecordingInput.RemoteMjpegStream -> sourceId == this.sourceId
        is ClassroomRecordingInput.PhoneCameraFallback -> sourceId == this.sourceId
        is ClassroomRecordingInput.TestVideo -> true
    }
}
