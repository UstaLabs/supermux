package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
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
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.gutterMarkersFacet
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

    /** The pointer shield over the hidden field: its 48 dp touch target, with a margin. */
    const val SHIELD_DP = 64

    /** Block widgets out of view stay composed while within this many screens of it... */
    const val RETAIN_SCREENS = 2

    /** ...and at most this many of them. */
    const val RETAIN_WIDGETS = 8
}

/** Whether an input session starts on any focus ([EditorController.inputOnAnyFocus]); tests switch it. */
internal val LocalEditorInputOnAnyFocus = staticCompositionLocalOf { platformInputOnAnyFocus }

/**
 * Whether the hidden field keeps a semantics node (marked hideFromAccessibility, which Android's
 * and the desktop's bridges honour, and which lets UI tests drive the real field through
 * `hasSetTextAction()`), or has its semantics cleared ([platformClearsFieldSemantics]: iOS and the
 * web ignore hideFromAccessibility and showed a second, unlabelled text element).
 */
internal val LocalEditorExposeField = staticCompositionLocalOf { !platformClearsFieldSemantics }

/** Whether the surface's own text node is the screen reader's ([platformSurfaceText]). */
internal val LocalEditorSurfaceText = staticCompositionLocalOf { platformSurfaceText }

/** Tests: a child of the surface under the hidden field (where M3c's widgets will be). */
internal val LocalEditorTestChild = staticCompositionLocalOf<(@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)?> { null }

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
 * @param label what a screen reader calls this editor (its content description).
 * @param onFontSize the font size (sp) after every zoom (`Mod +`/`Mod −`/`Mod 0`, a pinch once the
 *   fingers lift), for the host to keep per app; give it back through [EditorView.fontSize].
 * @param widgets the composable content of block widgets (and panels), by widget type.
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
    label: String = EditorSemantics.LABEL,
    widgets: WidgetRegistry = remember { WidgetRegistry() },
) {
    // cacheSize = 0: the surface keeps its own bounded caches (LineLayouts).
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    // The surface's focus and keyboard request outlive a view (a host showing another document):
    // the new view learns the focus, and the keyboard request carries over as it was (a
    // programmatic or mouse focus keeps asking for none; a touch's keeps its session).
    val surfaceInput = remember { SurfaceInput() }
    val controller = remember(view, measurer, scrollState) { EditorController(view, measurer, scrollState, surfaceInput) }
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
    // The surface's Compose focus, kept across views: a host showing another document gives this
    // Editor a new view while the hidden field keeps the focus, so no focus event ever tells the
    // new view it is focused (the caret was not painted and did not blink, typing still worked).
    DisposableEffect(view, controller) {
        view.focused = surfaceInput.focused
        onDispose { view.focused = false }
    }
    DisposableEffect(view, controller) {
        view.surface = controller
        view.geometry = controller.geometry
        view.scrollState = scrollState
        scrollState.surfaces += controller
        val removeFastTyping = installFastTyping(view, controller)
        val removeAnnouncer = view.addListener { controller.announcer.follow(it) }
        onDispose {
            removeAnnouncer()
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
    val inputOnAnyFocus = LocalEditorInputOnAnyFocus.current
    SideEffect {
        if (controller.inputOnAnyFocus != inputOnAnyFocus) {
            controller.inputOnAnyFocus = inputOnAnyFocus
            if (!controller.view.focused) controller.onBlur()
        }
        platformFieldLabel(controller, label)
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
            // INSIDE the scrollables: this node sees the Main pass first and consumes what is a
            // selection (mouse presses and drags), leaving a finger's drag to scroll.
            .pointerInput(pointer) { pointer.handle(this) }
            .editorMagnifier { controller.magnifierAt }
            .onGloballyPositioned { controller.coordinates = it },
    ) {
        val paintHook = rememberUpdatedState(onPaint)
        val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        SideEffect {
            controller.registry = widgets
            controller.saveableHolder = holder
        }
        val surfaceText = LocalEditorSurfaceText.current
        val testChild = LocalEditorTestChild.current
        // The surface's children, each subcomposed by the layout pass below (M3c's widgets join them).
        val slots = remember(controller, label, readOnly, surfaceText, testChild, clipboard, shownTheme) {
            SurfaceSlots(
                // The text node is its own layout node, a child of the scroll node: sharing one node
                // with `scrollable` made macOS map it to an AXScrollArea with no text.
                canvas = {
                    Spacer(Modifier.fillMaxSize().editorCanvas(controller, paintHook)
                        .then(if (surfaceText) Modifier.editorSemantics(controller, label, readOnly) else Modifier))
                },
                testChild = testChild?.let { child -> { Box(Modifier.fillMaxSize()) { child() } } },
                // The focus TARGET is the hidden field (an IME only runs for a focused text field); its
                // wrapper's `hasFocus` is the editor's focus and its key preview reaches the keymap
                // before the field could insert anything. The field's own, not the surface's: a
                // widget's text field (a review comment) is inside the surface too, and its focus and
                // keys are its own.
                field = {
                    EditorInputField(
                        controller, readOnly,
                        Modifier
                            .onFocusChanged {
                                surfaceInput.focused = it.hasFocus
                                controller.view.focused = it.hasFocus
                                platformFocusChanged(controller, it.hasFocus)
                                if (!it.hasFocus) { controller.handles = TouchHandles.NONE; controller.menuShown = false; controller.onBlur() }
                            }
                            .onPreviewKeyEvent { handleEditorKey(controller.view, it, controller.composing) },
                    )
                },
                // A pointer shield exactly over the hidden field's touch target: the topmost hit sibling
                // takes a pointer, so the field (at the caret, its target expanded to 48 dp) never gets
                // one. Its own touch selection crashed on iOS (a long press on an empty line: Compose's
                // moveCaretByLongPress with offset -1) and moved its selection behind the editor's back
                // everywhere. Only that square: other children of the surface (widgets) still get
                // pointers. It consumes nothing: the surface's own gestures (this Box's pointerInput)
                // see every event. (Android stylus handwriting INTO the field is blocked with it; the
                // editor has none of its own yet.)
                shield = {
                    Box(
                        Modifier.size(EditorDefaults.SHIELD_DP.dp)
                            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial) } },
                    )
                },
                overlay = {
                    EditorSelectionMenu(controller, readOnly, clipboard, shownTheme)
                    LineAnnouncement(controller.announcer)
                },
            )
        }
        // Measure before draw: the layout pass positions the scroll, lays out the visible lines and
        // places the children; the canvas then only paints what it decided.
        // The registry is the controller's before the first layout pass (a SideEffect runs after it).
        controller.registry = widgets
        controller.saveableHolder = holder
        val policy = remember(controller, slots, shownTheme, density, lineWrap, showLineNumbers) {
            surfaceMeasurePolicy(controller, slots) { controller.configure(shownTheme, density, lineWrap, showLineNumbers) }
        }
        SubcomposeLayout(Modifier.fillMaxSize(), policy)
    }
}

/** The surface's fixed children (see [surfaceMeasurePolicy]). */
internal class SurfaceSlots(
    val canvas: @Composable () -> Unit,
    val testChild: (@Composable () -> Unit)?,
    val field: @Composable () -> Unit,
    val shield: @Composable () -> Unit,
    val overlay: @Composable () -> Unit,
)

private enum class Slot { CANVAS, TEST, FIELD, SHIELD, OVERLAY }

/** A gutter marker's accessibility node's slot. */
internal data class MarkerSlot(val column: String, val line: Int, val marker: GutterMarker)

/**
 * The surface's layout pass: [configure] (cheap when nothing changed), then
 * [EditorController.layoutFrame] (the scroll, the visible lines, the frame), then the children:
 * the canvas over the whole surface, the hidden field and its shield at the main caret, the menu.
 */
private fun surfaceMeasurePolicy(
    c: EditorController,
    slots: SurfaceSlots,
    configure: () -> Unit,
): SubcomposeMeasureScope.(Constraints) -> MeasureResult = { constraints ->
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
    val h = if (constraints.hasBoundedHeight) constraints.maxHeight else constraints.minHeight
    // Block widgets are subcomposed and measured INSIDE the layout pass (their heights move lines).
    val widgetPlaceables = HashMap<dev.supermux.editor.core.WidgetKey, List<Placeable>>()
    val measureWidget: WidgetMeasurer = { key, width ->
        val ps = subcompose(WidgetSlot(key), c.widgetContent(key)).map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
        widgetPlaceables[key] = ps
        if (ps.isEmpty()) null else ps.maxOf { it.height }
    }
    val frame = c.layoutFrame(w.toFloat(), h.toFloat(), configure, measureWidget)
    val placed = frame?.widgets.orEmpty().filter { it.composed && widgetPlaceables.containsKey(it.key) }
    // Out of view but near: still composed (not measured, not placed), so their state lives.
    for (k in c.retainedWidgets(placed.map { it.key })) subcompose(WidgetSlot(k), c.widgetContent(k))
    val full = Constraints.fixed(w, h)
    val canvas = subcompose(Slot.CANVAS, slots.canvas).map { it.measure(full) }
    val test = slots.testChild?.let { t -> subcompose(Slot.TEST, t).map { it.measure(full) } }.orEmpty()
    val field = subcompose(Slot.FIELD, slots.field).map { it.measure(Constraints()) }
    val shield = subcompose(Slot.SHIELD, slots.shield).map { it.measure(Constraints()) }
    val overlay = subcompose(Slot.OVERLAY, slots.overlay).map { it.measure(Constraints(maxWidth = w, maxHeight = h)) }
    // A node per visible marker with a tooltip, for screen readers (it takes no pointer).
    val markers = frame?.markers.orEmpty().mapNotNull { m ->
        val key = MarkerSlot(m.column, m.line, m.marker)
        if (m.marker.tooltip == null) return@mapNotNull null
        val size = Constraints.fixed(m.rect.width.toInt().coerceAtLeast(1), m.rect.height.toInt().coerceAtLeast(1))
        m to subcompose(key, c.markerNode(key)).map { it.measure(size) }
    }
    c.pruneMarkerNodes(markers.mapTo(HashSet()) { MarkerSlot(it.first.column, it.first.line, it.first.marker) })
    val caret = frame?.caret ?: Rect.Zero
    layout(w, h) {
        canvas.forEach { it.place(0, 0) }
        for ((m, p) in markers) p.forEach { it.place(m.rect.left.toInt(), m.rect.top.toInt()) }
        test.forEach { it.place(0, 0) }
        // The field sits at the caret, where the platform anchors the keyboard's candidates.
        field.forEach { it.place(caret.left.toInt(), caret.top.toInt()) }
        shield.forEach { it.place(caret.left.toInt() - it.width / 2, caret.top.toInt() - it.height / 2) }
        // Widgets over the shield: a widget's text field right under the caret still gets its taps.
        for (pw in placed) widgetPlaceables[pw.key]?.forEach { it.place(pw.rect.left.toInt(), kotlin.math.round(pw.rect.top).toInt()) }
        overlay.forEach { it.place(0, 0) }
    }
}

/** A block widget's slot. */
private data class WidgetSlot(val key: dev.supermux.editor.core.WidgetKey)

/** The canvas: paints the controller's last frame ([EditorController.draw]), then tells the host. */
internal fun Modifier.editorCanvas(c: EditorController, onPaint: androidx.compose.runtime.State<(() -> Unit)?>): Modifier =
    this then EditorCanvasElement(c, onPaint)

private data class EditorCanvasElement(val c: EditorController, val onPaint: androidx.compose.runtime.State<(() -> Unit)?>) :
    ModifierNodeElement<EditorCanvasNode>() {
    override fun create() = EditorCanvasNode(c, onPaint)
    override fun update(node: EditorCanvasNode) = node.bind(c, onPaint)
}

internal class EditorCanvasNode(private var c: EditorController, private var onPaint: androidx.compose.runtime.State<(() -> Unit)?>) :
    Modifier.Node(), DrawModifierNode {
    override fun onAttach() { c.canvasNode = this }
    override fun onDetach() { if (c.canvasNode === this) c.canvasNode = null }

    fun bind(c: EditorController, onPaint: androidx.compose.runtime.State<(() -> Unit)?>) {
        if (c !== this.c) { if (this.c.canvasNode === this) this.c.canvasNode = null; this.c = c; c.canvasNode = this }
        this.onPaint = onPaint
        invalidate()
    }

    /** A new frame to paint (the layout pass built one). */
    fun invalidate() { if (isAttached) invalidateDraw() }

    override fun ContentDrawScope.draw() {
        c.draw(this)
        onPaint.value?.invoke()
    }
}

/**
 * Everything one composed surface keeps for its [view]: the layouts, the height map, the geometry
 * over them, the scroll position, the caret blink, and the hooks the view calls on each
 * transaction ([EditorSurfaceHooks]).
 */
@Stable
internal class EditorController(
    val view: EditorView,
    private val measurer: TextMeasurer,
    val scroll: EditorScrollState,
    private val surfaceInput: SurfaceInput = SurfaceInput(),
) : EditorSurfaceHooks {
    val layouts = LineLayouts(measurer)
    private var heights = HeightMap(view.state.doc.lineCount, layouts.lineHeightPx)

    var geometry: Geometry = Geometry({ view.state }, heights, layouts)
        private set


    /** iOS's space-bar trackpad, forwarded by the platform (see [FloatingCursor]). */
    val floatingCursor = FloatingCursor(this)

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

    /** Says the caret's new line to a screen reader. */
    val announcer = LineAnnouncer()

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
        requestKeyboard()
        // Not focused yet where only a touch starts a session: the focus is taken one frame later
        // (EditorInputField), once the field carries showKeyboardOnFocus = true, so the session
        // starts inside the focus change itself; started later from a recomposition, iOS never
        // made its input view first responder (no keyboard on the first tap).
        if (inputOnAnyFocus || view.focused) requestFocus() else focusAfterRequest = true
        keyboard?.show()
    }

    /** The platform's own per-editor input state (the web: this editor's DOM TEXTAREA binding). */
    var platformInput: Any? = null

    /** A touch's focus waits for the field's new options (see [focusFromTouch]). */
    var focusAfterRequest = false

    /**
     * Where an input session starts on ANY focus (desktop, web): true. The desktop has no soft
     * keyboard and needs the session for its IME; the web needs it for its DOM text input (the
     * TEXTAREA exists only while a session runs: without it there is no IME, no browser paste and
     * no hardware-key fast path). On Android and iOS a session start raises the soft keyboard,
     * so there only a touch (or `focus(showKeyboard = true)`) asks for one. Set from
     * [LocalEditorInputOnAnyFocus].
     */
    var inputOnAnyFocus: Boolean = platformInputOnAnyFocus

    /**
     * The hidden field's `KeyboardOptions.showKeyboardOnFocus`. Compose's text field (ui 1.12)
     * starts its input session on focus only when this is true, and starting the session is what
     * raises the soft keyboard: `SoftwareKeyboardController.show()` at the tap does nothing for a
     * `BasicTextField(TextFieldState)` (no session yet). A change while focused starts the session.
     * False after a blur, so a later mouse click or programmatic focus starts none and raises nothing.
     */
    var keyboardOnFocus: Boolean
        get() = surfaceInput.keyboardOnFocus
        private set(v) { surfaceInput.keyboardOnFocus = v }

    /**
     * Counts the keyboard requests (every touch): once the session exists, each one asks the
     * platform again (`rememberPlatformKeyboardShow`), so a keyboard the user dismissed comes back.
     */
    var keyboardRequests: Int by mutableStateOf(0)
        private set

    /** Ask for a soft keyboard now (a touch): see [keyboardOnFocus]. */
    fun requestKeyboard() {
        keyboardOnFocus = true
        keyboardRequests++
    }

    /** The surface lost the focus: the next focus raises a keyboard only if a touch asks. */
    fun onBlur() {
        keyboardOnFocus = inputOnAnyFocus
    }

    var theme: EditorTheme? = null
        private set
    private var density: Density = Density(1f)
    var lineWrap = false
        private set
    private var showLineNumbers = true

    /** The surface's size in pixels, from the last layout pass. */
    var viewportSize: Size = Size.Zero
        private set

    /** The gutter's width and where the text area starts, in pixels. */
    var gutterWidth = 0f
        private set
    var textLeft = 0f
        private set

    /** The lines the last layout pass laid out (a test hook). */
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
        gutterWidth = gutterFor(theme, charWidth)
        textLeft = gutterWidth + charWidth / 2
        val wrapWidth = if (lineWrap && viewportSize.width > 0f) (viewportSize.width - textLeft - charWidth / 2).toInt() else null
        val before = layouts.lineHeightPx to layouts.charWidthPx
        layouts.configure(theme, density, wrapWidth, view.state.facet(tabSizeFacet))
        if (before != (layouts.lineHeightPx to layouts.charWidthPx)) {
            // The cell changed: the gutter and the wrap width depend on it.
            gutterWidth = gutterFor(theme, layouts.charWidthPx)
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
            blocks.invalidate()
            geometry = Geometry({ view.state }, heights, layouts)
            if (view.surface === this) view.geometry = geometry
        }
    }

    // ------------------------------------------------------------------ block widgets --

    /** The state's block widgets and their heights in the height map. */
    val blocks = BlockWidgets()

    /** Where widget content comes from (`Editor(widgets = …)`). */
    var registry: WidgetRegistry? = null

    /** Keeps a disposed widget's saveable state (a draft) until it is composed again. */
    var saveableHolder: androidx.compose.runtime.saveable.SaveableStateHolder? = null

    /** The height map in step with the state's block widgets; true when a height changed. */
    fun syncBlocks(state: EditorState): Boolean = blocks.sync(state, heights, layouts.lineHeightPx, registry)

    /**
     * Measure the registered widgets on [lines] not measured yet this frame ([measure] subcomposes
     * them); true when a height changed (then the height map needs [syncBlocks]).
     */
    fun measureWidgets(lines: IntRange, measure: WidgetMeasurer?): Boolean {
        if (measure == null) return false
        val reg = registry ?: return false
        val width = (viewportSize.width - gutterWidth).toInt().coerceAtLeast(1)
        var changed = false
        for (e in blocks.inLines(lines)) {
            if (e.key.type !in reg || blocks.measuredThisFrame(e.key)) continue
            val h = measure(e.key, width) ?: continue
            if (blocks.setMeasured(e.key, h.toFloat())) changed = true
        }
        return changed
    }

    /** The widget scope every widget's content gets. */
    val widgetScope: WidgetScope = object : WidgetScope {
        override val view: EditorView get() = this@EditorController.view
        override val theme: EditorTheme get() = this@EditorController.theme ?: error("the editor is not configured")
        override val lineHeight: androidx.compose.ui.unit.Dp get() = with(density) { layouts.lineHeightPx.toDp() }
        override fun focusEditor() { requestFocus() }
    }

    private val widgetContents = HashMap<dev.supermux.editor.core.WidgetKey, @Composable () -> Unit>()

    /** A widget's content for its slot, kept per key (so a scroll recomposes nothing). */
    fun widgetContent(key: dev.supermux.editor.core.WidgetKey): @Composable () -> Unit = widgetContents.getOrPut(key) {
        {
            val content = registry?.content(key.type)
            val holder = saveableHolder
            if (content != null && holder != null) {
                holder.SaveableStateProvider(key.saveKey()) {
                    Box(Modifier.fillMaxWidth()) { widgetScope.content(key) }
                }
            }
        }
    }

    /** The widgets composed last frame, most recently shown last (the retained cache's order). */
    private val composedWidgets = LinkedHashSet<dev.supermux.editor.core.WidgetKey>()

    /**
     * The widgets kept composed but not placed: shown recently, now out of view but within
     * [EditorDefaults.RETAIN_SCREENS] screens of it, at most [EditorDefaults.RETAIN_WIDGETS]. So
     * a scroll out and back keeps even their `remember` state; a widget further away is disposed
     * (its `rememberSaveable` state, a draft, is kept by the saveable-state holder).
     */
    fun retainedWidgets(shown: List<dev.supermux.editor.core.WidgetKey>): List<dev.supermux.editor.core.WidgetKey> {
        val shownSet = shown.toHashSet()
        val h = viewportSize.height
        // The frame's position, never the scroll state's (a read here, after the pass, is observed).
        val y = frame?.scrollY ?: return emptyList()
        val near = composedWidgets.filter { k ->
            if (k in shownSet) return@filter false
            val e = blocks.entry(k) ?: return@filter false
            if (registry?.contains(k.type) != true || e.line >= heights.lineCount) return@filter false
            val top = heights.top(e.line)
            top + heights.height(e.line) >= y - EditorDefaults.RETAIN_SCREENS * h && top <= y + (1 + EditorDefaults.RETAIN_SCREENS) * h
        }.takeLast(EditorDefaults.RETAIN_WIDGETS)
        composedWidgets.clear()
        composedWidgets += near
        composedWidgets += shown
        widgetContents.keys.retainAll(composedWidgets)
        return near
    }

    /** True when [p] (surface pixels) is on a block widget's content: that pointer is the widget's. */
    fun widgetAt(p: androidx.compose.ui.geometry.Offset): Boolean = frame?.widgets?.any { it.composed && it.rect.contains(p) } == true

    // ------------------------------------------------------------------ the gutter --

    /** The gutter's marker columns, left to right after the line numbers (a test hook too). */
    var gutterColumns: List<GutterColumn> = emptyList()
        private set

    /** Every marker column seen, with the precedence index of the set it first came from. */
    private val knownColumns = LinkedHashMap<String, Int>()
    private var columnOrder: List<String> = emptyList()
    private var markerSets: List<dev.supermux.editor.core.RangeSet<GutterMarker>> = emptyList()

    /** The gutter's width: the line numbers, then each marker column at its theme width. */
    private fun gutterFor(theme: EditorTheme, cw: Float): Float {
        var x = if (showLineNumbers) (digits(view.state.doc.lineCount) + 2) * cw else 0f
        numbersRight = x
        val cols = ArrayList<GutterColumn>(columnOrder.size)
        for (id in columnOrder) {
            val w = theme.gutterColumns[id]?.let { it.value * density.density } ?: cw
            cols += GutterColumn(id, x, w)
            x += w
        }
        gutterColumns = cols
        return x
    }

    /** Where the line numbers' column ends (they are right-aligned a cell before it). */
    var numbersRight = 0f
        private set

    /**
     * Follow the markers' columns: one per distinct id, in precedence order (the index of the set it
     * first came from), kept once seen so a lint dot coming and going never shifts the text.
     * True when a column was added (the gutter is wider).
     */
    private fun followMarkerColumns(state: EditorState): Boolean {
        val sets = state.facet(gutterMarkersFacet)
        if (sets === markerSets) return false
        val last = markerSets
        markerSets = sets
        var added = false
        sets.forEachIndexed { i, set ->
            if (i < last.size && last[i] === set) return@forEachIndexed
            for (r in set) if (!knownColumns.containsKey(r.value.column)) { knownColumns[r.value.column] = i; added = true }
        }
        if (!added) return false
        columnOrder = knownColumns.entries.withIndex().sortedWith(compareBy({ it.value.value }, { it.index })).map { it.value.key }
        return true
    }

    /** What is under [p] (surface pixels) when it is a marker column's cell on a line's text row. */
    fun gutterHit(p: androidx.compose.ui.geometry.Offset): GutterHit? {
        val col = gutterColumns.firstOrNull { p.x >= it.x && p.x < it.x + it.width } ?: return null
        val y = p.y + scroll.y
        if (y < 0f || y >= heights.totalHeight) return null
        val line = heights.lineAt(y)
        val top = geometry.lineTop(line)
        if (y < top || y >= top + geometry.textHeight(line)) return null
        return GutterHit(col.id, line, markerAt(view.state, col.id, line))
    }

    /** The highest-precedence marker of [column] on [line]. */
    fun markerAt(state: EditorState, column: String, line: Int): GutterMarker? {
        val doc = state.doc
        val from = doc.lineStart(line)
        val to = if (line + 1 < doc.lineCount) doc.lineStart(line + 1) - 1 else doc.length
        for (set in state.facet(gutterMarkersFacet)) for (r in set.between(from, to)) if (r.from >= from && r.value.column == column) return r.value
        return null
    }

    private val markerNodes = HashMap<MarkerSlot, @Composable () -> Unit>()

    /** A marker's accessibility node: its tooltip as its label, a click that reports it. Kept per slot, so scrolling recomposes nothing. */
    fun markerNode(slot: MarkerSlot): @Composable () -> Unit = markerNodes.getOrPut(slot) {
        {
            Box(Modifier.fillMaxSize().semantics {
                contentDescription = slot.marker.tooltip.orEmpty()
                onClick(slot.marker.tooltip) { reportGutterClick(GutterHit(slot.column, slot.line, slot.marker)); true }
            })
        }
    }

    fun pruneMarkerNodes(keep: Set<MarkerSlot>) {
        if (markerNodes.size > keep.size) markerNodes.keys.retainAll(keep)
    }

    /** A click or tap on a marker column: the plugins' handlers ([gutterClickFacet]) first, then the host's. */
    fun reportGutterClick(hit: GutterHit) {
        val view = view
        for (h in view.state.facet(gutterClickFacet)) if (h.click(view, hit.column, hit.line, hit.marker)) return
        view.onGutterClick?.invoke(hit.column, hit.line, hit.marker)
    }

    private var heightsKey: Pair<Float, Boolean>? = null
    private var lastSize = Size.Zero
    private var lastDigits = 0

    /** The frame the last layout pass built: all the draw pass paints. */
    internal var frame: SurfaceFrame? = null
        private set

    /** The canvas painting [frame], told when there is a new one. */
    internal var canvasNode: EditorCanvasNode? = null

    /** Layout passes so far (a test hook). */
    var layoutPasses = 0
        private set

    /** The vertical scroll the last frame was built at (a test hook: what is on screen). */
    val frameScrollY: Float get() = frame?.scrollY ?: scroll.y

    /**
     * The layout pass (measure before draw): the size, the scroll position (the anchor, clamping),
     * the visible lines and block widgets measured, the frame decided. Everything it reads is
     * observed except the scroll position, which it reads through [EditorScrollState.version] (a
     * scroll it did not make), so its own anchoring and clamping never schedule another pass.
     */
    fun layoutFrame(width: Float, height: Float, configure: () -> Unit = {}, measureWidget: WidgetMeasurer? = null): SurfaceFrame? {
        layoutPasses++
        scroll.version
        scroll.layoutDepth++
        val f = try {
            configure()
            val state = view.state
            val theme = theme ?: return null
            val size = Size(width, height)
            val d = digits(state.doc.lineCount)
            val columns = followMarkerColumns(state)
            if (size != lastSize || d != lastDigits || columns) {
                lastSize = size
                lastDigits = d
                viewportSize = size
                relayout()
            }
            syncBlocks(state)
            buildFrame(state, theme, measureWidget)
        } finally {
            scroll.layoutDepth--
        }
        frame = f
        canvasNode?.invalidate()
        return f
    }

    /** The draw pass: the last frame as it is ([drawFrame]); it measures and scrolls nothing. */
    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
        val theme = theme ?: return
        val f = frame ?: return scope.drawRect(theme.background)
        DrawGuard.drawing { scope.drawFrame(f, theme, focused = view.focused, cursorOn = cursorOn) }
    }

    /** A cached layout of line number [n]. */
    fun numberLayout(n: Int): TextLayoutResult = numberLayouts.getOrPut(n) {
        DrawGuard.check("a line number layout")
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
        // Block widgets' heights follow at once (a scroll-into-view right after sees them).
        if (!tr.docChanged) { if (theme != null) syncBlocks(tr.state); return }
        if (heights.lineCount == tr.startState.doc.lineCount) heights.applyChanges(tr.changes, tr.startState.doc, tr.state.doc)
        else { heights.reset(tr.state.doc.lineCount); blocks.invalidate() }
        blocks.onChanges(tr)
        geometry.onChanges(tr.changes)
        // The anchor follows its text: an edit above the viewport does not move what is shown.
        val doc = tr.state.doc
        anchorPos = doc.lineStart(doc.lineIndexAt(tr.changes.mapPos(anchorPos.coerceIn(0, tr.changes.lengthBefore), -1)))
        if (theme != null) syncBlocks(tr.state)
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
        knownColumns.clear()
        columnOrder = emptyList()
        markerSets = emptyList()
        relayout()
        handles = TouchHandles.NONE
        menuShown = false
        geometry.clearPieceWidths()
        heights.reset(view.state.doc.lineCount)
        blocks.invalidate()
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
        if (showKeyboard) { focusFromTouch(); return true }
        return requestFocus()
    }

    override fun coordsAtPos(offset: Int): Rect = caretRectOnScreen(offset.coerceIn(0, view.state.doc.length))

    private fun digits(lines: Int): Int = maxOf(2, lines.toString().length)
}

/** One marker column of the gutter: its [id] ([GutterMarker.column]), where it starts and its width, in pixels. */
data class GutterColumn(val id: String, val x: Float, val width: Float)

/** A marker column's cell under a pointer: the column, the 0-based line, the marker there (if any). */
data class GutterHit(val column: String, val line: Int, val marker: GutterMarker?)

/**
 * What one composed `Editor` knows about its input across the views it shows: its Compose focus and
 * the field's keyboard request ([EditorController.keyboardOnFocus]).
 */
@Stable
internal class SurfaceInput {
    var focused: Boolean by mutableStateOf(false)
    var keyboardOnFocus: Boolean by mutableStateOf(platformInputOnAnyFocus)
}
