package dev.supermux.android.terminal

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalStatus
import dev.supermux.net.linesFromPixels
import dev.supermux.net.wheelEventsFromLines
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * Native terminal panel backed by ConnectBot termlib (libvterm).
 * I/O stays on the broker websocket via [TerminalClient].
 */
@Composable
fun TerminalPanel(
    connect: () -> TerminalClient,
    modifier: Modifier = Modifier,
    // Fires once when the session ends (CONNECTED → DISCONNECTED). The agent-PTY ("Native")
    // tab uses this to fall back to Chat on agent exit (iOS onExit parity). Null = no-op.
    onExit: (() -> Unit)? = null,
) {
    val c = LocalPanes.current
    val scope = rememberCoroutineScope()
    val client = remember { connect() }

    val emulator: TerminalEmulator = remember(client) {
        TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = Color(c.terminalForeground),
            defaultBackground = Color(c.terminal),
            onKeyboardInput = { data ->
                scope.launch { client.sendInput(data) }
            },
            onResize = { dims ->
                scope.launch { client.resize(dims.columns, dims.rows) }
            },
        )
    }

    LaunchedEffect(client) { client.run() }
    DisposableEffect(client) { onDispose { client.stop() } }

    val status by client.status.collectAsState()

    // Agent-PTY exit detection: once the client has connected, a drop to DISCONNECTED means the
    // session ended → fire onExit (the Native tab falls back to Chat). Latches so a transient
    // pre-connect DISCONNECTED never triggers it. No-op when onExit is null (scratch terminal).
    // Hooks are called unconditionally (rules of composition); only the effect body branches.
    val hadConnected = remember(client) { mutableStateOf(false) }
    LaunchedEffect(client, status) {
        if (status == TerminalStatus.CONNECTED) {
            hadConnected.value = true
        } else if (status == TerminalStatus.DISCONNECTED && hadConnected.value) {
            onExit?.invoke()
        }
    }

    LaunchedEffect(client, emulator) {
        client.output.collect { bytes ->
            emulator.writeInput(bytes)
        }
    }

    // Pixel height of the laid-out terminal viewport, for converting drag pixels → rows.
    var boxHeightPx by remember { mutableStateOf(0) }

    // Shrink the terminal above the soft keyboard (and nav bar) under edge-to-edge — mirrors the
    // chat composer's inset handling. Resizing the view makes termlib recompute its grid and emit a
    // pty resize, so the shell reflows to the visible rows/cols instead of the cursor line hiding
    // behind the IME. (Background stays full-bleed; only the content insets.)
    Box(
        modifier
            .fillMaxSize()
            .background(Color(c.terminal))
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
            .onSizeChanged { boxHeightPx = it.height }
            // Touch-drag → tmux scroll, mirroring the web PWA (src/web-app/.../touch-scroll.ts).
            // termlib's own drag only scrolls its LOCAL scrollback — empty under tmux's alternate
            // screen — and it exposes no mouse-forwarding, so we translate a vertical drag into SGR
            // mouse-wheel bytes (TerminalScroll.kt) and send them down the pty; tmux scrolls its
            // history. We read events in the Initial pass and consume them once a vertical drag is
            // recognized, so termlib never also acts on the gesture. Taps (keyboard focus) and
            // multi-touch (pinch-zoom) fall through untouched.
            .pointerInput(client, emulator) {
                val slop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var accumPx = 0.0
                        var totalDx = 0f
                        var totalDy = 0f
                        var scrolling = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.size > 1) break // multi-touch → let termlib handle it
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break // pointer lifted
                            val d = change.positionChange()
                            totalDx += d.x
                            totalDy += d.y
                            if (!scrolling && abs(totalDy) > slop && abs(totalDy) > abs(totalDx)) {
                                scrolling = true
                            }
                            if (scrolling) {
                                val rows = emulator.dimensions.rows
                                val cell = if (rows > 0 && boxHeightPx > 0) boxHeightPx.toDouble() / rows else 0.0
                                if (cell > 0.0) {
                                    // finger up (d.y < 0) → scroll toward newer output (positive accum)
                                    accumPx += -d.y.toDouble()
                                    val step = linesFromPixels(accumPx, cell)
                                    accumPx = step.remainderPx
                                    if (step.lines != 0) {
                                        val cols = emulator.dimensions.columns
                                        client.sendInput(
                                            wheelEventsFromLines(
                                                step.lines,
                                                if (cols > 1) cols / 2 else 1,
                                                if (rows > 1) rows / 2 else 1,
                                            ),
                                        )
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                }
            },
    ) {
        Terminal(
            terminalEmulator = emulator,
            modifier = Modifier.fillMaxSize(),
            typeface = Typeface.MONOSPACE,
            backgroundColor = Color(c.terminal),
            foregroundColor = Color(c.terminalForeground),
            keyboardEnabled = true,
            showSoftKeyboard = true,
        )
        StatusChip(
            status = status,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Space.sm),
        )
    }
}

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
