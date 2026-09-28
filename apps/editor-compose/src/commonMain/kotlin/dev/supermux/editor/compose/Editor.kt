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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import dev.supermux.editor.core.panelsFacet
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
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.gutterMarkersFacet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.ime
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
 * @param lightTheme / darkTheme what a state's `themeModeFacet` picks between (a host's own themes);
 *   without them the mode swaps only the palette of [theme] (see [EditorTheme.withPalette]).
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
 * @param linked keeps this editor's lines aligned with another's (a side-by-side diff), as
 *   [linkedSide]; see [LinkedScroll].
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
    linked: LinkedScroll? = null,
    linkedSide: LinkedSide = LinkedSide.A,
    lightTheme: EditorTheme? = null,
    darkTheme: EditorTheme? = null,
) {
    // cacheSize = 0: the surface keeps its own bounded caches (LineLayouts).
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    // The surface's focus and keyboard request outlive a view (a host showing another document):
    // the new view learns the focus, and the keyboard request carries over as it was (a
    // programmatic or mouse focus keeps asking for none; a touch's keeps its session).
    val surfaceInput = remember { SurfaceInput() }
    val controller = remember(view, measurer, scrollState) { EditorController(view, measurer, scrollState, surfaceInput) }
    // Settings a plugin (the view settings) put in the state override the parameters, and a
    // reconfigure changes them live. Derived: a keystroke recomposes nothing here.
    val settingWrap by remember(view) { androidx.compose.runtime.derivedStateOf { view.state.facet(lineWrappingFacet) } }
    val settingSize by remember(view) { androidx.compose.runtime.derivedStateOf { view.state.facet(fontSizeFacet) } }
    val settingMode by remember(view) { androidx.compose.runtime.derivedStateOf { view.state.facet(themeModeFacet) } }
    val wrap = settingWrap ?: lineWrap
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val baseTheme = when (val mode = settingMode) {
        null -> theme
        else -> remember(theme, mode, systemDark, lightTheme, darkTheme) {
            val dark = mode == EditorThemeMode.DARK || (mode == EditorThemeMode.SYSTEM && systemDark)
            // The host's own theme for that mode, else only the palette swapped under the host's theme.
            (if (dark) darkTheme else lightTheme)
                ?: theme.withPalette(if (dark) EditorTheme.dark(theme.fontFamily) else EditorTheme.light(theme.fontFamily))
        }
    }
    // The zoom is the view's (snapshot state): a change recomposes this with the theme at that size.
    // A size from the settings shows unless the user zoomed since it was set. (Read the setting
    // unconditionally: behind `?:` a zoomed view would stop observing it.)
    val setting = settingSize
    val zoomed = view.fontSize ?: setting
    val shownTheme = if (zoomed == null || zoomed == baseTheme.fontSizeSp) baseTheme else remember(baseTheme, zoomed) { baseTheme.copy(fontSizeSp = zoomed) }
    val reportFontSize by rememberUpdatedState(onFontSize)
    SideEffect {
        view.readOnly = readOnly
        view.baseFontSize = theme.fontSizeSp
        view.onFontSize = { reportFontSize(it) }
        // A new size from the settings (a reconfigure) replaces the user's zoom.
        if (view.settingFontSize != setting) {
            view.settingFontSize = setting
            if (setting != null) view.fontSize = null
        }
        controller.configure(shownTheme, density, wrap, showLineNumbers)
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
            controller.dropWidgetState()
            scrollState.surfaces -= controller
            removeFastTyping?.invoke()
            if (view.surface === controller) {
                view.surface = null
                view.geometry = null
            }
        }
    }
    // Linked views: the position is the pair of lines [linked] keeps; this side follows it and tells it its own scrolls.
    controller.linked = linked
    controller.linkedSide = linkedSide
    DisposableEffect(controller, linked, linkedSide) {
        linked?.attach(linkedSide, controller)
        controller.scroll.onOwnScroll = linked?.let { l -> { l.scrolledBy(linkedSide, controller) } }
        onDispose {
            linked?.detach(linkedSide, controller)
            if (controller.linked === linked) controller.scroll.onOwnScroll = null
        }
    }
    // The hover engine hears the "show hover" requests; it stops with the view.
    DisposableEffect(view, controller) {
        val remove = view.addListener { controller.hover.follow(it) }
        onDispose { remove(); controller.hover.dispose() }
    }
    val reportViewport by rememberUpdatedState(onViewport)
    LaunchedEffect(view) {
        // Plugins that asked for the viewport as state (EditorViewport, viewportEffectsFacet) get it
        // in ONE transaction, only when the laid-out lines leave the window they were given.
        var window: IntRange? = null
        fun follow(laid: IntRange) {
            if (laid.isEmpty()) return
            val st = view.state
            val providers = st.facet(viewportEffectsFacet)
            val field = EditorViewport.of(st)
            if (field == null && providers.isEmpty()) return
            val known = if (field != null) field.takeIf { !it.isEmpty() } else window
            if (known != null && laid.first >= known.first && laid.last <= known.last) return
            val w = EditorViewport.around(st.doc, laid)
            window = w
            val effects = buildList {
                if (field != null) add(EditorViewport.set.of(w))
                for (p in providers) addAll(p(laid))
            }
            if (effects.isNotEmpty()) view.dispatch(TransactionSpec(effects = effects))
        }
        // Another state in this view (a document switch): its plugins start without a viewport.
        val removeReplace = view.addReplaceListener { window = null; follow(view.viewport.value) }
        try {
            view.viewport.collect {
                if (it.isEmpty()) return@collect
                follow(it)
                reportViewport(it)
            }
        } finally {
            removeReplace()
        }
    }

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
    // Panels (a search bar) above and below, outside the scrolling area: the surface takes the rest.
    // One Column always, so a panel coming or going never rebuilds the surface.
    // Derived: a keystroke recomposes nothing here, only a change of the panels does.
    val panels by remember(view) { androidx.compose.runtime.derivedStateOf { view.state.facet(panelsFacet) } }
    Column(modifier) {
    for (p in panels) if (p.top) androidx.compose.runtime.key(p) { EditorPanel(controller, p, widgets) }
    Box(
        Modifier.weight(1f).fillMaxWidth()
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
                enabled = !wrap,
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
                // Above the widgets: the touch handles, and a pointer shield over each one's target so a
                // widget under a handle never takes the finger meant for it (the surface's gestures,
                // an ancestor, still see every event).
                handles = { Spacer(Modifier.fillMaxSize().editorCanvas(controller, paintHook, handles = true)) },
                handleShield = {
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial) } })
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
                    KeyboardInsets(controller)
                },
            )
        }
        // Measure before draw: the layout pass positions the scroll, lays out the visible lines and
        // places the children; the canvas then only paints what it decided.
        // The registry is the controller's before the first layout pass (a SideEffect runs after it).
        controller.registry = widgets
        controller.saveableHolder = holder
        val policy = remember(controller, slots, shownTheme, density, wrap, showLineNumbers) {
            surfaceMeasurePolicy(controller, slots) { controller.configure(shownTheme, density, wrap, showLineNumbers) }
        }
        SubcomposeLayout(Modifier.fillMaxSize(), policy)
    }
    for (p in panels) if (!p.top) androidx.compose.runtime.key(p) { EditorPanel(controller, p, widgets) }
    }
}

/**
 * A panel strip: the registry's `panel:<id>` content, full width, its own height. Its input and
 * focus are its own (the search field); an Escape the content did not take gives the focus back to
 * the editor (the content sees it first: the search panel closes itself on Escape).
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.EditorPanel(c: EditorController, panel: dev.supermux.editor.core.Panel, widgets: WidgetRegistry) {
    val key = dev.supermux.editor.core.WidgetKey("panel:" + panel.id, panel.id)
    val content = widgets.content(key.type) ?: return
    Box(
        Modifier.fillMaxWidth().onKeyEvent { e ->
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Escape) { c.requestFocus(); true } else false
        },
    ) { c.widgetScope.content(key) }
}

/**
 * The soft keyboard's height and the window's, for the tooltips (kept above the keyboard). Its own
 * small scope: the keyboard's animation recomposes only this.
 */
@Composable
private fun KeyboardInsets(c: EditorController) {
    val density = LocalDensity.current
    val ime = androidx.compose.foundation.layout.WindowInsets.ime.getBottom(density)
    val window = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height
    SideEffect {
        c.imeBottomPx = ime
        c.windowHeightPx = window
    }
}

/** The surface's fixed children (see [surfaceMeasurePolicy]). */
internal class SurfaceSlots(
    val canvas: @Composable () -> Unit,
    val handles: @Composable () -> Unit,
    val handleShield: @Composable () -> Unit,
    val testChild: (@Composable () -> Unit)?,
    val field: @Composable () -> Unit,
    val shield: @Composable () -> Unit,
    val overlay: @Composable () -> Unit,
)

private enum class Slot { CANVAS, TEST, FIELD, SHIELD, HANDLES, OVERLAY }

private data class HandleShieldSlot(val i: Int)

/**
 * A gutter marker's accessibility node's slot: the marker by IDENTITY (a plugin's RangeSet keeps its
 * instances through edits, so an Enter above never recreates the nodes below), and its occurrence
 * among the drawn ones (one instance on several lines).
 */
internal class MarkerSlot(val marker: GutterMarker, val n: Int) {
    override fun equals(other: Any?) = other is MarkerSlot && other.marker === marker && other.n == n
    override fun hashCode() = marker.hashCode() * 31 + n
}

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
    // Each slot is subcomposed at most once per pass: a widget measured early in the pass and then
    // pushed out of the range (another one grew) is still subcomposed, never again as "retained".
    val widgetPlaceables = HashMap<WidgetSlot, List<Placeable>>()
    val measureWidget: WidgetMeasurer = { key, inline, cs ->
        val slot = c.widgetSlot(key, inline)
        // Measured once per pass (a measurable may not be measured twice): a second ask reuses it.
        val ps = widgetPlaceables[slot] ?: subcompose(slot, c.widgetContent(slot)).map { it.measure(cs) }
        widgetPlaceables[slot] = ps
        if (ps.isEmpty()) null else androidx.compose.ui.unit.IntSize(ps.maxOf { it.width }, ps.maxOf { it.height })
    }
    val frame = c.layoutFrame(w.toFloat(), h.toFloat(), configure, measureWidget)
    val placed = frame?.widgets.orEmpty().filter { it.composed && widgetPlaceables.containsKey(c.widgetSlot(it.key, it.inline)) }
    // Out of view but near: still composed (not measured, not placed), so their state lives.
    for (slot in c.retainedWidgets(widgetPlaceables.keys)) subcompose(slot, c.widgetContent(slot))
    val full = Constraints.fixed(w, h)
    val canvas = subcompose(Slot.CANVAS, slots.canvas).map { it.measure(full) }
    val test = slots.testChild?.let { t -> subcompose(Slot.TEST, t).map { it.measure(full) } }.orEmpty()
    val field = subcompose(Slot.FIELD, slots.field).map { it.measure(Constraints()) }
    val shield = subcompose(Slot.SHIELD, slots.shield).map { it.measure(Constraints()) }
    val handles = subcompose(Slot.HANDLES, slots.handles).map { it.measure(full) }
    val handleShields = frame?.handles.orEmpty().mapIndexed { i, spot ->
        val r = spot.touch
        spot to subcompose(HandleShieldSlot(i), slots.handleShield).map { it.measure(Constraints.fixed(r.width.toInt().coerceAtLeast(1), r.height.toInt().coerceAtLeast(1))) }
    }
    val overlay = subcompose(Slot.OVERLAY, slots.overlay).map { it.measure(Constraints(maxWidth = w, maxHeight = h)) }
    // A node per visible marker with a tooltip, for screen readers (it takes no pointer).
    val seen = HashMap<GutterMarker, Int>()
    val markerSlots = HashSet<MarkerSlot>()
    val markers = frame?.markers.orEmpty().mapNotNull { m ->
        if (m.marker.tooltip == null) return@mapNotNull null
        val n = seen.getOrElse(m.marker) { 0 }.also { seen[m.marker] = it + 1 }
        val key = MarkerSlot(m.marker, n)
        markerSlots += key
        c.markerPlaces[key] = m.column to m.line
        val size = Constraints.fixed(m.rect.width.toInt().coerceAtLeast(1), m.rect.height.toInt().coerceAtLeast(1))
        m to subcompose(key, c.markerNode(key)).map { it.measure(size) }
    }
    c.pruneMarkerNodes(markerSlots)
    val caret = frame?.caret ?: Rect.Zero
    // Tooltips (completion, hover, signature help, lint): measured after the frame, where the caret
    // rects are known, and placed over everything else of the surface.
    val tips = if (frame == null) emptyList() else {
        val hf = h.toFloat()
        val gap = 3f * c.densityValue
        val bounds = Rect(0f, 0f, w.toFloat(), hf - c.keyboardOverlap(hf))
        val mainCaret = caret.takeIf { it.bottom >= 0f && it.top <= hf }
        c.tooltipsToShow(w.toFloat(), hf).map { (t, anchor) ->
            val maxH = TooltipLayout.maxHeight(anchor, mainCaret, bounds, gap).toInt().coerceAtLeast(0)
            val ps = subcompose(TooltipSlot(c.view.widgetStateId, t.key), c.tooltipContent(t.key)).map { it.measure(Constraints(maxWidth = w, maxHeight = maxH)) }
            val tw = ps.maxOfOrNull { it.width } ?: 0
            val th = ps.maxOfOrNull { it.height } ?: 0
            val at = TooltipLayout.place(anchor, mainCaret, tw, th, bounds, t.above, gap)
            Triple(t.key, Rect(at.x, at.y, at.x + tw, at.y + th), ps)
        }
    }
    c.placedTooltips = tips.associate { it.first to it.second }
    layout(w, h) {
        canvas.forEach { it.place(0, 0) }
        for ((m, p) in markers) p.forEach { it.place(m.rect.left.toInt(), m.rect.top.toInt()) }
        test.forEach { it.place(0, 0) }
        // The field sits at the caret, where the platform anchors the keyboard's candidates.
        field.forEach { it.place(caret.left.toInt(), caret.top.toInt()) }
        shield.forEach { it.place(caret.left.toInt() - it.width / 2, caret.top.toInt() - it.height / 2) }
        // Widgets over the shield: a widget's text field right under the caret still gets its taps.
        for (pw in placed) widgetPlaceables[c.widgetSlot(pw.key, pw.inline)]?.forEach {
            // An inline widget sits on its row, centred; a block across the text area.
            val y = if (pw.inline) pw.rect.top + (pw.rect.height - it.height) / 2 else pw.rect.top
            it.place(kotlin.math.round(pw.rect.left).toInt(), kotlin.math.round(y).toInt())
        }
        handles.forEach { it.place(0, 0) }
        for ((spot, ps) in handleShields) ps.forEach { it.place(spot.touch.left.toInt(), spot.touch.top.toInt()) }
        overlay.forEach { it.place(0, 0) }
        for ((_, r, ps) in tips) ps.forEach { it.place(kotlin.math.round(r.left).toInt(), kotlin.math.round(r.top).toInt()) }
    }
}

/** A tooltip's slot, scoped to its view like a widget's. */
internal data class TooltipSlot(val view: Long, val key: dev.supermux.editor.core.WidgetKey)

/**
 * A widget's slot: its key in a role (a key may be shown as a block and inline at once: two slots),
 * scoped to the view showing it (another document's `thread/t1` is another widget).
 */
internal data class WidgetSlot(val view: Long, val key: dev.supermux.editor.core.WidgetKey, val inline: Boolean) {
    /** The saveable state holder's key (a String: a platform bundle can keep it). */
    val saveKey: String get() = "$view\u0000${if (inline) "i" else "b"}\u0000${key.type}\u0000${key.id}"
}

/** The canvas: paints the controller's last frame ([EditorController.draw]), then tells the host. */
internal fun Modifier.editorCanvas(c: EditorController, onPaint: androidx.compose.runtime.State<(() -> Unit)?>, handles: Boolean = false): Modifier =
    this then EditorCanvasElement(c, onPaint, handles)

private data class EditorCanvasElement(val c: EditorController, val onPaint: androidx.compose.runtime.State<(() -> Unit)?>, val handles: Boolean) :
    ModifierNodeElement<EditorCanvasNode>() {
    override fun create() = EditorCanvasNode(c, onPaint, handles)
    override fun update(node: EditorCanvasNode) = node.bind(c, onPaint, handles)
}

/** The main canvas (the frame, then the host's paint hook) or, with [handles], the touch handles' overlay. */
internal class EditorCanvasNode(private var c: EditorController, private var onPaint: androidx.compose.runtime.State<(() -> Unit)?>, private var handles: Boolean) :
    Modifier.Node(), DrawModifierNode {
    override fun onAttach() { c.canvasNodes += this }
    override fun onDetach() { c.canvasNodes -= this }

    fun bind(c: EditorController, onPaint: androidx.compose.runtime.State<(() -> Unit)?>, handles: Boolean) {
        if (c !== this.c) { this.c.canvasNodes -= this; this.c = c; c.canvasNodes += this }
        this.onPaint = onPaint
        this.handles = handles
        invalidate()
    }

    /** A new frame to paint (the layout pass built one). */
    fun invalidate() { if (isAttached) invalidateDraw() }

    override fun ContentDrawScope.draw() {
        if (handles) { c.drawHandles(this); return }
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

    var geometry: Geometry = newGeometry(null)
        private set

    /** A geometry over the current height map, keeping [previous]'s folds until they are synced again. */
    private fun newGeometry(previous: Geometry?): Geometry = Geometry({ view.state }, heights, layouts).also { g ->
        g.widgetWidth = { widgetWidthOf(it) }
        previous?.let { g.folds = it.folds }
    }


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
    /** The tab size the layouts use (`tabSizeFacet`): a reconfigure of it relayouts. */
    private var tabSize = -1
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

    fun configure(theme: EditorTheme, density: Density, lineWrap: Boolean, showLineNumbersParam: Boolean) {
        // A plugin's or a setting's facet overrides the parameter (and a reconfigure changes it).
        val showLineNumbers = view.state.facet(lineNumbersFacet) ?: showLineNumbersParam
        val tabSize = view.state.facet(tabSizeFacet)
        val changed = theme != this.theme || density != this.density || lineWrap != this.lineWrap || showLineNumbers != this.showLineNumbers ||
            tabSize != this.tabSize
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
        this.tabSize = tabSize
        this.showLineNumbers = showLineNumbers
        numberLayouts.clear()
        chipGlyph = null
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
            geometry = newGeometry(geometry)
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
    fun syncBlocks(state: EditorState): Boolean = blocks.sync(state, heights, layouts.lineHeightPx, registry) { geometry.folds.isHidden(it) }

    // ------------------------------------------------------------------ folds, inline widgets --

    private var foldDecos: List<dev.supermux.editor.core.RangeSet<dev.supermux.editor.core.Decoration>>? = null
    private var foldDoc: dev.supermux.editor.core.Rope? = null
    private var foldHeights: HeightMap? = null
    private var appliedHidden: List<IntRange> = emptyList()

    /** The lines edits replaced since the last fold sync (new doc lines). */
    private val editedLines = ArrayList<IntRange>()

    /**
     * The state's replaced ranges and inline widgets into the geometry, and their hidden lines into
     * the height map (zero; unfolded ones back to an estimate, measured as they come into view).
     * True when a height changed.
     */
    fun syncFolds(state: EditorState): Boolean {
        val decos = state.facet(dev.supermux.editor.core.decorationsFacet)
        if (decos === foldDecos && state.doc === foldDoc && heights === foldHeights) return false
        if (heights.lineCount != state.doc.lineCount) return false
        if (heights !== foldHeights) appliedHidden = emptyList()
        foldDecos = decos
        foldDoc = state.doc
        foldHeights = heights
        val f = Folds.build(state.doc, decos, view.sharedFoldCache) { geometry.isLong(it) }
        geometry.folds = f
        var changed = false
        val hidden = f.hidden
        // Only the lines whose state changed: unfolded ones back to an estimate, newly folded ones to
        // zero, and the lines an edit replaced (they came back as estimates). Never every hidden line.
        for (r in runsMinus(appliedHidden, hidden)) for (l in r) {
            if (l < heights.lineCount && heights.textHeight(l) == 0f) { heights.setMeasured(l, heights.estimatedLineHeight); changed = true }
        }
        for (r in runsMinus(hidden, appliedHidden)) for (l in r) if (heights.textHeight(l) != 0f) { heights.setMeasured(l, 0f); changed = true }
        for (r in editedLines) for (l in r) if (l < heights.lineCount && f.isHidden(l) && heights.textHeight(l) != 0f) { heights.setMeasured(l, 0f); changed = true }
        editedLines.clear()
        appliedHidden = hidden
        return changed
    }

    /** Measured sizes of registered inline widgets' content, in pixels. */
    private val inlineSizes = HashMap<dev.supermux.editor.core.WidgetKey, androidx.compose.ui.unit.IntSize>()
    private val inlineMeasured = HashSet<dev.supermux.editor.core.WidgetKey>()

    /** A drawn placeholder chip's width: an unregistered inline widget or a fold's "⋯". */
    fun chipWidth(): Float = kotlin.math.round(layouts.charWidthPx * 2.5f)

    /** How wide a widget part is in its row: its content's measured width, a chip, nothing for a bare Replace. */
    private fun widgetWidthOf(p: LinePart): Float {
        val key = p.widget ?: return 0f
        if (registry?.contains(key.type) == true) return inlineSizes[key]?.width?.toFloat() ?: (layouts.charWidthPx * 2)
        return chipWidth()
    }

    fun inlineMeasuredThisFrame(key: dev.supermux.editor.core.WidgetKey) = key in inlineMeasured

    /**
     * Measure the registered inline widgets on the rows of [lines] (shown lines) not measured yet this
     * frame, BEFORE those rows are laid out (their widths go into the layout).
     */
    fun measureInline(lines: List<Int>, measure: WidgetMeasurer?) {
        if (measure == null || geometry.folds.inline.isEmpty() && geometry.folds.replaces.isEmpty()) return
        val reg = registry ?: return
        val g = geometry
        val doc = view.state.doc
        val lh = layouts.lineHeightPx.toInt().coerceAtLeast(1)
        for (l in lines) {
            val from = doc.lineStart(l)
            val to = geometry.visualEnd(from)
            val parts = g.folds.parts(from, to) ?: continue
            for (p in parts) {
                val key = p.widget ?: continue
                if (p.isText || key.type !in reg || key in inlineMeasured) continue
                inlineMeasured += key
                val size = measure(key, true, Constraints(maxHeight = lh)) ?: continue
                inlineSizes[key] = size
            }
        }
    }

    /** A drawn chip under [p] (surface pixels): a click on it is the plugin's (an unfold). */
    fun chipAt(p: androidx.compose.ui.geometry.Offset): DrawnChip? = frame?.chips?.firstOrNull { it.rect.contains(p) }

    /** A click or tap on a drawn chip: the plugins' handlers ([widgetClickFacet]) first, then the host's. */
    fun reportWidgetClick(chip: DrawnChip) {
        if (view.runningCommand { view.state.facet(widgetClickFacet).any { h -> view.guarded("widget click handler", true) { h.click(view, chip.key, chip.from, chip.to) } } }) return
        view.onWidgetClick?.invoke(chip.key, chip.from, chip.to)
    }

    private var chipGlyph: TextLayoutResult? = null

    /** The "⋯" a placeholder chip shows. */
    fun chipGlyph(): TextLayoutResult = chipGlyph ?: run {
        DrawGuard.check("a chip layout")
        measurer.measure("⋯", numberStyle, softWrap = false, density = density).also { chipGlyph = it }
    }

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
            if (e.key.type !in reg || blocks.measuredThisFrame(e.key) || geometry.folds.isHidden(e.line)) continue
            val size = measure(e.key, false, Constraints(minWidth = width, maxWidth = width)) ?: continue
            if (blocks.setMeasured(e.key, size.height.toFloat())) changed = true
        }
        return changed
    }

    /** The widget scope every widget's content gets. */
    val widgetScope: WidgetScope = object : WidgetScope {
        override val editor: dev.supermux.editor.core.CommandTarget get() = this@EditorController.view
        override val theme: EditorTheme get() = this@EditorController.theme ?: error("the editor is not configured")
        override val lineHeight: androidx.compose.ui.unit.Dp get() = with(density) { layouts.lineHeightPx.toDp() }
        override fun focusEditor() { requestFocus() }
    }

    private val widgetContents = HashMap<WidgetSlot, @Composable () -> Unit>()
    private val tooltipContents = HashMap<dev.supermux.editor.core.WidgetKey, @Composable () -> Unit>()

    /** A tooltip's content for its key (the registry's `key.type`), kept per key. */
    fun tooltipContent(key: dev.supermux.editor.core.WidgetKey): @Composable () -> Unit = tooltipContents.getOrPut(key) {
        if (tooltipContents.size > 64) tooltipContents.clear()
        val content: @Composable () -> Unit = { registry?.content(key.type)?.let { c -> Box { widgetScope.c(key) } } }
        content
    }

    /** The slot of [key] in its role, in this view. */
    fun widgetSlot(key: dev.supermux.editor.core.WidgetKey, inline: Boolean) = WidgetSlot(view.widgetStateId, key, inline)

    /** The slots whose saveable state the holder may keep (removed when their decoration goes). */
    private val savedSlots = HashSet<WidgetSlot>()

    /** A widget's content for its slot, kept per slot (so a scroll recomposes nothing). */
    fun widgetContent(slot: WidgetSlot): @Composable () -> Unit = widgetContents.getOrPut(slot) {
        savedSlots += slot
        {
            val content = registry?.content(slot.key.type)
            val holder = saveableHolder
            if (content != null && holder != null) {
                holder.SaveableStateProvider(slot.saveKey) {
                    Box(if (slot.inline) Modifier else Modifier.fillMaxWidth()) { widgetScope.content(slot.key) }
                }
            }
        }
    }

    private var aliveBlocks: List<BlockEntry>? = null
    private var aliveFolds: Folds? = null

    /**
     * Forget what belongs to widgets whose decoration is gone: their saved state (a draft of a thread
     * that was resolved), their measured inline size. Cheap unless the widgets changed.
     */
    fun pruneWidgetState() {
        val entries = blocks.entries
        val folds = geometry.folds
        if (entries === aliveBlocks && folds === aliveFolds) return
        aliveBlocks = entries
        aliveFolds = folds
        val alive = HashSet<WidgetSlot>()
        for (e in entries) alive += widgetSlot(e.key, false)
        val inlineKeys = HashSet<dev.supermux.editor.core.WidgetKey>()
        for (p in folds.inline) { alive += widgetSlot(p.key, true); inlineKeys += p.key }
        for (r in folds.replaces) r.widget?.let { alive += widgetSlot(it, true); inlineKeys += it }
        inlineSizes.keys.retainAll(inlineKeys)
        val gone = savedSlots.filter { it !in alive }
        if (gone.isEmpty()) return
        val holder = saveableHolder
        for (slot in gone) { holder?.removeState(slot.saveKey); savedSlots -= slot; widgetContents -= slot }
    }

    /** This view is no longer shown: none of its widgets' saved state is kept. */
    fun dropWidgetState() {
        val holder = saveableHolder
        for (slot in savedSlots) holder?.removeState(slot.saveKey)
        savedSlots.clear()
        widgetContents.clear()
    }

    /** The slots composed last pass, most recently shown last (the retained cache's order). */
    private val composedWidgets = LinkedHashSet<WidgetSlot>()

    /**
     * The block widgets to keep composed but not placed: shown recently, now out of view but within
     * [EditorDefaults.RETAIN_SCREENS] screens of it, at most [EditorDefaults.RETAIN_WIDGETS]; never
     * one already subcomposed this pass ([subcomposed]). So a scroll out and back keeps even their
     * `remember` state; a widget further away is disposed (its `rememberSaveable` state, a draft, is
     * kept by the saveable-state holder until its decoration goes).
     */
    fun retainedWidgets(subcomposed: Set<WidgetSlot>): List<WidgetSlot> {
        val h = viewportSize.height
        // The frame's position, never the scroll state's (a read here, after the pass, is observed).
        val y = frame?.scrollY ?: return emptyList()
        val near = composedWidgets.filter { s ->
            if (s in subcomposed || s.inline || s.view != view.widgetStateId) return@filter false
            val e = blocks.entry(s.key) ?: return@filter false
            if (registry?.contains(s.key.type) != true || e.line >= heights.lineCount || geometry.folds.isHidden(e.line)) return@filter false
            val top = heights.top(e.line)
            top + heights.height(e.line) >= y - EditorDefaults.RETAIN_SCREENS * h && top <= y + (1 + EditorDefaults.RETAIN_SCREENS) * h
        }.takeLast(EditorDefaults.RETAIN_WIDGETS)
        composedWidgets.clear()
        composedWidgets += near
        composedWidgets += subcomposed
        widgetContents.keys.retainAll(composedWidgets)
        return near
    }

    /** True when [p] (surface pixels) is on a block widget's content or a tooltip: that pointer is theirs. */
    fun widgetAt(p: androidx.compose.ui.geometry.Offset): Boolean =
        tooltipAt(p) || frame?.widgets?.any { !it.inline && it.composed && it.rect.contains(p) } == true

    // ------------------------------------------------------------------ tooltips --

    /** The tooltips placed by the last layout pass (surface pixels), by key. */
    var placedTooltips: Map<dev.supermux.editor.core.WidgetKey, Rect> = emptyMap()
        internal set

    /** True when [p] (surface pixels) is on a tooltip placed in the last layout pass. */
    fun tooltipAt(p: androidx.compose.ui.geometry.Offset): Boolean = placedTooltips.values.any { it.contains(p) }

    /** The mouse hover engine ([hoverTooltip]'s sources). */
    val hover = HoverEngine(this)

    /** The soft keyboard's height over the window's bottom (px), and the window's height: observed by the layout pass. */
    var imeBottomPx: Int by androidx.compose.runtime.mutableIntStateOf(0)
    var windowHeightPx: Int by androidx.compose.runtime.mutableIntStateOf(0)

    /** How much of the surface's bottom a soft keyboard covers (px): the tooltips stay above it. */
    fun keyboardOverlap(height: Float): Float {
        val ime = imeBottomPx
        val win = windowHeightPx
        if (ime <= 0 || win <= 0) return 0f
        val co = coordinates?.takeIf { it.isAttached } ?: return 0f
        val bottom = co.localToRoot(androidx.compose.ui.geometry.Offset(0f, height)).y
        return (bottom - (win - ime)).coerceIn(0f, height)
    }

    /** Where each hideOnScroll tooltip's anchor was last pass (to see the text move under it), and the ones already dismissed. */
    private val tooltipAnchors = HashMap<dev.supermux.editor.core.WidgetKey, Pair<dev.supermux.editor.core.Rope, Rect>>()
    private val dismissedTooltips = HashSet<dev.supermux.editor.core.WidgetKey>()

    /**
     * The tooltips to show this pass: each with its anchor (the caret rect at its position, surface
     * pixels) and the bounds it may take. A strict one whose position is out of view is left out; a
     * hideOnScroll one whose text moved on screen (a scroll, not an edit) is dismissed (dispatched
     * after the pass).
     */
    fun tooltipsToShow(width: Float, height: Float): List<Pair<dev.supermux.editor.core.Tooltip, Rect>> {
        val st = view.state
        val list = st.facet(dev.supermux.editor.core.tooltipsFacet)
        if (list.isEmpty()) { tooltipAnchors.clear(); dismissedTooltips.clear(); return emptyList() }
        val reg = registry ?: return emptyList()
        val out = ArrayList<Pair<dev.supermux.editor.core.Tooltip, Rect>>()
        val keys = HashSet<dev.supermux.editor.core.WidgetKey>()
        for (t in list) {
            keys += t.key
            if (t.key.type !in reg || t.key in dismissedTooltips) continue
            val a = caretRectOnScreen(t.pos.coerceIn(0, st.doc.length))
            if (t.hideOnScroll) {
                val last = tooltipAnchors[t.key]
                tooltipAnchors[t.key] = st.doc to a
                if (last != null && last.first === st.doc && (kotlin.math.abs(last.second.top - a.top) > 0.5f || kotlin.math.abs(last.second.left - a.left) > 0.5f)) {
                    dismissedTooltips += t.key
                    val key = t.key
                    view.scope?.launch { view.dispatch(TransactionSpec(effects = listOf(dev.supermux.editor.core.Tooltip.dismissed.of(key)))) }
                    continue
                }
            }
            val out0 = a.bottom < 0f || a.top > height || a.left < gutterWidth - 1f || a.left > width
            if (out0 && t.strict) continue
            val clamped = if (!out0) a else Rect(
                a.left.coerceIn(gutterWidth, width), a.top.coerceIn(0f, maxOf(0f, height - a.height)),
                a.left.coerceIn(gutterWidth, width) + a.width, a.top.coerceIn(0f, maxOf(0f, height - a.height)) + a.height,
            )
            out += t to clamped
        }
        tooltipAnchors.keys.retainAll(keys)
        dismissedTooltips.retainAll(keys)
        return out
    }

    /** True when [p] is on an inline widget's content (a tap there is the text's unless the widget took it). */
    fun inlineWidgetAt(p: androidx.compose.ui.geometry.Offset): Boolean = frame?.widgets?.any { it.inline && it.composed && it.rect.contains(p) } == true

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

    /** Marker nodes created so far (a test hook). */
    var markerNodesCreated = 0
        private set

    /** Widget states the saveable holder keeps (a test hook). */
    val savedWidgetStates: Int get() = savedSlots.size

    /** Where each marker node's marker is now (its click reports that line). */
    val markerPlaces = HashMap<MarkerSlot, Pair<String, Int>>()

    /** A marker's accessibility node: its tooltip as its label, a click that reports it. Kept per slot, so scrolling recomposes nothing. */
    fun markerNode(slot: MarkerSlot): @Composable () -> Unit = markerNodes.getOrPut(slot) {
        markerNodesCreated++
        {
            Box(Modifier.fillMaxSize().semantics {
                contentDescription = slot.marker.tooltip.orEmpty()
                onClick(slot.marker.tooltip) {
                    markerPlaces[slot]?.let { (column, line) -> reportGutterClick(GutterHit(column, line, slot.marker)) }
                    true
                }
            })
        }
    }

    fun pruneMarkerNodes(keep: Set<MarkerSlot>) {
        if (markerNodes.size > keep.size) markerNodes.keys.retainAll(keep)
        if (markerPlaces.size > keep.size) markerPlaces.keys.retainAll(keep)
    }

    /** A click or tap on a marker column: the plugins' handlers ([gutterClickFacet]) first, then the host's. */
    fun reportGutterClick(hit: GutterHit) {
        val view = view
        if (view.runningCommand { view.state.facet(gutterClickFacet).any { h -> view.guarded("gutter click handler", true) { h.click(view, hit.column, hit.line, hit.marker) } } }) return
        view.onGutterClick?.invoke(hit.column, hit.line, hit.marker)
    }

    private var heightsKey: Pair<Float, Boolean>? = null
    private var lastSize = Size.Zero
    private var lastDigits = 0

    /** The frame the last layout pass built: all the draw pass paints. */
    internal var frame: SurfaceFrame? = null
        private set

    /** The canvases painting [frame] (the text, the handles' overlay), told when there is a new one. */
    internal val canvasNodes = LinkedHashSet<EditorCanvasNode>()

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
        linked?.observe(linkedSide)
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
            syncFolds(state)
            syncBlocks(state)
            pruneWidgetState()
            inlineMeasured.clear()
            buildFrame(state, theme, measureWidget)
        } finally {
            scroll.layoutDepth--
        }
        frame = f
        for (n in canvasNodes) n.invalidate()
        return f
    }

    /** The handles' overlay's draw pass. */
    fun drawHandles(scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
        val theme = theme ?: return
        val f = frame ?: return
        DrawGuard.drawing { scope.drawHandleLayer(f, theme) }
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
        if (!tr.docChanged) { if (theme != null) { syncFolds(tr.state); syncBlocks(tr.state) }; return }
        if (heights.lineCount == tr.startState.doc.lineCount) heights.applyChanges(tr.changes, tr.startState.doc, tr.state.doc)
        else { heights.reset(tr.state.doc.lineCount); blocks.invalidate() }
        blocks.onChanges(tr)
        val before = tr.startState.doc
        val lineDelta = tr.state.doc.lineCount - before.lineCount
        val edits = tr.changes.iterChanges()
        val firstEdited = before.lineIndexAt(edits.first().fromA)
        // Runs before the first edit stay as they are, runs after the edits shift (no rope lookups
        // per run for a keystroke far from them); a run an edit touches is mapped.
        appliedHidden = appliedHidden.mapNotNull { r ->
            when {
                r.last < firstEdited -> r
                r.last >= before.lineCount -> null
                edits.size == 1 && r.first > before.lineIndexAt(edits[0].toA) -> (r.first + lineDelta)..(r.last + lineDelta)
                else -> tr.state.doc.lineIndexAt(tr.changes.mapPos(before.lineStart(r.first), 1))..tr.state.doc.lineIndexAt(tr.changes.mapPos(before.lineStart(r.last), 1))
            }
        }
        for (c in edits) editedLines += tr.state.doc.lineIndexAt(c.fromB)..tr.state.doc.lineIndexAt(c.toB)
        geometry.onChanges(tr.changes)
        // The anchor follows its text: an edit above the viewport does not move what is shown.
        val doc = tr.state.doc
        anchorPos = doc.lineStart(doc.lineIndexAt(tr.changes.mapPos(anchorPos.coerceIn(0, tr.changes.lengthBefore), -1)))
        if (theme != null) { syncFolds(tr.state); syncBlocks(tr.state) }
        linked?.followEdit(linkedSide, tr)
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
    override fun scrollIntoView(pos: Int?) {
        if (viewportSize.height <= 0f) return
        val r = geometry.rectFor(pos ?: view.state.selection.main.head)
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
        if (followLink()) return
        if (!anchorValid || scroll.y != anchorScrollY) recordAnchor() else restoreAnchor()
    }

    fun restoreAnchor() {
        linked?.let { it.align(linkedSide); followLink(); return }
        if (!anchorValid || scroll.y != anchorScrollY || scroll.shared) return
        val doc = view.state.doc
        if (heights.lineCount != doc.lineCount) return
        val line = doc.lineIndexAt(anchorPos.coerceIn(0, doc.length))
        val want = (heights.topD(line) + anchorDelta).toFloat()
        if (kotlin.math.abs(want - scroll.y) > 0.01f) scroll.scrollTo(y = want)
        anchorScrollY = scroll.y
    }

    fun recordAnchor() {
        if (linked != null) return
        val doc = view.state.doc
        if (heights.lineCount != doc.lineCount) return
        val line = heights.lineAt(scroll.y)
        anchorPos = doc.lineStart(line)
        anchorDelta = scroll.y - heights.top(line)
        anchorScrollY = scroll.y
        anchorValid = true
    }

    // ------------------------------------------------------------------ linked views --

    /** The [LinkedScroll] this surface is a side of, and which side. */
    var linked: LinkedScroll? = null
    var linkedSide: LinkedSide = LinkedSide.A

    /** The height map (linked views pad it). */
    internal val linkHeights: HeightMap get() = heights

    /** True when this side can take part in the alignment: configured, sized, its height map the document's. */
    internal fun linkReady(): Boolean = theme != null && viewportSize.height > 0f && heights.lineCount == view.state.doc.lineCount

    /**
     * Linked: the scroll where the shared sync point says (read observed, so the other side's
     * scroll relayouts this one in the same frame). A sync point is a line, so a height measured
     * anywhere moves nothing on screen. False when not linked.
     */
    private fun followLink(): Boolean {
        val l = linked ?: return false
        val a = l.anchor
        val x = l.x
        val y = l.yOf(linkedSide, a.sync) ?: return true
        scroll.scrollTo(if (lineWrap) 0f else x, y + a.px)
        return true
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
