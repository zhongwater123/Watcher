package com.example.watcher.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MjpegFramePublishGateTest {
    @Test
    fun shouldPublishFirstFrameImmediatelyAndThrottleFollowingFrames() {
        val gate = MjpegFramePublishGate(minFrameIntervalMs = 66L)

        assertTrue(gate.shouldPublishFrame(nowMs = 1_000L))
        assertFalse(gate.shouldPublishFrame(nowMs = 1_040L))
        assertTrue(gate.shouldPublishFrame(nowMs = 1_066L))
    }

    @Test
    fun previewInactiveStillAllowsBusinessFrameButBlocksPreviewFrame() {
        val policy = MjpegFrameDispatchPolicy(
            previewActive = false,
            previewPublishGate = MjpegFramePublishGate(minFrameIntervalMs = 66L)
        )

        assertTrue(policy.shouldDispatchBusinessFrame())
        assertFalse(policy.shouldPublishPreviewFrame(nowMs = 1_000L))
    }

    @Test
    fun previewActiveAllowsPreviewFrameThroughGate() {
        val policy = MjpegFrameDispatchPolicy(
            previewActive = true,
            previewPublishGate = MjpegFramePublishGate(minFrameIntervalMs = 66L)
        )

        assertTrue(policy.shouldDispatchBusinessFrame())
        assertTrue(policy.shouldPublishPreviewFrame(nowMs = 1_000L))
        assertFalse(policy.shouldPublishPreviewFrame(nowMs = 1_040L))
    }
}
