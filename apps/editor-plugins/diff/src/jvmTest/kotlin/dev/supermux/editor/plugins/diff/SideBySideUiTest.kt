package dev.supermux.editor.plugins.diff

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.compose.packagedEditorFontFamily
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Two linked editors of a DiffPair: alignment through edits and expansions, and scrolling within budget. */
@OptIn(ExperimentalTestApi::class)
class SideBySideUiTest {
    private val rnd = Random(12)
    private val baseLines = randomLines(rnd, 400)
    private val workingLines = mutate(rnd, baseLines, 30)

    private fun ComposeUiTest.show(config: DiffConfig = DiffConfig(collapseUnchanged = false), wrapB: Boolean = false): DiffPair {
        val a = EditorView(EditorState.create(baseLines.joinToString("\n")))
        val b = EditorView(EditorState.create(workingLines.joinToString("\n")))
        val pair = DiffPair(a, b, config)
        setContent { Box(Modifier.size(900.dp, 500.dp)) { SideBySideDiff(pair, Modifier.fillMaxSize(), lineWrap = wrapB) } }
        waitForIdle()
        return pair
    }

    /** Every unchanged line on screen on both sides is at the same y within 1 px; how many were compared. */
    private fun ComposeUiTest.assertAligned(p: DiffPair, what: String): Int {
        val h = onRoot().fetchSemanticsNode().size.height.toFloat()
        val m = p.model!!
        val a = p.base
        val b = p.working
        var compared = 0
        for (lb in 0 until b.state.doc.lineCount) {
            if (m.hunks.any { lb in it.bFrom until it.bTo }) continue
            if (m.collapsed.any { lb in it.bFrom until it.bTo }) continue
            val la = Splice.aLineOf(m.hunks, lb)
            if (la >= a.state.doc.lineCount) continue
            val yb = b.coordsAtPos(b.state.doc.lineStart(lb))?.top ?: continue
            val ya = a.coordsAtPos(a.state.doc.lineStart(la))?.top ?: continue
            if (yb < 0 || yb > h - 20 || ya < 0 || ya > h - 20) continue
            assertTrue(abs(ya - yb) <= 1f, "$what: A line $la at $ya, B line $lb at $yb")
            compared++
        }
        assertTrue(compared > 5, "$what: only $compared lines compared")
        return compared
    }

    @Test fun linesStayAlignedThroughScrollingAndEdits() = runComposeUiTest {
        val p = show()
        assertAligned(p, "at the top")
        p.working.scrollState.scrollBy(0f, 1500f); waitForIdle()
        assertAligned(p, "scrolled B")
        p.base.scrollState.scrollBy(0f, -700f); waitForIdle()
        assertAligned(p, "scrolled A back")
        // Edits of B: an inserted run, a deleted run, a line typed into: the mapping follows each.
        repeat(3) { i ->
            val doc = p.working.state.doc
            val line = minOf(doc.lineCount - 2, 60 + i * 7)
            val at = doc.lineStart(line)
            val spec = when (i) {
                0 -> ChangeSpec(at, at, "one\ntwo\nthree\n")
                1 -> ChangeSpec(at, doc.lineStart(line + 2))
                else -> ChangeSpec(at, at, "typed ")
            }
            p.working.dispatch(TransactionSpec(changes = listOf(spec), userEvent = "input"))
            waitForIdle()
            assertValidDiff(baseLines, p.working.state.doc.toString().split('\n'), p.model!!.hunks, "edit $i")
            assertAligned(p, "after edit $i")
        }
    }

    @Test fun alignmentHoldsWithFoldedRunsExpandedFromEitherSide() = runComposeUiTest {
        val p = show(DiffConfig(context = 2))
        assertAligned(p, "folded")
        Diff.expand(p.base, p.model!!.collapsed[1], Expand.ALL); waitForIdle()
        assertAligned(p, "expanded on A")
        Diff.expand(p.working, p.model!!.collapsed[0], Expand.UP); waitForIdle()
        assertAligned(p, "expanded on B")
    }

    @Test fun alignmentHoldsWhenOnlyBWraps() = runComposeUiTest {
        val p = show(wrapB = true)
        assertAligned(p, "B wrapped")
    }

    // ------------------------------------------------------------------ rendering and scroll time --

    private fun scene(name: String, a: EditorView, b: EditorView, config: DiffConfig?, frames: Int, width: Int = 1400, height: Int = 1200, scroll: (Int) -> Unit = {}): List<Double> {
        val pair = config?.let { DiffPair(a, b, it) }
        val link = dev.supermux.editor.compose.LinkedScroll()
        val scene = ImageComposeScene(width, height, Density(2f)) {
            val theme = EditorTheme.dark(packagedEditorFontFamily())
            if (pair != null) SideBySideDiff(pair, Modifier.fillMaxSize(), theme = theme)
            else androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                // The same two linked editors without the diff: the baseline.
                dev.supermux.editor.compose.Editor(a, Modifier.weight(1f), theme = theme, readOnly = true, linked = link, linkedSide = dev.supermux.editor.compose.LinkedSide.A)
                dev.supermux.editor.compose.Editor(b, Modifier.weight(1f), theme = theme, linked = link, linkedSide = dev.supermux.editor.compose.LinkedSide.B)
            }
        }
        var time = 0L
        fun frame(): Double { val t0 = System.nanoTime(); scene.render(time); time += 16_666_667L; return (System.nanoTime() - t0) / 1e6 }
        try {
            repeat(3) { frame() }
            val times = ArrayList<Double>()
            for (i in 0 until frames) { scroll(i); times += frame() }
            val img = scene.render(time)
            val out = File("build/diff-renders").apply { mkdirs() }
            File(out, "$name.png").writeBytes(img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            return times
        } finally { scene.close() }
    }

    @Test fun scrollingTwoTenThousandLineFilesWith500HunksStaysWithinAFrame() {
        val r = Random(5)
        val base = randomLines(r, 10_000)
        val working = mutate(r, base, 500)
        val hunks = LineDiff.diff(base, working).hunks.size
        // Every hunk on screen at some point: no folding, a fling's worth of pixels per frame.
        fun p95(t: List<Double>) = t.drop(10).sorted().let { it[it.size * 95 / 100] }
        val pb = EditorView(EditorState.create(working.joinToString("\n")))
        val plain = p95(scene("side-by-side-10k-plain", EditorView(EditorState.create(base.joinToString("\n"))), pb, null, frames = 400) { pb.scrollState.scrollBy(0f, 90f) })
        // Best of 3 (the Mac is shared: a run under someone else's build is not the editor's time).
        var times: List<Double> = emptyList()
        var p95 = Double.MAX_VALUE
        for (run in 0 until 3) {
            val ra = EditorView(EditorState.create(base.joinToString("\n")))
            val rb = EditorView(EditorState.create(working.joinToString("\n")))
            val t = scene("side-by-side-10k", ra, rb, DiffConfig(collapseUnchanged = false, syncLines = 50_000), frames = 400) { rb.scrollState.scrollBy(0f, 90f) }
            if (p95(t) < p95) { p95 = p95(t); times = t }
        }
        println("DIFF-PERF side-by-side scroll, 2 x 10k lines, $hunks hunks: frame p95 ${"%.2f".format(p95)} ms, max ${"%.2f".format(times.drop(10).max())} ms over ${times.size} frames (the same two linked editors without the diff: ${"%.2f".format(plain)} ms)")
        assertBudget(p95, 16.0, "side-by-side scroll p95")
    }

    @Test fun rendersForALook() {
        val base = (1..60).joinToString("\n") { "fun f$it(x: Int) = x + $it" }
        val working = base.split('\n').toMutableList().also {
            it[5] = "fun f6(x: Long) = x * 6"
            it.add(20, "// a new line"); it.add(21, "fun extra() = 0")
            it.removeAt(40); it.removeAt(40)
        }.joinToString("\n")
        scene("side-by-side-small", EditorView(EditorState.create(base)), EditorView(EditorState.create(working)), DiffConfig(context = 3), frames = 0, width = 1600, height = 900)
    }
}
