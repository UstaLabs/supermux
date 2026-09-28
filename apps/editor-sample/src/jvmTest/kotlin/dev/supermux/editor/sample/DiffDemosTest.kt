package dev.supermux.editor.sample

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.plugins.diff.Diff
import dev.supermux.editor.plugins.diff.Review
import dev.supermux.editor.syntax.NativeBackend
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sample app's two diff demos open on the plugin: the walkthrough and the side-by-side pair
 * (the app's own wiring: its host, DiffPair, the widgets). A scene driven frame by frame (the app's
 * frame-stats loop never lets a UI test go idle).
 */
class DiffDemosTest {
    @Test fun theWalkthroughAndTheSideBySideDemosOpen() {
        val views = ArrayList<EditorView>()
        // The scene's UI thread is this one: its coroutines are queued and run between frames (the
        // default, Unconfined, would resume them on the syntax worker's thread).
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val ui = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queue.add(block) }
        }
        val offBefore = dev.supermux.editor.compose.EditorDiagnostics.offThreadDispatches
        val scene = ImageComposeScene(2400, 1800, Density(2f), coroutineContext = ui) {
            SampleApp(loadBackend = { NativeBackend().also { it.ensureLanguageNow("kotlin") } }, initialFile = SampleFile.WALKTHROUGH, onView = { views += it })
        }
        var time = 0L
        fun until(what: String, p: () -> Boolean) {
            val end = System.currentTimeMillis() + 20_000
            while (!p()) {
                check(System.currentTimeMillis() < end) { "timed out: $what" }
                while (true) (queue.poll() ?: break).run()
                scene.render(time); time += 16_666_667L
                Thread.sleep(5)
            }
            repeat(5) { while (true) (queue.poll() ?: break).run(); scene.render(time); time += 16_666_667L }
        }
        try {
            until("the walkthrough's diff") { views.lastOrNull()?.let { Diff.model(it.state)?.ready } == true }
            val w = views.last()
            assertEquals(4, Diff.model(w.state)!!.hunks.size, "the fake working copy's four changes")
            assertEquals(listOf("w1", "w2"), Review.threads(w.state).map { it.first.id })
            assertTrue(Review.composer(w.state) != null, "the walkthrough opens with the host's composer")
            assertTrue(Diff.model(w.state)!!.config.let { !it.editable && it.context == 20 })
            val out = File("build/demo-renders").apply { mkdirs() }
            File(out, "app-walkthrough.png").writeBytes(scene.render(time).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            // The side-by-side demo: A (the session's view) shows B's model; B holds it.
            sampleFileOpener!!(SampleFile.SIDE_BY_SIDE)
            until("the side-by-side pair") { views.last() !== w && Diff.hunks(views.last().state).size == 4 }
            assertTrue(Diff.model(views.last().state) == null, "A shows B's model and holds none of its own")
            assertEquals(offBefore, dev.supermux.editor.compose.EditorDiagnostics.offThreadDispatches, "every dispatch on the UI thread")
            File(out, "app-side-by-side.png").writeBytes(scene.render(time).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        } finally {
            // (Compose 1.12's scene teardown can throw "LayoutNode not found in RectList" for the
            // app's popups; the test is about what was shown, not the teardown.)
            runCatching { scene.close() }
        }
    }
}
