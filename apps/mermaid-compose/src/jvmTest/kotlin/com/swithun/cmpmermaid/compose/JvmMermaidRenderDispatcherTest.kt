package com.swithun.cmpmermaid.compose

import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class JvmMermaidRenderDispatcherTest {
    @Test
    fun dispatchesComposeTextMeasurementToSwingEventThread() = runBlocking {
        withContext(mermaidRenderDispatcher()) {
            assertTrue(SwingUtilities.isEventDispatchThread())
        }
    }
}
