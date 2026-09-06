// Cluster G1: Android's actual behind `Platform.terminalView()`. The engine binding itself is
// [TerminalPanel] (ConnectBot termlib in an AndroidView) — this is only the seam that lets a SHARED
// screen mount it without naming the library.
package dev.supermux.android.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.supermux.net.TerminalClient
import dev.supermux.ui.terminal.LazyTerminalClient
import dev.supermux.ui.terminal.TerminalKeySink
import dev.supermux.ui.terminal.TerminalSurface
import dev.supermux.ui.terminal.TerminalViewFactory
import dev.supermux.ui.terminal.rememberLazyTerminalClient
import dev.supermux.ui.terminal.rememberTerminalKeySink

/**
 * termlib as a [TerminalViewFactory].
 *
 * The lifecycle contract is [TerminalPanel]'s, unchanged: bytes reach libvterm through
 * `writeInput`, the view measures its own grid and calls `TerminalClient.resize`, focus follows
 * `active && resumed` (so a background pane never holds the IME), dispose stops the client and
 * releases the emulator, and predictions run through [TermlibPredictionAdapter].
 *
 * The surface's [TerminalKeySink] is handed DOWN into the panel, so the in-panel key bar (which
 * cluster G3 replaces with the shared one) and any bar drawn outside the pane share one modifier
 * state — an armed Ctrl is armed for the real keyboard too, exactly as before.
 */
object TermlibTerminalViewFactory : TerminalViewFactory {
    override val available: Boolean = true

    @Composable
    override fun rememberTerminalSurface(connect: () -> TerminalClient): TerminalSurface {
        // Lazy: a surface whose Content is never composed must not open a pty (the sink builds the
        // client on the first press, Content on the first composition).
        val client = rememberLazyTerminalClient(connect)
        val keys = rememberTerminalKeySink { bytes -> client.get().sendInput(bytes) }
        return remember(client, keys) { TermlibSurface(client, keys) }
    }
}

private class TermlibSurface(
    private val client: LazyTerminalClient,
    override val keys: TerminalKeySink,
) : TerminalSurface {
    @Composable
    override fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?) =
        TerminalPanel(
            // Built once by the lazy holder; the panel's own `remember { connect() }` just adopts it.
            connect = { client.get() },
            modifier = modifier,
            active = active,
            onExit = onExit,
            keys = keys,
        )
}
