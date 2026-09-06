// Cluster G1: the terminal engine seam. `:ui` names NEITHER com.jediterm NOR org.connectbot — a
// shared terminal screen (cluster G3's TerminalTabs) asks Platform.terminalView() for a surface and
// the host binds its own engine behind it.
package dev.supermux.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import dev.supermux.net.CursorPos
import dev.supermux.net.DisplayOp
import dev.supermux.net.TerminalClient

/**
 * Builds the host's native terminal surface for one [TerminalClient].
 *
 * There are exactly two implementations: `JediTermTerminalViewFactory` (desktop — a
 * `JediTermWidget` inside a `SwingPanel`) and `TermlibTerminalViewFactory` (Android — ConnectBot
 * termlib's emulator inside an `AndroidView`). Both drive the SAME lifecycle, which is the contract
 * a shared caller may rely on:
 *
 *  - **feed** — every byte the client emits reaches the emulator in arrival order (desktop through
 *    the connector's ordered FIFO, Android through `writeInput`).
 *  - **resize** — the grid's own layout is authoritative: the surface measures itself and calls
 *    `TerminalClient.resize` (never the other way round), and a hidden/kept-alive pane laid out at
 *    0×0 must NOT shrink the remote pty.
 *  - **focus** — `TerminalClient.focus(active && hostForeground)` is hoisted on [active], so a
 *    background pane never owns the shared pty geometry or the soft keyboard.
 *  - **dispose** — leaving the composition stops the client, closes the byte stream and releases
 *    the engine.
 *  - **predictions** — a [PredictionSink] over the shared `PredictionEngine`'s [DisplayOp]s.
 *
 * DEVIATION (recorded in G1): those five are the surface's OWN lifecycle rather than five methods
 * on this interface. Both engines own their geometry and run their emulator on their own thread, so
 * an imperative `feed`/`resize` reachable from shared code would either race that thread or need a
 * second ordering lane; the sink is the one part a caller can genuinely hold, so it is the one part
 * that is a typed interface here. G3 moves `TerminalTabs` on top of [TerminalView] unchanged.
 */
@Stable
interface TerminalViewFactory {
    /**
     * Whether this host has a terminal engine at all. Mirrors [dev.supermux.ui.platform.Caps.terminal]
     * — check the cap before offering a "new terminal" affordance; a screen that mounts
     * [TerminalView] anyway gets [UnavailableTerminalHint] rather than a crash.
     */
    val available: Boolean

    /**
     * Mount a live terminal on [connect]'s client. Called ONCE per surface: `connect` is
     * deliberately un-keyed inside, so a caller that rebinds a pane to another session must wrap
     * this in `key(...)` exactly as the host panels always did.
     *
     * [onExit] fires when the pty ends server-side (desktop: the broker's `exit` frame; Android:
     * the CONNECTED→DISCONNECTED latch) — never on a transient reconnect. Null = no-op.
     */
    @Composable
    fun TerminalView(
        connect: () -> TerminalClient,
        modifier: Modifier,
        active: Boolean,
        onExit: (() -> Unit)?,
    )
}

/**
 * Renders the shared `PredictionEngine`'s display ops against one engine's screen — the typed shape
 * both host adapters (`JediTermPredictionAdapter`, `TermlibPredictionAdapter`) already had.
 *
 * [available] is false where the engine's cursor/cell read path is unreachable (Android reflects
 * into termlib's internal snapshot and can lose it on a library bump); the pipeline then skips
 * prediction wholesale and the terminal runs unaffected.
 */
interface PredictionSink {
    val available: Boolean

    /** Current caret, screen-relative and 0-based on BOTH axes (all three clients agree). */
    fun cursor(): CursorPos

    /** Render one engine batch. Ops are applied in order; `Passthrough` carries server bytes. */
    fun render(ops: List<DisplayOp>)
}

/** The "this host has no terminal engine" factory — mounts [UnavailableTerminalHint]. */
object UnavailableTerminalViewFactory : TerminalViewFactory {
    override val available: Boolean = false

    @Composable
    override fun TerminalView(
        connect: () -> TerminalClient,
        modifier: Modifier,
        active: Boolean,
        onExit: (() -> Unit)?,
    ) = UnavailableTerminalHint(modifier)
}
