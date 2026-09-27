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
        val sb = b?.let { SampleSession(it, "kotlin", backend, LanguageRegistry.default, scope, sideBySideExtension) { r -> queue.add(r) } }
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
        assertTrue(p95 <= 16.0, "demo scroll frame p95 $p95 ms")
    }

    @Test fun theSideBySideDemoRendersAndScrollsWithinBudget() {
        var a: SampleSession? = null
        // The hunks: a deletion (a gap in B), an insertion (a gap in A), a changed run of another length.
        for ((name, line) in listOf("side-by-side-deleted" to 32, "side-by-side-inserted" to 92, "side-by-side-changed" to 140)) {
            render(name, 1400, 1200, sideBySideExtension, b = fakeWorkingCopy(kotlin), frames = 3, scroll = { i ->
                if (i == 0) a?.view?.let { v -> v.restoreScroll(dev.supermux.editor.compose.EditorScrollPosition(v.state.doc.lineStart(line))) }
            }) { sa, sb ->
                a = sa
                val theme = EditorTheme.dark(packagedEditorFontFamily())
                SideBySidePane(sa!!, sb!!, theme, null) {}
            }
        }
        val times = render("side-by-side", 1400, 1200, sideBySideExtension, b = fakeWorkingCopy(kotlin), frames = 300, scroll = { a?.view?.scrollState?.scrollBy(0f, 45f) }) { sa, sb ->
            a = sa
            val theme = EditorTheme.dark(packagedEditorFontFamily())
            SideBySidePane(sa!!, sb!!, theme, null) {}
        }
        val p95 = times.drop(10).sorted().let { it[it.size * 95 / 100] }
        println("PERF side-by-side scroll (two linked editors, gaps, tints): frame p95 ${"%.2f".format(p95)} ms over ${times.size} frames")
        assertTrue(p95 <= 16.0, "side-by-side scroll frame p95 $p95 ms")
    }
}
