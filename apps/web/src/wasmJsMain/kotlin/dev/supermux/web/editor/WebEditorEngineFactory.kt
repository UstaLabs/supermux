package dev.supermux.web.editor

import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.EngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * `Platform.editorEngine` for the browser.
 *
 * [state] is permanently [EngineState.Ready] and [ensureInit] is a no-op, because — unlike
 * desktop's JCEF, whose native bring-up is a real state machine — there is no runtime to start
 * here: the host already IS a browser. A probe of `editor/index.html` before the first mount would
 * only move a failure earlier without making it more informative; a page that does not load
 * reports itself through [WebEditorEngine.failed] (its 8 s ready timeout names the URL), which is
 * the path a mid-session failure has to take anyway.
 *
 * [prewarmHost] stays false, Android's reason inverted: a frame is cheap to create, and a frame
 * mounted into the 0×0 box a hidden pane gets would first-paint cm6 against an empty viewport.
 * `EditorEngineHost` therefore only attaches once Compose has given the container a real size.
 */
class WebEditorEngineFactory : EditorEngineFactory {
    override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Ready).asStateFlow()

    override fun ensureInit() = Unit

    override fun create(lineWrap: Boolean, fontSize: Int): EditorEngine =
        WebEditorEngine(lineWrap, fontSize)
}
