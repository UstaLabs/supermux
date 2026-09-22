package dev.supermux.ui.editor.engine

import dev.supermux.ui.editor.EditorState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorScrollCaptureTest {
    private fun state() = EditorState(
        fsRead = { path -> Result.success("body:$path") },
        fsWrite = { _, _ -> true },
        scope = TestScope(UnconfinedTestDispatcher()),
    )

    @Test fun capture_outgoing_scroll_lands_on_the_tab_that_was_active_at_call_time() {
        val s = state()
        s.openFile("a.txt")
        s.openFile("b.txt")
        var pending: ((Int) -> Unit)? = null
        val reader = EditorScrollReader().apply { read = { cb -> pending = cb } }

        captureOutgoingScroll(s, reader)
        s.selectTab("a.txt")
        pending!!.invoke(99)

        assertEquals(99, s.tabs.find { it.path == "b.txt" }?.scrollTop)
        assertEquals(0, s.tabs.find { it.path == "a.txt" }?.scrollTop)
    }

    @Test fun capture_outgoing_scroll_is_a_no_op_with_no_active_tab() {
        val s = state()
        var reads = 0
        val reader = EditorScrollReader().apply { read = { reads++; it(42) } }
        captureOutgoingScroll(s, reader)
        assertEquals(0, reads)
    }
}
