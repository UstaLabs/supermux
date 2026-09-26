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
import kotlinx.coroutines.flow.drop
import androidx.compose.ui.layout.onGloballyPositioned

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
 * @param onFontSize the font size (sp) after every zoom (`Mod +`/`Mod −`/`Mod 0`, a pinch once the
 *   fingers lift), for the host to keep per app; give it back through [EditorView.fontSize].
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
    onFontSize: (Float) -> Unit = {},
) {
    // cacheSize = 0: the surface keeps its own bounded caches (LineLayouts).
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val controller = remember(view, measurer, scrollState) { EditorController(view, measurer, scrollState) }
    // The zoom is the view's (snapshot state): a change recomposes this with the theme at that size.
    val zoomed = view.fontSize
    val shownTheme = if (zoomed == null || zoomed == theme.fontSizeSp) theme else remember(theme, zoomed) { theme.copy(fontSizeSp = zoomed) }
    val reportFontSize by rememberUpdatedState(onFontSize)
    SideEffect {
        view.readOnly = readOnly
        view.baseFontSize = theme.fontSizeSp
        view.onFontSize = { reportFontSize(it) }
        controller.configure(shownTheme, density, lineWrap, showLineNumbers)
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

    // The selection menu hides while the view scrolls and comes back once it settles.
    LaunchedEffect(controller) {
        snapshotFlow { controller.scroll.x to controller.scroll.y }.drop(1).collectLatest {
            controller.scrolling = true
            delay(EditorMenu.SCROLL_SETTLE_MILLIS)
            controller.scrolling = false
        }
    }

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
            .onFocusChanged {
                view.focused = it.hasFocus
                if (!it.hasFocus) { controller.handles = TouchHandles.NONE; controller.menuShown = false }
            }
            .onPreviewKeyEvent { handleEditorKey(view, it, controller.composing) }
            // INSIDE the scrollables: this node sees the Main pass first and consumes what is a
            // selection (mouse presses and drags), leaving a finger's drag to scroll.
            .pointerInput(pointer) { pointer.handle(this) }
            .editorMagnifier { controller.magnifierAt }
            .onGloballyPositioned { controller.coordinates = it },
    ) {
        val paintHook by rememberUpdatedState(onPaint)
        Canvas(Modifier.fillMaxSize()) {
            controller.paint(this)
            paintHook?.invoke()
        }
        EditorInputField(controller, readOnly)
        EditorSelectionMenu(controller, readOnly, clipboard, shownTheme)
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

    /** The touch handles shown (only a touch gesture turns them on; see [TouchHandles]). */
    var handles: TouchHandles by mutableStateOf(TouchHandles.NONE)

    /**
     * The selection menu (Cut, Copy, Paste, Select All) was asked for: after a long press, a tap in
     * the selection or on the caret's handle, the end of a handle drag. It is hidden while
     * [menuHeld] (a handle is being dragged) or [scrolling], and reappears after.
     */
    var menuShown: Boolean by mutableStateOf(false)
    var menuHeld: Boolean by mutableStateOf(false)
    var scrolling: Boolean by mutableStateOf(false)

    /** The surface's layout coordinates (the platform toolbar wants root coordinates). */
    var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null

    /**
     * Where the menu points, in surface pixels: the main range's rows (their full width when it
     * spans rows) or the caret, limited to the viewport.
     */
    fun menuAnchor(): Rect {
        val main = view.state.selection.main
        val a = caretRectOnScreen(main.from)
        val b = if (main.empty) a else caretRectOnScreen(main.to)
        val sameRow = kotlin.math.abs(a.top - b.top) < 0.5f
        val left = if (sameRow) minOf(a.left, b.left) else textLeft
        val right = if (sameRow) maxOf(a.left, b.left) else viewportSize.width
        return Rect(left, a.top.coerceIn(0f, viewportSize.height), right, b.bottom.coerceIn(0f, viewportSize.height))
    }

    /** Where a finger dragging a selection end is, for the platform magnifier (Unspecified: none). */
    var magnifierAt: androidx.compose.ui.geometry.Offset by mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified)

    /** Pixels per dp, from the last configuration. */
    val densityValue: Float get() = density.density

    /**
     * The handles to draw and hit-test now, in surface pixels: the main range's two ends
     * ([TouchHandles.SELECTION]) or its caret ([TouchHandles.CURSOR]); a handle whose tip is outside
     * the viewport is left out.
     */
    fun handleSpots(): List<HandleSpot> {
        val mode = handles
        if (mode == TouchHandles.NONE || viewportSize.height <= 0f) return emptyList()
        val main = view.state.selection.main
        val wanted = when (mode) {
            TouchHandles.SELECTION -> if (main.empty) return emptyList() else listOf(HandleKind.START to main.from, HandleKind.END to main.to)
            else -> if (!main.empty) return emptyList() else listOf(HandleKind.CURSOR to main.head)
        }
        val out = ArrayList<HandleSpot>(2)
        for ((kind, at) in wanted) {
            val caret = caretRectOnScreen(at.coerceIn(0, view.state.doc.length))
            if (caret.bottom < 0f || caret.top > viewportSize.height || caret.left < gutterWidth - 1f || caret.left > viewportSize.width) continue
            out += EditorTouch.spot(kind, at, caret, density.density)
        }
        return out
    }

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
        // A new font size (a zoom): the line at the top stays at the top, the same fraction of a
        // line into it (anchored to the visible top line, not to a pixel offset).
        val zoom = this.theme != null && theme.fontSizeSp != this.theme?.fontSizeSp && viewportSize.height > 0f &&
            heights.lineCount == view.state.doc.lineCount && !scroll.shared
        val keep = if (zoom) scrollPosition() else null
        val oldLine = layouts.lineHeightPx
        val oldCell = layouts.charWidthPx
        this.theme = theme
        this.density = density
        this.lineWrap = lineWrap
        this.showLineNumbers = showLineNumbers
        numberLayouts.clear()
        numberStyle = TextStyle(fontFamily = theme.fontFamily, fontSize = theme.fontSizeSp.sp, fontFeatureSettings = "liga 0, calt 0")
        relayout()
        if (keep != null && oldLine > 0f) {
            val lineRatio = layouts.lineHeightPx / oldLine
            val cellRatio = if (oldCell > 0f) layouts.charWidthPx / oldCell else 1f
            restoreScroll(EditorScrollPosition(keep.anchor, keep.offsetPx * lineRatio, keep.x * cellRatio))
        }
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
        // Measured heights stay valid only for the line height and wrap mode they were measured
        // with. A new wrap WIDTH (the gutter grew a digit, the window was resized) keeps them as
        // estimates: they are re-measured as they come into view, without a jump to one-row guesses.
        val key = layouts.lineHeightPx to lineWrap
        if (key != heightsKey) {
            heightsKey = key
            heights = HeightMap(view.state.doc.lineCount, layouts.lineHeightPx)
            geometry = Geometry({ view.state }, heights, layouts)
            if (view.surface === this) view.geometry = geometry
        }
    }

    private var heightsKey: Pair<Float, Boolean>? = null
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
        followHandles(tr)
        if (!tr.docChanged) return
        if (heights.lineCount == tr.startState.doc.lineCount) heights.applyChanges(tr.changes, tr.startState.doc, tr.state.doc)
        else heights.reset(tr.state.doc.lineCount)
        geometry.onChanges(tr.changes)
        // The anchor follows its text: an edit above the viewport does not move what is shown.
        val doc = tr.state.doc
        anchorPos = doc.lineStart(doc.lineIndexAt(tr.changes.mapPos(anchorPos.coerceIn(0, tr.changes.lengthBefore), -1)))
    }

    /**
     * The touch handles hide on typing (the caret's), when the selection they hold collapses, and
     * when anything but a pointer gesture sets the selection (a hardware key, a command).
     */
    private fun followHandles(tr: Transaction) {
        val pointer = tr.isUserEvent("select.pointer")
        // The menu acts on what it was shown for: an edit or a selection made elsewhere hides it.
        if (menuShown && (tr.docChanged || (tr.selectionSet && !pointer))) menuShown = false
        if (handles == TouchHandles.NONE) return
        val main = tr.state.selection.main
        when {
            tr.selectionSet && !pointer -> handles = TouchHandles.NONE
            tr.docChanged && (handles == TouchHandles.CURSOR || main.empty) -> handles = TouchHandles.NONE
            handles == TouchHandles.SELECTION && main.empty -> handles = TouchHandles.NONE
        }
    }

    override fun onStateReplaced() {
        handles = TouchHandles.NONE
        menuShown = false
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
        // The position IS the anchor: a line measured before the next paint (the hidden field lays
        // out the caret's line while it is placed) moves the scroll with it instead of shifting
        // the text under a stale pixel offset.
        anchorPos = doc.lineStart(line)
        anchorDelta = position.offsetPx
        anchorScrollY = scroll.y
        anchorValid = true
    }

    override fun focus(showKeyboard: Boolean): Boolean {
        val took = requestFocus()
        if (showKeyboard) keyboard?.show()
        return took
    }

    override fun coordsAtPos(offset: Int): Rect = caretRectOnScreen(offset.coerceIn(0, view.state.doc.length))

    private fun digits(lines: Int): Int = maxOf(2, lines.toString().length)
}
