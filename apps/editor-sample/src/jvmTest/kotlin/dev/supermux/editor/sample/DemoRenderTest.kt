package dev.supermux.editor.sample

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.packagedEditorFontFamily
import dev.supermux.editor.syntax.LanguageRegistry
import dev.supermux.editor.syntax.NativeBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The M3c demos render (a PNG of each under build/demo-renders for a look), and scrolling the demo
 * with its gutter markers, fold arrows and review-thread widget keeps its frames within budget.
 */
class DemoRenderTest {
    private val kotlin by lazy { runBlocking { SampleFiles.kotlin() } }

    private fun render(name: String, width: Int, height: Int, extraA: dev.supermux.editor.core.Extension, b: String? = null, frames: Int = 0, scroll: (Int) -> Unit = {}, content: @Composable (SampleSession?, SampleSession?) -> Unit): List<Double> {
        val queue = ConcurrentLinkedQueue<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob())
        val backend = NativeBackend().also { it.ensureLanguageNow("kotlin") }
        val a = SampleSession(kotlin, "kotlin", backend, LanguageRegistry.default, scope, extraA) { queue.add(it) }
        val sb = b?.let { SampleSession(it, "kotlin", backend, LanguageRegistry.default, scope) { r -> queue.add(r) } }
        val scene = ImageComposeScene(width, height, Density(2f)) { content(a, sb) }
        var time = 0L
        fun frame(): Double {
            val t0 = System.nanoTime()
            while (true) (queue.poll() ?: break).invoke()
            scene.render(time)
            time += 16_666_667L
            return (System.nanoTime() - t0) / 1e6
        }
        try {
            repeat(3) { runBlocking { a.worker.idle(); sb?.worker?.idle() }; frame() }
            val times = ArrayList<Double>()
            for (i in 0 until frames) { scroll(i); times += frame() }
            val img = scene.render(time)
            val out = File("build/demo-renders").apply { mkdirs() }
            File(out, "$name.png").writeBytes(img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            return times
        } finally {
            scene.close()
            a.close()
            sb?.close()
            scope.cancel()
        }
    }

    @Test fun theM3cDemoRendersAndScrollsWithinBudget() {
        var view: dev.supermux.editor.compose.EditorView? = null
        render("m3c-demo", 1100, 1400, M3cDemo.extension()) { a, _ ->
            val theme = EditorTheme.dark(packagedEditorFontFamily())
            view = a!!.view
            SampleEditorPane(a, theme, lineWrap = false, widgets = M3cDemo.rememberWidgets(theme.foreground, androidx.compose.ui.graphics.Color(0xFF151713)))
        }
        // Scrolling through markers, fold arrows and the thread: every frame's work.
        val times = render("m3c-demo-scrolled", 1100, 1400, M3cDemo.extension(), frames = 300, scroll = { view?.let { v -> (v.scrollState).scrollBy(0f, 45f) } }) { a, _ ->
            val theme = EditorTheme.dark(packagedEditorFontFamily())
            view = a!!.view
            SampleEditorPane(a, theme, lineWrap = false, widgets = M3cDemo.rememberWidgets(theme.foreground, androidx.compose.ui.graphics.Color(0xFF151713)))
        }
        val p95 = times.drop(10).sorted().let { it[it.size * 95 / 100] }
        println("PERF demo scroll (markers, fold arrows, a thread widget): frame p95 ${"%.2f".format(p95)} ms over ${times.size} frames")
        assertBudget(p95, 16.0, "demo scroll frame p95")
    }

    @Test fun theSideBySideDemoRendersAndScrollsWithinBudget() {
        var a: SampleSession? = null
        // The hunks: a deletion (a gap in B), an insertion (a gap in A), a changed run of another length.
        for ((name, line) in listOf("side-by-side-deleted" to 32, "side-by-side-inserted" to 92, "side-by-side-changed" to 140)) {
            render(name, 1400, 1200, dev.supermux.editor.core.extensionOf(), b = fakeWorkingCopy(kotlin), frames = 3, scroll = { i ->
                if (i == 0) a?.view?.let { v -> v.restoreScroll(dev.supermux.editor.compose.EditorScrollPosition(v.state.doc.lineStart(line))) }
            }) { sa, sb ->
                a = sa
                val theme = EditorTheme.dark(packagedEditorFontFamily())
                SideBySideDemo(sa!!, sb!!, theme)
            }
        }
        val times = render("side-by-side", 1400, 1200, dev.supermux.editor.core.extensionOf(), b = fakeWorkingCopy(kotlin), frames = 300, scroll = { a?.view?.scrollState?.scrollBy(0f, 45f) }) { sa, sb ->
            a = sa
            val theme = EditorTheme.dark(packagedEditorFontFamily())
            SideBySideDemo(sa!!, sb!!, theme)
        }
        val p95 = times.drop(10).sorted().let { it[it.size * 95 / 100] }
        println("PERF side-by-side scroll (the diff plugin's DiffPair: two linked editors, tints, folded runs, a thread): frame p95 ${"%.2f".format(p95)} ms over ${times.size} frames")
        assertBudget(p95, 16.0, "side-by-side scroll frame p95")
    }

    /** The sample's side-by-side pane: the diff plugin's pair of A and B, with a review thread on B. */
    @Composable
    private fun SideBySideDemo(a: SampleSession, b: SampleSession, theme: EditorTheme) {
        val pair = androidx.compose.runtime.remember(a, b) {
            val host = SampleReviewHost(SampleReviewHost.demoThreads().take(1))
            b.view.dispatch(dev.supermux.editor.core.TransactionSpec(effects = listOf(dev.supermux.editor.core.StateEffect.appendConfig.of(dev.supermux.editor.plugins.diff.review(host)))))
            dev.supermux.editor.plugins.diff.DiffPair(a.view, b.view, dev.supermux.editor.plugins.diff.DiffConfig(), host).also { host.view = b.view }
        }
        dev.supermux.editor.plugins.diff.SideBySideDiff(pair, androidx.compose.ui.Modifier, theme = theme, widgets = rememberSearchWidgets(dev.supermux.editor.plugins.diff.rememberDiffWidgets()))
    }

    @Test fun theWalkthroughRenders() {
        render("walkthrough", 1100, 1400, dev.supermux.editor.core.extensionOf()) { a, _ ->
            val theme = EditorTheme.dark(packagedEditorFontFamily())
            val host = androidx.compose.runtime.remember { SampleReviewHost(SampleReviewHost.demoThreads()) }
            val view = androidx.compose.runtime.remember {
                dev.supermux.editor.compose.EditorView(dev.supermux.editor.core.EditorState.create(fakeWorkingCopy(kotlin), extensions = dev.supermux.editor.core.extensionOf(
                    dev.supermux.editor.plugins.diff.inlineDiff(kotlin, dev.supermux.editor.plugins.diff.DiffConfig(editable = false, context = 20)),
                    dev.supermux.editor.plugins.diff.review(host),
                ))).also { v ->
                    host.view = v
                    v.restoreScroll(dev.supermux.editor.compose.EditorScrollPosition(v.state.doc.lineStart(80)))
                }
            }
            dev.supermux.editor.plugins.diff.InlineDiffEditor(view, androidx.compose.ui.Modifier, theme = theme)
        }
    }
}
