// The editor ENGINE seam: what a shared editor surface may ask of the browser that actually hosts
// CodeMirror. Android drives an `android.webkit.WebView`, desktop drives a direct-JCEF browser; both
// run the SAME committed cm6 bundle and both speak the SAME `cm*` JS (built by the pure helpers in
// EditorBridge.kt), so everything above this interface — the surface, the panes, the walkthrough —
// is written once in `:ui`.
//
// Nothing here touches a platform type. An engine is created by an [EditorEngineFactory] that each
// app installs on its `Platform`; the surface reads it from `LocalPlatform` and never names a
// WebView or a CefBrowser.
package dev.supermux.ui.editor.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything a shared editor surface pushes into (and reads back from) one live CodeMirror.
 *
 * Ordering, echo-skip and the queue-until-first-paint are NOT the implementations' business: both
 * engines route every Kotlin→JS call through the shared [EditorPushPlanner] and forward the JS
 * strings it returns. What stays per-platform is only "how do I evaluate this JS", "how do I get a
 * view", and "how do inbound bridge calls reach me".
 */
interface EditorEngine {
    /** cm6 first-paint gate: false until the bundle's `onReady` fires (and again if the renderer dies). */
    val ready: StateFlow<Boolean>

    /** A terminal per-engine failure (renderer gone, page load error) as a human reason, else null. */
    val failed: StateFlow<String?>

    /**
     * Where inbound bridge events land. Written from composition on every recomposition (the whole
     * record at once, so a caller can never leave half of it stale) and invoked on the UI thread.
     */
    var callbacks: EditorCallbacks

    /** Push a document. [path] drives the language mode; a same-path same-content push is a no-op. */
    fun setDocument(path: String, content: String, scrollTop: Int = 0)

    /** Scroll to a 1-indexed [line] (optional [endLine]); queued until [ready]. */
    fun revealLine(line: Int, endLine: Int? = null)

    fun setFontSize(px: Int)
    fun setLineWrap(on: Boolean)
    fun setScrollTop(px: Int)

    /** Read cm6's scroll offset (px). Async; fires 0 when there is no live view. */
    fun readScrollTop(cb: (Int) -> Unit)

    /** Read the live document. Async; fires "" when there is no live view. */
    fun getContent(cb: (String) -> Unit)

    fun lspConnect(serverId: String, rootUri: String, fileUri: String, languageId: String)
    fun lspMessage(serverId: String, message: String)
    fun lspDisconnect()

    /** Switch CodeMirror into the read-only walkthrough renderer. Calls before [ready] are queued. */
    fun showDiffRegion(
        path: String,
        content: String,
        ranges: List<DiffRegionRange>,
        language: String,
        restoreScrollTop: Int? = null,
        threads: List<DiffRegionThread> = emptyList(),
        composer: DiffRegionComposer? = null,
    )

    /** Refresh threads / composer inside the region established by [showDiffRegion]. */
    fun updateDiffThreads(threads: List<DiffRegionThread>, composer: DiffRegionComposer?)

    /** Tear the browser down. Idempotent. */
    fun dispose()
}

/**
 * The inbound half of the bridge, as ONE value so the surface can rebind it in a single assignment.
 * Every member defaults to a no-op: a surface that only edits text sets `onChange`/`onSave` and
 * leaves the walkthrough's seven diff/comment hooks alone.
 */
data class EditorCallbacks(
    /** cm6's document changed (the full text). */
    val onChange: (String) -> Unit = {},
    /** Mod-S inside the editor. */
    val onSave: () -> Unit = {},
    /** Outbound LSP JSON-RPC, already parsed into (serverId, message). */
    val onLspOut: (serverId: String, message: String) -> Unit = { _, _ -> },
    /** A user zoom (pinch/keyboard) already applied in-page — persist it, do not push it back. */
    val onFontSize: (Int) -> Unit = {},
    val onDiffLineClick: (Int) -> Unit = {},
    val onDiffExpand: (String) -> Unit = {},
    val onDiffPage: (String) -> Unit = {},
    val onCommentSubmit: (line: Int, text: String) -> Unit = { _, _ -> },
    val onReplySubmit: (threadId: String, text: String) -> Unit = { _, _ -> },
    val onResolveThread: (threadId: String) -> Unit = {},
    /** Composer draft persistence; `line == 0` means the composer closed. */
    val onComposerState: (line: Int, text: String) -> Unit = { _, _ -> },
)

/**
 * Whether this machine can host an engine AT ALL — the generalisation of desktop's `JcefState`.
 *
 * Android is [Ready] from the first frame (a WebView needs no process-wide bring-up); desktop walks
 * Idle→Initializing→Ready|Failed as JCEF starts. Desktop's `Idle` folds into [Initializing]: from a
 * surface's point of view "nothing has started yet" and "the runtime is starting" call for the same
 * strip, and [EditorEngineFactory.ensureInit] runs on first mount anyway.
 */
sealed interface EngineState {
    /** [EditorEngineFactory.create] may be called. */
    data object Ready : EngineState

    /** The runtime is coming up. [progress] and [message] are optional detail for the strip. */
    data class Initializing(val progress: Float? = null, val message: String? = null) : EngineState

    /** Terminal for this process — the surface uses its native fallback and says why. */
    data class Failed(val reason: String) : EngineState
}

/** Creates engines for one platform. Installed on `Platform.editorEngine` by each app. */
interface EditorEngineFactory {
    /** Whether an engine can be built right now. */
    val state: StateFlow<EngineState>

    /** Idempotent: start the runtime if it has not started. Called from the surface's first mount. */
    fun ensureInit()

    /** Build ONE engine. Only called while [state] is [EngineState.Ready]; may throw, and the
     *  surface then degrades to its native fallback rather than crashing the pane. */
    fun create(lineWrap: Boolean, fontSize: Int): EditorEngine
}

/**
 * A factory for a machine that has no browser to give: [state] is permanently [EngineState.Failed],
 * so a surface backed by it renders the native fallback with [reason]. Used as a safe default and by
 * tests/probes that must never boot a real engine.
 */
class UnavailableEditorEngineFactory(reason: String) : EditorEngineFactory {
    override val state: StateFlow<EngineState> = MutableStateFlow(EngineState.Failed(reason)).asStateFlow()
    override fun ensureInit() = Unit
    override fun create(lineWrap: Boolean, fontSize: Int): EditorEngine =
        error("no editor engine on this platform")
}
