package com.example.watcher.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassroomRecordingCompletedCardsTest {

    @Test
    fun autoPlayStartsOnlyBeforeFirstPlayback() {
        assertTrue(
            shouldAutoStartClassroomVideo(
                autoPlay = true,
                userPaused = false,
                playbackStarted = false
            )
        )

        assertFalse(
            shouldAutoStartClassroomVideo(
                autoPlay = true,
                userPaused = false,
                playbackStarted = true
            )
        )
    }

    @Test
    fun userPausePreventsAutoRestart() {
        assertFalse(
            shouldAutoStartClassroomVideo(
                autoPlay = true,
                userPaused = true,
                playbackStarted = true
            )
        )
    }
}
