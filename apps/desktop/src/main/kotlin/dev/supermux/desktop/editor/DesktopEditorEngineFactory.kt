// Desktop's `Platform.editorEngine`: the shared editor surface asks THIS for a browser, and gets a
// direct-JCEF one. Everything JCEF-shaped that the surface used to carry itself — the process-global
// runtime state machine, the "init after the window exists" rule, the bundle extraction — is behind
// these three members.
package dev.supermux.desktop.editor

import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Wraps the process-global [JcefRuntime].
 *
 * [state] is that runtime's own lifecycle in the shared vocabulary: `Idle` and `Initializing` both
 * read as [EngineState.Initializing] (from a surface's point of view "nothing started yet" and
 * "starting" call for the same strip, and [ensureInit] runs on the first mount regardless), `Ready`
 * lets [create] build a browser, and `Error` is terminal — every editor pane then draws its native
 * fallback and says why.
 */
class DesktopEditorEngineFactory(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** `file://…/index.html` for the extracted bundle. Only called once JCEF is Ready. */
    private val indexUrlProvider: () -> String? = { defaultIndexUrl() },
) : EditorEngineFactory {

    override val state: StateFlow<EngineState> = JcefRuntime.state
        .map { it.asEngineState() }
        .stateIn(scope, SharingStarted.Eagerly, JcefRuntime.state.value.asEngineState())

    /** Idempotent (the runtime holds a started-CAS). Runs the native bring-up off the EDT. */
    override fun ensureInit() = JcefRuntime.ensureInit(scope)

    /** Throws if the bundle cannot be extracted; the surface catches that and falls back natively. */
    override fun create(lineWrap: Boolean, fontSize: Int): EditorEngine {
        val url = indexUrlProvider() ?: error("editor bundle unavailable")
        return DesktopEditorEngine(url, lineWrap, fontSize)
    }

    /** Stop mirroring the runtime state. Called at app shutdown, next to [JcefRuntime.dispose]. */
    fun dispose() = scope.cancel()

    companion object {
        /**
         * The ONE factory for the process. It wraps a process-global runtime and holds a
         * long-lived scope for its state mirror, so a per-window instance would mean a per-window
         * never-cancelled scope (detached workspace windows come and go) mirroring the same flow.
         */
        val shared: DesktopEditorEngineFactory by lazy { DesktopEditorEngineFactory() }
    }
}

private fun JcefState.asEngineState(): EngineState = when (this) {
    JcefState.Idle -> EngineState.Initializing()
    JcefState.Initializing -> EngineState.Initializing(message = "Starting editor…")
    JcefState.Ready -> EngineState.Ready
    is JcefState.Error -> EngineState.Failed(msg)
}

/** `file://…/index.html` for the extracted bundle, or null if extraction fails. Only called once
 *  JCEF is Ready, so touching the classpath here is on the live path. */
internal fun defaultIndexUrl(): String? = runCatching {
    EditorWebAssets.extractTo(JcefRuntime.editorWebDir()).toUri().toString()
}.getOrNull()
