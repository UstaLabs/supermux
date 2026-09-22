package dev.supermux.terminal.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
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
 * - Maps [theme] onto the engine's default colours and palette before repainting, so that cells the
 *   engine already resolved (palette indices, OSC colours) follow the theme too.
 * - Reports the failure that stopped the session, if one ever does, through [onFailure]: a renderer
 *   uses only non-blocking calls, so without it a dead terminal is just a screen that stopped
 *   changing.
 *
 * **What it does not do yet.** Smooth scrolling (Task 3), gestures and mouse routing (Task 4) and
 * IME, selection and full semantics (Task 5) arrive in their own files; the seams are here — the
 * painter takes a pixel scroll offset, and input will be a modifier of its own.
 *
 * @param active `false` for a surface that is off-screen or in a background tab: it stops asking
 *   the session for frames (the session keeps parsing output and delivering effects), and later
 *   also stops animation, focus and input. It is NOT a close, and it never resizes the session.
 * @param onTitle the newest OSC 0/2 title — only ever called when the host installed a
 *   [TerminalEffectRelay] through [LocalTerminalEffects] (the host, not this surface, owns effects).
 * @param onLink an OSC 8 hyperlink the user activated. The link geometry is already in every frame
 *   (`TerminalViewport.links`); the gesture that fires this lands with Task 4.
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
    onTitle: (String) -> Unit = {},
    onLink: (String) -> Unit = {},
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

    // Every frame is applied, and acknowledged, on the composition's own dispatcher: the snapshot
    // writes happen on the UI owner and nothing in the draw path ever touches the engine.
    LaunchedEffect(session, model, active) {
        if (!active) {
            session.setRenderingEnabled(false)
            return@LaunchedEffect
        }
        session.setRenderingEnabled(true)
        session.viewports.collect { viewport ->
            val update = model.apply(viewport)
            if (update is ViewportUpdate.Rejected && update.reason == ViewportRejection.NEEDS_FULL) {
                // This surface has no rows to patch (it attached to a session that was already
                // running, or the grid just changed): ask for a frame that carries all of them.
                session.requestFullFrame()
            }
            // Wait for the frame that draws it before telling the session it may publish the next.
            withFrameNanos { }
            session.acknowledge(viewport.generation)
        }
    }
    DisposableEffect(session) {
        onDispose { session.setRenderingEnabled(false) }
    }

    var viewportPx by remember { mutableStateOf(IntSize.Zero) }
    var requested by remember(session) { mutableStateOf<TerminalSize?>(null) }
    LaunchedEffect(session, viewportPx, metrics) {
        val next = TerminalGeometry.nextSize(
            requested = requested,
            widthPx = viewportPx.width.toFloat(),
            heightPx = viewportPx.height.toFloat(),
            cell = metrics,
        ) ?: return@LaunchedEffect
        requested = next
        // The host forwards the size to its transport from the frames it observes; the engine is
        // told exactly once per distinct size.
        runCatching { session.resize(next) }
    }

    Box(
        modifier
            .onSizeChanged { viewportPx = it }
            // A new frame invalidates the semantics and the draw, never the whole composable.
            .terminalSemantics(model),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(theme.background)
            val frame = model.frame ?: return@Canvas
            drawTerminalFrame(
                frame = frame,
                runs = TerminalRuns.build(frame, theme),
                metrics = metrics,
                theme = theme,
                measurer = measurer,
                cache = layoutCache,
                scrollOffsetPx = 0f,
                cursorEnabled = active,
            )
        }
        overlay()
    }
}
