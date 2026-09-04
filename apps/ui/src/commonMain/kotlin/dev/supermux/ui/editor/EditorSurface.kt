// The engine-backed editing SURFACE — ONE implementation for both apps (it was desktop's; Android's
// twin, a `Box` overlay whose fallback painted its reason line over the first line of the file, is
// gone). Everything platform-shaped is behind two seams: [EditorEngineFactory], read from
// `LocalPlatform`, and [EditorEngineHost], the expect/actual view host (a Swing interop panel over
// JCEF's AWT child on desktop, a view-interop host over the WebView on Android).
//
// ── The state machine (what renders when) ────────────────────────────────────
//   EngineState.Initializing        → the initialization strip; NO engine is built yet, so a browser
//                                     is NEVER created optimistically.
//   EngineState.Ready, !engineReady → the dark #282C34 cover (the white-flash guard) while cm6
//                                     first-paints; the host is composed but hidden.
//   EngineState.Ready, engineReady  → the host full-size (the real CodeMirror surface).
//   Failed / engine failure / 8s    → the native BasicTextField fallback (still editable + saves).
//
// The whole surface is a plain @Composable driven by an injected factory, so its tests exercise
// every state (initializing / cover / native fallback) WITHOUT booting a browser.
package dev.supermux.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.editor.engine.DiffRegionComposer
import dev.supermux.ui.editor.engine.DiffRegionRange
import dev.supermux.ui.editor.engine.DiffRegionThread
import dev.supermux.ui.editor.engine.EDITOR_BG
import dev.supermux.ui.editor.engine.EDITOR_FG
import dev.supermux.ui.editor.engine.EDITOR_READY_TIMEOUT_MS
import dev.supermux.ui.editor.engine.EditorCallbacks
import dev.supermux.ui.editor.engine.EditorEngine
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.EditorScrollReader
import dev.supermux.ui.editor.engine.EngineState
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.EDITOR_FONT_DEFAULT
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Hosts the platform view the [engine] draws into. Desktop: JCEF's heavyweight AWT child in a Swing
 * interop panel, kept alive at 0×0 while [visible] is false and stepping aside for modals. Android:
 * the engine's web view in a view-interop host, attached two frames late and kept invisible until
 * cm6 has first-painted.
 */
@Composable
expect fun EditorEngineHost(engine: EditorEngine, visible: Boolean, modifier: Modifier)

/**
 * Seam letting a panel drive the live engine's LSP bridge (connect/message/disconnect) without
 * owning the engine itself (which stays encapsulated in [EditorSurface]) — mirrors
 * [EditorScrollReader]. Before an engine is attached (or after one is disposed) every call is a
 * harmless no-op; [EditorSurface] rebinds the real engine calls each time its engine changes.
 */
class EditorLspHandle {
    // NOTE: the bindable fields are named onConnect/onMessage/onDisconnect (not connect/message/
    // disconnect) — a property and a member function CANNOT share one name in the same Kotlin
    // class (it's a "conflicting declarations" compile error, not an overload), so the public
    // call-surface below needs distinct backing-field names.
    internal var onConnect: (serverId: String, rootUri: String, fileUri: String, languageId: String) -> Unit =
        { _, _, _, _ -> }
    internal var onMessage: (serverId: String, message: String) -> Unit = { _, _ -> }
    internal var onDisconnect: () -> Unit = {}

    fun connect(serverId: String, rootUri: String, fileUri: String, languageId: String) =
        onConnect(serverId, rootUri, fileUri, languageId)
    fun message(serverId: String, message: String) = onMessage(serverId, message)
    fun disconnect() = onDisconnect()
}

/**
 * The editing surface for one document. Builds + drives an [EditorEngine] once the platform's
 * [EditorEngineFactory] is [EngineState.Ready], and falls back to a native editor on a terminal
 * runtime failure, a renderer loss, or an 8s ready-miss.
 *
 * @param factory the engine seam; defaults to this platform's. Tests pass a fake.
 * @param scrollReader when non-null, receives this surface's live scroll reader so the panel can
 *   capture the outgoing tab's offset before a switch (see `captureOutgoingScroll`).
 */
@Composable
fun EditorSurface(
    content: String,
    filename: String,
    lineWrap: Boolean,
    fontSize: Int,
    scrollTop: Int,
    revealLine: Pair<Int, Int?>?,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onRevealConsumed: () -> Unit,
    onFontSize: (Int) -> Unit,
    modifier: Modifier = Modifier,
    factory: EditorEngineFactory = LocalPlatform.current.editorEngine,
    scrollReader: EditorScrollReader? = null,
    // LSP: forward cm6's outbound JSON-RPC to the caller's bridge, report the engine's ready-gate so
    // the caller can wait for it, and bind the caller's [EditorLspHandle] to the live engine's push
    // methods — same non-ownership seam pattern as [scrollReader].
    onLspOut: (serverId: String, message: String) -> Unit = { _, _ -> },
    onEngineReadyChange: (Boolean) -> Unit = {},
    lspHandle: EditorLspHandle? = null,
) {
    // Idempotent: only the first editor surface ever mounted actually starts the runtime, and it is
    // a no-op once Ready/Failed — so calling it unconditionally on mount is safe.
    LaunchedEffect(factory) { factory.ensureInit() }
    val state by factory.state.collectAsState()

    val runtimeReady = state is EngineState.Ready
    // NEVER build a browser optimistically: the engine is null until the runtime is Ready. On a
    // Ready→build failure it stays null → the fallback shows, and no browser was created.
    val engine: EditorEngine? = remember(factory, runtimeReady) {
        if (!runtimeReady) null else runCatching { factory.create(lineWrap, fontSize) }.getOrNull()
    }

    // Dispose the engine with its lifetime. dispose() is idempotent. NB: on desktop the browser is
    // CREATED lazily in the view host — NOT here — because a windowed CEF browser only loads its
    // page once its AWT component is realized in a shown, non-zero window.
    DisposableEffect(engine) { onDispose { engine?.dispose() } }

    // Callbacks are rebound every recomposition, as one record. The scroll-reader/LSP seams are
    // bound the same way, but ONLY once an engine exists: before that the reader keeps whatever it
    // holds (the default fires 0 — a no-op capture; a test's injected fake must not be clobbered).
    SideEffect {
        if (engine != null) {
            engine.callbacks = EditorCallbacks(
                onChange = onChange,
                onSave = onSave,
                onLspOut = onLspOut,
                onFontSize = onFontSize,
            )
            scrollReader?.read = { cb -> engine.readScrollTop(cb) }
            lspHandle?.onConnect = engine::lspConnect
            lspHandle?.onMessage = engine::lspMessage
            lspHandle?.onDisconnect = engine::lspDisconnect
        }
    }

    val engineReady by (engine?.ready ?: remember { MutableStateFlow(false) }).collectAsState()
    val engineFailure by (engine?.failed ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    LaunchedEffect(engineReady) { onEngineReadyChange(engineReady) }

    // Push the active document / reveal into cm6 (queued in the engine's planner until first paint).
    LaunchedEffect(engine, content, filename, scrollTop) {
        engine?.setDocument(filename, content, scrollTop)
    }
    // Settings-driven wrap/zoom changes go to the LIVE engine — neither rebuilds it, so a pinch or a
    // wrap toggle never reloads the file (Android used to re-key its whole engine on `lineWrap`).
    LaunchedEffect(engine, lineWrap) { engine?.setLineWrap(lineWrap) }
    LaunchedEffect(engine, fontSize) { engine?.setFontSize(fontSize) }
    LaunchedEffect(engine, revealLine) {
        // Consume the reveal ONLY once an engine exists to receive it: consuming while engine == null
        // (pre-Ready — where a chat-tap open typically lands) would silently drop the reveal. Leaving
        // it pending lets this effect re-run when the engine arrives (engine is a key) and deliver it
        // then; the engine's planner queues it further until cm6 first-paints.
        if (engine != null && revealLine != null) {
            engine.revealLine(revealLine.first, revealLine.second)
            onRevealConsumed() // one-shot: returning to this tab restores scroll instead of re-jumping
        }
    }

    // A browser needs a document to be worth showing; the host is laid out full-size only when there
    // is an active tab (empty filename = no tab). ⚠️ A CEF browser at 0×0 never loads its page, so
    // onReady can't fire while hidden — the ready-gate + fallback timer are keyed on `hasDoc` too,
    // never arming while the view is deliberately hidden.
    val hasDoc = filename.isNotEmpty()

    // 8s ready-miss → native fallback. Armed only once the browser is actually loading (Ready + a
    // visible doc); the initializing state owns the pre-engine wait, and a hidden (docless) browser
    // must not trip it. Reset per engine instance.
    var missedReady by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine, engineReady, hasDoc) {
        if (engine == null || engineReady || !hasDoc) return@LaunchedEffect
        delay(EDITOR_READY_TIMEOUT_MS)
        if (!engine.ready.value) missedReady = true
    }

    val nativeFallback = state is EngineState.Failed || engineFailure != null || missedReady

    when {
        nativeFallback -> NativeCodeEditor(
            content = content,
            fontSize = fontSize,
            reason = fallbackReason(state, engineFailure),
            onChange = onChange,
            onSave = onSave,
            modifier = modifier,
        )
        state is EngineState.Initializing -> InitializingView(modifier)
        else -> {
            // The host may be a HEAVYWEIGHT child (desktop): Compose siblings cannot paint over it,
            // so the "white-flash cover" is this Box's dark #282C34 backing plus cm6's dark HTML —
            // shown until the page first-paints.
            //
            // WHEN the host is mounted is the platform's call ([EditorEngineFactory.prewarmHost]).
            // Desktop mounts only ONCE a document exists ([shownOnce] latch) so the browser is BORN
            // into a realized, full-size window — a CEF browser born at 0×0 never loads its page.
            // Android does the opposite and mounts immediately, because its expensive step is
            // CREATING the WebView (hundreds of ms of Chromium bring-up) and doing that inside the
            // frame that opens a file is the "first editor open flashes" bug.
            //
            // Either way the host is then kept composed and merely hidden when there is no active
            // tab, so the empty-state prompt underneath shows and the browser + its document
            // survive. `engine` keys BOTH the remember and the effect so the latch and its setter
            // reset together.
            var shownOnce by remember(engine) { mutableStateOf(false) }
            LaunchedEffect(engine, hasDoc) { if (hasDoc) shownOnce = true }
            Box(modifier.fillMaxSize().background(EDITOR_BG).testTag("editor_web_area")) {
                if (engine != null && (factory.prewarmHost || shownOnce)) {
                    Box(Modifier.fillMaxSize().testTag("editor_engine_host")) {
                        EditorEngineHost(engine, visible = hasDoc, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Read-only CodeMirror host used by walkthrough slides. Unlike [EditorSurface], its fallback is
 * supplied by the caller so the native path can reuse a diff-rows renderer instead of a text box. */
@Composable
fun DiffRegionSurface(
    path: String,
    content: String,
    ranges: List<DiffRegionRange>,
    language: String,
    onLineClick: (Int) -> Unit,
    onExpand: (String) -> Unit = {},
    onPage: (String) -> Unit = {},
    /** Comment threads rendered as in-editor block widgets (GitHub-PR style). */
    threads: List<DiffRegionThread> = emptyList(),
    /** An open/restored in-editor composer, or null. */
    composer: DiffRegionComposer? = null,
    onCommentSubmit: (line: Int, text: String) -> Unit = { _, _ -> },
    onReplySubmit: (threadId: String, text: String) -> Unit = { _, _ -> },
    onResolveThread: (threadId: String) -> Unit = {},
    onComposerState: (line: Int, text: String) -> Unit = { _, _ -> },
    scrollKey: Any? = null,
    scrollTop: Int = 0,
    onScrollChange: (Int) -> Unit = {},
    onEngineReadyChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    factory: EditorEngineFactory = LocalPlatform.current.editorEngine,
    fallback: @Composable (String) -> Unit,
) {
    LaunchedEffect(factory) { factory.ensureInit() }
    val state by factory.state.collectAsState()
    val engine = remember(factory, state is EngineState.Ready) {
        if (state !is EngineState.Ready) null
        else runCatching { factory.create(true, EDITOR_FONT_DEFAULT) }.getOrNull()
    }
    DisposableEffect(engine) { onDispose { engine?.dispose() } }
    DisposableEffect(engine, scrollKey) {
        onDispose { engine?.readScrollTop(onScrollChange) }
    }
    SideEffect {
        engine?.callbacks = EditorCallbacks(
            onDiffLineClick = onLineClick,
            onDiffExpand = onExpand,
            onDiffPage = onPage,
            onCommentSubmit = onCommentSubmit,
            onReplySubmit = onReplySubmit,
            onResolveThread = onResolveThread,
            onComposerState = onComposerState,
        )
    }
    val ready by (engine?.ready ?: remember { MutableStateFlow(false) }).collectAsState()
    val failure by (engine?.failed ?: remember { MutableStateFlow<String?>(null) }).collectAsState()
    LaunchedEffect(ready) { onEngineReadyChange(ready) }
    LaunchedEffect(engine, path, content, ranges, language, scrollKey) {
        engine?.setDocument(path, "")
        engine?.showDiffRegion(path, content, ranges, language, scrollTop, threads, composer)
    }
    // Live thread/composer updates re-enter the SAME region: keyed on the threads and the composer's
    // LINE (never its draft — a per-keystroke push would rebuild the textarea under the caret), and
    // deliberately skipping the first run, which the region effect above already covered with the
    // scroll restore attached.
    var threadsPushed by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine, threads, composer?.line) {
        if (!threadsPushed) { threadsPushed = true; return@LaunchedEffect }
        engine?.updateDiffThreads(threads, composer)
    }
    var missedReady by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine, ready) {
        if (engine == null || ready) return@LaunchedEffect
        delay(EDITOR_READY_TIMEOUT_MS)
        if (!engine.ready.value) missedReady = true
    }
    when {
        state is EngineState.Failed || failure != null || missedReady ||
            (state is EngineState.Ready && engine == null) -> fallback(fallbackReason(state, failure))
        state is EngineState.Initializing -> InitializingView(modifier)
        else -> Box(modifier.fillMaxSize().background(EDITOR_BG).testTag("walkthrough_diff_region")) {
            if (engine != null) EditorEngineHost(engine, visible = true, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun InitializingView(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxSize().background(EDITOR_BG).testTag("editor_initializing"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = cs.primary)
            Text(
                "Starting editor…",
                color = EDITOR_FG,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = Space.sm),
            )
        }
    }
}

/**
 * Native fallback editor — a mono BasicTextField on the same dark backing. Edits flow through
 * [onChange] (so the header save button stays live), and Ctrl/Cmd+S wires [onSave] directly since
 * the engine's own Mod-S handler is gone in this path.
 */
@Composable
private fun NativeCodeEditor(
    content: String,
    fontSize: Int,
    reason: String,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    // A Column, not a Box. In a Box the reason line is drawn AFTER the field and lands at the
    // default TopStart, i.e. painted straight over the first line of the file (Android's twin had
    // exactly that bug). A file pane draws the document immediately, so the overlap showed on every
    // launch that fell back to native.
    Column(
        modifier.fillMaxSize().background(EDITOR_BG).testTag("editor_native_fallback"),
    ) {
        Text(
            reason,
            color = cs.onSurfaceVariant,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.sm)
                .testTag("editor_native_fallback_reason"),
        )
        BasicTextField(
            value = content,
            onValueChange = onChange,
            textStyle = TextStyle(
                color = EDITOR_FG,
                fontFamily = MonoFontFamily,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 6).sp,
            ),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scroll)
                .padding(Space.md)
                .testTag("editor_native_input")
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.S &&
                        (e.isCtrlPressed || e.isMetaPressed)
                    ) {
                        onSave(); true
                    } else {
                        false
                    }
                },
        )
    }
}

/** Why the native editor is showing: the runtime's terminal error, a dead renderer, or neither. */
internal fun fallbackReason(state: EngineState, failure: String? = null): String = when {
    state is EngineState.Failed -> "Native editor (embedded browser failed: ${state.reason})"
    failure != null -> "Native editor (embedded browser failed: $failure)"
    else -> "Native editor (rich editor unavailable)"
}
