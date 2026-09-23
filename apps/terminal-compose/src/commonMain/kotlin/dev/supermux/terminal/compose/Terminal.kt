package dev.supermux.terminal.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSize

/**
 * The shared terminal surface: one [TerminalSession]'s frames, drawn on a Compose canvas.
 *
 * **Ownership.** The CALLER owns the session — it opened it, it feeds it bytes from its transport
 * and it closes it. This composable owns only what it draws and what it subscribes to: leaving the
 * composition unsubscribes and stops asking for frames, it never closes or resizes the session to
 * nothing.
 *
 * **What it does.**
 * - Collects `session.viewports`, folds each frame into a [ViewportModel] (full frames replace,
 *   partial frames patch) and acknowledges the frame once Compose has produced the frame that shows
 *   it. Acknowledging is what makes the session publish the next one.
 * - Measures the layout and resizes the session to the cells that fit — once per distinct size, see
 *   [TerminalGeometry].
 * - Scrolls the local scrollback at pixel resolution: a drag, a wheel notch or a fling moves a
 *   [ScrollController] that asks the engine for a new viewport only when a ROW boundary is crossed
 *   and translates the painted grid for everything in between. Nothing about it reaches the host or
 *   the program on the other end of the pty — see [ScrollController].
 * - Routes input to whoever the terminal's negotiated modes say owns it: keys, the accessory bar and
 *   pastes go to the engine's encoders, ordinary wheel and touch gestures stay local. See
 *   [TerminalInputPolicy] for the routing rules and [TerminalAccessoryState] for the bar.
 * - Maps [theme] onto the engine's default colours and palette before repainting, so that cells the
 *   engine already resolved (palette indices, OSC colours) follow the theme too.
 * - Reports the failure that stopped the session, if one ever does, through [onFailure]: a renderer
 *   uses only non-blocking calls, so without it a dead terminal is just a screen that stopped
 *   changing.
 *
 * - Takes text from an IME without ever sending a preedit byte: the composing text is held apart
 *   from the terminal's own content and drawn at the cursor until the user commits it. See
 *   [TerminalImeState].
 * - Selects text with the mouse, a long press and two touch handles, in the engine's absolute row
 *   space, and copies it through the ENGINE (`selectedText()`) so wraps and grapheme clusters come
 *   out right. See [TerminalSelectionController].
 * - Publishes itself to accessibility and to UI tests: a name, the visible rows, the selection, and
 *   stable copy / paste / scroll / focus actions — and deliberately no live region. See
 *   [terminalSemantics].
 *
 * **Sharing a session.** Several surfaces may show one session. Each takes its own
 * [dev.supermux.terminal.RendererLease], so one going away (or inactive) never stops the frames the
 * others are watching.
 *
 * @param active `false` for a surface that is off-screen or in a background tab: it releases its
 *   renderer lease, so it stops asking the session for frames (the session keeps parsing output and
 *   delivering effects, and any OTHER surface on the same session keeps receiving them), stops
 *   animating — a fling is cancelled, not left running in a tab nobody is looking at — and takes no
 *   focus and no input. It is NOT a close, and it never resizes the session.
 * @param accessories the armed-modifier and direct-key state an accessory bar drives, and the entry
 *   point for [TerminalAccessoryState.paste]. One per terminal; hoist it to put a bar of your own
 *   next to the surface. It is bound while this terminal is composed and disarmed on every focus
 *   change.
 * @param onTitle the newest OSC 0/2 title — only ever called when the host installed a
 *   [TerminalEffectRelay] through [LocalTerminalEffects] (the host, not this surface, owns effects).
 * @param label the terminal's name for a screen reader; see [terminalSemantics].
 * @param clipboard where copy puts text and paste takes it from. A clipboard READ only ever happens
 *   behind a user action (the paste accessory, the a11y paste action); nothing the program prints
 *   can reach it.
 * @param onLink an OSC 8 hyperlink the user activated with a plain click (a press and a release on
 *   the same cell, with no drag, that the program did not ask for). The surface never opens
 *   anything itself — it does not launch a URL and it does not run a shell: what a URI means is the
 *   host's decision, and a link that arrived in a build log is not a reason to open anything.
 * @param onClipboard an OSC 52 clipboard request from the PROGRAM, reported and nothing more —
 *   DEFAULT DENY. The surface never puts a program's text on the clipboard and never reads the
 *   clipboard for one; a host that wants to honour a write asks the user and calls its own
 *   clipboard. Reads (`write = false`) are informational only: the engine already denied them
 *   synchronously, because it cannot wait for an embedder to answer. Only fires when the host
 *   installed a [TerminalEffectRelay] through [LocalTerminalEffects].
 * @param onFailure the unrecoverable engine error that stopped the session, at most once. The
 *   surface keeps showing the last frame it drew — a frozen screen with an explanation beats a
 *   blank one — and the host decides whether to close, retry or show it in [overlay].
 * @param overlay drawn on top of the grid, inside the same box: scrollbars, "connection lost"
 *   banners, a paste confirmation. It recomposes independently of the grid.
 */
@Composable
fun Terminal(
    session: TerminalSession,
    modifier: Modifier = Modifier,
    theme: TerminalTheme = TerminalTheme(),
    active: Boolean = true,
    accessories: TerminalAccessoryState = rememberTerminalAccessories(),
    label: String = TerminalSemantics.LABEL,
    clipboard: TerminalClipboard = rememberTerminalClipboard(),
    onTitle: (String) -> Unit = {},
    onLink: (String) -> Unit = {},
    onClipboard: (TerminalEffect.ClipboardRequest) -> Unit = {},
    onFailure: (Throwable) -> Unit = {},
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current
    // cacheSize = 0: this surface owns its own bounded cache (TextRunCache) and measures the same
    // strings over and over; a second unbounded one behind it would only hide the bounds.
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val metrics = remember(theme.fontFamily, theme.fontSize, theme.lineHeightScale, density) {
        measureCellMetrics(measurer, theme, density)
    }
    val model = remember(session) { ViewportModel() }
    val layoutCache = remember { TextLayoutCache() }
    // Draw work that does not depend on WHEN the paint happens, kept across paints: the grid's runs
    // and each overscan edge's. A repaint with no new frame (a scroll offset, a blink, a neighbour
    // invalidating) then walks no cells at all. See [FrameRunsCache].
    val gridRuns = remember { FrameRunsCache() }
    val runsAbove = remember { OverscanRunsCache() }
    val runsBelow = remember { OverscanRunsCache() }

    val scope = rememberCoroutineScope()
    val scroll = remember(session, scope) {
        // The ONLY thing scrolling asks of the session, and only once per row boundary. The result
        // matters: a refused enqueue means the request was NOT made, and the controller retries.
        ScrollController(scope) { row -> session.scrollTo(row).accepted }
    }
    // The metrics decide what a pixel of scrolling means; they are known before the first paint.
    SideEffect { scroll.onCellHeight(metrics.height) }

    val focusRequester = remember(session) { FocusRequester() }
    // Declared before the frame collector: eviction renumbers the rows a selection is anchored to,
    // so the selection has to see every frame the scroll controller does.
    val selection = remember(session, model, scroll, scope) {
        TerminalSelectionController(session, model, scroll, scope)
    }
    val ime = remember(session) { TerminalImeState() }

    // The engine resolved palette indices and OSC colours itself, so a theme change has to reach it
    // before the next paint or half the screen would keep the old palette.
    val engineColors = remember(theme.foreground, theme.background, theme.cursor, theme.ansi) {
        theme.engineColors()
    }
    LaunchedEffect(session, engineColors) {
        // A dead session reports itself through `failure`; a throw here would take the UI with it.
        runCatching { session.colors(engineColors) }
    }

    val failure by session.failure.collectAsState()
    LaunchedEffect(failure) { failure?.let(onFailure) }

    val relay = LocalTerminalEffects.current
    val title by relay.title.collectAsState()
    LaunchedEffect(title) { title?.let(onTitle) }
    // Reported, never honoured: the surface does not touch the clipboard on a program's say-so.
    val clipboardRequest by relay.clipboard.collectAsState()
    LaunchedEffect(clipboardRequest) { clipboardRequest?.let { onClipboard(it.effect) } }

    // The lease, not a session-wide flag: a second surface on the same session must not be frozen
    // by this one going off-screen. Publication runs while at least one lease is open.
    DisposableEffect(session, active) {
        val lease = if (active) session.attachRenderer() else null
        onDispose { lease?.close() }
    }

    // Every frame is applied, and acknowledged, on the composition's own dispatcher: the snapshot
    // writes happen on the UI owner and nothing in the draw path ever touches the engine.
    LaunchedEffect(session, model, scroll, selection, active) {
        if (!active) {
            scroll.cancelFling()
            return@LaunchedEffect
        }
        session.viewports.collect { viewport ->
            val update = model.apply(viewport)
            when (update) {
                is ViewportUpdate.Applied -> {
                    scroll.onFrame(update.frame)
                    selection.onFrame(update.frame)
                }
                is ViewportUpdate.Rejected -> if (update.reason == ViewportRejection.NEEDS_FULL) {
                    // This surface has no rows to patch (it attached to a session that was already
                    // running, or the grid just changed): ask for a frame that carries all of them.
                    session.requestFullFrame()
                }
            }
            // Wait for the frame that draws it before telling the session it may publish the next.
            withFrameNanos { }
            session.acknowledge(viewport.generation)
        }
    }
    DisposableEffect(session, scroll) {
        onDispose { scroll.cancelFling() }
    }

    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    var requested by remember(session) { mutableStateOf<TerminalSize?>(null) }
    LaunchedEffect(session, scroll, viewportPx, metrics) {
        val next = TerminalGeometry.nextSize(
            requested = requested,
            widthPx = viewportPx.width.toFloat(),
            heightPx = viewportPx.height.toFloat(),
            cell = metrics,
        ) ?: return@LaunchedEffect
        requested = next
        // A reflow rewraps the text the sub-row displacement was measured against, and a fling
        // aimed at the old geometry is meaningless; the LOGICAL anchor survives, the pixels do not.
        scroll.onGridChanged()
        // The host forwards the size to its transport from the frames it observes; the engine is
        // told exactly once per distinct size.
        runCatching { session.resize(next) }
    }

    // A read of `model.frame` in the composable body would recompose the whole surface on every
    // published frame; this one only invalidates when the MODE actually flips.
    val mouseMode by remember(model) {
        derivedStateOf { model.frame?.modes?.mouseTracking == true }
    }
    // Application mouse mode means the program wants the wheel and the drag itself: a fling that
    // was already running is not something the program asked for, so stop it.
    LaunchedEffect(mouseMode, scroll) { if (mouseMode) scroll.cancelFling() }

    val input = remember(session, model, scroll, selection, accessories, scope) {
        TerminalInputController(session, model, scroll, selection, accessories, scope, focusRequester)
    }
    val handleRadiusPx = with(density) { theme.selectionHandleSize.toPx() / 2f }
    // The controller is long-lived; these follow every recomposition without restarting it.
    SideEffect {
        input.metrics = metrics
        input.enabled = active
        input.onLink = onLink
        input.clipboard = clipboard
        input.handleRadiusPx = handleRadiusPx
        input.surfaceHeightPx = viewportPx.height.toFloat()
    }
    DisposableEffect(input, accessories) {
        accessories.bind(input)
        onDispose { accessories.unbind(input) }
    }

    CompositionLocalProvider(LocalTerminalScroll provides scroll) {
        Box(
            modifier
                .onSizeChanged { viewportPx = it }
                // `scrollable`, not a hand-rolled drag detector: it is where Compose normalizes a
                // platform wheel event into pixels (a JVM notch is NOT a pixel count), tracks
                // release velocity in pixels/second, runs the fling through Compose's decay spec
                // and cancels it the moment a new finger lands.
                .scrollable(
                    state = scroll.scrollableState,
                    orientation = Orientation.Vertical,
                    // Enabled even under application mouse mode: what the program actually gets is
                    // decided per gesture by TerminalInputPolicy, and the input node below consumes
                    // what belongs to it. Disabling the whole scrollable here would take Shift+wheel
                    // — the user's override — away with it.
                    enabled = active,
                    reverseDirection = ScrollableDefaults.reverseDirection(
                        layoutDirection = LocalLayoutDirection.current,
                        orientation = Orientation.Vertical,
                        reverseScrolling = false,
                    ),
                    flingBehavior = ScrollableDefaults.flingBehavior(),
                )
                // INSIDE the scrollable on purpose: the pointer node sees the main pass first and
                // consumes what the program asked for before the scrollable can scroll on it. This
                // REGRESSION GUARD. Under application mouse mode `scrollable` above stays ENABLED
                // (disabling it would take Shift+wheel away with it), so the ONLY thing stopping the
                // local history from moving as well is this descendant consuming the Scroll event in
                // Compose's Main pass, before the scrollable's own node sees it. That ordering is a
                // Compose implementation detail: `anApplicationWheelIsEncodedByTheEngineAndNever
                // ScrollsHistory` (InputPolicyTest) asserts the anchor does not move and is what
                // catches a Compose upgrade that changes it.
                .terminalInput(input)
                // A new frame invalidates the semantics and the draw, never the whole composable.
                .terminalSemantics(
                    model = model,
                    scroll = scroll,
                    label = label,
                    enabled = active,
                    focused = { input.focused },
                    onRequestFocus = input::requestFocus,
                    onCopy = input::copySelection,
                    onPaste = input::pasteClipboard,
                ),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(theme.background)
                val frame = model.frame ?: return@Canvas
                val cell = metrics.height
                val offset = scroll.paintOffset(frame)
                // The grid is exactly rows x cellHeight. Anything below it is chrome and stays
                // background, so an overscan row can never flicker into the leftover strip.
                clipRect(bottom = gridHeightPx(frame.size.rows, cell, size.height)) {
                    drawTerminalFrame(
                        frame = frame,
                        runs = gridRuns.runs(frame, theme),
                        metrics = metrics,
                        theme = theme,
                        measurer = measurer,
                        cache = layoutCache,
                        scrollOffsetPx = offset,
                        cursorEnabled = active,
                        marked = ime.marked,
                    )
                    // Shifted up: the bottom edge of the grid is past the frame's last row.
                    if (offset > 0f) {
                        val below = frame.viewportTop + frame.size.rows
                        scroll.rowBelow(frame)?.let {
                            val strip = runsBelow.frame(frame, it, below)
                            drawTerminalFrame(
                                frame = strip,
                                runs = runsBelow.runs(strip, theme),
                                metrics = metrics,
                                theme = theme,
                                measurer = measurer,
                                cache = layoutCache,
                                scrollOffsetPx = offset - frame.size.rows * cell,
                                cursorEnabled = false,
                            )
                        }
                    }
                    // Shifted down: the engine has not published the anchor's frame yet.
                    if (offset < 0f) {
                        scroll.rowAbove(frame)?.let {
                            val strip = runsAbove.frame(frame, it, frame.viewportTop - 1)
                            drawTerminalFrame(
                                frame = strip,
                                runs = runsAbove.runs(strip, theme),
                                metrics = metrics,
                                theme = theme,
                                measurer = measurer,
                                cache = layoutCache,
                                scrollOffsetPx = offset + cell,
                                cursorEnabled = false,
                            )
                        }
                    }
                }
                // Chrome, not grid: the handles sit outside the cells they mark (above the first,
                // below the last), so they are drawn past the clip the overscan rows need.
                drawSelectionHandles(
                    handles = selectionHandles(frame, metrics, offset),
                    theme = theme,
                    radiusPx = handleRadiusPx,
                )
            }
            // The IME's own node: invisible, one pixel, and the surface's focus target.
            TerminalImeField(
                state = ime,
                enabled = active,
                focusRequester = focusRequester,
                onCommit = input::commitText,
                onComposing = input::composing,
            )
            overlay()
        }
    }
}
