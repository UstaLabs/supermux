package dev.supermux.editor.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.supermux.editor.core.Transaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Tunables of the surface. */
internal object EditorDefaults {
    /** Lines laid out above and below the visible ones (and reported in the viewport). */
    const val OVERSCAN_LINES = 4

    /** The caret blink half-period. */
    const val BLINK_MILLIS = 530L
}

/** Tests turn the caret blink off, so a pixel check is not a coin toss. */
internal val LocalEditorCursorBlink = staticCompositionLocalOf { true }

/**
 * The editor surface: [view]'s state drawn on one Canvas (line backgrounds, selections, text,
 * cursors and the line-number gutter), laying out only the visible lines plus overscan.
 *
 * @param theme colours, the font and the token styles; the default follows the system dark mode.
 * @param lineWrap wrap long lines at the viewport's width (no horizontal scrolling then).
 * @param showLineNumbers the gutter.
 * @param readOnly no user edits (see [EditorView.readOnly]); the selection still moves.
 * @param onViewport the UTF-16 range the surface lays out, at most once per frame: a syntax host
 *   dispatches it as `Syntax.setViewport`.
 */
@Composable
fun Editor(
    view: EditorView,
    modifier: Modifier = Modifier,
    theme: EditorTheme = EditorTheme.default(),
    lineWrap: Boolean = false,
    showLineNumbers: Boolean = true,
    readOnly: Boolean = false,
    onViewport: (IntRange) -> Unit = {},
) {
    // cacheSize = 0: the surface keeps its own bounded caches (LineLayouts).
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val controller = remember(view, measurer) { EditorController(view, measurer) }
    SideEffect {
        view.readOnly = readOnly
        controller.configure(theme, density, lineWrap, showLineNumbers)
    }
    DisposableEffect(view, controller) {
        view.surface = controller
        view.geometry = controller.geometry
        onDispose {
            if (view.surface === controller) {
                view.surface = null
                view.geometry = null
            }
        }
    }
    val reportViewport by rememberUpdatedState(onViewport)
    LaunchedEffect(view) { view.viewport.collect { if (!it.isEmpty()) reportViewport(it) } }

    val blink = LocalEditorCursorBlink.current
    LaunchedEffect(view, controller, blink) {
        // Restarted by every selection change: the caret stays solid while the user types or moves.
        snapshotFlow { view.focused to view.state.selection }.collectLatest { (focused, _) ->
            controller.cursorOn = true
            if (!blink || !focused) return@collectLatest
            while (true) {
                delay(EditorDefaults.BLINK_MILLIS)
                controller.cursorOn = !controller.cursorOn
            }
        }
    }

    Box(modifier.clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) { controller.paint(this) }
    }
}

/**
 * Everything one composed surface keeps for its [view]: the layouts, the height map, the geometry
 * over them, the scroll position, the caret blink, and the hooks the view calls on each
 * transaction ([EditorSurfaceHooks]).
 */
@Stable
internal class EditorController(val view: EditorView, private val measurer: TextMeasurer) : EditorSurfaceHooks {
    val layouts = LineLayouts(measurer)
    private var heights = HeightMap(view.state.doc.lineCount, layouts.lineHeightPx)

    var geometry: Geometry = Geometry({ view.state }, heights, layouts)
        private set

    val scroll = EditorScroll(maxX = ::maxScrollX, maxY = ::maxScrollY)

    /** The caret's blink phase. */
    var cursorOn: Boolean by mutableStateOf(true)

    var theme: EditorTheme? = null
        private set
    private var density: Density = Density(1f)
    var lineWrap = false
        private set
    private var showLineNumbers = true

    /** The surface's size in pixels, from the last paint. */
    var viewportSize: Size = Size.Zero
        private set

    /** The gutter's width and where the text area starts, in pixels. */
    var gutterWidth = 0f
        private set
    var textLeft = 0f
        private set

    /** The lines the last paint laid out (a test hook). */
    var drawnLines: IntRange = IntRange.EMPTY
        internal set

    private val numberLayouts = HashMap<Int, TextLayoutResult>()
    private var numberStyle: TextStyle = TextStyle.Default

    override val viewportHeightPx: Float get() = viewportSize.height

    fun configure(theme: EditorTheme, density: Density, lineWrap: Boolean, showLineNumbers: Boolean) {
        val changed = theme != this.theme || density != this.density || lineWrap != this.lineWrap || showLineNumbers != this.showLineNumbers
        if (!changed) return
        this.theme = theme
        this.density = density
        this.lineWrap = lineWrap
        this.showLineNumbers = showLineNumbers
        numberLayouts.clear()
        numberStyle = TextStyle(fontFamily = theme.fontFamily, fontSize = theme.fontSizeSp.sp, fontFeatureSettings = "liga 0, calt 0")
        relayout()
    }

    /** Apply the current configuration and size to the layouts; a new geometry when it changed. */
    private fun relayout() {
        val theme = theme ?: return
        val charWidth = layouts.charWidthPx
        gutterWidth = if (showLineNumbers) (digits(view.state.doc.lineCount) + 2) * charWidth else 0f
        textLeft = gutterWidth + charWidth / 2
        val wrapWidth = if (lineWrap && viewportSize.width > 0f) (viewportSize.width - textLeft - charWidth / 2).toInt() else null
        val before = layouts.lineHeightPx to layouts.charWidthPx
        layouts.configure(theme, density, wrapWidth, view.state.facet(tabSizeFacet))
        if (before != (layouts.lineHeightPx to layouts.charWidthPx)) {
            // The cell changed: the gutter and the wrap width depend on it.
            gutterWidth = if (showLineNumbers) (digits(view.state.doc.lineCount) + 2) * layouts.charWidthPx else 0f
            textLeft = gutterWidth + layouts.charWidthPx / 2
            val w = if (lineWrap && viewportSize.width > 0f) (viewportSize.width - textLeft - layouts.charWidthPx / 2).toInt() else null
            layouts.configure(theme, density, w, view.state.facet(tabSizeFacet))
        }
        if (layouts.generation != heightsGeneration) {
            heightsGeneration = layouts.generation
            // Everything was measured under another configuration: back to estimates.
            heights = HeightMap(view.state.doc.lineCount, layouts.lineHeightPx)
            geometry = Geometry({ view.state }, heights, layouts)
            if (view.surface === this) view.geometry = geometry
        }
    }

    private var heightsGeneration = -1
    private var lastSize = Size.Zero
    private var lastDigits = 0

    /** Paint one frame. */
    fun paint(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
        val theme = theme ?: return
        val state = view.state
        val d = digits(state.doc.lineCount)
        if (scope.size != lastSize || d != lastDigits) {
            lastSize = scope.size
            lastDigits = d
            viewportSize = scope.size
            relayout()
        }
        scope.paintEditor(this, theme, state, focused = view.focused, cursorOn = cursorOn)
    }

    /** A cached layout of line number [n]. */
    fun numberLayout(n: Int): TextLayoutResult = numberLayouts.getOrPut(n) {
        if (numberLayouts.size > 4096) numberLayouts.clear()
        measurer.measure(n.toString(), numberStyle, softWrap = false, density = density)
    }

    /** The caret rect at [offset] in the surface's own pixels (a test hook, and the IME's anchor). */
    fun caretRectOnScreen(offset: Int): Rect = geometry.rectFor(offset).translate(textLeft - scroll.x, -scroll.y)

    private fun maxScrollY(): Float = heights.totalHeight - viewportSize.height
    private fun maxScrollX(): Float = if (lineWrap) 0f else layouts.maxLineWidth + layouts.charWidthPx * 2 - (viewportSize.width - textLeft)

    // ------------------------------------------------------------------ EditorSurfaceHooks --

    override fun onTransaction(tr: Transaction) {
        if (!tr.docChanged) return
        if (heights.lineCount == tr.startState.doc.lineCount) heights.applyChanges(tr.changes, tr.startState.doc, tr.state.doc)
        else heights.reset(tr.state.doc.lineCount)
    }

    override fun onStateReplaced() {
        heights.reset(view.state.doc.lineCount)
        scroll.scrollTo(0f, 0f)
    }

    override fun scrollIntoView() {}

    override fun scrollBy(dy: Float) {
        scroll.scrollBy(0f, dy)
    }

    private fun digits(lines: Int): Int = maxOf(2, lines.toString().length)
}
