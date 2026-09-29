package dev.supermux.editor.plugins.fold

import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorState
import kotlin.test.Test
import kotlin.test.assertTrue

/** The plan's budget: foldAll over a 10k-line file under 100 ms (JVM; the iOS test binaries are Debug). */
class FoldPerfTest {
    @Test fun foldAllOn10kLinesStaysUnder100ms() {
        val v = EditorView(EditorState.create(FoldPerf.bigText, extensions = fold()))
        val best = FoldPerf.bestOf3(v)
        assertTrue(best < 100, "foldAll over 10k lines took $best ms")
    }
}
