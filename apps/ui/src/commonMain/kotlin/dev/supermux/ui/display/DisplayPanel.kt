// The one Display panel for both apps (cluster G4).
//
// Base = desktop's `display/DisplayPanel.kt`: the outer resolve/empty/start state machine, the VNC
// body over the shared `VncFramebuffer` + `VncFrame` + `Modifier.vncPointerInput`, the hardware-key
// route (its AWT keycode table is now the commonMain `Key` table below) and every test tag.
// Android contributes the TRANSPORT SWITCH — an h264 stream renders through `Platform.videoDecoder()`
// when the host has one — plus the control bar, the 4-state status chip and the hidden keyboard
// field, which are what a touch client needs and desktop never had.
//
// Nothing here names MediaCodec, SurfaceView or AWT: the scrcpy half is entirely behind the G1
// `VideoSurfaceFactory` seam, and a host without one (desktop) simply never takes that branch.
package dev.supermux.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.display.VncInput
import dev.supermux.net.DisplayStream
import dev.supermux.net.ScrcpyStatus
import dev.supermux.net.VncClient
import dev.supermux.net.VncStatus
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import kotlinx.coroutines.launch

// ── Key → X11 keysym (was desktop's AWT keycode table) ────────────────────────────────────────

/**
 * A Compose [Key] that both transports forward as a NAMED key rather than a character.
 *
 * This is desktop's `awtSpecialKey` retyped onto the multiplatform key codes (its AWT key-event
 * constants cannot be named in `:ui`), unioned with the table Android's hidden keyboard field
 * already used — they were the same eight keys plus the numeric-pad Enter.
 */
internal fun specialKeyFor(key: Key): VncInput.SpecialKey? = when (key) {
    Key.Enter, Key.NumPadEnter -> VncInput.SpecialKey.ENTER
    Key.Backspace -> VncInput.SpecialKey.BACKSPACE
    Key.Tab -> VncInput.SpecialKey.TAB
    Key.Escape -> VncInput.SpecialKey.ESCAPE
    Key.DirectionLeft -> VncInput.SpecialKey.ARROW_LEFT
    Key.DirectionUp -> VncInput.SpecialKey.ARROW_UP
    Key.DirectionRight -> VncInput.SpecialKey.ARROW_RIGHT
    Key.DirectionDown -> VncInput.SpecialKey.ARROW_DOWN
    else -> null
}

/**
 * X11 keysym for a hardware key event: the [specialKeyFor] table first, else the character the
 * event carries (`utf16CodePoint`, desktop's `awt.keyChar`). Null = not forwarded.
 *
 * KNOWN LIMITATION (carried over verbatim from desktop): modifier CHORDS (Ctrl+C, Alt+Tab) are not
 * forwarded — a held modifier collapses the code point into a control character that
 * [VncInput.keysymForChar] rejects. The explicit Ctrl+Alt+Del button is the one exception.
 */
internal fun keysymForKeyEvent(event: KeyEvent): Long? {
    specialKeyFor(event.key)?.let { return VncInput.keysymForSpecial(it) }
    val code = event.utf16CodePoint
    if (code <= 0 || code > 0xFFFF) return null
    return VncInput.keysymForChar(code.toChar())
}

// ── 4-state status model (parity with iOS DisplayStatusChip.State) ────────────────────────────

/** Connecting / Connected / Disconnected / Needs-password — independent of either transport's
 *  status enum; callers map their status into it. */
enum class DisplayState { CONNECTING, CONNECTED, DISCONNECTED, NEEDS_PASSWORD }

fun ScrcpyStatus.toDisplayState(): DisplayState = when (this) {
    ScrcpyStatus.CONNECTING -> DisplayState.CONNECTING
    ScrcpyStatus.CONNECTED -> DisplayState.CONNECTED
    ScrcpyStatus.DISCONNECTED -> DisplayState.DISCONNECTED
}

fun VncStatus.toDisplayState(): DisplayState = when (this) {
    VncStatus.CONNECTING -> DisplayState.CONNECTING
    VncStatus.CONNECTED -> DisplayState.CONNECTED
    VncStatus.DISCONNECTED -> DisplayState.DISCONNECTED
    VncStatus.NEEDS_PASSWORD -> DisplayState.NEEDS_PASSWORD
}

// ── The panel ─────────────────────────────────────────────────────────────────────────────────

/**
 * Display pane for a session's mirrored screen.
 *
 * Resolves the newest RUNNING stream named [sessionName] from [DisplayActions.displays] (seeded
 * once via `listDisplays`, live afterwards through `display_added`/`display_removed`), then hands
 * it to [DisplayStreamSurface]. With no stream it offers to start one.
 *
 * @param sessionName the session's NAME (not its id) — that is what a `DisplayStream` carries.
 */
@Composable
fun DisplayPanel(
    sessionName: String,
    actions: DisplayActions,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val live by actions.displays.collectAsState()
    var seeded by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    // Seed the live list once on first open (the reducer otherwise only fills from frames); after
    // that the StateFlow keeps the pane reactive (parity with iOS/web).
    LaunchedEffect(sessionName) {
        actions.listDisplays()
        seeded = true
    }

    val stream = remember(live, sessionName) {
        live.filter { it.sessionName == sessionName && it.status == "running" }
            .maxByOrNull { it.createdAt ?: "" }
    }

    Box(
        modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            stream != null -> DisplayStreamSurface(stream, actions, Modifier.fillMaxSize())
            !seeded -> CircularProgressIndicator(color = cs.primary)
            else -> DisplayEmptyState(
                starting = starting,
                onStart = {
                    starting = true
                    scope.launch {
                        // the display_added frame flips `stream` non-null live
                        actions.startDisplay(sessionName)
                        starting = false
                    }
                },
                onRefresh = { scope.launch { actions.listDisplays() } },
            )
        }
    }
}

/**
 * The live surface for ONE resolved [stream] — the analog of iOS `DisplayStreamView`. Reused by
 * [DisplayPanel] (the chat Display tab), the shell's view host and the [DisplaysScreen] viewer.
 *
 * TRANSPORT SWITCH (Android's, now over the G1 seam): h264 goes to the platform's hardware decoder
 * when the host HAS one and declares `Caps.scrcpy`; everything else paints the VNC framebuffer. An
 * h264 stream on a host with no decoder (desktop) keeps desktop's honest "unsupported" message
 * rather than pointing an RFB client at an H.264 socket.
 */
@Composable
fun DisplayStreamSurface(
    stream: DisplayStream,
    actions: DisplayActions,
    modifier: Modifier = Modifier,
) {
    val platform = LocalPlatform.current
    val decoder = platform.videoDecoder()
    when (displayTransportFor(stream.transport, decoder != null, platform.caps.scrcpy)) {
        // The decoding surface owns its whole loop (client, codec, touch, keys) and paints the
        // shared chip/control bar/keyboard field with it — it is the only holder of the
        // ScrcpyClient, so the panel cannot draw that chrome on its behalf.
        DisplayTransport.VIDEO -> decoder!!.VideoSurface(stream.id, actions.connectScrcpy, modifier)
        DisplayTransport.VNC -> VncView(stream.id, actions.connectVnc, stream.provider, modifier)
        DisplayTransport.UNSUPPORTED ->
            Text(
                "Unsupported display transport '${stream.transport}'",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("display_unsupported"),
            )
    }
}

/** How a stream is going to be painted — the pure half of [DisplayStreamSurface]'s switch. */
internal enum class DisplayTransport { VIDEO, VNC, UNSUPPORTED }

/**
 * The transport switch, as a rule rather than a branch in a composable.
 *
 * Android's original was "h264 with a decoder and the cap → scrcpy, ANYTHING else → VNC"; desktop's
 * was "vnc → paint, anything else → unsupported". The union keeps both honest answers: an h264
 * stream on a host with no decoder is UNSUPPORTED (pointing an RFB client at an H.264 socket is not
 * a fallback), and an unset transport — an older broker — still means VNC, which is what every
 * pre-`transport` stream was.
 */
internal fun displayTransportFor(
    transport: String,
    hasDecoder: Boolean,
    scrcpyCap: Boolean,
): DisplayTransport = when {
    transport == "h264" && hasDecoder && scrcpyCap -> DisplayTransport.VIDEO
    transport == "h264" -> DisplayTransport.UNSUPPORTED
    transport.isEmpty() || transport == "vnc" -> DisplayTransport.VNC
    else -> DisplayTransport.UNSUPPORTED
}

/** Centered "no display" state: a filled Start button + a Refresh text button. */
@Composable
private fun DisplayEmptyState(starting: Boolean, onStart: () -> Unit, onRefresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.testTag("display_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            Icons.Filled.Monitor,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            "No active display for this session",
            color = cs.onSurfaceVariant,
            fontFamily = MonoFontFamily,
            fontSize = 13.sp,
        )
        Button(
            onClick = onStart,
            enabled = !starting,
            modifier = Modifier.touchTarget().testTag("display_start_button"),
        ) {
            if (starting) {
                CircularProgressIndicator(color = cs.onPrimary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Start display", fontWeight = FontWeight.Medium)
            }
        }
        TextButton(onClick = onRefresh, modifier = Modifier.touchTarget().testTag("display_refresh_button")) {
            Text(
                "Refresh",
                color = cs.primary,
                fontFamily = MonoFontFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Material's 48dp minimum, applied only where there is no pointer (the cluster-E hit-target rule). */
@Composable
private fun Modifier.touchTarget(): Modifier =
    if (LocalPointerAvailable.current) this else this.heightIn(min = 48.dp)

/**
 * Live VNC framebuffer + pointer/keyboard surface for a single display [streamId].
 *
 * Runs the [VncClient], blits decoded rects into the shared [VncFramebuffer] and paints it
 * aspect-fit through [VncFrame] (`ContentScale.Fit` does the letterbox — the same formula
 * [VncInput.mapToRemote] maps a pointer with), forwards pointer events through the shared
 * [vncPointerInput] and keys as X11 keysyms. Hardware keys arrive on the focusable surface itself
 * (desktop's route, harmless on a tablet with a real keyboard); a touch client raises the soft
 * keyboard through [HiddenKeyboardField] instead.
 */
@Composable
private fun VncView(
    streamId: String,
    connectVnc: (String) -> VncClient,
    provider: String,
    modifier: Modifier = Modifier,
) {
    val client = remember(streamId) { connectVnc(streamId) }
    val fb = remember(streamId) { VncFramebuffer() }
    val status by client.status.collectAsState()
    val size by client.size.collectAsState()
    val scope = rememberCoroutineScope()
    val sizeRef by rememberUpdatedState(size)
    val focusRequester = remember { FocusRequester() }
    val touch = !LocalPointerAvailable.current

    LaunchedEffect(client) { client.run() }
    LaunchedEffect(client) { client.updates.collect { rects -> fb.applyUpdate(rects, client.size.value) } }
    // Warm-display: with keepAlivePanel the panel stays composed across tab toggles, so onDispose
    // fires only on real teardown (leaving the session) — the right point.
    DisposableEffect(client) { onDispose { client.stop(); fb.release() } }

    var showPassword by remember { mutableStateOf(false) }
    LaunchedEffect(status) { if (status == VncStatus.NEEDS_PASSWORD) showPassword = true }

    var keyboardActive by remember { mutableStateOf(false) }
    val keyboardFocus = remember { FocusRequester() }

    // The Box's own on-screen pixel size (NOT the remote framebuffer size, which is `size`):
    // mapToRemote's viewW/viewH must be the painted canvas' pixel dimensions in the SAME coordinate
    // space `change.position` arrives in, so the pointer map matches the letterbox ContentScale.Fit drew.
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val viewSizeRef by rememberUpdatedState(viewSize)

    Box(
        modifier
            .fillMaxSize()
            .testTag("vnc_surface")
            .onSizeChanged { viewSize = it }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { e ->
                val keysym = keysymForKeyEvent(e) ?: return@onPreviewKeyEvent false
                scope.launch { client.sendKey(keysym, e.type == KeyEventType.KeyDown) }
                true
            }
            // Shared with the scrcpy path's own listener since G2's review fix: same mask rules,
            // plus "ignore what the overlay buttons consumed" and "release a cancelled gesture".
            .vncPointerInput(
                key = streamId,
                viewSize = { viewSizeRef },
                remoteSize = { sizeRef },
                onPress = { runCatching { focusRequester.requestFocus() } },
            ) { rx, ry, mask -> scope.launch { client.sendPointer(rx, ry, mask) } },
    ) {
        // The ONLY reader of the framebuffer state: a new frame recomposes this leaf, not the
        // control bar / status chip / keyboard field around it.
        VncFrame(fb, Modifier.fillMaxSize())

        DisplayStatusChip(
            state = status.toDisplayState(),
            modifier = Modifier.align(Alignment.TopEnd).padding(Space.sm),
        )
        DisplayControlBar(
            keyboardActive = keyboardActive,
            onToggleKeyboard = if (touch) ({ keyboardActive = !keyboardActive }) else null,
            onCtrlAltDel = { scope.launch { client.sendCtrlAltDel() } },
            modifier = Modifier.align(Alignment.BottomStart).padding(Space.md),
        )
        if (touch) {
            HiddenKeyboardField(
                focusRequester = keyboardFocus,
                enabled = keyboardActive,
                onChar = { ch ->
                    VncInput.keysymForChar(ch)?.let { ks ->
                        scope.launch { client.sendKey(ks, true); client.sendKey(ks, false) }
                    }
                },
                onSpecial = { sp ->
                    val ks = VncInput.keysymForSpecial(sp)
                    scope.launch { client.sendKey(ks, true); client.sendKey(ks, false) }
                },
            )
        }
    }

    if (showPassword) {
        VncPasswordPrompt(
            provider = provider,
            onSubmit = { pw -> client.setPassword(pw); showPassword = false },
            onDismiss = { showPassword = false },
        )
    }
}

/**
 * Bottom-leading pill: Ctrl+Alt+Del plus, on a touch client, the soft-keyboard toggle. Desktop's
 * lone `Ctrl+Alt+Del` text button becomes the ⌃⌥⌦ chip Android drew, keeping BOTH tags.
 */
@Composable
fun DisplayControlBar(
    keyboardActive: Boolean,
    modifier: Modifier = Modifier,
    onToggleKeyboard: (() -> Unit)? = null,
    onCtrlAltDel: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    M3Surface(
        modifier = modifier.testTag("display_control_bar"),
        shape = RoundedCornerShape(Radii.pill),
        color = cs.surfaceContainer.copy(alpha = 0.9f),
        contentColor = cs.onSurface,
    ) {
        Row(
            Modifier.padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (onCtrlAltDel != null) {
                TextButton(
                    onClick = onCtrlAltDel,
                    modifier = Modifier.touchTarget().testTag("display_ctrl_alt_del"),
                ) {
                    Text("⌃⌥⌦", color = cs.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (onToggleKeyboard != null) {
                TextButton(
                    onClick = onToggleKeyboard,
                    modifier = Modifier.size(48.dp).testTag("display_keyboard_toggle"),
                ) {
                    Icon(
                        Icons.Filled.Keyboard,
                        contentDescription = "Toggle keyboard",
                        tint = if (keyboardActive) cs.primary else cs.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * "This screen needs a password" prompt, shown when the RFB handshake reports NEEDS_PASSWORD.
 *
 * A bottom sheet on a touch client (Android's), an alert dialog where there is a pointer
 * (desktop's) — both carry their original tags.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VncPasswordPrompt(provider: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var password by remember { mutableStateOf("") }
    val isMac = provider == "macos-screen"
    val explain = if (isMac) "This Mac's Screen Sharing requires a password to connect."
        else "This display requires a password to connect."

    if (LocalPointerAvailable.current) {
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.testTag("vnc_password_dialog"),
            title = { Text("Password required") },
            text = {
                Column {
                    Text(explain)
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
                TextButton(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text("Connect") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.lg)
                .padding(bottom = Space.xl)
                .testTag("vnc_password_sheet"),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Text("Password required", color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(explain, color = cs.onSurfaceVariant, fontSize = 13.sp)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Screen sharing password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onSubmit(password) }),
                modifier = Modifier.fillMaxWidth().testTag("vnc_password_field"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(
                    onClick = { onSubmit(password) },
                    enabled = password.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Connect") }
            }
        }
    }
}

/** Subtle top-right indicator tinted by the stream connection [state] (4-state). */
@Composable
fun DisplayStatusChip(state: DisplayState, modifier: Modifier = Modifier) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val (label, tint) = when (state) {
        DisplayState.CONNECTING -> "Connecting…" to cs.primary
        DisplayState.CONNECTED -> "Connected" to Color(c.warning)
        DisplayState.DISCONNECTED -> "Disconnected" to cs.onSurfaceVariant
        DisplayState.NEEDS_PASSWORD -> "Password required" to cs.primary
    }
    Row(
        modifier
            .background(cs.surfaceContainer.copy(alpha = 0.85f), RoundedCornerShape(Radii.pill))
            .padding(horizontal = Space.sm, vertical = 3.dp)
            .testTag("display_status_chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(Modifier.size(6.dp).background(tint, RoundedCornerShape(Radii.pill)))
        Text(
            label,
            color = tint,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
