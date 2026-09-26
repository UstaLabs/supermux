package dev.supermux.editor.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.platform.LocalLayoutDirection
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
 * @param scrollState the scroll position; the view's own by default, or one shared by several editors.
 * @param clipboard where copy/cut put text and paste takes it from (the platform's by default).
 * @param onPaint called at the end of every paint of the surface (frame-time and edit-to-paint
 *   measurements). It runs inside the draw pass: keep it to taking a timestamp.
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
    onPaint: (() -> Unit)? = null,
    clipboard: EditorClipboard = rememberEditorClipboard(),
    scrollState: EditorScrollState = view.defaultScrollState,
) {
    // cacheSize = 0: the surface keeps its own bounded caches (LineLayouts).
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val controller = remember(view, measurer, scrollState) { EditorController(view, measurer, scrollState) }
    SideEffect {
        view.readOnly = readOnly
        controller.configure(theme, density, lineWrap, showLineNumbers)
    }
    DisposableEffect(view, controller) {
        view.surface = controller
        view.geometry = controller.geometry
        view.scrollState = scrollState
        scrollState.surfaces += controller
        val removeFastTyping = installFastTyping(view, controller)
        onDispose {
            scrollState.surfaces -= controller
            removeFastTyping?.invoke()
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

    val layoutDirection = LocalLayoutDirection.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val pointer = remember(controller, scope) { EditorPointer(controller, scope) }
    SideEffect {
        controller.keyboard = keyboard
        view.clipboard = clipboard
        view.scope = scope
    }
    Box(
        modifier
            .clipToBounds()
            // `scrollable`, not a hand-rolled drag detector: it normalizes wheel notches and
            // trackpad deltas into pixels, tracks release velocity and runs the fling's decay.
            .scrollable(
                state = controller.scroll.vertical,
                orientation = Orientation.Vertical,
                reverseDirection = ScrollableDefaults.reverseDirection(layoutDirection, Orientation.Vertical, false),
                flingBehavior = ScrollableDefaults.flingBehavior(),
            )
            .scrollable(
                state = controller.scroll.horizontal,
                orientation = Orientation.Horizontal,
                enabled = !lineWrap,
                reverseDirection = ScrollableDefaults.reverseDirection(layoutDirection, Orientation.Horizontal, false),
                flingBehavior = ScrollableDefaults.flingBehavior(),
            )
            // The focus TARGET is the hidden field inside (an IME only runs for a focused text
            // field); this box is its ancestor, so `hasFocus` is the surface's focus and a key
            // preview reaches the keymap before the field could insert anything.
            .onFocusChanged { view.focused = it.hasFocus }
            .onPreviewKeyEvent { handleEditorKey(view, it, controller.composing) }
            // INSIDE the scrollables: this node sees the Main pass first and consumes what is a
            // selection (mouse presses and drags), leaving a finger's drag to scroll.
            .pointerInput(pointer) { pointer.handle(this) },
    ) {
        val paintHook by rememberUpdatedState(onPaint)
        Canvas(Modifier.fillMaxSize()) {
            controller.paint(this)
            paintHook?.invoke()
        }
        EditorInputField(controller, readOnly)
    }
}

/**
 * Everything one composed surface keeps for its [view]: the layouts, the height map, the geometry
 * over them, the scroll position, the caret blink, and the hooks the view calls on each
 * transaction ([EditorSurfaceHooks]).
 */
@Stable
internal class EditorController(val view: EditorView, private val measurer: TextMeasurer, val scroll: EditorScrollState) : EditorSurfaceHooks {
    val layouts = LineLayouts(measurer)
    private var heights = HeightMap(view.state.doc.lineCount, layouts.lineHeightPx)

    var geometry: Geometry = Geometry({ view.state }, heights, layouts)
        private set


    /** The caret's blink phase. */
    var cursorOn: Boolean by mutableStateOf(true)

    /** The surface's focus target (the hidden field). */
    val focusRequester = FocusRequester()

    /** The hidden field and the document, in step (see [FieldSync]). */
    val fieldSync = FieldSync(view)

    /** Writes into the hidden field while it is composed. */
    var fieldWriter: ((FieldText) -> Unit)? = null

    /** True while the IME composes: every key is the IME's then. */
    var composing = false

    /** The document range the IME is composing, underlined by the painter. */
    var composition: IntRange? by mutableStateOf(null)

    /** The platform's soft keyboard, or null where there is none (a desktop). */
    var keyboard: SoftwareKeyboardController? = null

    /** Take the keyboard focus (a mouse click): never raises a soft keyboard. */
    fun requestFocus(): Boolean = runCatching { focusRequester.requestFocus() }.isSuccess

    /**
     * A finger touched the text: focus AND show the soft keyboard, explicitly and every time. A
     * field that is already focused starts no new input session, so a keyboard the user dismissed
     * would otherwise never come back (terminal-compose's lesson).
     */
    fun focusFromTouch() {
        requestFocus()
        keyboard?.show()
    }

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

    internal fun maxScrollY(): Float = heights.totalHeight - viewportSize.height
    internal fun maxScrollX(): Float = if (lineWrap) 0f else layouts.maxLineWidth + layouts.charWidthPx * 2 - (viewportSize.width - textLeft)

    // ------------------------------------------------------------------ EditorSurfaceHooks --

    override fun onTransaction(tr: Transaction) {
        if (!tr.docChanged) return
        if (heights.lineCount == tr.startState.doc.lineCount) heights.applyChanges(tr.changes, tr.startState.doc, tr.state.doc)
        else heights.reset(tr.state.doc.lineCount)
        // The anchor follows its text: an edit above the viewport does not move what is shown.
        val doc = tr.state.doc
        anchorPos = doc.lineStart(doc.lineIndexAt(tr.changes.mapPos(anchorPos.coerceIn(0, tr.changes.lengthBefore), -1)))
    }

    override fun onStateReplaced() {
        heights.reset(view.state.doc.lineCount)
        anchorValid = false
        scroll.scrollTo(0f, 0f)
        fieldSync.rewindow()?.let { fieldWriter?.invoke(it) }
        composition = null
    }

    /** The least scrolling that shows the main cursor with a margin (a line, four cells). */
    override fun scrollIntoView() {
        if (viewportSize.height <= 0f) return
        val r = geometry.rectFor(view.state.selection.main.head)
        val lh = layouts.lineHeightPx
        val my = minOf(lh, viewportSize.height / 4)
        var y = scroll.y
        if (r.top - my < y) y = r.top - my
        else if (r.bottom + my > y + viewportSize.height) y = r.bottom + my - viewportSize.height
        var x = scroll.x
        if (!lineWrap) {
            val area = viewportSize.width - textLeft
            val mx = minOf(4 * layouts.charWidthPx, area / 4)
            if (r.left - mx < x) x = r.left - mx
            else if (r.left + mx > x + area) x = r.left + mx - area
        }
        scroll.scrollTo(x, y)
    }

    // ------------------------------------------------------------------ scroll anchoring --
    //
    // The first visible line is the anchor: when heights change above it (a wrapped line measured
    // for the first time, an edit above the viewport), the scroll position follows it so the text on
    // screen stays put. Only while nothing else moved the scroll since the last paint: a gesture or
    // scrollIntoView wins.

    private var anchorPos = 0
    private var anchorDelta = 0f
    private var anchorScrollY = 0f
    private var anchorValid = false

    /**
     * The start of a paint: when the scroll moved since the last one (a gesture, scroll-into-view),
     * the anchor is taken again at the new position against the CURRENT estimates, before anything
     * is measured; otherwise the last one is restored (heights or the document changed under it).
     * Either way, measuring the lines that come into view then moves nothing on screen.
     */
    fun beginAnchor() {
        view.pendingScroll?.let { restoreScroll(it) }
        if (!anchorValid || scroll.y != anchorScrollY) recordAnchor() else restoreAnchor()
    }

    fun restoreAnchor() {
        if (!anchorValid || scroll.y != anchorScrollY || scroll.shared) return
        val doc = view.state.doc
        if (heights.lineCount != doc.lineCount) return
        val line = doc.lineIndexAt(anchorPos.coerceIn(0, doc.length))
        val want = (heights.topD(line) + anchorDelta).toFloat()
        if (kotlin.math.abs(want - scroll.y) > 0.01f) scroll.scrollTo(y = want)
        anchorScrollY = scroll.y
    }

    fun recordAnchor() {
        val doc = view.state.doc
        if (heights.lineCount != doc.lineCount) return
        val line = heights.lineAt(scroll.y)
        anchorPos = doc.lineStart(line)
        anchorDelta = scroll.y - heights.top(line)
        anchorScrollY = scroll.y
        anchorValid = true
    }

    override fun scrollBy(dy: Float) {
        scroll.scrollBy(0f, dy)
    }

    override fun scrollPosition(): EditorScrollPosition {
        val doc = view.state.doc
        if (heights.lineCount != doc.lineCount) return EditorScrollPosition(0)
        val line = heights.lineAt(scroll.y)
        return EditorScrollPosition(doc.lineStart(line), scroll.y - heights.top(line), scroll.x)
    }

    override fun restoreScroll(position: EditorScrollPosition) {
        val doc = view.state.doc
        if (viewportSize.height <= 0f || heights.lineCount != doc.lineCount) { view.pendingScroll = position; return }
        view.pendingScroll = null
        val line = doc.lineIndexAt(position.anchor.coerceIn(0, doc.length))
        scroll.scrollTo(position.x, (heights.topD(line) + position.offsetPx).toFloat())
        anchorValid = false
    }

    override fun focus(showKeyboard: Boolean): Boolean {
        val took = requestFocus()
        if (showKeyboard) keyboard?.show()
        return took
    }

    override fun coordsAtPos(offset: Int): Rect = caretRectOnScreen(offset.coerceIn(0, view.state.doc.length))

    private fun digits(lines: Int): Int = maxOf(2, lines.toString().length)
}
