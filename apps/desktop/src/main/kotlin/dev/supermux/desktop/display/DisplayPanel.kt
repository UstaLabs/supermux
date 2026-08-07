// Desktop VNC display panel (M5-2): connects a session's running display stream, paints its
// framebuffer via the Skia-backed VncFramebuffer (VncFramebuffer.kt), and forwards mouse +
// keyboard to the remote. h264/scrcpy transports are NOT handled — see this milestone's Goal;
// a stream with transport != "vnc" shows a plain "unsupported" message rather than crashing.
package dev.supermux.desktop.display

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.supermux.desktop.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.Space
import dev.supermux.net.DisplayStream
import dev.supermux.net.VncClient
import dev.supermux.net.VncStatus
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.launch

/**
 * Display pane for a session's running VNC stream. Resolves the newest running [DisplayStream]
 * for [session] from [DesktopAppState.displays] (seeded via [DesktopAppState.listDisplays]),
 * shows an empty state with a "Start display" button when none exists, and connects/paints/
 * forwards input via [VncCanvas] once one is running.
 */
@Composable
fun DisplayPanel(app: DesktopAppState, session: SessionInfo, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val live by app.displays.collectAsState()
    var seeded by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    LaunchedEffect(session.id) {
        app.listDisplays()
        seeded = true
    }

    val stream = remember(live, session.name) {
        live.filter { it.sessionName == session.name && it.status == "running" }
            .maxByOrNull { it.createdAt ?: "" }
    }

    Box(
        modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            stream != null && stream.transport != "vnc" ->
                Text(
                    "Unsupported display transport '${stream.transport}'",
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.testTag("display_unsupported"),
                )
            stream != null -> VncCanvas(streamId = stream.id, provider = stream.provider, connectVnc = app::connectVnc)
            !seeded -> CircularProgressIndicator(color = cs.primary)
            else -> DisplayEmptyState(
                starting = starting,
                onStart = {
                    starting = true
                    scope.launch {
                        app.startDisplay(session.name) // display_added frame flips `stream` non-null live
                        starting = false
                    }
                },
                onRefresh = { scope.launch { app.listDisplays() } },
            )
        }
    }
}

@Composable
private fun DisplayEmptyState(starting: Boolean, onStart: () -> Unit, onRefresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.testTag("display_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text("No active display for this session", color = cs.onSurfaceVariant, fontSize = 13.sp)
        Button(onClick = onStart, enabled = !starting, modifier = Modifier.testTag("display_start_button")) {
            if (starting) {
                CircularProgressIndicator(color = cs.onPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text("Start display", fontWeight = FontWeight.Medium)
            }
        }
        TextButton(onClick = onRefresh, modifier = Modifier.testTag("display_refresh_button")) { Text("Refresh") }
    }
}

/** Map an AWT special key code to [VncInput.SpecialKey], or null if it's not one we forward. */
private fun awtSpecialKey(keyCode: Int): VncInput.SpecialKey? = when (keyCode) {
    java.awt.event.KeyEvent.VK_ENTER -> VncInput.SpecialKey.ENTER
    java.awt.event.KeyEvent.VK_BACK_SPACE -> VncInput.SpecialKey.BACKSPACE
    java.awt.event.KeyEvent.VK_TAB -> VncInput.SpecialKey.TAB
    java.awt.event.KeyEvent.VK_ESCAPE -> VncInput.SpecialKey.ESCAPE
    java.awt.event.KeyEvent.VK_LEFT -> VncInput.SpecialKey.ARROW_LEFT
    java.awt.event.KeyEvent.VK_UP -> VncInput.SpecialKey.ARROW_UP
    java.awt.event.KeyEvent.VK_RIGHT -> VncInput.SpecialKey.ARROW_RIGHT
    java.awt.event.KeyEvent.VK_DOWN -> VncInput.SpecialKey.ARROW_DOWN
    else -> null
}

/**
 * Live VNC framebuffer + pointer/keyboard surface for a single display [streamId]. Runs the
 * [VncClient], blits decoded rects into a [DesktopVncFramebuffer], paints it aspect-fit via a
 * plain Compose [Image] (`ContentScale.Fit` does the letterbox — no manual Canvas math needed),
 * and forwards clicks (button-mask 1 on Press/Move, 0 on Release — matches Android's VncView) +
 * keyboard (AWT keyChar/keyCode → [VncInput]'s X11 keysym tables) to the remote. Not unit tested
 * — see [DisplayPanelTest]'s header for why; proven live by this milestone's Task 5.
 */
@Composable
private fun VncCanvas(streamId: String, provider: String, connectVnc: (String) -> VncClient) {
    val client = remember(streamId) { connectVnc(streamId) }
    val fb = remember(streamId) { DesktopVncFramebuffer() }
    val status by client.status.collectAsState()
    val size by client.size.collectAsState()
    val bitmap by fb.bitmap
    val scope = rememberCoroutineScope()
    val sizeRef by rememberUpdatedState(size)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(client) { client.run() }
    LaunchedEffect(client) { client.updates.collect { rects -> fb.applyUpdate(rects, client.size.value) } }
    DisposableEffect(client) { onDispose { client.stop(); fb.release() } }

    var showPasswordDialog by remember { mutableStateOf(false) }
    LaunchedEffect(status) { if (status == VncStatus.NEEDS_PASSWORD) showPasswordDialog = true }

    // The Box's own on-screen pixel size (NOT the remote framebuffer size, which is `size` above) —
    // mapToRemote's viewW/viewH must be the rendered canvas' pixel dimensions in the SAME coordinate
    // space `change.position` arrives in, so the letterbox math matches what ContentScale.Fit painted.
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val viewSizeRef by rememberUpdatedState(viewSize)

    Box(
        Modifier
            .fillMaxSize()
            .testTag("vnc_surface")
            .onSizeChanged { viewSize = it }
            .focusRequester(focusRequester)
            .focusable()
            // KNOWN LIMITATION (v1): hardware modifier CHORDS (Ctrl/Alt/Meta + key, e.g. Ctrl+C,
            // Alt+Tab) are NOT forwarded — a held modifier collapses awt.keyChar into a control code
            // that keysymForChar rejects, so the combo is silently dropped. This is the verbatim
            // VncInput port (parity with Android, whose software-IME input surface has no hardware
            // modifier concept). The explicit "Ctrl+Alt+Del" button is the one deliberate exception.
            // Forwarding modifier keysyms as their own down/up events is a clean follow-up milestone.
            .onPreviewKeyEvent { e ->
                val awt = e.nativeKeyEvent as? java.awt.event.KeyEvent ?: return@onPreviewKeyEvent false
                val down = e.type == KeyEventType.KeyDown
                val special = awtSpecialKey(awt.keyCode)
                val keysym = if (special != null) VncInput.keysymForSpecial(special) else VncInput.keysymForChar(awt.keyChar)
                if (keysym == null) return@onPreviewKeyEvent false
                scope.launch { client.sendKey(keysym, down) }
                true
            }
            .pointerInput(streamId) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        if (event.type == PointerEventType.Press) focusRequester.requestFocus()
                        val mask = when (event.type) {
                            PointerEventType.Press, PointerEventType.Move -> if (event.buttons.isPrimaryPressed) 1 else 0
                            PointerEventType.Release -> 0
                            else -> continue
                        }
                        val sz = sizeRef ?: continue
                        val vs = viewSizeRef
                        val (rx, ry) = VncInput.mapToRemote(change.position.x, change.position.y, vs.width, vs.height, sz.first, sz.second)
                        scope.launch { client.sendPointer(rx, ry, mask) }
                    }
                }
            },
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        DisplayStatusChip(status, Modifier.align(Alignment.TopEnd).padding(Space.sm))
        TextButton(
            onClick = { scope.launch { client.sendCtrlAltDel() } },
            modifier = Modifier.align(Alignment.BottomStart).padding(Space.md).testTag("display_ctrl_alt_del"),
        ) { Text("Ctrl+Alt+Del") }
    }

    if (showPasswordDialog) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            modifier = Modifier.testTag("vnc_password_dialog"),
            title = { Text("Password required") },
            text = {
                Column {
                    Text(
                        if (provider == "macos-screen") "This Mac's Screen Sharing requires a password to connect."
                        else "This display requires a password to connect.",
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("vnc_password_field"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { client.setPassword(password); showPasswordDialog = false },
                    enabled = password.isNotEmpty(),
                ) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = { showPasswordDialog = false }) { Text("Cancel") } },
        )
    }
}

/** Small top-right pill tinted by the stream's [VncStatus]. */
@Composable
private fun DisplayStatusChip(status: VncStatus, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (label, tint) = when (status) {
        VncStatus.CONNECTING -> "Connecting…" to cs.primary
        VncStatus.CONNECTED -> "Connected" to Color(0xFF4CAF50)
        VncStatus.DISCONNECTED -> "Disconnected" to cs.onSurfaceVariant
        VncStatus.NEEDS_PASSWORD -> "Password required" to cs.primary
    }
    Row(
        modifier
            .background(cs.surfaceContainer.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .padding(horizontal = Space.sm, vertical = 3.dp)
            .testTag("display_status_chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}
