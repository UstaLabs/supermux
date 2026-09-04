package dev.supermux.android.editor

import dev.supermux.ui.editor.engine.EngineState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A dead renderer must stay a PER-ENGINE failure.
 *
 * `onRenderProcessGone` is routine on Android: the system reclaims a backgrounded renderer with
 * `didCrash = false`, and even a real crash is recovered by building another WebView. Routing it
 * into the factory's state would take every editor in the app to the native text fallback until the
 * process restarts — a whole-app degradation triggered by ordinary memory pressure. The engine's own
 * `failed` flow fails exactly the surface that saw it; the next mount asks for a fresh engine.
 *
 * No WebView and no Context: the factory takes a context PROVIDER, and this test never calls it.
 */
class AndroidEditorEngineFactoryTest {

    private val logged = mutableListOf<String>()
    private val factory = AndroidEditorEngineFactory(
        context = { error("no Context in a unit test") },
        log = { logged += it },
    )

    @Test
    fun `the factory is ready from the first frame`() {
        assertEquals(EngineState.Ready, factory.state.value)
    }

    @Test
    fun `a renderer loss leaves the factory ready so the next editor gets a fresh WebView`() {
        factory.handleRendererGone("renderer gone (didCrash=false)")
        factory.handleRendererGone("renderer gone (didCrash=true)")

        assertEquals(
            EngineState.Ready, factory.state.value,
            "a dead renderer must not latch the whole app into the native fallback",
        )
        assertEquals(2, logged.size, "…but it is still worth saying out loud")
    }

    @Test
    fun `android pre-warms the view host`() {
        assertTrue(factory.prewarmHost, "creating the first WebView must not land in the open-file frame")
    }
}
