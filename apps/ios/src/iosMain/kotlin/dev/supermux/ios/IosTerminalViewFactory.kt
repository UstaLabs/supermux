// Cluster H5: iOS's actual behind `Platform.terminalView()`. The engine binding is Swift's
// SwiftTerm view, reached through [IosTerminalVendor]; this file is the seam that lets the SHARED
// terminal screens (`TerminalTabs`, `TerminalPane`, `TerminalKeyBar`) mount it without naming the
// library — the same shape `JediTermTerminalViewFactory` and `TermlibTerminalViewFactory` have.
package dev.supermux.ios

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.supermux.net.TerminalClient
import dev.supermux.ui.terminal.LazyTerminalClient
import dev.supermux.ui.terminal.TerminalKeySink
import dev.supermux.ui.terminal.TerminalModState
import dev.supermux.ui.terminal.TerminalSurface
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.terminal.rememberLazyTerminalClient
import dev.supermux.ui.terminal.rememberTerminalKeySink
import kotlinx.coroutines.launch

/**
 * SwiftTerm as a [TerminalViewFactory].
 *
 * Everything the seam's KDoc promises is honoured here, and honoured by the SAME division of labour
 * the other two hosts use:
 *
 *  - **feed** — one collector on `TerminalClient.output` forwards each chunk to
 *    [IosTerminalHandle.feed], which runs it through Swift's prediction adapter. Arrival order is
 *    the flow's order; there is no second write path into the emulator.
 *  - **resize** — SwiftTerm measures itself and calls back; we guard `cols > 0 && rows > 0` (see
 *    [IosTerminalVendor.make]) so a pane hidden at 0×0 by `KeepAlivePanel` cannot shrink the pty.
 *  - **focus** — `client.focus(active && foreground)`, hoisted on both, exactly as desktop hoists
 *    it on `active && windowFocused`. A backgrounded app must not own the shared pty geometry.
 *  - **dispose** — the client is stopped and the Swift handle released.
 *  - **predictions** — they stay in Swift (the `PredictionAdapter` is bound to the SwiftTerm view),
 *    so this factory exposes no [dev.supermux.ui.terminal.PredictionSink]; the pipeline is inside
 *    the handle rather than beside it.
 *
 * Accessory keys take the same route as everywhere else: the shared [TerminalKeySink] encodes the
 * press with `:shared`'s sequence builders and enqueues the bytes on the client's own input FIFO,
 * so a bar press and a typed key cannot get out of order and there is no second input path to keep
 * in sync with the grid.
 */
class IosTerminalViewFactory(private val vendor: IosTerminalVendor) : TerminalViewFactory {
    override val available: Boolean = true

    @Composable
    override fun rememberTerminalSurface(connect: () -> TerminalClient): TerminalSurface {
        // Lazy: a surface whose Content is never composed must not open a pty (the sink builds the
        // client on the first press, Content on the first composition).
        val client = rememberLazyTerminalClient(connect)
        val keys = rememberTerminalKeySink { bytes -> client.get().sendInput(bytes) }
        return remember(client, keys) { IosTerminalSurface(vendor, client, keys) }
    }
}

private class IosTerminalSurface(
    private val vendor: IosTerminalVendor,
    private val client: LazyTerminalClient,
    override val keys: TerminalKeySink,
) : TerminalSurface {

    @Composable
    override fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?) {
        // The client is built HERE, once, by the lazy holder — which also stops it on dispose.
        val c = remember(client) { client.get() }
        val scope = rememberCoroutineScope()

        // The Swift view is created OUTSIDE the UIKitView factory and remembered, so a hide/show
        // cycle (KeepAlivePanel lays the pane out at 0×0 and back) re-parents the SAME view: the
        // emulator buffer and the scrollback survive, exactly as desktop reuses its JediTermWidget.
        val handle = remember(c) {
            vendor.make(
                // The shared key bar's armed Ctrl/Alt has to reach a SOFT-KEYBOARD keystroke, which
                // never passes through `keys.press` — it comes from the grid. `applyArmedModifiers`
                // is the shared rule (Android runs the same call): non-null means the keystroke was
                // re-encoded with the modifier and a `once` consumed.
                onInput = { bytes -> c.sendInput(keys.applyArmedModifiers(bytes) ?: bytes) },
                onSize = { cols, rows ->
                    // Guard degenerate sizes: a hidden pane must not shrink the remote pty. The
                    // real grid is reported again by SwiftTerm's own layoutSubviews on re-show.
                    if (cols > 0 && rows > 0) scope.launch { c.resize(cols, rows) }
                },
            )
        }

        // Swift must skip predictive echo while a modifier is armed — the byte that leaves is a
        // control code, not the letter typed. See IosTerminalHandle.setMods.
        LaunchedEffect(handle, keys.ctrl, keys.alt) {
            handle.setMods(keys.ctrl != TerminalModState.OFF, keys.alt != TerminalModState.OFF)
        }

        val foreground by IosAppState.foreground.collectAsState()

        // Hoisted on BOTH, like desktop's `active && windowFocused`: a kept-alive background pane —
        // or a foreground one in a backgrounded app — must not own the shared pty geometry, and
        // must not hold the soft keyboard.
        LaunchedEffect(c, handle, active, foreground) {
            val live = active && foreground
            handle.setActive(live)
            c.focus(live)
        }

        LaunchedEffect(c) { c.run() }

        // Single consumer of the byte stream; `feed` hands the array to Swift whole.
        LaunchedEffect(c, handle) { c.output.collect { handle.feed(it) } }

        // The broker's explicit exit/error frame — NOT a dropped socket, which the client's own
        // reconnect loop handles silently. Same trigger desktop uses (web parity).
        LaunchedEffect(c, onExit) {
            if (onExit != null) c.exit.collect { onExit() }
        }

        DisposableEffect(c, handle) {
            onDispose {
                c.stop()        // stop the reconnect loop + close the input queue (idempotent)
                handle.dispose()
            }
        }

        UIKitView(
            factory = { handle.view },
            modifier = modifier.fillMaxSize(),
            // NonCooperative: the terminal is a text-input surface with its own pan, tap,
            // long-press and pinch recognisers (SwiftTerm's, plus the scroll pan Swift installs).
            // Cooperative would let Compose's gesture arbitration claim a drag before SwiftTerm
            // sees it, which is precisely the scrollback gesture.
            properties = UIKitInteropProperties(interactionMode = UIKitInteropInteractionMode.NonCooperative),
        )
    }
}
