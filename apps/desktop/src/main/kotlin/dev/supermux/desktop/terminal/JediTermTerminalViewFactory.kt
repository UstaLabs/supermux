// Cluster G1: desktop's actual behind `Platform.terminalView()`. The engine binding itself is
// [DesktopTerminalPanel] (jediterm in a SwingPanel) — this is only the seam that lets a SHARED
// screen mount it without naming `com.jediterm`.
package dev.supermux.desktop.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.net.TerminalClient
import dev.supermux.ui.terminal.TerminalViewFactory

/**
 * JediTerm as a [TerminalViewFactory].
 *
 * The lifecycle contract is [DesktopTerminalPanel]'s, unchanged: bytes reach the emulator through
 * [MuxTtyConnector]'s ordered FIFO, the grid owns its own geometry (and guards a 0×0 kept-alive
 * pane against shrinking the remote pty), focus follows `active && windowFocused`, dispose stops
 * the client and closes the widget, and predictions run through [JediTermPredictionAdapter] behind
 * [PredictionPipeline].
 */
object JediTermTerminalViewFactory : TerminalViewFactory {
    override val available: Boolean = true

    @Composable
    override fun TerminalView(
        connect: () -> TerminalClient,
        modifier: Modifier,
        active: Boolean,
        onExit: (() -> Unit)?,
    ) = DesktopTerminalPanel(connect = connect, modifier = modifier, active = active, onExit = onExit)
}
