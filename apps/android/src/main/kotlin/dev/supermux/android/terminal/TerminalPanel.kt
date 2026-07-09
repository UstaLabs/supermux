package dev.supermux.android.terminal

import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.Radii
import dev.supermux.android.theme.Space
import dev.supermux.net.DEFAULT_CONFIG
import dev.supermux.net.Mods
import dev.supermux.net.PredictionEngine
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalStatus
import dev.supermux.net.decodeInput
import dev.supermux.net.linesFromPixels
import dev.supermux.net.printableSequence
import dev.supermux.net.specialKeySequence
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalPanel(
    connect: () -> TerminalClient,
    modifier: Modifier = Modifier,
    // Whether this terminal is the foreground pane. Drives soft-keyboard focus: a background
    // (kept-alive) pane must never hold the IME. Defaults true for hosts that mount/unmount the
    // pane instead of keeping it alive (the tablet workspace).
    active: Boolean = true,
    // Fires once when the session ends (CONNECTED → DISCONNECTED). The agent-PTY ("Native")
    // tab uses this to fall back to Chat on agent exit (iOS onExit parity). Null = no-op.
    onExit: (() -> Unit)? = null,
) {
    val c = LocalPanes.current
    val scope = rememberCoroutineScope()
    val client = remember { connect() }

    // Predictive local echo: the shared Kotlin engine + termlib op-renderer + keystroke->echo
    // RTT stamp, bundled so the emulator's onKeyboardInput closure (baked in at create time) can
    // reach an engine/adapter built AFTER the emulator exists. Mirrors iOS TerminalCoordinator.
    val pred = remember(client) { PredictionPipeline() }

    // Sticky Ctrl/Alt modifiers from the key bar (tri-state, like iOS Shift). Declared BEFORE the
    // emulator so its onKeyboardInput closure (fixed at create time) can read them: an armed
    // modifier transforms the NEXT real-keyboard keystroke too (Ctrl then `c` = Ctrl-C), matching
    // the web PWA (TerminalPane.vue). Mutations here are on the main thread — termlib posts
    // onKeyboardInput to the main looper — so touching Compose state is safe.
    var ctrlState by remember(client) { mutableStateOf(ModState.OFF) }
    var altState by remember(client) { mutableStateOf(ModState.OFF) }

    val emulator: TerminalEmulator = remember(client) {
        TerminalEmulatorFactory.create(
            initialRows = 24,
            initialCols = 80,
            defaultForeground = Color(c.terminalForeground),
            defaultBackground = Color(c.terminal),
            onKeyboardInput = { data ->
                val ch = singlePrintableChar(data)
                val armed = ctrlState != ModState.OFF || altState != ModState.OFF
                if (armed && ch != null) {
                    // Apply the armed modifier to this keystroke and send it directly. Control codes
                    // aren't printable, so skip predictive echo (web parity). Consume `once`.
                    val bytes = printableSequence(ch, Mods(ctrl = ctrlState != ModState.OFF, alt = altState != ModState.OFF))
                        .encodeToByteArray()
                    if (ctrlState == ModState.ONCE) ctrlState = ModState.OFF
                    if (altState == ModState.ONCE) altState = ModState.OFF
                    scope.launch { client.sendInput(bytes) }
                } else {
                    pred.handleInput(data) // predictive echo BEFORE the send (web/iOS parity)
                    scope.launch { client.sendInput(data) }
                }
            },
            onResize = { dims ->
                scope.launch { client.resize(dims.columns, dims.rows) }
            },
        )
    }

    // Build the engine+adapter once the emulator exists; drop them on teardown (web parity:
    // predictor = null). Runs on the main thread, before any input/output can arrive.
    DisposableEffect(emulator) {
        pred.attach(emulator)
        onDispose { pred.teardown() }
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
            // Reconcile predictions against the authoritative bytes; the engine re-emits them
            // inside a Passthrough op, so there is NO separate emulator.writeInput here (web/iOS
            // parity). Falls back to a direct write only after teardown (engine gone).
            pred.handleOutput(bytes) { emulator.writeInput(bytes) }
        }
    }

    // Pixel height of the laid-out terminal viewport, for converting drag pixels → rows.
    var boxHeightPx by remember { mutableStateOf(0) }

    // ── Soft-keyboard focus (iOS TerminalPane parity) ─────────────────────────────────────────
    // The terminal must NOT grab focus on appear — in the tablet workspace that steals the IME
    // from another visible pane, and on a phone tab it pops the keyboard over output you may only
    // want to read. termlib shows the keyboard purely from `showSoftKeyboard` (it has no built-in
    // tap→IME path), so we drive that flag from a tap: tapping the terminal focuses it. The request
    // is cleared when the pane leaves the foreground (a kept-alive background terminal must never
    // hold the IME) and when the platform IME is dismissed (back button) — termlib can't observe a
    // system dismissal, so we watch WindowInsets.ime for it — so the next tap re-shows the keyboard.
    var wantKeyboard by remember { mutableStateOf(false) }
    var imeWasVisible by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(active) { if (!active) { wantKeyboard = false; imeWasVisible = false } }
    LaunchedEffect(imeVisible) {
        if (imeVisible) imeWasVisible = true
        else if (imeWasVisible) { imeWasVisible = false; wantKeyboard = false }
    }

    // Key-bar press handler: modifier keys cycle their tri-state; every other key builds its byte
    // sequence with the current modifiers (appCursor=false — termlib doesn't expose DECCKM) and
    // sends it, then consumes any `once` modifier. Mirrors TerminalPane.vue's onKeyPress.
    fun onKeyPress(press: KeyPress) {
        when (press) {
            is KeyPress.Mod -> {
                fun next(s: ModState) = when (s) {
                    ModState.OFF -> ModState.ONCE
                    ModState.ONCE -> ModState.LOCKED
                    ModState.LOCKED -> ModState.OFF
                }
                if (press.key == ModKey.CTRL) ctrlState = next(ctrlState) else altState = next(altState)
                return
            }
            is KeyPress.Special -> {
                val mods = Mods(ctrl = ctrlState != ModState.OFF, alt = altState != ModState.OFF)
                val seq = specialKeySequence(press.key, mods, appCursor = false)
                if (seq.isNotEmpty()) { val b = seq.encodeToByteArray(); scope.launch { client.sendInput(b) } }
            }
            is KeyPress.Printable -> {
                val mods = Mods(ctrl = ctrlState != ModState.OFF, alt = altState != ModState.OFF)
                val b = printableSequence(press.ch, mods).encodeToByteArray()
                scope.launch { client.sendInput(b) }
            }
        }
        // Consume `once` modifiers after they've modified a key (leave `locked` armed).
        if (ctrlState == ModState.ONCE) ctrlState = ModState.OFF
        if (altState == ModState.ONCE) altState = ModState.OFF
    }

    // Shrink the terminal above the soft keyboard (and nav bar) under edge-to-edge — mirrors the
    // chat composer's inset handling. Resizing the view makes termlib recompute its grid and emit a
    // pty resize, so the shell reflows to the visible rows/cols instead of the cursor line hiding
    // behind the IME. (Background stays full-bleed; only the content insets.)
    Box(
        modifier
            .fillMaxSize()
            .background(Color(c.terminal))
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
      Column(Modifier.fillMaxSize()) {
        // The terminal viewport takes the space above the key bar; boxHeightPx measures THIS inner
        // box (not the bar) so the drag→row math stays correct.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
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
            }
            // Trackpad / mouse-wheel: real Scroll events (not a touch drag) reach us as
            // PointerEventType.Scroll, which the drag handler above (keyed on awaitFirstDown)
            // never sees — so wheel/trackpad scroll was dead in the terminal + native views.
            // Forward them as the same tmux SGR wheel bytes.
            .pointerInput(client, emulator) {
                var scrollAccum = 0.0
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Scroll) continue
                        var dy = 0f
                        for (ch in event.changes) dy += ch.scrollDelta.y
                        if (dy == 0f) continue
                        // Compose negates AXIS_VSCROLL, so wheel-down → +y → newer output, which
                        // matches linesFromPixels' sign. Accumulate so a trackpad's sub-line
                        // deltas eventually move a whole row.
                        scrollAccum += dy.toDouble()
                        val lines = scrollAccum.toInt()
                        if (lines != 0) {
                            scrollAccum -= lines
                            val rows = emulator.dimensions.rows
                            val cols = emulator.dimensions.columns
                            client.sendInput(
                                wheelEventsFromLines(
                                    lines,
                                    if (cols > 1) cols / 2 else 1,
                                    if (rows > 1) rows / 2 else 1,
                                ),
                            )
                        }
                        for (ch in event.changes) ch.consume()
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
            // No auto-focus on appear (see the wantKeyboard note above); a tap requests the
            // keyboard. termlib has no built-in tap→IME path, so onTerminalTap drives the flag.
            showSoftKeyboard = active && wantKeyboard,
            onTerminalTap = { wantKeyboard = true },
        )
        StatusChip(
            status = status,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(Space.sm),
        )
        }

        // Key-accessory bar pinned above the soft keyboard. Foreground pane only — a kept-alive
        // background terminal must not show it. (Android is always touch, so no pointer gate.)
        if (active) {
            TerminalKeyBar(
                ctrl = ctrlState,
                alt = altState,
                onPress = { onKeyPress(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
      }
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

/**
 * A single printable ASCII char (0x20–0x7e) from a keystroke's bytes, or null. This is the only
 * input an armed bar modifier transforms (Ctrl/Alt + letter/punct); control keys, Enter, and
 * multi-byte/IME input pass through untouched. Mirrors TerminalPane.vue's isSinglePrintable.
 */
private fun singlePrintableChar(data: ByteArray): Char? {
    if (data.size != 1) return null
    val b = data[0].toInt() and 0xff
    return if (b in 0x20..0x7e) b.toChar() else null
}

/**
 * Bundles the predictive-echo engine + termlib [PredictionAdapter] + keystroke->echo RTT clock
 * for one terminal, so the emulator's `onKeyboardInput` closure (fixed at create time) can reach
 * an engine/adapter built AFTER the emulator. The Android twin of iOS `TerminalCoordinator`'s
 * engine/predAdapter/lastKeyAt + handleInput/handleOutput/teardownPrediction.
 *
 * THREADING: the shared engine is NOT thread-safe. All entry points run on the MAIN thread —
 * [handleInput] from the emulator's `onKeyboardInput` (termlib posts it to its default
 * `Looper.getMainLooper()` handler), [handleOutput] from the `output.collect` LaunchedEffect
 * (Compose main dispatcher), and [attach]/[teardown] from a DisposableEffect (main). So engine
 * access is single-threaded with no extra confinement needed.
 */
private class PredictionPipeline {
    private var engine: PredictionEngine? = null
    private var adapter: PredictionAdapter? = null
    // nowMs of the last keystroke still awaiting its echo (0 = none). Bootstraps the latency gate
    // from a real keystroke->echo RTT, INDEPENDENTLY of the prediction path — without it the gate
    // could never open (latency starts at 0, predictions need latency >= threshold).
    private var lastKeyAt = 0L

    /** Build the engine + adapter once the terminal exists (mirror iOS `attach`). If the adapter
     *  can't read termlib's cursor (its internal snapshot is unreachable), leave the engine null
     *  so prediction is disabled and the terminal runs unaffected. */
    fun attach(emulator: TerminalEmulator) {
        val a = PredictionAdapter(emulator)
        lastKeyAt = 0L
        if (!a.available) {
            adapter = null
            engine = null
            return
        }
        adapter = a
        engine = PredictionEngine(DEFAULT_CONFIG) { nowMonotonicMs() }
    }

    /** INPUT: decode the keystroke, render the engine's predicted ops, then stamp the RTT clock.
     *  Called from `onKeyboardInput` BEFORE the bytes reach the pty. No-op until attached. */
    fun handleInput(data: ByteArray) {
        val e = engine ?: return
        val a = adapter ?: return
        a.render(e.onInput(decodeInput(data.decodeToString()), a.cursor()))
        lastKeyAt = nowMonotonicMs()
    }

    /** OUTPUT: bootstrap the latency estimate from the keystroke->echo RTT, then let the engine
     *  reconcile and re-emit the server bytes via its ops (the Passthrough op carries them — NO
     *  separate writeInput). Before attach / after teardown, [fallback] writes the bytes directly
     *  so none are lost. */
    fun handleOutput(bytes: ByteArray, fallback: () -> Unit) {
        val e = engine ?: return fallback()
        val a = adapter ?: return fallback()
        if (lastKeyAt > 0L) {
            e.setLatencyEstimate(nowMonotonicMs() - lastKeyAt)
            lastKeyAt = 0L
        }
        // Guard ONLY the predicted-output render: an exception thrown here would propagate out of
        // output.collect and CANCEL the collector -> the terminal freezes, which is worse than
        // losing prediction. On any engine/adapter failure, fall back to a direct write so the
        // byte stream (and terminal) stays alive. The INPUT path is deliberately NOT guarded — we
        // want engine bugs to surface there, not be masked.
        runCatching { a.render(e.onServerData(bytes)) }.onFailure { fallback() }
    }

    /** Drop the engine + adapter (teardown). Later output falls back to a direct write. */
    fun teardown() {
        engine = null
        adapter = null
        lastKeyAt = 0L
    }

    private companion object {
        /** Monotonic millisecond clock (does not jump on wall-clock changes) — the Android twin of
         *  the web's performance.now() / iOS's DispatchTime.uptimeNanoseconds. Drives the engine
         *  timing AND the keystroke->echo RTT, so both share one source. Returns Long directly
         *  (no boxing — direct Kotlin, unlike iOS's SKIE KotlinLong). */
        fun nowMonotonicMs(): Long = SystemClock.uptimeMillis()
    }
}
