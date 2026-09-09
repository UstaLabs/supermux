package dev.supermux.ui.editor

import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.EngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSLog

/**
 * iOS's `Platform.editorEngine` — the twin of `AndroidEditorEngineFactory`, and for the same
 * reasons.
 *
 * [state] is [EngineState.Ready] for the life of the process: `WKWebView` needs no process-wide
 * bring-up (that is desktop's JCEF problem), so there is nothing to wait for and [ensureInit] has
 * nothing to do.
 *
 * ⚠️ A DEAD CONTENT PROCESS MUST NOT LATCH HERE, exactly as on Android. WebKit reclaiming a
 * backgrounded content process is routine, and recoverable — the engine re-issues its own
 * `loadFileURL` in `webViewWebContentProcessDidTerminate` (a bare `reload()` would come back
 * without the directory read-access scope and render a blank page). Flipping this flow to
 * [EngineState.Failed] would take EVERY editor in the app to its native fallback until relaunch.
 * Renderer loss therefore stays on the ENGINE's own `failed` flow, which fails the one surface that
 * saw it — and only after a SECOND termination with no successful load in between, which is a page
 * crashing its own renderer rather than the system reclaiming it.
 *
 * An `object` rather than a class because it holds nothing: unlike Android's, which carries an
 * activity-context provider (the display density a WebView is born with is load-bearing there), an
 * iOS web view needs no construction context at all.
 */
object IosEditorEngineFactory : EditorEngineFactory {
    private val _state = MutableStateFlow<EngineState>(EngineState.Ready)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    /**
     * Pre-warm, like Android.
     *
     * The cost is smaller than Android's Chromium bring-up but the same shape: the first
     * `WKWebView` in a process spins up a content process and a network process, which is hundreds
     * of milliseconds that must not land in the frame that opens a file. Desktop's `false` is a
     * different problem entirely — a windowed CEF browser laid out at 0×0 never loads at all.
     */
    override val prewarmHost: Boolean = true

    override fun ensureInit() = Unit

    override fun create(lineWrap: Boolean, fontSize: Int): EditorEngine =
        WKWebViewEditorEngine(lineWrap, fontSize, ::handleRendererGone)

    /** Deliberately does NOT touch [state] — see the class note. */
    internal fun handleRendererGone(why: String) {
        NSLog("[IosEditorEngine] %s; reloading this surface's web view", why)
    }
}
