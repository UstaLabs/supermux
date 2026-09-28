package dev.supermux.editor.plugins.lint

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.gutterMarkersFacet
import kotlin.test.Test
import kotlin.test.assertTrue

/** 10,000 diagnostics: a keystroke maps them (and their marks and markers), never rebuilds: under 2 ms. */
class LintPerfTest {
    @Test fun aKeystrokeWithTenThousandDiagnosticsTakesUnderTwoMilliseconds() {
        val text = (0 until 10_000).joinToString("\n") { "val item$it = compute($it) // TODO" } + "\n"
        val view = EditorView(EditorState.create(text, EditorSelection.cursor(text.length / 2), lint()))
        val diags = (0 until 10_000).map { i ->
            val start = view.state.doc.lineStart(i)
            Diagnostic(start + 4, start + 8, Severity.entries[i % 4], "problem $i", "perf", id = "d$i")
        }
        view.dispatch(Lint.setDiagnostics(view.state, diags))
        var best = Double.MAX_VALUE
        repeat(5) { round ->
            val times = ArrayList<Double>()
            repeat(100) {
                val at = view.state.selection.main.head
                val t0 = System.nanoTime()
                view.dispatch(TransactionSpec(changes = listOf(ChangeSpec(at, at, "x")), selection = EditorSelection.cursor(at + 1), userEvent = "input.type"))
                view.state.facet(decorationsFacet); view.state.facet(gutterMarkersFacet)
                times += (System.nanoTime() - t0) / 1e6
            }
            times.sort()
            if (round > 0) best = minOf(best, times[95])
        }
        println("LINT-PERF 10k diagnostics: keystroke p95 ${"%.3f".format(best)} ms (best of 4)")
        assertTrue(best < 2.0, "a keystroke with 10k diagnostics took $best ms")
        assertTrue(Lint.diagnostics(view.state).size == 10_000)
    }
}
