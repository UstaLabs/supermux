package com.swithun.cmpmermaid.compose

import kotlinx.coroutines.CoroutineDispatcher
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

internal actual fun mermaidRenderDispatcher(): CoroutineDispatcher = SwingEventDispatcher

private object SwingEventDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !SwingUtilities.isEventDispatchThread()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        SwingUtilities.invokeLater(block)
    }
}
