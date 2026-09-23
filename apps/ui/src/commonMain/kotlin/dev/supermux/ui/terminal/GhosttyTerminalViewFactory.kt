// Plan 4 Task 3: ONE renderer, behind the seam the four host renderers used.
//
// `TerminalViewFactory` was built (cluster G1) so `:ui` would not have to name a terminal library.
// It named four instead — one per host — and every rule the seam's KDoc states had four
// implementations that agreed only by review: four prediction adapters, four ways of encoding an
// armed Ctrl, four answers to "what does a hidden tab do". This is the fifth and last one, and it
// is the same code on every target.
package dev.supermux.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.Mods
import dev.supermux.net.SpecialKey
import dev.supermux.net.TerminalClient
import dev.supermux.net.TerminalEvent
import dev.supermux.net.CursorPos
import dev.supermux.terminal.OutputOrigin
import dev.supermux.terminal.TerminalCursor
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalKeys
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalRuntime
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.compose.LocalTerminalScroll
import dev.supermux.terminal.compose.Terminal
import dev.supermux.terminal.compose.TerminalAccessoryState
import dev.supermux.terminal.compose.TerminalTheme
import dev.supermux.terminal.compose.rememberTerminalAccessories
import dev.supermux.ui.theme.LocalPanes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The grid's node, for tests. */
const val TERMINAL_GRID_TAG = "terminal_grid"

/** The banner a mid-life engine failure draws over the grid. */
const val TERMINAL_FAILURE_TAG = "terminal_failure"

/** The size a session opens at, before the first layout tells it the real one. */
private val INITIAL_SIZE = TerminalSize(columns = 80, rows = 24, cellWidthPx = 8, cellHeightPx = 16)

/**
 * The shared Compose terminal, behind [TerminalViewFactory].
 *
 * **One session per surface, and the surface owns it.** A single `LaunchedEffect` opens the
 * engine, runs the adapter and closes the engine again — open, adapt and close cannot end up on
 * different owners, which is how a renderer ends up with a session nobody closes or a close that
 * races the feed.
 *
 * **The adapter is the ONLY thing that touches the engine's input.** Every server frame reaches
 * Ghostty through [TerminalEvent.Output] exactly once, unchanged, with the origin the replay
 * boundary says. Predictions are drawn on top ([GhosttyPredictionState]) and never written in.
 *
 * @param wasmAssetUrl where the browser fetches the engine's `.wasm` from, for a host that serves
 *   the file itself. Null — the default, and what every supermux host uses — takes the package's
 *   own URL, which the bundler has already rewritten to the staged, content-hashed asset (see
 *   [SharedTerminal]). Awaited before the first engine is built
 *   (`terminal-core/native/README.md`, "Browser (wasmJs)"); on every other target
 *   `TerminalRuntime.initialize` is a no-op and this is ignored. It must be host configuration,
 *   never user input, and it can only be decided ONCE: a second `initialize` with a different URL
 *   is rejected.
 * @param themeOf the palette, read in composition so it follows the app's theme.
 * @param nowMs the monotonic clock the prediction engine's latency gate and cooldown run on.
 * @param openSession the engine seam. Real builds get the native engine; a test passes its own so
 *   the surface can be driven without one.
 * @param predictionsOf the prediction state each surface gets. A host that wants no speculative
 *   echo at all passes one whose clock never opens the latency gate; a test passes one it holds,
 *   so it can read what the overlay would draw.
 */
@Stable
class GhosttyTerminalViewFactory(
    private val wasmAssetUrl: String? = null,
    private val themeOf: @Composable () -> TerminalTheme = { defaultTerminalTheme() },
    private val nowMs: () -> Long = { currentTimeMillis() },
    private val openSession: suspend (TerminalSize, (TerminalEffect) -> Unit) -> TerminalSession =
        { size, effects -> TerminalSession.open(size = size, limits = TerminalLimits(), effects = effects) },
    private val predictionsOf: @Composable () -> GhosttyPredictionState = { rememberGhosttyPredictions(nowMs) },
) : TerminalViewFactory {

    /** The engine ships with the app on every target; whether it LOADS is a per-mount failure. */
    override val available: Boolean = true

    @Composable
    override fun rememberTerminalSurface(connect: () -> TerminalClient): TerminalSurface {
        val client = rememberLazyTerminalClient(connect)
        val accessories = rememberTerminalAccessories()
        // The bar's presses become KEYS, not bytes: Ghostty owns the encoding (see
        // TerminalKeySink.semantic), so an armed Ctrl is applied exactly once, by the same encoder
        // that handles the physical keyboard and the IME.
        val keys = rememberSemanticTerminalKeySink { key, mods -> sendAccessoryKey(accessories, key, mods) }
        return remember(client, keys, accessories) {
            GhosttyTerminalSurface(this, client, keys, accessories)
        }
    }

    internal suspend fun open(effects: (TerminalEffect) -> Unit): TerminalSession {
        TerminalRuntime.initialize(wasmAssetUrl)
        return openSession(INITIAL_SIZE, effects)
    }

    @Composable
    internal fun theme(): TerminalTheme = themeOf()

    @Composable
    internal fun predictions(): GhosttyPredictionState = predictionsOf()

    internal fun now(): Long = nowMs()
}

/**
 * The renderer every host mounts.
 *
 * One instance, because there is nothing per-host left to vary: the engine is the same on all five
 * targets and the factory holds no state.
 *
 * INCLUDING ON THE BROWSER, WHICH IS THE SURPRISING ONE. The wasm module comes from the package's
 * own default URL — `new URL("./supermux-terminal.wasm", import.meta.url)`, the binary next to the
 * loader. That is not a fallback: webpack (which the Kotlin plugin runs) recognises the pattern and
 * emits the binary as an asset, `:web:stageForBroker` content-hashes every `.js`/`.wasm` into
 * `assets/` and rewrites the reference inside the bundle, and the broker serves a `.wasm` as
 * `application/wasm` and everything under `assets/` as `immutable`
 * (`src/channels/web/static-serve.ts`). So the default URL already IS the hashed asset, and
 * passing one explicitly here would only be a second place for the hash to go stale. A host that
 * genuinely serves the file itself constructs its own
 * [GhosttyTerminalViewFactory].
 */
val SharedTerminal: TerminalViewFactory = GhosttyTerminalViewFactory()

/** The app palette, as the renderer's theme. */
@Composable
fun defaultTerminalTheme(): TerminalTheme {
    val panes = LocalPanes.current
    return remember(panes.terminal, panes.terminalForeground) {
        TerminalTheme(
            background = Color(panes.terminal),
            foreground = Color(panes.terminalForeground),
        )
    }
}

/**
 * One pane: a client, an engine, and the adapter between them.
 *
 * The [keys] sink and [accessories] are TWO views of one armed-modifier state and are kept in step
 * by [BindArmedModifiers]. They cannot be collapsed into one: the sink models supermux's sticky
 * tri-state (off → once → locked), which no emulator has a concept of, while `accessories` is what
 * the shared renderer's key router reads when the PHYSICAL keyboard or the IME produces a key. One
 * source of truth, two readers.
 */
private class GhosttyTerminalSurface(
    private val factory: GhosttyTerminalViewFactory,
    private val client: LazyTerminalClient,
    override val keys: TerminalKeySink,
    private val accessories: TerminalAccessoryState,
) : TerminalSurface {

    @Composable
    override fun Content(modifier: Modifier, active: Boolean, onExit: (() -> Unit)?) {
        // FIRST USE is here: composing the grid is what opens the socket. A surface that only ever
        // handed out its `keys` never connects, and never has to be disconnected.
        val terminal = remember { client.get() }
        val theme = factory.theme()
        val predictions = factory.predictions()
        var session by remember { mutableStateOf<TerminalSession?>(null) }
        var failure by remember { mutableStateOf<Throwable?>(null) }
        val latestOnExit by rememberUpdatedState(onExit)
        val exited = remember { mutableStateOf(false) }
        // ONE ORDERED LANE into the prediction engine.
        //
        // The engine is a state machine over an alternating story — "the user typed x", "the
        // server said x" — and it is the ORDER of those two, not their content, that decides
        // whether a prediction confirms or is read as a divergence. Typing arrives on the
        // engine's coroutine and server bytes on the adapter's, so without a lane between them a
        // fast echo can be reconciled against a keystroke the engine has not been told about yet:
        // the confirmation is missed, the epoch never opens, and nothing is ever predicted again.
        // Not a rare race — on a local broker it is the common case.
        //
        // Everything that touches the engine goes through here, including the clears, so a
        // "forget everything" cannot overtake the keystroke it was meant to forget.
        val signals = remember { Channel<PredictionSignal>(Channel.UNLIMITED) }

        BindArmedModifiers(keys, accessories)

        // ONE owner: open the engine, run the adapter, close the engine. Nothing else opens or
        // closes a session, so there is no order for the two to get wrong.
        LaunchedEffect(terminal, predictions) {
            var opened: TerminalSession? = null
            try {
                val session0 = factory.open(
                    terminalEffects(
                        predict = { signal -> signals.trySend(signal) },
                        sendInput = { bytes -> terminal.sendInput(bytes) },
                        sendReply = { bytes -> terminal.sendReply(bytes) },
                        now = factory::now,
                        caret = { opened?.viewports?.value?.cursor?.toPredictionCursor() },
                    ),
                )
                opened = session0
                session = session0
                adaptTerminalEvents(session0, terminal, signals, factory::now) {
                    if (!exited.value) {
                        exited.value = true
                        latestOnExit?.invoke()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                failure = error
            } finally {
                session = null
                // The pane is going away, so close the LOCAL engine — and only that. The backing
                // terminal outlives every viewer of it; dropping the socket is a detach, not a
                // close, and asking for a close here would kill a shell the user only navigated
                // away from.
                withContext(NonCancellable) { runCatching { opened?.close() } }
            }
        }

        val live = session
        if (live == null) {
            // Two different states, and saying the wrong one is worse than saying nothing. Before
            // the engine has opened there is simply nothing yet — on the browser that is a wasm
            // module being fetched, and a pane that announced "this client has no terminal" for
            // those few hundred milliseconds would be lying. Once `failure` is set it is not
            // coming: the wasm never loaded, or the native library is missing.
            //
            // And that is not "this client has no terminal" either. The engine ships with the app
            // on every target, so a load that failed is a DEPLOYMENT fact — most often a tab open
            // since before a deploy, asking for a hashed asset the current build no longer serves
            // — with an action attached to it. Saying it costs one hint and saves a blank pane.
            if (failure == null) {
                Box(modifier.testTag(TERMINAL_GRID_TAG).background(theme.background))
            } else {
                TerminalLoadFailedHint(failure?.message, modifier)
            }
            return
        }

        // The lane's ONE consumer. Every mutation of the prediction engine happens here, in the
        // order the lane carries, on the composition's thread — which is also the thread the
        // overlay's Canvas reads the result from.
        LaunchedEffect(predictions, signals) {
            for (signal in signals) {
                when (signal) {
                    is PredictionSignal.Typed ->
                        predictions.onInput(signal.bytes, signal.caret, signal.atMs)
                    is PredictionSignal.Served -> predictions.onServerData(signal.bytes, signal.atMs)
                    PredictionSignal.Forget -> predictions.clear()
                }
            }
        }

        // Every published frame reconciles the overlay: what the server confirmed stops being
        // drawn, and a reflow or an alt-screen flip drops the lot.
        //
        // Off the lane on purpose. Reconciling touches only the DRAWN CELLS — it is idempotent
        // pruning against the frame in hand, not a transition of the prediction state machine —
        // and the engine itself is still mutated from exactly one place, the lane's consumer.
        // Putting frames through the lane would only make the overlay lag the grid it sits on.
        LaunchedEffect(live, predictions) {
            live.viewports.collect { predictions.reconcile(it) }
        }

        // GEOMETRY. The grid's own layout is authoritative — the renderer sizes the session from
        // its measured box and we report THAT, never the other way round.
        LaunchedEffect(live, terminal) {
            live.viewports
                .map { it.size.columns to it.size.rows }
                .distinctUntilChanged()
                .collect { (columns, rows) -> terminal.resize(columns, rows) }
        }

        // OWNERSHIP. Only a pane that is both the visible one AND in the front window claims the
        // shared size. A background tab reports its geometry (harmless: the backend's lease
        // decides whether it reaches the pty) but never claims the lease.
        val windowFocused = LocalWindowInfo.current.isWindowFocused
        val claimsGeometry = active && windowFocused
        LaunchedEffect(live, terminal, claimsGeometry) { terminal.focus(claimsGeometry) }

        // A pane the user is not looking at holds no modifiers and predicts nothing. A locked Ctrl
        // on a hidden tab has no bar to show it and would fire into whatever the user came back to.
        LaunchedEffect(active, signals) {
            if (!active) {
                keys.clearArmed()
                accessories.clear()
                signals.trySend(PredictionSignal.Forget)
            }
        }

        // THE EPOCH — the one signal that says "the screen you were drawing is gone", whether
        // because this is a fresh connection or because the backing target re-synchronised
        // underneath us. Until now nothing consumed it.
        //
        // Everything speculative belongs to the screen it was speculated about, so the epoch is
        // what ends it: predictions are dropped, and the next frame is demanded FULL so the
        // viewport model patches nothing it did not see the start of.
        //
        // What is deliberately NOT done here is tear the keyboard away. An in-flight IME
        // composition is the platform's, and the only lever this layer has over it is dropping
        // focus — which on a reconnect (every network blip opens a new epoch) would close the soft
        // keyboard under someone mid-word. The platform finalizes or abandons the preedit on the
        // next focus change of its own, and the committed text is already on its way to a pty that
        // refuses it while `restoring` is true.
        val epoch by terminal.epoch.collectAsState()
        LaunchedEffect(live, epoch) {
            signals.trySend(PredictionSignal.Forget)
            live.requestFullFrame()
        }

        // NOTE: nothing stops the client here. `rememberLazyTerminalClient` owns that, and owning
        // it TWICE is not harmless belt-and-braces — a second stop() racing the first reaches a
        // socket the first one is already closing. One owner, one stop.

        Box(modifier.testTag(TERMINAL_GRID_TAG)) {
            // NOT keyed on the epoch. Re-keying would rebuild the renderer on every reconnect and
            // take the keyboard with it; `requestFullFrame` above is what makes the surviving
            // viewport model safe across a new screen, which is the only part that needed it.
            Terminal(
                session = live,
                modifier = Modifier.fillMaxSize(),
                theme = theme,
                active = active,
                accessories = accessories,
                onFailure = { failure = it },
            ) {
                val scroll = LocalTerminalScroll.current
                GhosttyPredictionOverlay(
                    state = predictions,
                    theme = theme,
                    modifier = Modifier.matchParentSize(),
                    scrolledBack = scroll?.following == false,
                )
                // A failure is not an exit: the grid keeps whatever it had, and this says why it
                // stopped moving.
                failure?.let { TerminalFailureBanner(it, Modifier.align(Alignment.TopCenter)) }
            }
        }
    }
}

/** What a mid-life engine failure looks like: a line over the grid, not a blank pane. */
@Composable
private fun TerminalFailureBanner(error: Throwable, modifier: Modifier = Modifier) {
    Text(
        text = error.message ?: "terminal failed",
        color = Color(0xFFFFD7D7),
        fontSize = 12.sp,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .testTag(TERMINAL_FAILURE_TAG)
            .fillMaxWidth()
            .background(Color(0xCC5A1F1F))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * The event adapter: ONE owner, one pass, in wire order.
 *
 * The client's `events` flow is the wire's order made explicit, and this is the only consumer of
 * it. Subscribing BEFORE `run()` starts is not a nicety — `events` has no replay, so a collector
 * that registers a moment late misses the `ready` and the `reset` and then patches a screen it
 * never saw the start of.
 *
 * `run()` returning means the client is stopped for good (an exit, a fatal failure, or the pane
 * going away); anything short of that is a reconnect the client handles itself, with the tab left
 * standing.
 */
internal suspend fun adaptTerminalEvents(
    session: TerminalSession,
    client: TerminalClient,
    predictions: SendChannel<PredictionSignal>,
    now: () -> Long,
    onEnd: () -> Unit,
): Unit = coroutineScope {
    // REPLAY vs LIVE. Everything between `replay-start` and `replay-end` is the screen as it
    // already was, and the engine is told so: a replayed query is not a question anybody is
    // waiting for an answer to, and answering it would type the answer into the shell.
    var origin = OutputOrigin.LIVE
    val subscribed = CompletableDeferred<Unit>()
    val events = launch {
        client.events
            .onSubscription { subscribed.complete(Unit) }
            .collect { event ->
                when (event) {
                    is TerminalEvent.Ready -> {
                        // Protocol version, epoch and reply ownership are the client's own state
                        // (`epoch`, `replyOwner`), and `sendReply` already refuses on both. There
                        // is deliberately nothing to mirror here: two owners of one fact is how
                        // they come to disagree.
                        origin = OutputOrigin.LIVE
                    }
                    is TerminalEvent.Reset -> {
                        // A new screen. Everything drawn so far is void — including the parts of
                        // it that were ours.
                        session.reset()
                        session.select(null)
                        predictions.trySend(PredictionSignal.Forget)
                        session.requestFullFrame()
                    }
                    is TerminalEvent.ReplayStart -> origin = OutputOrigin.REPLAY
                    is TerminalEvent.Output -> {
                        // THE one feed. Exact bytes, once, with the current origin.
                        session.receive(event.bytes, origin)
                        // Only LIVE bytes can confirm or contradict a prediction: a replay is the
                        // screen's history, and a prediction made against the screen it is
                        // replacing has nothing to say about it.
                        if (origin == OutputOrigin.LIVE) {
                            predictions.trySend(PredictionSignal.Served(event.bytes, now()))
                        }
                    }
                    is TerminalEvent.ReplayEnd -> {
                        origin = OutputOrigin.LIVE
                        // Input re-enables itself: the client refuses `sendInput` with RESTORING
                        // while the boundary is open and accepts it once it closes, so there is no
                        // second flag here to fall out of step with the first.
                    }
                    is TerminalEvent.Owner -> {
                        // Ownership moved. The client tracks it; what matters here is what we do
                        // NOT do — gaining ownership never takes the keyboard. A hidden pane that
                        // grabbed focus because the broker handed it the size lease would steal
                        // the caret out from under whatever the user was typing into.
                    }
                    is TerminalEvent.Exit -> {
                        // The program ended. Stop the client so `run()` returns and this scope
                        // unwinds; the session is closed by the surface that opened it.
                        predictions.trySend(PredictionSignal.Forget)
                        client.stop()
                        onEnd()
                    }
                    is TerminalEvent.Failure -> {
                        // A failure is NOT an exit, and it never calls onEnd: "your program ended"
                        // closes a tab while "I cannot see your program" retries. Recoverable ones
                        // the client reconnects on its own; a fatal one stops it, and the surface
                        // shows why.
                        predictions.trySend(PredictionSignal.Forget)
                    }
                }
            }
    }
    subscribed.await()
    client.run()
    events.cancel()
}

/**
 * The engine's effects, routed — PREDICT FIRST, SEND SECOND.
 *
 * Lifted out of the composable that installs it so the ORDER is testable. It is the one ordering
 * in this file that a fake socket cannot reproduce: both halves happen inside a single call on the
 * engine's coroutine, so no amount of driving the transport can observe them interleaved. Swapping
 * the two lines leaves every end-to-end test green and breaks prediction the moment the round trip
 * is fast — which is every local broker. A test that calls THIS and records the order is the only
 * thing that fails when it is reverted.
 *
 * WHY THE ORDER. The instant [TerminalClient.sendInput] hands the bytes over, the echo is racing
 * us: the event adapter can put that echo on the prediction lane before this coroutine gets to put
 * the keystroke on it, and an echo that arrives before the keystroke it confirms is read as a
 * divergence — the epoch never opens and nothing is ever predicted again. The keystroke cannot
 * come back before it has left.
 *
 * [caret] is read HERE too, at the moment the key was encoded, not when the lane drains: by then
 * the echo may already have moved it, and a prediction placed at the new caret is a prediction one
 * cell to the right of where the user typed.
 *
 * Only two effects leave the engine: what the user typed, and what the emulator ANSWERED. They
 * take different routes because the broker applies opposite rules to them — typing reaches the pty
 * from any viewer, a reply only from the size owner.
 *
 * [sendInput]/[sendReply] are lambdas rather than the `TerminalClient` itself for one reason: the
 * client is a final class whose writes are only observable through a running socket, and the whole
 * point here is to observe a WRITE relative to a PREDICTION, with nothing running.
 */
internal fun terminalEffects(
    predict: (PredictionSignal) -> Unit,
    sendInput: (ByteArray) -> Unit,
    sendReply: (ByteArray) -> Unit,
    now: () -> Long,
    caret: () -> CursorPos?,
): (TerminalEffect) -> Unit = { effect ->
    when (effect) {
        is TerminalEffect.Input -> {
            predict(PredictionSignal.Typed(effect.bytes, caret() ?: ORIGIN, now()))
            sendInput(effect.bytes)
        }
        is TerminalEffect.Response -> sendReply(effect.bytes)
        else -> Unit
    }
}

/**
 * What the prediction engine is told, in the order it is told.
 *
 * A sealed lane rather than three separate calls, because the three are not independent: a
 * `Served` that overtakes the `Typed` it confirms is read as a divergence, and a `Forget` that
 * overtakes either clears a screen that had not been drawn yet.
 */
internal sealed interface PredictionSignal {
    /**
     * The user typed, and Ghostty encoded it to [bytes], with the caret then at [caret] and the
     * clock at [atMs]. Both are captured at the KEYSTROKE, not when the lane drains: the caret
     * because the echo may have moved it by then, and the time because the round trip this
     * measures is the server's, not the UI thread's.
     */
    class Typed(val bytes: ByteArray, val caret: CursorPos, val atMs: Long) : PredictionSignal

    /** LIVE server bytes — already fed to Ghostty, here only to be reconciled against. */
    class Served(val bytes: ByteArray, val atMs: Long) : PredictionSignal

    /** The screen these predictions belong to is gone. */
    data object Forget : PredictionSignal
}

/** The caret of a session that has not published a frame yet. */
private val ORIGIN = CursorPos(row = 0, col = 0)

/** A published frame's caret, in the prediction engine's coordinates. */
private fun TerminalCursor.toPredictionCursor(): CursorPos = CursorPos(row = row, col = column)

/**
 * Keep the bar's sticky tri-state and the renderer's armed modifiers in step.
 *
 * The bar owns the state the user can SEE (off → once → locked); the renderer owns what its key
 * router applies to the next physical key or IME commit. The renderer disarms after every local
 * input — which is exactly right for a one-shot and exactly wrong for a lock — so this watches for
 * that disarm, tells the sink its `once` was used up, and re-arms whatever is still locked.
 *
 * A `snapshotFlow`, not a `SideEffect`: nothing in this composition READS the renderer's armed
 * flags, so a press that only clears them would never recompose anything and a composition-phase
 * mirror would never run.
 */
@Composable
private fun BindArmedModifiers(keys: TerminalKeySink, accessories: TerminalAccessoryState) {
    LaunchedEffect(keys, accessories) {
        var pushedCtrl = false
        var pushedAlt = false
        snapshotFlow { ArmedSnapshot(keys.ctrl, keys.alt, accessories.ctrl, accessories.alt) }
            .collect { armed ->
                // A modifier WE armed is no longer armed over there: a keystroke consumed it.
                if ((pushedCtrl && !armed.engineCtrl) || (pushedAlt && !armed.engineAlt)) {
                    keys.consumeOnce()
                }
                val ctrl = keys.ctrl != TerminalModState.OFF
                val alt = keys.alt != TerminalModState.OFF
                if (accessories.ctrl != ctrl) accessories.ctrl = ctrl
                if (accessories.alt != alt) accessories.alt = alt
                pushedCtrl = ctrl
                pushedAlt = alt
            }
    }
}

private data class ArmedSnapshot(
    val sinkCtrl: TerminalModState,
    val sinkAlt: TerminalModState,
    val engineCtrl: Boolean,
    val engineAlt: Boolean,
)

/**
 * A bar press, as a KEY.
 *
 * The modifiers are written into [accessories] first and applied by `sendKey`, so the armed Ctrl
 * travels with the key into Ghostty's encoder and is applied THERE — once, under whatever modes
 * the program negotiated. The old renderers built the control byte here and handed the emulator a
 * byte it then encoded again; that second transformation is why an armed Alt could arrive as a
 * literal ESC the program read as a keystroke of its own.
 */
private fun sendAccessoryKey(accessories: TerminalAccessoryState, key: TerminalKey, mods: Mods) {
    accessories.ctrl = mods.ctrl
    accessories.alt = mods.alt
    when (key) {
        is TerminalKey.Special -> accessories.sendKey(physicalCodeOf(key.key))
        is TerminalKey.Printable -> accessories.sendKey(physicalCodeOf(key.ch), key.ch.toString())
        is TerminalKey.Mod -> Unit // handled by the sink's own state machine
    }
}

/** The USB HID usage the renderer names this key by. */
internal fun physicalCodeOf(key: SpecialKey): Int = when (key) {
    SpecialKey.Escape -> TerminalKeys.ESCAPE
    SpecialKey.Tab -> TerminalKeys.TAB
    SpecialKey.ArrowUp -> TerminalKeys.ARROW_UP
    SpecialKey.ArrowDown -> TerminalKeys.ARROW_DOWN
    SpecialKey.ArrowRight -> TerminalKeys.ARROW_RIGHT
    SpecialKey.ArrowLeft -> TerminalKeys.ARROW_LEFT
    SpecialKey.Home -> TerminalKeys.HOME
    SpecialKey.End -> TerminalKeys.END
    SpecialKey.PageUp -> TerminalKeys.PAGE_UP
    SpecialKey.PageDown -> TerminalKeys.PAGE_DOWN
}

/**
 * The key a character would have come from on a US keyboard, or `UNIDENTIFIED`.
 *
 * The text alone is enough for an unmodified character, but not for a MODIFIED one: `Ctrl` plus
 * the letter `c` is 0x03 because of the KEY, and an encoder handed only the text "c" has nothing
 * to apply the modifier to. Sending both is what a real keyboard sends.
 */
internal fun physicalCodeOf(ch: Char): Int = when (ch) {
    in 'a'..'z' -> TerminalKeys.A + (ch - 'a')
    in 'A'..'Z' -> TerminalKeys.A + (ch - 'A')
    in '1'..'9' -> TerminalKeys.DIGIT_1 + (ch - '1')
    '0' -> TerminalKeys.DIGIT_0
    ' ' -> TerminalKeys.SPACE
    '-', '_' -> TerminalKeys.MINUS
    '=', '+' -> TerminalKeys.EQUAL
    '[', '{' -> TerminalKeys.BRACKET_LEFT
    ']', '}' -> TerminalKeys.BRACKET_RIGHT
    '\\', '|' -> TerminalKeys.BACKSLASH
    ';', ':' -> TerminalKeys.SEMICOLON
    '\'', '"' -> TerminalKeys.QUOTE
    '`', '~' -> TerminalKeys.BACKQUOTE
    ',', '<' -> TerminalKeys.COMMA
    '.', '>' -> TerminalKeys.PERIOD
    '/', '?' -> TerminalKeys.SLASH
    else -> TerminalKeys.UNIDENTIFIED
}
