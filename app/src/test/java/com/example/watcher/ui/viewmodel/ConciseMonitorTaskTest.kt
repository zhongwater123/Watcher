package com.example.watcher.ui.viewmodel

import com.example.watcher.data.model.BaselineSource
import com.example.watcher.data.model.IntentResult
import com.example.watcher.data.model.MonitorMode
import com.example.watcher.data.model.TargetTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class ConciseMonitorTaskTest {
    @Test
    fun `concise monitor task forces four second interval and keeps generated fields`() {
        val task = IntentResult(
            title = "Watch the cup",
            userInput = "Tell me if this cup disappears",
            userRequirement = "Watch whether the cup disappears",
            originalSceneDescription = "A blue cup on the desk",
            checkInterval = 90,
            promptTemplate = "Return JSON only",
            baseFrameBase64 = "baseline",
            baselineImagePath = "/tmp/baseline.jpg",
            monitorMode = MonitorMode.ReferenceTarget,
            targetTrigger = TargetTrigger.OnDisappear,
            baselineSource = BaselineSource.UploadedImage
        )

        val conciseTask = task.toConciseMonitorTask()

        assertEquals(4, conciseTask.checkInterval)
        assertEquals(MonitorMode.ReferenceTarget, conciseTask.monitorMode)
        assertEquals(TargetTrigger.OnDisappear, conciseTask.targetTrigger)
        assertEquals(BaselineSource.UploadedImage, conciseTask.baselineSource)
        assertEquals("baseline", conciseTask.baseFrameBase64)
        assertEquals("/tmp/baseline.jpg", conciseTask.baselineImagePath)
    }
}
