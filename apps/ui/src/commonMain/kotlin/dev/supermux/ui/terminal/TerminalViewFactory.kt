// Cluster G1: the terminal engine seam. `:ui` names NEITHER terminal library — a shared terminal
// screen (cluster G3's TerminalTabs) asks Platform.terminalView() for a surface and the host binds
// its own engine behind it. (Neither library's package is spelled here on purpose: the purity grep
// that guards this module matches those literals anywhere in the file, comments included.)
package dev.supermux.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
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
     * Build one terminal surface on [connect]'s client, WITHOUT drawing it yet.
     *
     * The split exists for the key bar (cluster G3): a shared bar pinned above the soft keyboard is
     * drawn OUTSIDE the surface's own subtree, so it needs the surface's [TerminalSurface.keys]
     * before — and independently of — [TerminalSurface.Content]. A caller that needs no bar can use
     * [TerminalView] and never see the handle.
     *
     * `connect` is invoked ONCE, LAZILY (see [LazyTerminalClient]) and un-keyed inside, so a
     * caller rebinding a pane to another session must wrap this in `key(...)` exactly as the host
     * panels always did.
     */
    @Composable
    fun rememberTerminalSurface(connect: () -> TerminalClient): TerminalSurface

    /**
     * Mount a live terminal on [connect]'s client — [rememberTerminalSurface] plus its
     * [TerminalSurface.Content], for the panes that never draw a key bar of their own.
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
    ) {
        rememberTerminalSurface(connect).Content(modifier, active, onExit)
    }
}

/**
 * One live terminal: its grid, and the keys that reach the pty from outside the grid.
 *
 * [keys] is stable for the life of the surface and usable BEFORE [Content] is composed, which is
 * what lets a bar live somewhere else in the tree (and keeps a background pane's armed Ctrl from
 * leaking into the foreground one — each pane has its own sink).
 */
@Stable
interface TerminalSurface {
    /** The accessory-key route into this pane's pty, and the modifier state a bar renders. */
    val keys: TerminalKeySink

    /** Draw the grid. Same contract as [TerminalViewFactory.TerminalView]. */
    @Composable
    fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?)
}

/**
 * The client of one terminal surface, built on first USE rather than on `rememberTerminalSurface`.
 *
 * A surface hands out its [TerminalSurface.keys] before anything is drawn, so a caller may compose
 * one and never mount [TerminalSurface.Content] (a key bar for a pane that stays hidden, a screen
 * that measures before it draws). Connecting eagerly there would open a pty nobody ever runs and
 * nobody ever disposes — the client is created by the first `Content` composition or the first
 * `keys.press`, whichever comes first, and both host actuals build theirs through this.
 *
 * Compose-thread confined, like every other piece of surface state: `Content` composes there and a
 * key bar's press arrives there too.
 */
@Stable
class LazyTerminalClient(private val connect: () -> TerminalClient) {
    private var client: TerminalClient? = null

    /** Whether the client has been built yet — for tests, and for a dispose that must not connect. */
    val created: Boolean get() = client != null

    /** The client, building it on the first call. */
    fun get(): TerminalClient = client ?: connect().also { client = it }
}

/** Remember one [LazyTerminalClient] for the life of the surface. */
@Composable
fun rememberLazyTerminalClient(connect: () -> TerminalClient): LazyTerminalClient =
    remember { LazyTerminalClient(connect) }

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
    override fun rememberTerminalSurface(connect: () -> TerminalClient): TerminalSurface =
        remember { UnavailableTerminalSurface }

    @Composable
    override fun TerminalView(
        connect: () -> TerminalClient,
        modifier: Modifier,
        active: Boolean,
        onExit: (() -> Unit)?,
    ) = UnavailableTerminalHint(modifier)
}

/** The surface of a host with no engine: a hint, and a sink whose keys go nowhere. */
private object UnavailableTerminalSurface : TerminalSurface {
    override val keys: TerminalKeySink = TerminalKeySink { }

    @Composable
    override fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?) =
        UnavailableTerminalHint(modifier)
}
