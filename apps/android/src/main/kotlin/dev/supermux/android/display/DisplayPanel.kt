package dev.supermux.android.display

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.supermux.android.R
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.net.DisplayStream
import dev.supermux.net.ScrcpyClient
import dev.supermux.net.ScrcpyStatus
import dev.supermux.net.VncClient
import dev.supermux.net.VncStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Feeds Annex-B H.264 access units to a MediaCodec decoding straight to a Surface.
 * Configures lazily once SPS (NAL type 7) + PPS (type 8) + surface + dims are known.
 * All entry points are synchronized; feed() is called from the WS collect coroutine.
 */
class H264SurfaceDecoder {
    private var codec: MediaCodec? = null
    @Volatile private var configured = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var sawKey = false
    private var ptsUs = 0L
    private val bufInfo = MediaCodec.BufferInfo()

    @Synchronized fun setDims(w: Int, h: Int) { width = w; height = h; tryConfigure() }

    @Synchronized fun setSurface(s: Surface?) {
        if (s == null) { releaseLocked(); surface = null; return }
        surface = s; tryConfigure()
    }

    @Synchronized fun feed(isKey: Boolean, data: ByteArray) {
        if (!configured) {
            scanCsd(data)
            tryConfigure()
            if (!configured) return
        }
        if (!sawKey) {
            if (!isKey) return
            sawKey = true
        }
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val ib = c.getInputBuffer(inIdx) ?: return
                ib.clear()
                ib.put(data)
                c.queueInputBuffer(inIdx, 0, data.size, ptsUs, 0)
                ptsUs += 33_333
            }
            var outIdx = c.dequeueOutputBuffer(bufInfo, 0)
            while (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true)
                outIdx = c.dequeueOutputBuffer(bufInfo, 0)
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun release() { releaseLocked(); surface = null; sps = null; pps = null }

    private fun releaseLocked() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null; configured = false; sawKey = false
    }

    private fun tryConfigure() {
        if (configured) return
        val s = surface ?: return
        val sp = sps ?: return
        val pp = pps ?: return
        if (width == 0 || height == 0) return
        try {
            val fmt = MediaFormat.createVideoFormat("video/avc", width, height)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sp))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(pp))
            val c = MediaCodec.createDecoderByType("video/avc")
            c.configure(fmt, s, null, 0)
            c.start()
            codec = c
            configured = true
            sawKey = false
        } catch (_: Exception) { codec = null; configured = false }
    }

    /** Scan an Annex-B buffer for SPS (type 7) / PPS (type 8); store each with a 4-byte start code. */
    private fun scanCsd(data: ByteArray) {
        val n = data.size
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 < n) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) { starts.add(i); i += 3; continue }
                if (data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) { starts.add(i); i += 4; continue }
            }
            i++
        }
        for (k in starts.indices) {
            val sc = starts[k]
            val hdr = if (sc + 2 < n && data[sc + 2].toInt() == 1) sc + 3 else sc + 4
            if (hdr >= n) continue
            val type = data[hdr].toInt() and 0x1F
            val end = if (k + 1 < starts.size) starts[k + 1] else n
            if (type == 7 && sps == null) sps = withStartCode(data, hdr, end)
            if (type == 8 && pps == null) pps = withStartCode(data, hdr, end)
        }
    }

    private fun withStartCode(data: ByteArray, nalStart: Int, end: Int): ByteArray {
        val body = data.copyOfRange(nalStart, end)
        val out = ByteArray(4 + body.size)
        out[3] = 1
        System.arraycopy(body, 0, out, 4, body.size)
        return out
    }
}

// ── 4-state status model (parity with iOS DisplayStatusChip.State) ─────────────

/** Connecting / Connected / Disconnected / Needs-password — independent of either
 *  transport's status enum; callers map their status into it. */
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

/**
 * Native Display panel for a session's mirrored device/desktop screen.
 *
 * Resolves the session's newest running display from the live [displays] StateFlow
 * (kept current by `display_added`/`display_removed`; seeded once via [listDisplays]),
 * then routes by transport: h264 → MediaCodec decode to a SurfaceView ([ScrcpyView]);
 * vnc → software framebuffer blit to a TextureView ([VncView]). Both forward touch +
 * keyboard. When there's no display, offers to start one ([onStartDisplay]).
 */
@Composable
fun DisplayPanel(
    sessionName: String,
    displays: StateFlow<List<DisplayStream>>,
    listDisplays: suspend () -> List<DisplayStream>,
    connectScrcpy: (String) -> ScrcpyClient,
    connectVnc: (String) -> VncClient,
    onStartDisplay: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    val live by displays.collectAsStateWithLifecycle()
    var seeded by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    // Seed the live list once on first open (the reducer otherwise only fills from
    // frames); after that the StateFlow keeps the pane reactive (parity with iOS/web).
    LaunchedEffect(Unit) {
        listDisplays()
        seeded = true
    }

    // Newest running stream for this session, regardless of transport (iOS runningDisplay).
    val s = remember(live, sessionName) {
        live.filter { it.sessionName == sessionName && it.status == "running" }
            .maxByOrNull { it.createdAt ?: "" }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF000000)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            s != null -> DisplayStreamSurface(s, connectScrcpy, connectVnc)
            !seeded -> CircularProgressIndicator(color = cs.primary)
            else -> DisplayEmptyState(
                starting = starting,
                onStart = {
                    starting = true
                    scope.launch {
                        onStartDisplay()   // the display_added frame flips `s` non-nil live
                        starting = false
                    }
                },
                onRefresh = { scope.launch { listDisplays() } },
            )
        }
    }
}

/** Centered "no display" state with a filled Start button + a Refresh text button. */
@Composable
private fun DisplayEmptyState(starting: Boolean, onStart: () -> Unit, onRefresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.testTag("display_empty_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_monitor),
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
            modifier = Modifier.heightIn(min = 48.dp).testTag("display_start_button"),
        ) {
            if (starting) {
                CircularProgressIndicator(color = cs.onPrimary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text("Start display", fontWeight = FontWeight.Medium)
            }
        }
        TextButton(onClick = onRefresh, modifier = Modifier.heightIn(min = 48.dp)) {
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

/**
 * The live display surface for ONE resolved [stream] plus its on-screen controls —
 * the analog of iOS `DisplayStreamView`. Reused by [DisplayPanel] (chat tab) and the
 * management full-screen viewer (DisplaysScreen): h264 → [ScrcpyView], vnc → [VncView].
 */
@Composable
fun DisplayStreamSurface(
    stream: DisplayStream,
    connectScrcpy: (String) -> ScrcpyClient,
    connectVnc: (String) -> VncClient,
) {
    if (stream.transport == "h264") {
        ScrcpyView(stream.id, connectScrcpy)
    } else {
        VncView(stream.id, connectVnc, stream.provider)
    }
}

/**
 * Live H.264 decode + touch/keyboard surface for a single display [streamId].
 *
 * Runs the [ScrcpyClient] connect loop, pipes frames into a [H264SurfaceDecoder]
 * rendering to a SurfaceView, maps touches from the letterboxed (aspect-fit) view rect
 * back into the remote screen's pixels, and forwards keyboard text/keys as scrcpy JSON.
 */
@Composable
private fun ScrcpyView(streamId: String, connect: (String) -> ScrcpyClient) {
    val client = remember(streamId) { connect(streamId) }
    val decoder = remember(streamId) { H264SurfaceDecoder() }

    val status by client.status.collectAsState()
    val dims by client.dims.collectAsState()

    LaunchedEffect(client) { client.run() }
    LaunchedEffect(dims) { dims?.let { decoder.setDims(it.first, it.second) } }
    LaunchedEffect(client) { client.frames.collect { decoder.feed(it.isKey, it.data) } }
    // Warm-display: with keepAlivePanel the panel stays composed across tab toggles,
    // so onDispose fires only on real teardown (leaving the session) — the right point.
    DisposableEffect(client) { onDispose { client.stop(); decoder.release() } }

    val scope = rememberCoroutineScope()
    // The touch listener is installed once but must always read the latest stream
    // dims; rememberUpdatedState keeps a stable ref whose value tracks recomposition.
    val dimsRef by rememberUpdatedState(dims)

    val focusRequester = remember { FocusRequester() }
    var keyboardActive by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("scrcpy_surface"),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            decoder.setSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            decoder.setSurface(null)
                        }
                    })
                    setOnTouchListener { v, e ->
                        val action = when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> 0
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 1
                            MotionEvent.ACTION_MOVE -> 2
                            else -> return@setOnTouchListener true
                        }
                        val d = dimsRef
                        if (d != null) {
                            val (w, h) = d
                            val (sx, sy) = VncInput.mapToRemote(e.x, e.y, v.width, v.height, w, h)
                            scope.launch {
                                client.sendInput(
                                    "{\"type\":\"touch\",\"action\":$action,\"x\":$sx,\"y\":$sy,\"width\":$w,\"height\":$h}",
                                )
                            }
                        }
                        true
                    }
                }
            },
        )

        DisplayStatusChip(
            state = status.toDisplayState(),
            modifier = Modifier.align(Alignment.TopEnd).padding(Space.sm),
        )
        DisplayControlBar(
            keyboardActive = keyboardActive,
            onToggleKeyboard = { keyboardActive = !keyboardActive },
            modifier = Modifier.align(Alignment.BottomStart).padding(Space.md),
        )
        HiddenKeyboardField(
            focusRequester = focusRequester,
            enabled = keyboardActive,
            onChar = { ch ->
                scope.launch { client.sendInput("{\"type\":\"text\",\"text\":${jsonStr(ch.toString())}}") }
            },
            onSpecial = { sp ->
                val name = VncInput.scrcpyKeyName(sp)
                scope.launch {
                    client.sendInput("{\"type\":\"key\",\"key\":${jsonStr(name)},\"action\":0}")
                    client.sendInput("{\"type\":\"key\",\"key\":${jsonStr(name)},\"action\":1}")
                }
            },
        )
    }
}

/**
 * Live VNC framebuffer + pointer/keyboard surface for a single display [streamId].
 *
 * Runs the [VncClient], blits decoded BGRA rects into a [VncFramebuffer] (drawn aspect-fit
 * to a TextureView's Surface), and forwards pointer (button mask 1 on DOWN/MOVE, 0 on UP —
 * matching iOS) + keyboard (RFB keysyms). A macOS Screen-Sharing password sheet appears
 * when the RFB handshake reports NEEDS_PASSWORD.
 *
 * Uses a TextureView (not SurfaceView) so it re-parents cleanly under keepAlivePanel's
 * alpha/zIndex hidden state.
 */
@Composable
private fun VncView(streamId: String, connectVnc: (String) -> VncClient, provider: String) {
    val client = remember(streamId) { connectVnc(streamId) }
    val fb = remember(streamId) { VncFramebuffer() }
    val status by client.status.collectAsState()
    val size by client.size.collectAsState()
    val scope = rememberCoroutineScope()
    val sizeRef by rememberUpdatedState(size)

    LaunchedEffect(client) { client.run() }
    LaunchedEffect(client) { client.updates.collect { rects -> fb.applyUpdate(rects, client.size.value) } }
    // Warm-display: replicate ScrcpyView verbatim — onDispose only on real teardown.
    DisposableEffect(client) { onDispose { client.stop(); fb.release() } }

    var showPasswordSheet by remember { mutableStateOf(false) }
    LaunchedEffect(status) { if (status == VncStatus.NEEDS_PASSWORD) showPasswordSheet = true }

    val focusRequester = remember { FocusRequester() }
    var keyboardActive by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().testTag("vnc_surface"),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            fb.setSurface(Surface(st), w, h)
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                            fb.onSizeChanged(w, h)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            fb.setSurface(null, 0, 0); return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                    setOnTouchListener { v, e ->
                        val mask = when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> 1   // left button down
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 0   // release
                            else -> return@setOnTouchListener true
                        }
                        val sz = sizeRef ?: return@setOnTouchListener true
                        val (rx, ry) = VncInput.mapToRemote(e.x, e.y, v.width, v.height, sz.first, sz.second)
                        scope.launch { client.sendPointer(rx, ry, mask) }
                        true
                    }
                }
            },
        )

        DisplayStatusChip(
            state = status.toDisplayState(),
            modifier = Modifier.align(Alignment.TopEnd).padding(Space.sm),
        )
        DisplayControlBar(
            keyboardActive = keyboardActive,
            onToggleKeyboard = { keyboardActive = !keyboardActive },
            onCtrlAltDel = { scope.launch { client.sendCtrlAltDel() } },
            modifier = Modifier.align(Alignment.BottomStart).padding(Space.md),
        )
        HiddenKeyboardField(
            focusRequester = focusRequester,
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

    if (showPasswordSheet) {
        VncPasswordSheet(
            provider = provider,
            onSubmit = { pw -> client.setPassword(pw); showPasswordSheet = false },
            onDismiss = { showPasswordSheet = false },
        )
    }
}

/** Bottom-leading M3 pill: optional Ctrl-Alt-Del (VNC) + a keyboard toggle. ≥48dp targets. */
@Composable
private fun DisplayControlBar(
    keyboardActive: Boolean,
    onToggleKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
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
                    modifier = Modifier.heightIn(min = 48.dp).testTag("display_ctrl_alt_del"),
                ) {
                    Text("⌃⌥⌦", color = cs.primary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
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

/** macOS Screen-Sharing password sheet, shown when the RFB handshake reports NEEDS_PASSWORD. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VncPasswordSheet(provider: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState()
    var password by remember { mutableStateOf("") }
    val isMac = provider == "macos-screen"

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
            Text(
                if (isMac) "This Mac's Screen Sharing requires a password to connect."
                else "This screen sharing session requires a password to connect.",
                color = cs.onSurfaceVariant,
                fontSize = 13.sp,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Screen sharing password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onSubmit(password) }),
                modifier = Modifier.fillMaxWidth(),
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
            .padding(horizontal = Space.sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(tint, RoundedCornerShape(Radii.pill)),
        )
        Text(
            label,
            color = tint,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Minimal JSON string escaping for scrcpy text/key payloads (quotes/backslash/control). */
private fun jsonStr(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    return sb.append("\"").toString()
}
