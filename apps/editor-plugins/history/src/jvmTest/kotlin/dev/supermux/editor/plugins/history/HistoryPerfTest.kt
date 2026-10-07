package dev.supermux.editor.plugins.history

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** An agent streaming edits into a document with a full history: the history must not cost per event or selection. */
class HistoryPerfTest {
    @Test fun streamingRemoteEditsCostUnderATenthOfAMillisecondEach() {
        var now = 0L
        var st = EditorState.create("x".repeat(2000), EditorSelection.cursor(1000), History.extension(HistoryConfig(clock = { now })))
        // 100 steps, the last with 200 remembered selections.
        repeat(100) { i -> now += 1000; st = st.update(TransactionSpec(changes = listOf(ChangeSpec(10 * i, 10 * i, "e")), userEvent = "input")).state }
        repeat(200) { i -> now += 1000; st = st.update(TransactionSpec(selection = EditorSelection.cursor(i * 3), userEvent = "select")).state }
        assertEquals(100, History.undoDepth(st))
        fun stream(n: Int): Double {
            val t = TimeSource.Monotonic.markNow()
            repeat(n) { st = st.update(TransactionSpec(changes = listOf(ChangeSpec(st.doc.length, st.doc.length, "a")), userEvent = "agent")).state }
            return t.elapsedNow().inWholeNanoseconds / 1e6 / n
        }
        stream(2000) // warm-up
        val best = (1..3).minOf { stream(1000) }
        println("PERF history under streaming agent edits: ${"%.4f".format(best)} ms per transaction")
        assertTrue(best < 0.1, "$best ms per agent transaction")
        // Still correct afterwards: the last typed step undoes where it now is.
        val before = st.doc.toString()
        val target = object : dev.supermux.editor.core.CommandTarget {
            override val state get() = st
            override fun dispatch(spec: TransactionSpec) { st = st.update(spec).state }
        }
        assertTrue(History.undo.run(target))
        assertEquals(before.length - 1, st.doc.length)
    }
}
