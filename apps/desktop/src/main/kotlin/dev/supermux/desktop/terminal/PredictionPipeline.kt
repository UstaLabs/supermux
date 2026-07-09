package dev.supermux.desktop.terminal

import com.jediterm.terminal.ui.JediTermWidget
import dev.supermux.net.DEFAULT_CONFIG
import dev.supermux.net.PredictionEngine
import dev.supermux.net.decodeInput

/**
 * Bundles the predictive-echo engine + [PredictionAdapter] + keystroke→echo RTT clock for one
 * terminal, so the connector's pre-send input tap and the output collector can reach an
 * engine/adapter built AFTER the widget exists. Faithful port of Android's `PredictionPipeline`
 * (TerminalPanel.kt:307-372); the platform swaps are the [attach] signature (widget + connector
 * instead of the termlib emulator) and the monotonic clock (`System.nanoTime()/1_000_000` instead
 * of `SystemClock.uptimeMillis()`).
 *
 * THREADING: the shared engine is NOT thread-safe. All entry points run on the AWT EDT (which IS
 * Compose Desktop's main dispatcher): [handleInput] from `MuxTtyConnector.onUserInput` (JediTerm
 * dispatches key input on the EDT), [handleOutput] from the `client.output` collect LaunchedEffect
 * (Dispatchers.Main = EDT), and [attach]/[teardown] from a DisposableEffect (composition, EDT). So
 * engine access is single-threaded with no extra confinement — the same discipline as Android's
 * main-thread confinement.
 *
 * @param nowMs monotonic millisecond clock; injectable so tests can drive the RTT deterministically.
 */
class PredictionPipeline(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var engine: PredictionEngine? = null
    private var adapter: PredictionAdapter? = null

    // nowMs of the last keystroke still awaiting its echo (0 = none). Bootstraps the latency gate
    // from a real keystroke->echo RTT, INDEPENDENTLY of the prediction path — without it the gate
    // could never open (latency starts at 0, predictions need latency >= threshold).
    private var lastKeyAt = 0L

    /** Build the engine + adapter once the widget exists (mirror Android/iOS `attach`). The adapter
     *  reads cursor/cells through JediTerm's PUBLIC model, so it is always [PredictionAdapter.available]
     *  — but we keep Android's `if (!available)` shape so the two ports stay line-for-line comparable. */
    fun attach(widget: JediTermWidget, connector: MuxTtyConnector) {
        attachAdapter(PredictionAdapter(widget.terminal, widget.terminalTextBuffer, connector))
    }

    /** Seam shared by [attach] and tests (which pass a throwing/spy adapter). Keeps the engine null
     *  when the adapter is unavailable (dead branch on desktop, live on Android). */
    internal fun attachAdapter(a: PredictionAdapter) {
        lastKeyAt = 0L
        if (!a.available) {
            adapter = null
            engine = null
            return
        }
        adapter = a
        engine = PredictionEngine(DEFAULT_CONFIG) { nowMs() }
    }

    /** INPUT: decode the keystroke, render the engine's predicted ops, then stamp the RTT clock.
     *  Called from the connector's pre-send tap BEFORE the bytes reach the pty. No-op until attached. */
    fun handleInput(data: ByteArray) {
        val e = engine ?: return
        val a = adapter ?: return
        a.render(e.onInput(decodeInput(data.decodeToString()), a.cursor()))
        lastKeyAt = nowMs()
    }

    /** OUTPUT: bootstrap the latency estimate from the keystroke->echo RTT, then let the engine
     *  reconcile and re-emit the server bytes via its ops (the Passthrough op carries them — NO
     *  separate inject/offer). Before attach / after teardown, [fallback] writes the bytes directly
     *  so none are lost. */
    fun handleOutput(bytes: ByteArray, fallback: () -> Unit) {
        val e = engine ?: return fallback()
        val a = adapter ?: return fallback()
        if (lastKeyAt > 0L) {
            e.setLatencyEstimate(nowMs() - lastKeyAt)
            lastKeyAt = 0L
        }
        // Guard ONLY the predicted-output render: an exception here would propagate out of
        // output.collect and CANCEL the collector -> the terminal freezes, worse than losing
        // prediction. On any engine/adapter failure, fall back to a direct write so the byte stream
        // (and terminal) stays alive. The INPUT path is deliberately NOT guarded — engine bugs
        // should surface there, not be masked.
        runCatching { a.render(e.onServerData(bytes)) }.onFailure { fallback() }
    }

    /** Drop the engine + adapter (teardown). Later output falls back to a direct write. */
    fun teardown() {
        engine = null
        adapter = null
        lastKeyAt = 0L
    }

    /** Test-only: observe the pre-send tap's RTT stamp (0 = none / consumed). */
    internal fun peekLastKeyAt(): Long = lastKeyAt
}
