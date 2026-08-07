// M3 editor: the engine-backed editing SURFACE (the desktop analog of Android's WebCodeEditor.kt).
// Routes editing to a KCEF-hosted [DesktopEditorEngine] (embedded Chromium running the committed cm6
// bundle), falling back to a native BasicTextField editor when the runtime can't host a browser:
//   • KcefState.Error / RestartRequired — KCEF init failed or wants a JVM restart (both TERMINAL —
//     see KcefRuntime). No browser will ever come, so drop straight to the native editor.
//   • the 8s ready-miss — KCEF is up and a browser was created but cm6 never fired onReady (a wedged
//     renderer). Port of WebCodeEditor.kt:53-57.
//
// ── The ready-state machine (what renders when) ──────────────────────────────
//   KcefState.Idle / Downloading   → dark cover / a "Downloading…" strip; NO engine is built yet, so
//                                    a browser is NEVER created optimistically (obligation: the panel
//                                    only builds the engine once KCEF is Ready).
//   KcefState.Ready, !engine.ready → dark #282C34 cover (the white-flash guard) while cm6 first-paints;
//                                    the KCEF view is composed but laid out at 0×0 (KeepAlivePanel) so
//                                    a half-attached browser never flashes.
//   KcefState.Ready, engine.ready  → the KCEF view is shown full-size (the real CodeMirror surface).
//   Error / RestartRequired / 8s   → the native BasicTextField fallback (still fully editable + saves).
//
// The whole surface is a plain @Composable driven by an injected [kcefState] + [onEnsureInit] seam, so
// the panel's UI tests exercise every state (cover / downloading / native-fallback) WITHOUT booting
// Chromium — the engine is only ever built when [kcefState] is Ready, which the tests never pass.
package dev.supermux.desktop.editor

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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
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
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.KeepAlivePanel
import dev.supermux.desktop.ui.HeavyweightModalShield
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.BorderLayout
import javax.swing.JPanel

/** The dark backing (One-Dark #282C34) the cm6 bundle paints on — used as the white-flash cover. */
private val EDITOR_BG = Color(0xFF282C34)
private val EDITOR_FG = Color(0xFFABB2BF)

/** How long to wait for cm6's first paint before dropping to the native fallback (WebCodeEditor:55). */
private const val READY_MISS_MS = 8_000L

/**
 * Seam letting the panel read the live editor's scroll offset without owning the engine (which is
 * encapsulated in [EditorSurface]): the surface installs the real reader once its engine exists; the
 * default fires 0 so callers degrade to "no capture" before then. Used via [captureOutgoingScroll]
 * right before a tab switch / reveal (Android EditorScreen.kt:406-408 + :216 parity).
 */
class EditorScrollReader {
    internal var read: ((Int) -> Unit) -> Unit = { it(0) }
    operator fun invoke(cb: (Int) -> Unit) = read(cb)
}

/**
 * Seam letting the panel drive the live engine's LSP bridge (connect/message/disconnect) without
 * owning the engine itself (which stays encapsulated in [EditorSurface]) — mirrors
 * [EditorScrollReader]. Before an engine is attached (KCEF not Ready yet) or after one is disposed,
 * every call is a harmless no-op; [EditorSurface] rebinds the real engine calls into this each time
 * its engine identity changes (see its `SideEffect`).
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
 * Capture the CURRENT (outgoing) tab's scroll before a tab switch/reveal. DELIBERATE divergence from
 * Android's `engine.readScrollTop { editor.captureActiveScroll(it) }`: the read is async, so by the
 * time its callback lands `selectTab` has already flipped `activeTab` and captureActiveScroll would
 * mis-attribute the offset to the INCOMING tab. Snapshotting the outgoing tab at call time makes the
 * late callback always land on the right tab (backport candidate).
 */
internal fun captureOutgoingScroll(editor: EditorState, reader: EditorScrollReader) {
    val outgoing = editor.activeTab ?: return
    reader { scroll -> outgoing.scrollTop = scroll }
}

/**
 * The editing surface for the active tab. Builds + drives a [DesktopEditorEngine] once KCEF is
 * [KcefState.Ready], and falls back to a native editor on a terminal KCEF error or an 8s ready-miss.
 *
 * @param onEnsureInit kicks the (idempotent) KCEF init on first mount. Injected so tests pass `{}`.
 * @param indexUrlProvider `file://…/index.html` for the extracted bundle; only called once Ready.
 * @param engineFactory builds the engine (seam for tests; never invoked unless Ready).
 * @param scrollReader when non-null, receives this surface's live scroll reader so the panel can
 *   capture the outgoing tab's offset before a switch (see [captureOutgoingScroll]).
 */
@Composable
fun EditorSurface(
    kcefState: KcefState,
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
    onEnsureInit: (CoroutineScope) -> Unit = { KcefRuntime.ensureInit(it) },
    indexUrlProvider: () -> String? = { defaultIndexUrl() },
    engineFactory: (String, Boolean, Int) -> DesktopEditorEngine = { url, lw, fs ->
        DesktopEditorEngine(url, lw, fs)
    },
    scrollReader: EditorScrollReader? = null,
    // LSP (M4g-3): forward cm6's outbound JSON-RPC to the caller's bridge, report the engine's
    // ready-gate so the caller can wait for it, and bind the caller's [EditorLspHandle] to the
    // live engine's push methods — same non-ownership seam pattern as [scrollReader].
    onLspOut: (serverId: String, message: String) -> Unit = { _, _ -> },
    onEngineReadyChange: (Boolean) -> Unit = {},
    lspHandle: EditorLspHandle? = null,
) {
    val scope = rememberCoroutineScope()
    // Idempotent: only the first editor pane ever mounted actually starts KCEF (started CAS in the
    // runtime), and it's a no-op once Ready/Error — so calling it unconditionally on mount is safe.
    LaunchedEffect(Unit) { onEnsureInit(scope) }

    val kcefReady = kcefState is KcefState.Ready
    // NEVER build a browser optimistically: the engine is null until KCEF is Ready. On a Ready→build
    // failure (bundle extraction / construction) it stays null → the cover/fallback shows, no browser.
    val engine: DesktopEditorEngine? = remember(kcefReady) {
        if (!kcefReady) null
        else runCatching { indexUrlProvider()?.let { engineFactory(it, lineWrap, fontSize) } }.getOrNull()
    }

    // Dispose the engine with its lifetime. dispose() nulls everything (idempotent). NB: the browser
    // is CREATED lazily in the SwingPanel factory (see [EditorSwingHost]) — NOT here — because a
    // windowed CEF browser only loads its page once its AWT component is realized in a shown, non-zero
    // window; creating it in a detached/0-size effect leaves the load deferred forever.
    DisposableEffect(engine) { onDispose { engine?.dispose() } }

    // Engine callbacks are plain EDT-confined vars — keep them current each recomposition. The
    // scroll-reader seam is bound the same way, but ONLY once an engine exists: before that the
    // reader keeps whatever it holds (the default fires 0 — a no-op capture; a test's injected fake
    // must not be clobbered by an engine-less rebind). A read against a later-disposed engine is
    // safe: getScrollTop fires 0 when the browser is gone.
    SideEffect {
        if (engine != null) {
            engine.onChange = onChange
            engine.onSave = onSave
            engine.onFontSize = onFontSize
            engine.onLspOut = onLspOut
            scrollReader?.read = { cb -> engine.getScrollTop(cb) }
            lspHandle?.onConnect = engine::lspConnect
            lspHandle?.onMessage = engine::lspMessage
            lspHandle?.onDisconnect = engine::lspDisconnect
        }
    }

    val engineReady by (engine?.ready ?: remember { MutableStateFlow(false) }).collectAsState()
    LaunchedEffect(engineReady) { onEngineReadyChange(engineReady) }

    // Push the active document / reveal into cm6 (queued in the engine's planner until first paint).
    LaunchedEffect(engine, content, filename, scrollTop) {
        engine?.setDocument(filename, content, scrollTop)
    }
    LaunchedEffect(engine, revealLine) {
        // Consume the reveal ONLY once an engine exists to receive it: consuming while engine == null
        // (pre-Ready — where T5's chat-tap open typically lands) would silently drop the reveal.
        // Leaving it pending lets this effect re-run when the engine arrives (engine is a key) and
        // deliver it then; the engine's planner queues it further until cm6 first-paints.
        if (engine != null && revealLine != null) {
            engine.revealLine(revealLine.first, revealLine.second)
            onRevealConsumed() // one-shot: returning to this tab restores scroll instead of re-jumping
        }
    }

    // A browser needs a document to be worth showing; the KCEF view is laid out full-size only when
    // there's an active tab (empty filename = no tab). ⚠️ A CEF browser at 0×0 never loads its page,
    // so onReady can't fire while hidden — the ready-gate + fallback timer are therefore keyed on
    // `hasDoc` too, never arming while the view is deliberately hidden.
    val hasDoc = filename.isNotEmpty()

    // 8s ready-miss → native fallback. Armed only once the browser is actually loading (Ready + a
    // visible doc); the Downloading state owns the pre-engine wait, and a hidden (docless) browser
    // must not trip it. Reset per engine instance.
    var missedReady by remember(engine) { mutableStateOf(false) }
    LaunchedEffect(engine, engineReady, hasDoc) {
        if (engine == null || engineReady || !hasDoc) return@LaunchedEffect
        delay(READY_MISS_MS)
        if (!engine.ready.value) missedReady = true
    }

    val nativeFallback = kcefState is KcefState.Error ||
        kcefState is KcefState.RestartRequired ||
        missedReady

    when {
        nativeFallback -> NativeCodeEditor(
            content = content,
            fontSize = fontSize,
            reason = fallbackReason(kcefState),
            onChange = onChange,
            onSave = onSave,
            modifier = modifier,
        )
        kcefState is KcefState.Downloading -> DownloadingView(kcefState.pct, modifier)
        else -> {
            // Ready / initializing. The KCEF view is a HEAVYWEIGHT AWT child: Compose siblings can't
            // paint over it, so the "white-flash cover" is the AWT holder's OWN dark #282C34 backing
            // (set in EditorSwingHost) plus cm6's dark HTML — shown until the page first-paints.
            //
            // The host is mounted only ONCE a document exists ([shownOnce] latch) so the browser is
            // BORN into a realized, full-size window (its factory creates it) — the load-forever-if-
            // detached trap. Thereafter it is kept composed and merely laid out at 0×0 (KeepAlivePanel)
            // when there's no active tab, so the Compose empty-state prompt underneath shows instead
            // (a heavyweight child would otherwise occlude it), and the browser + its document survive.
            // `engine` keys BOTH the remember and the effect so the latch and its setter reset/relaunch
            // together on an engine swap — keying the effect on hasDoc alone would rely on a non-local
            // invariant (that hasDoc happens to change across the swap) to re-arm the latch.
            var shownOnce by remember(engine) { mutableStateOf(false) }
            LaunchedEffect(engine, hasDoc) { if (hasDoc) shownOnce = true }
            Box(modifier.fillMaxSize().background(EDITOR_BG).testTag("editor_web_area")) {
                if (engine != null && shownOnce) {
                    KeepAlivePanel(visible = hasDoc) {
                        EditorSwingHost(engine, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/**
 * Hosts the engine's KCEF AWT child. The browser is CREATED here — inside the SwingPanel factory,
 * which the compose-desktop runtime runs on the EDT when the panel is realized at full size — so the
 * windowed CEF browser is born attached to a shown, non-zero window and actually loads its page (the
 * load-forever-if-detached trap this avoids). The [update] block parents the browser's UI component
 * into the dark holder once available; a stable dark holder means no white flash before first paint.
 */
@Composable
private fun EditorSwingHost(engine: DesktopEditorEngine, modifier: Modifier = Modifier) {
    val holder = remember { JPanel(BorderLayout()).apply { background = java.awt.Color(0x28, 0x2C, 0x34) } }
    // Step aside while anything modal is open — Compose cannot paint over this
    // heavyweight child, so a dialog or menu over the editor would be invisible.
    // Unlike the terminal this cannot be rescued by interop blending: KCEF is a
    // NATIVE window, not Swing content, and a dialog over it comes out sheared off
    // at the page's top edge (measured on Metal). So this one hides on every
    // platform — hence no `evenWithBlending = false` here.
    // Hiding is by LAYOUT (0×0 + clip), which is what an AWT child
    // respects, and the browser is not recreated. Note this only ever HIDES an
    // already-realized browser; the factory above still runs at full size, so the
    // born-detached trap it guards against is unaffected.
    HeavyweightModalShield {
        SwingPanel(
            factory = {
                engine.load() // create the browser NOW, on the EDT, in this realized full-size panel
                holder
            },
            modifier = modifier,
            update = {
                val comp = engine.uiComponent()
                if (comp != null && comp.parent !== holder) {
                    holder.removeAll()
                    holder.add(comp, BorderLayout.CENTER)
                    holder.revalidate()
                    holder.repaint()
                }
            },
        )
    }
}

@Composable
private fun DownloadingView(pct: Float, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxSize().background(EDITOR_BG).testTag("editor_downloading"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = cs.primary)
            Text(
                "Downloading editor… ${pct.toInt()}%",
                color = EDITOR_FG,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = Space.sm),
            )
        }
    }
}

/**
 * Native fallback editor (port of WebCodeEditor.kt:71-110) — a mono BasicTextField on the same dark
 * backing. Edits flow through [onChange] (so the header save button stays live), and Ctrl/Cmd+S wires
 * [onSave] directly since the WebView's own Mod-S handler is gone in this path.
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

    Box(
        modifier.fillMaxSize().background(EDITOR_BG).testTag("editor_native_fallback"),
    ) {
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
                .fillMaxSize()
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
        Text(
            reason,
            color = cs.onSurfaceVariant,
            fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(Space.sm),
        )
    }
}

private fun fallbackReason(kcefState: KcefState): String = when (kcefState) {
    is KcefState.Error -> "Native editor (embedded browser failed: ${kcefState.msg})"
    is KcefState.RestartRequired -> "Native editor (restart the app to enable the rich editor)"
    else -> "Native editor (rich editor unavailable)"
}

/** `file://…/index.html` for the extracted bundle, or null if extraction fails. Only called once
 *  KCEF is Ready, so touching [KcefRuntime.installDir] / the classpath here is on the live path. */
private fun defaultIndexUrl(): String? = runCatching {
    EditorWebAssets.extractTo(KcefRuntime.installDir().resolve("editor-web")).toUri().toString()
}.getOrNull()
