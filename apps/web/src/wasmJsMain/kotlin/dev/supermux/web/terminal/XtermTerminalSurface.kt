// `HtmlElementView` — Compose-for-Web's DOM interop, the web twin of `SwingPanel` — is still
// experimental in CMP 1.11.1.
@file:OptIn(ExperimentalComposeUiApi::class)

package dev.supermux.web.terminal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.HtmlElementView
import dev.supermux.net.TerminalClient
import dev.supermux.ui.terminal.LazyTerminalClient
import dev.supermux.ui.terminal.TerminalKeySink
import dev.supermux.ui.terminal.TerminalSurface
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.terminal.rememberLazyTerminalClient
import dev.supermux.ui.terminal.rememberTerminalKeySink
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.web.WebAppState
import kotlinx.browser.document
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLDivElement

/**
 * xterm.js as a [TerminalViewFactory] — the browser's actual behind `Platform.terminalView()`.
 *
 * Same shape as desktop's `JediTermTerminalViewFactory`, because the lifecycle contract is the
 * seam's, not the engine's: bytes reach the emulator in arrival order, the GRID owns its geometry
 * and calls `TerminalClient.resize` (never the other way round, and never at 0×0 — a kept-alive
 * hidden pane must not shrink the remote pty), focus follows `active && foreground`, dispose stops
 * the client and disposes the terminal, and predictions run through [XtermPredictionSink] behind
 * [XtermPredictionPipeline].
 *
 * Accessory keys take the shared route: [TerminalKeySink] encodes the press with `:shared`'s
 * sequences and writes the bytes to the pty, whose echo the emulator renders — there is no second
 * input path to keep in sync with the grid's own key handling.
 */
object XtermTerminalViewFactory : TerminalViewFactory {
    override val available: Boolean = true

    @Composable
    override fun rememberTerminalSurface(connect: () -> TerminalClient): TerminalSurface {
        // Lazy: a surface whose Content is never composed must not open a pty (the sink builds the
        // client on the first press, Content on the first composition).
        val client = rememberLazyTerminalClient(connect)
        // `sendInput` queues (non-suspending), so the bar's bytes join the SAME ordered input queue
        // the emulator's own keystrokes use, in press order.
        val keys = rememberTerminalKeySink { bytes -> client.get().sendInput(bytes) }
        return remember(client, keys) { XtermSurface(client, keys) }
    }
}

private class XtermSurface(
    private val client: LazyTerminalClient,
    override val keys: TerminalKeySink,
) : TerminalSurface {
    @Composable
    override fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?) =
        XtermTerminalView(client, keys, modifier, active, onExit)
}

/** Mutable, NON-state attach bookkeeping: `update` must not invalidate the composition merely to
 *  record that the terminal is already open (the plan-1 `HtmlElementView` rule). */
private class XtermAttachState {
    var attached = false
    var observer: JsAny? = null
}

/**
 * One live terminal: an xterm.js `Terminal` in a `<div>` that Compose positions over its canvas.
 *
 * The terminal is remembered OUTSIDE the `HtmlElementView` factory, so a hide/show cycle
 * ([dev.supermux.ui.widgets.KeepAlivePanel], which lays a background pane out at 0×0) reuses the
 * same instance — grid, scrollback and parser state all survive. `term.open` happens from `update`
 * rather than `factory`, and only once the div is in the document at a non-zero size: xterm
 * measures a character cell on open, and opening into a detached or 0×0 node yields a 0×0 grid
 * that never recovers. `update` runs once at attach (before sizing) and thereafter only when a
 * snapshot state it reads changes, so `sized` — written from `onGloballyPositioned` — is what
 * brings it back for the real attach.
 *
 * @param active whether this is the foreground pane. Background panes stay connected but neither
 *   own the shared pty geometry nor take focus.
 * @param onExit fires on the broker's `exit`/`error` frame (`TerminalClient.exit`), never on a
 *   transient reconnect — desktop's rule, itself the web PWA's original one.
 */
@Composable
private fun XtermTerminalView(
    client: LazyTerminalClient,
    keys: TerminalKeySink,
    modifier: Modifier,
    active: Boolean,
    onExit: (() -> Unit)?,
) {
    val panes = LocalPanes.current
    val scope = rememberCoroutineScope()
    val foreground by WebAppState.foreground.collectAsState()
    val state = remember(client) { XtermAttachState() }
    var sized by remember(client) { mutableStateOf(false) }

    val term = remember(client) {
        Terminal(terminalOptions(hexColor(panes.terminal), hexColor(panes.terminalForeground), FONT_SIZE))
    }
    val fit = remember(client) { FitAddon() }

    // Predictive local echo: the shared Kotlin engine + xterm op-renderer + keystroke->echo RTT
    // stamp. Attached here (not in `update`) so the input handler installed below can always reach
    // it, and torn down with the composition.
    val pred = remember(client) { XtermPredictionPipeline() }

    // Input and geometry handlers, installed ONCE per terminal: xterm's `onData`/`onResize` are
    // registrations, not properties, so re-running this on every recomposition would stack
    // duplicate listeners and send every keystroke twice.
    DisposableEffect(term) {
        pred.attach(term)
        xtermOnData(term) { data ->
            val bytes = data.encodeToByteArray()
            // The armed-bar-modifier rule lives on the sink, shared with every other host. Non-null
            // = re-encoded with the modifier, sent directly, `once` consumed: a control code is not
            // printable, so predictive echo is skipped (Android/iOS/web parity).
            val modified = keys.applyArmedModifiers(bytes)
            if (modified != null) {
                client.get().sendInput(modified)
            } else {
                pred.handleInput(bytes) // predictive echo BEFORE the send (ordering contract)
                client.get().sendInput(bytes)
            }
        }
        xtermOnResize(term) { cols, rows ->
            // A hidden pane is laid out at 0×0 (KeepAlivePanel) and must NOT shrink the remote
            // pty; the real size is re-sent when it comes back.
            if (cols > 0 && rows > 0) scope.launch { client.get().resize(cols, rows) }
        }
        onDispose { pred.teardown() }
    }

    LaunchedEffect(client) { client.get().run() }

    // Server → screen. Single consumer of client.output: when the pipeline is attached the bytes
    // flow engine.onServerData → ops → sink.render (Passthrough → term.write), so they are NOT
    // also written directly (that would double-render). The lambda is the fallback the pipeline
    // calls only before attach / after teardown / on an engine failure.
    LaunchedEffect(client) {
        client.get().output.collect { bytes ->
            pred.handleOutput(bytes) { term.write(bytes.toJsBytes()) }
        }
    }

    // Kept-alive terminals stay connected when hidden; the foreground pane explicitly owns the
    // shared pty geometry so another warm client cannot pin its size. `foreground` is the
    // document's visibility (WebAppState), the browser's answer to desktop's window focus.
    LaunchedEffect(client, active, foreground) {
        client.get().focus(active && foreground)
        if (active && foreground && state.attached) term.focus()
    }

    // The pty ended server-side (the broker's explicit frame). A dropped socket does not land here
    // — the client's reconnect loop handles that silently.
    LaunchedEffect(client) {
        client.get().exit.collect { onExit?.invoke() }
    }

    HtmlElementView<HTMLDivElement>(
        modifier = modifier.fillMaxSize().onGloballyPositioned { sized = it.size.width > 0 },
        factory = {
            (document.createElement("div") as HTMLDivElement).also { div ->
                div.style.width = "100%"
                div.style.height = "100%"
                // No typed accessors for these in kotlinx-browser's CSSStyleDeclaration.
                div.style.setProperty("overflow", "hidden")
                div.style.setProperty("background", hexColor(panes.terminal))
            }
        },
        update = { div ->
            if (sized && !state.attached && div.isConnected && div.clientWidth > 0) {
                state.attached = true
                term.open(div)
                term.loadAddon(fit)
                // GPU renderer where it exists; a machine without WebGL2 throws on load and xterm
                // keeps its canvas renderer — a terminal that renders slightly slower beats none.
                runCatching { term.loadAddon(WebglAddon()) }
                runCatching { fit.fit() }
                // `fit` only emits onResize when the geometry CHANGED, and the very first fit may
                // land on xterm's default 80×24 — send the size once explicitly so the pty is sized
                // even then.
                val cols = term.cols
                val rows = term.rows
                if (cols > 0 && rows > 0) scope.launch { client.get().resize(cols, rows) }
                // Compose does not re-run `update` when the pane merely changes pixel size, so the
                // reflow is driven by the DOM itself.
                state.observer = observeResize(div) { runCatching { fit.fit() } }
                if (active) term.focus()
            }
        },
        onRelease = {
            state.observer?.let { disconnectResizeObserver(it) }
            state.observer = null
            state.attached = false
            term.dispose()
        },
    )
}

/** `LocalPanes` carries ARGB ints; CSS and xterm's theme want `#rrggbb` (alpha is always FF here). */
private fun hexColor(argb: Int): String =
    "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')

private const val FONT_SIZE = 13
