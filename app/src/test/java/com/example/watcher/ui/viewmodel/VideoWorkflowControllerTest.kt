package com.example.watcher.ui.viewmodel

import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWorkflowControllerTest {
    @Test
    fun staleProcessingJobCannotApplyTerminalUiUpdateAfterReset() {
        val oldJob = Job()

        assertFalse(shouldApplyVideoProcessingJobUpdate(currentJob = null, updateJob = oldJob))
    }

    @Test
    fun replacedProcessingJobCannotApplyTerminalUiUpdate() {
        val oldJob = Job()
        val newJob = Job()

        assertFalse(shouldApplyVideoProcessingJobUpdate(currentJob = newJob, updateJob = oldJob))
    }

    @Test
    fun currentProcessingJobCanApplyTerminalUiUpdate() {
        val job = Job()

        assertTrue(shouldApplyVideoProcessingJobUpdate(currentJob = job, updateJob = job))
    }

    @Test
    fun staleVideoWorkflowSessionCannotApplyUiUpdateAfterNewRecording() {
        assertFalse(
            shouldApplyVideoWorkflowSessionUpdate(
                currentSessionId = 2L,
                updateSessionId = 1L
            )
        )
    }

    @Test
    fun currentVideoWorkflowSessionCanApplyUiUpdate() {
        assertTrue(
            shouldApplyVideoWorkflowSessionUpdate(
                currentSessionId = 2L,
                updateSessionId = 2L
            )
        )
    }
}
