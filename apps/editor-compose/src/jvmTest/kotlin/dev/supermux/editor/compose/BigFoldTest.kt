package dev.supermux.editor.compose

import androidx.compose.ui.test.ExperimentalTestApi
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.decorationsFacet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fold over 300k lines costs nothing per keystroke and one step for Up/Down. */
@OptIn(ExperimentalTestApi::class)
class BigFoldTest {
    private val lines = 300_000
    private val text = (0 until lines).joinToString("\n") { "l$it" }

    @Test fun typingNextToAHugeFoldAndSteppingOverIt() {
        val base = EditorState.create(text)
        val a = base.doc.lineStart(3) - 1
        val b = base.doc.lineStart(lines - 3) - 1
        val plugin = RangePlugin("fold", decorationsFacet, listOf(Ranged(a, b, Decoration.Replace(WidgetKey("fold", "f"), fold = true) as Decoration)))
        editorTest(EditorState.create(text, EditorSelection.cursor(1), extensions = plugin.extension)) { f ->
            assertEquals(0f, f.geometry.heights.height(lines / 2))
            fun keys(n: Int): Double {
                val t0 = System.nanoTime()
                repeat(n) {
                    val at = f.view.state.selection.main.head
                    f.view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "x")), selection = EditorSelection.cursor(at + 1), userEvent = "input"))
                    f.controller.syncFolds(f.view.state)
                }
                return (System.nanoTime() - t0) / 1e6 / n
            }
            keys(40) // warm-up
            val folded = minOf(keys(50), keys(50))
            // The same keystrokes on the same surface, unfolded: what a keystroke costs anyway on 300k lines.
            plugin.replace(f.view, emptyList())
            keys(40)
            val base = minOf(keys(50), keys(50))
            // Folded again for the rest of the test.
            val st = f.view.state
            plugin.replace(f.view, listOf(Ranged(st.doc.lineStart(3) - 1, st.doc.lineStart(lines - 3) - 1, Decoration.Replace(WidgetKey("fold", "f"), fold = true) as Decoration)))
            val perKey = folded - base
            println("PERF keystroke next to a 300k-line fold: $folded ms, unfolded $base ms")
            assertTrue(perKey < 0.5, "the fold costs ${perKey} ms per keystroke")
            waitForIdle()
            assertEquals(0f, f.geometry.heights.height(lines / 2), "the fold opened")
            // Down from line 2 (the fold's line) lands after it, in one step.
            fun overIt(): Double {
                f.view.dispatch(TransactionSpec(selection = EditorSelection.cursor(f.view.state.doc.lineStart(1))))
                val t1 = System.nanoTime()
                DefaultCommands.cursorDown.run(f.view)
                DefaultCommands.cursorDown.run(f.view)
                return (System.nanoTime() - t1) / 1e6
            }
            repeat(20) { overIt() } // warm-up
            val step = (1..5).minOf { overIt() }
            assertEquals(lines - 3, f.view.state.doc.lineIndexAt(f.view.state.selection.main.head))
            println("PERF two Down steps over a 300k-line fold: $step ms")
            assertTrue(step < 2.0, "stepping over a 300k-line fold took $step ms")
        }
    }
}
