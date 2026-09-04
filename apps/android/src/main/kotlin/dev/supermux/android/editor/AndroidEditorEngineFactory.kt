package dev.supermux.android.editor

import android.content.Context
import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.EngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android's `Platform.editorEngine`.
 *
 * [state] starts — and normally stays — [EngineState.Ready]: a WebView needs no process-wide
 * bring-up, so there is nothing to wait for and [ensureInit] has nothing to do. It flips to
 * [EngineState.Failed] only when a renderer actually dies: WebView's render process is shared, so a
 * crash is rarely specific to one editor, and handing out another engine that will die the same way
 * just makes every pane flicker into the same fallback one at a time.
 *
 * @param context the ACTIVITY context — see [AndroidEditorEngine] on why the display density makes
 *   this load-bearing.
 */
class AndroidEditorEngineFactory(private val context: Context) : EditorEngineFactory {
    private val _state = MutableStateFlow<EngineState>(EngineState.Ready)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    override fun ensureInit() = Unit

    override fun create(lineWrap: Boolean, fontSize: Int): EditorEngine =
        AndroidEditorEngine(context, lineWrap, fontSize) { why -> _state.value = EngineState.Failed(why) }
}
