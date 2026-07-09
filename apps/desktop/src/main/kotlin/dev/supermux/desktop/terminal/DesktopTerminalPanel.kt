// Modeled on apps/android/src/main/kotlin/dev/supermux/android/terminal/TerminalPanel.kt —
// same public shape (connect/active/onExit), same lifecycle discipline, same agent-exit latch and
// status chip. Platform swap: ConnectBot termlib → JediTermWidget (Swing) hosted in a SwingPanel,
// with MuxTtyConnector bridging the shared TerminalClient byte stream to JediTerm's char stream.
package dev.supermux.desktop.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jediterm.terminal.ui.JediTermWidget
import dev.supermux.desktop.theme.LocalPanes
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalStatus
import kotlinx.coroutines.launch

/**
 * Agent-exit latch as a pure function (Android TerminalPanel:111-122 semantics): once the client
 * has EVER been CONNECTED ([hadConnected] latches true), a drop to DISCONNECTED means the session
 * ended → fire onExit. The latch subsumes any "previous status" argument — a transient pre-connect
 * DISCONNECTED (or CONNECTING→DISCONNECTED retry loop) never fires because the latch is still
 * false, regardless of what the previous status was. Extracted so the decision is unit-testable
 * headlessly; the composable wiring itself is exercised in the Task 8 live verification.
 */
internal fun shouldFireExit(now: TerminalStatus, hadConnected: Boolean): Boolean =
    now == TerminalStatus.DISCONNECTED && hadConnected

/**
 * Native terminal panel backed by JediTerm (Swing) embedded via [SwingPanel].
 * I/O stays on the broker websocket via the shared [TerminalClient].
 *
 * @param connect factory for this panel's [TerminalClient]; called once and remembered.
 * @param active whether this terminal is the foreground pane (Android-parity parameter). Desktop
 *   has no soft keyboard to manage; focus is click-to-focus (Swing default) and we deliberately do
 *   NOT auto-focus on composition (Android rule). Kept-alive background panes are hidden by the
 *   host via [dev.supermux.desktop.ui.KeepAlivePanel], which shrinks the heavyweight Swing child
 *   to 0×0 so it can't be clicked (and thus can't take focus) while hidden.
 * @param onExit fires once when the session ends (CONNECTED → DISCONNECTED). The agent-PTY
 *   ("Native") tab uses this to fall back to Chat on agent exit. Null = no-op (scratch terminal).
 */
@Composable
fun DesktopTerminalPanel(
    connect: () -> TerminalClient,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onExit: (() -> Unit)? = null,
) {
    val c = LocalPanes.current
    val scope = rememberCoroutineScope()
    val client = remember { connect() }

    // Predictive local echo: the shared Kotlin engine + JediTerm op-renderer + keystroke->echo RTT
    // stamp, bundled so the connector's pre-send input tap and the output collector can reach an
    // engine/adapter built AFTER the widget exists. Mirrors Android's PredictionPipeline.
    val pred = remember(client) { PredictionPipeline() }

    // Byte-stream bridge: JediTerm writes (keystrokes/paste) → client.sendInput (FIFO queue, safe
    // from any thread); JediTerm grid resizes → client.resize (suspending → scoped launch);
    // isConnected mirrors the live status. Server output is pushed in via offerServerBytes below.
    val connector = remember(client) {
        MuxTtyConnector(
            sendInput = client::sendInput,
            requestResize = { cols, rows ->
                // Guard degenerate sizes: a kept-alive hidden pane is laid out at 0×0 (see
                // KeepAlivePanel) and must not shrink the remote pty; the real size is re-sent
                // on re-show.
                if (cols > 0 && rows > 0) scope.launch { client.resize(cols, rows) }
            },
            isConnected = { client.status.value == TerminalStatus.CONNECTED },
        )
    }

    // The Swing widget is built OUTSIDE the SwingPanel factory and remembered, so a hide/show
    // cycle (KeepAlivePanel) or a SwingPanel re-attach reuses the SAME widget instance — the
    // terminal grid, scrollback, and emulator thread all survive. Compose Desktop's UI thread IS
    // the AWT EDT, so constructing a Swing component here is thread-correct.
    // widget.start() spawns JediTerm's emulator thread, which drives connector.read().
    val widget = remember(client) {
        JediTermWidget(80, 24, SupermuxTermSettings(background = c.terminal, foreground = c.terminalForeground)).also {
            it.ttyConnector = connector
            it.start()
        }
    }

    // Wheel/mouse → tmux: NO custom bridge on desktop. JediTerm 3.73 natively forwards wheel AND
    // click/drag as SGR mouse reports to the TtyConnector once tmux negotiates mouse tracking
    // (`mouse on` — always the case for supermux terminals), matching the web client's xterm.js
    // behaviour. See the mouse-reporting note on [SupermuxTermSettings] for the empirical
    // verification and the fallback plan — read it BEFORE adding any wheel listener here.

    // Build the engine+adapter once the widget exists and install the pre-send input tap; drop them
    // on teardown. Runs on the AWT EDT (composition), AFTER widget.start(), so onUserInput is armed
    // by the time JediTerm can dispatch a keystroke — @Volatile on the field covers the visibility.
    DisposableEffect(widget, connector) {
        pred.attach(widget, connector)
        // Pre-send tap: JediTerm routes user input through connector.write() → onUserInput → sendInput.
        // handleInput renders the predicted ops BEFORE the bytes leave, mirroring Android's ordering.
        // NB: this tap fires on JediTerm's WRITE-EXECUTOR thread (not the EDT) while handleOutput
        // runs on the EDT — the pipeline serializes the two internally on one monitor (see
        // PredictionPipeline's THREADING KDoc).
        connector.onUserInput = { pred.handleInput(it) }
        onDispose {
            connector.onUserInput = null
            pred.teardown()
        }
    }

    LaunchedEffect(client) { client.run() }

    // Server → screen. Single consumer of client.output. When the pipeline is attached the bytes
    // flow engine.onServerData → ops → adapter.render (Passthrough → injectDisplayBytes), so they
    // are NOT also offered directly (that would double-render). The lambda is the fallback the
    // pipeline calls only before attach / after teardown / on an adapter failure, keeping every
    // byte on the same ordered FIFO either way.
    LaunchedEffect(client, connector) {
        client.output.collect { bytes ->
            pred.handleOutput(bytes) { connector.offerServerBytes(bytes) }
        }
    }

    DisposableEffect(client) {
        onDispose {
            client.stop()          // stop reconnect loop + close input queue
            connector.closeStream() // unblock JediTerm's reader thread with EOF
            widget.close()          // JediTermWidget 3.73 has both stop() and close(); close()
            //                         stops the emulator thread AND disposes widget resources.
        }
    }

    val status by client.status.collectAsState()

    // Agent-PTY exit detection (Android TerminalPanel:111-122 parity) — see shouldFireExit.
    val hadConnected = remember(client) { mutableStateOf(false) }
    LaunchedEffect(client, status) {
        if (status == TerminalStatus.CONNECTED) {
            hadConnected.value = true
        } else if (shouldFireExit(status, hadConnected.value)) {
            onExit?.invoke()
        }
    }

    // SwingPanel is a HEAVYWEIGHT AWT child: without the experimental interop blending
    // (`compose.interop.blending`), Compose siblings CANNOT paint above it — an overlaid chip
    // would be occluded (review catch). The status bar therefore lives in its own lightweight
    // strip ABOVE the terminal (only present in non-CONNECTED states, so the terminal keeps the
    // full pane height while healthy).
    Column(
        modifier
            .fillMaxSize()
            .background(Color(c.terminal)),
    ) {
        if (status != TerminalStatus.CONNECTED) {
            Row(
                Modifier.fillMaxWidth().padding(Space.sm),
                horizontalArrangement = Arrangement.End,
            ) {
                StatusChip(status = status)
            }
        }
        // No auto-focus on composition: SwingPanel does not request focus for its child; the
        // JediTerm panel takes focus on click (Swing default), matching the Android rule.
        SwingPanel(
            factory = { widget },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Connection-status pill (Android TerminalPanel StatusChip port, same colors/typography). */
@Composable
private fun StatusChip(status: TerminalStatus, modifier: Modifier = Modifier) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val (label, tint) = when (status) {
        TerminalStatus.CONNECTING -> "Connecting…" to Color(c.warning)
        TerminalStatus.CONNECTED -> "Connected" to cs.primary
        TerminalStatus.DISCONNECTED -> "Disconnected" to cs.onSurfaceVariant
    }
    Row(
        modifier
            .background(cs.surfaceContainer.copy(alpha = 0.85f), RoundedCornerShape(Radii.pill))
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(tint, RoundedCornerShape(Radii.pill)),
        )
        Text(label, color = cs.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
    }
}
