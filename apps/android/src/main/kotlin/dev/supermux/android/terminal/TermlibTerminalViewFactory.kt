// Cluster G1: Android's actual behind `Platform.terminalView()`. The engine binding itself is
// [TerminalPanel] (ConnectBot termlib in an AndroidView) — this is only the seam that lets a SHARED
// screen mount it without naming `org.connectbot`.
package dev.supermux.android.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.net.TerminalClient
import dev.supermux.ui.terminal.TerminalViewFactory

/**
 * termlib as a [TerminalViewFactory].
 *
 * The lifecycle contract is [TerminalPanel]'s, unchanged: bytes reach libvterm through
 * `writeInput`, the view measures its own grid and calls `TerminalClient.resize`, focus follows
 * `active && resumed` (so a background pane never holds the IME), dispose stops the client and
 * releases the emulator, and predictions run through [TermlibPredictionAdapter].
 */
object TermlibTerminalViewFactory : TerminalViewFactory {
    override val available: Boolean = true

    @Composable
    override fun TerminalView(
        connect: () -> TerminalClient,
        modifier: Modifier,
        active: Boolean,
        onExit: (() -> Unit)?,
    ) = TerminalPanel(connect = connect, modifier = modifier, active = active, onExit = onExit)
}
