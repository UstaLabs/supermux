package dev.supermux.android.terminal

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalStatus
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

    LaunchedEffect(client, emulator) {
        client.output.collect { bytes ->
            emulator.writeInput(bytes)
        }
    }

    Box(modifier.fillMaxSize().background(Color(c.terminal))) {
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
