package dev.supermux.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.editor.engine.EDITOR_READY_TIMEOUT_MS
import dev.supermux.ui.editor.engine.EngineState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The shared editing surface, driven entirely through the [FakeEditorEngineFactory] seam — the
 * engine is real code (the shared [dev.supermux.ui.editor.engine.EditorPushPlanner]) with no browser
 * behind it, so every branch of the state machine is exercised without JCEF or a WebView.
 */
@OptIn(ExperimentalTestApi::class)
class EditorSurfaceTest {

    @Test
    fun a_ready_runtime_shows_the_web_area_and_starts_the_runtime_once() = runComposeUiTest {
        val factory = FakeEditorEngineFactory()
        setContent { Surface(factory) }
        waitForIdle()

        onNodeWithTag("editor_web_area").assertIsDisplayed()
        assertEquals(1, factory.inits, "the surface must kick the (idempotent) runtime init on mount")
        assertEquals(1, factory.created.size, "exactly one engine per Ready runtime")
    }

    @Test
    fun an_initializing_runtime_shows_the_strip_and_builds_no_engine() = runComposeUiTest {
        val factory = FakeEditorEngineFactory(EngineState.Initializing())
        setContent { Surface(factory) }
        waitForIdle()

        onNodeWithTag("editor_initializing").assertIsDisplayed()
        assertTrue(factory.created.isEmpty(), "no browser may be created optimistically")
    }

    @Test
    fun a_failed_runtime_falls_back_to_the_native_editor_with_the_reason() = runComposeUiTest {
        setContent { Surface(FakeEditorEngineFactory(EngineState.Failed("no chromium"))) }
        waitForIdle()

        onNodeWithTag("editor_native_fallback").assertIsDisplayed()
        onNodeWithText("Native editor (embedded browser failed: no chromium)").assertIsDisplayed()
    }

    @Test
    fun a_renderer_that_dies_falls_back_with_its_own_reason() = runComposeUiTest {
        val factory = FakeEditorEngineFactory()
        setContent { Surface(factory) }
        waitForIdle()
        onNodeWithTag("editor_web_area").assertIsDisplayed()

        factory.engine!!.fail("renderer gone")
        waitForIdle()

        onNodeWithTag("editor_native_fallback").assertIsDisplayed()
        onNodeWithText("Native editor (embedded browser failed: renderer gone)").assertIsDisplayed()
    }

    @Test
    fun the_document_and_the_reveal_reach_the_engine_in_planner_order() = runComposeUiTest {
        val factory = FakeEditorEngineFactory(startReady = false)
        setContent { Surface(factory, content = "hello", filename = "a.kt", revealLine = 4 to null) }
        waitForIdle()
        val engine = factory.engine!!
        assertEquals(emptyList<String>(), engine.js, "nothing may reach cm6 before it has first-painted")

        engine.markReady()

        // A path change pushes the WHOLE document (content, language, wrap, font, scroll) and the
        // reveal lands after it — never before, or cmSetScrollTop would undo the jump.
        assertEquals(
            listOf(
                "cmSetContent(\"hello\")",
                "cmSetLanguage(\"a.kt\")",
                "cmSetLineWrap(true)",
                "cmSetFontSize(13)",
                "cmSetScrollTop(0)",
                "cmRevealLine(4, -1)",
            ),
            engine.js,
        )
    }

    @Test
    fun wrap_and_zoom_go_to_the_live_engine_instead_of_rebuilding_it() = runComposeUiTest {
        val factory = FakeEditorEngineFactory()
        var wrap by mutableStateOf(true)
        var font by mutableStateOf(13)
        setContent { Surface(factory, lineWrap = wrap, fontSize = font, filename = "a.kt") }
        waitForIdle()
        val engine = factory.engine!!
        engine.js.clear()

        wrap = false
        font = 18
        waitForIdle()

        assertEquals(listOf("cmSetLineWrap(false)", "cmSetFontSize(18)"), engine.js)
        assertEquals(1, factory.created.size, "a wrap/zoom change must not rebuild the engine")
        assertEquals(0, engine.disposed, "…nor dispose the live one")
    }

    @Test
    fun an_engine_that_never_first_paints_falls_back_after_the_ready_timeout() = runComposeUiTest {
        val factory = FakeEditorEngineFactory(startReady = false)
        mainClock.autoAdvance = false
        setContent { Surface(factory, filename = "a.kt") }
        mainClock.advanceTimeBy(100)
        onNodeWithTag("editor_web_area").assertIsDisplayed() // still covered, still hoping

        mainClock.advanceTimeBy(EDITOR_READY_TIMEOUT_MS + 500)

        onNodeWithTag("editor_native_fallback").assertIsDisplayed()
        onNodeWithText("Native editor (rich editor unavailable)").assertIsDisplayed()
    }

    @Test
    fun the_engine_is_disposed_when_the_surface_leaves() = runComposeUiTest {
        val factory = FakeEditorEngineFactory()
        var show by mutableStateOf(true)
        setContent { if (show) Surface(factory) }
        waitForIdle()
        val engine = factory.engine!!

        show = false
        waitForIdle()

        assertEquals(1, engine.disposed)
    }
}

@androidx.compose.runtime.Composable
private fun Surface(
    factory: FakeEditorEngineFactory,
    content: String = "",
    filename: String = "a.kt",
    lineWrap: Boolean = true,
    fontSize: Int = 13,
    revealLine: Pair<Int, Int?>? = null,
) {
    Box(Modifier.size(400.dp, 300.dp)) {
        EditorSurface(
            content = content,
            filename = filename,
            lineWrap = lineWrap,
            fontSize = fontSize,
            scrollTop = 0,
            revealLine = revealLine,
            onChange = {},
            onSave = {},
            onRevealConsumed = {},
            onFontSize = {},
            factory = factory,
        )
    }
}
