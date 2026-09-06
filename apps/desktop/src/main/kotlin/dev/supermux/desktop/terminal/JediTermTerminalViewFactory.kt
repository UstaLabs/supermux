// Cluster G1: desktop's actual behind `Platform.terminalView()`. The engine binding itself is
// [JediTermTerminalView] (jediterm in a SwingPanel) — this is only the seam that lets a SHARED
// screen mount it without naming the library.
package dev.supermux.desktop.terminal

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
 * JediTerm as a [TerminalViewFactory].
 *
 * The lifecycle contract is [JediTermTerminalView]'s, unchanged: bytes reach the emulator through
 * [MuxTtyConnector]'s ordered FIFO, the grid owns its own geometry (and guards a 0×0 kept-alive
 * pane against shrinking the remote pty), focus follows `active && windowFocused`, dispose stops
 * the client and closes the widget, and predictions run through [JediTermPredictionAdapter] behind
 * [PredictionPipeline].
 *
 * Accessory keys are translated the same way every other client translates them: the shared
 * [TerminalKeySink] encodes the press with `:shared`'s `specialKeySequence`/`printableSequence` and
 * writes the bytes to the pty, whose echo the emulator renders — there is no second input path to
 * keep in sync with the grid's own key handling.
 */
object JediTermTerminalViewFactory : TerminalViewFactory {
    override val available: Boolean = true

    @Composable
    override fun rememberTerminalSurface(connect: () -> TerminalClient): TerminalSurface {
        // Lazy: a surface whose Content is never composed must not open a pty (the sink builds the
        // client on the first press, Content on the first composition).
        val client = rememberLazyTerminalClient(connect)
        // `sendInput` queues (it is not suspending), so the bar's bytes join the SAME ordered input
        // queue the widget's own keystrokes use, in press order.
        val keys = rememberTerminalKeySink { bytes -> client.get().sendInput(bytes) }
        return remember(client, keys) { JediTermSurface(client, keys) }
    }
}

private class JediTermSurface(
    private val client: LazyTerminalClient,
    override val keys: TerminalKeySink,
) : TerminalSurface {
    @Composable
    override fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?) =
        JediTermTerminalView(
            // Built once by the lazy holder; the panel's own `remember { connect() }` just adopts it.
            connect = { client.get() },
            modifier = modifier,
            active = active,
            onExit = onExit,
        )
}
