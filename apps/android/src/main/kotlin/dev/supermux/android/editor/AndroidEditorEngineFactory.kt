package dev.supermux.android.editor

import android.content.Context
import android.util.Log
import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.EngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android's `Platform.editorEngine`.
 *
 * [state] is [EngineState.Ready] for the life of the process: a WebView needs no bring-up, so there
 * is nothing to wait for and [ensureInit] has nothing to do.
 *
 * ⚠️ A DEAD RENDERER MUST NOT LATCH HERE. `onRenderProcessGone` is routine on Android — the system
 * reclaims a backgrounded renderer with `didCrash=false` — and a crashed one is recoverable by
 * simply building another WebView. Flipping this flow to [EngineState.Failed] would take EVERY
 * editor in the app to its native fallback until restart, and keep it there. So renderer loss stays
 * on the ENGINE's own `failed` flow, which fails exactly the one surface that saw it; that surface's
 * next mount calls [create] again and gets a fresh, working WebView. [handleRendererGone] exists to
 * log it and to hold that rule under test.
 *
 * @param context supplies the ACTIVITY context per engine — see [AndroidEditorEngine] on why the
 *   display density makes this load-bearing. A provider (not the Context itself) so this class can
 *   be unit-tested without one.
 */
class AndroidEditorEngineFactory(
    private val context: () -> Context,
    /** Injected so the rule below is unit-testable: `android.util.Log` is not mocked off-device. */
    private val log: (String) -> Unit = { Log.w("AndroidEditorEngine", it) },
) : EditorEngineFactory {
    private val _state = MutableStateFlow<EngineState>(EngineState.Ready)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /** Pre-warm: creating the first WebView is the expensive step, and it must not land in the
     *  frame that opens a file. */
    override val prewarmHost: Boolean = true

    override fun ensureInit() = Unit

    override fun create(lineWrap: Boolean, fontSize: Int): EditorEngine =
        AndroidEditorEngine(context(), lineWrap, fontSize, ::handleRendererGone)

    /** Deliberately does NOT touch [state] — see the class note. */
    internal fun handleRendererGone(why: String) {
        log("renderer lost ($why); the next editor mount builds a fresh WebView")
    }
}
