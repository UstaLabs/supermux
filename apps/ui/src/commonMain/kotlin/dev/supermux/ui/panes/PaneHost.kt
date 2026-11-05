package dev.supermux.ui.panes

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import dev.supermux.ui.theme.Motion
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.layoutNodeAt
import dev.supermux.workspace.moveViewToGroup
import dev.supermux.workspace.normalizeLayout
import dev.supermux.workspace.reorderWithinGroup
import dev.supermux.workspace.resizeSplitSeam
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.setSplitSizes
import dev.supermux.workspace.splitGroup
import dev.supermux.workspace.splitSizesByPath
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Renders a workspace [LayoutNode] as nested resizable splits with tab groups at
 * the leaves.
 *
 * This composable is PURE PRESENTATION over the tree. It never edits the tree
 * itself: a tab click and a splitter drag report the new tree through
 * [onLayoutChange], and a tab close reports only the view id through
 * [onCloseView]. A close ends real work (spec §9.3) — the caller confirms with
 * the user, calls the broker, and the resulting workspace_changed frame is what
 * finally changes what is drawn.
 *
 * [content] draws one view's body. The caller maps the view id to a chat, an
 * editor, a terminal, or a display (see ViewHost.kt).
 *
 * Only the ACTIVE view of each group is composed. That is load-bearing, not an
 * optimisation: a live terminal or editor surface per background tab costs memory
 * and frames for nothing. (Both were heavyweight interop children once; both are
 * pure Compose now, and the rule is unchanged.)
 *
 * While a tab drag targets a pane body, content stays mounted and a translucent
 * [PaneDropOverlay] is shown in a [Popup] above it (a Popup also paints over a
 * platform interop child, should a pane ever embed one again).
 */
/** The view tab strip's height. The collapsed sidebar's title band matches it. */
val TAB_STRIP_HEIGHT = 32.dp

@Composable
fun PaneHost(
    layout: LayoutNode,
    /**
     * The resulting tree, for callers that just hold a tree (tests, previews).
     * Ignored when [onEdit] is set — prefer [onEdit] anywhere the tree is synced
     * with the broker, because only a transform can be replayed onto a frame
     * that lands mid-edit.
     */
    onLayoutChange: (LayoutNode) -> Unit = {},
    /**
     * The edit itself, as a function of the tree. This is what WorkspaceLayoutState
     * keeps as its pending edit and replays over incoming `workspace_changed`
     * frames, so the broker's membership and the user's arrangement can both
     * survive. A tree cannot be replayed — it would carry every view it happened
     * to hold at the time back with it.
     */
    onEdit: (((LayoutNode) -> LayoutNode) -> Unit)? = null,
    modifier: Modifier = Modifier,
    titleFor: (String) -> String = { it },
    onCloseView: (String) -> Unit = {},
    /**
     * "+" on each group's tab strip, drawn by the caller. Receives the GROUP the user clicked in,
     * so a new item lands in that group rather than somewhere arbitrary. Null hides the button.
     */
    addSlot: (@Composable (groupId: String) -> Unit)? = null,
    /**
     * What a group with no items draws. The layer keeps the `layout-empty` box and its bounds;
     * only the message inside is the caller's, because "no open views" is content vocabulary.
     * Null draws an empty box.
     */
    emptyGroupSlot: (@Composable () -> Unit)? = null,
    /**
     * Overrides how one tab is drawn. Null uses [DefaultTabChip] — a label plus a close
     * affordance. Supply this when a strip needs more than that (an editor's unsaved-changes dot,
     * a loading spinner). The layer still owns position, size, gestures, and the tab's test tag.
     */
    tabSlot: (@Composable (itemId: String, state: TabSlotState) -> Unit)? = null,
    /**
     * Font for the drag ghost's label — the only text this layer draws itself. Everything else is
     * a slot. Defaults to the platform's generic monospace so the layer needs no font resource.
     */
    labelFont: FontFamily = FontFamily.Monospace,
    /**
     * Shared drag state so the sidebar can register workspace-row drop targets.
     * When null, [PaneHost] owns a private instance (standalone / tests).
     */
    dragState: PaneDragController? = null,
    /**
     * Cross-workspace drop (sidebar row). Spec §9.4 — session workdir is unchanged.
     * Null ignores workspace drops.
     */
    onMoveToWorkspace: ((viewId: String, toWorkspaceId: String) -> Unit)? = null,
    /** Per-strip platform window chrome (drag regions, etc.); defaults to none. */
    chrome: PaneStripChrome = PaneStripChrome.None,
    /**
     * A drag released OUTSIDE the window, over no drop target, where no [tearOff] took the drag
     * over. A release inside the window that misses every target does nothing.
     */
    onDragEndMiss: (viewId: String) -> Unit = {},
    /**
     * After a successful in-tree drop (reorder / move / split). Desktop transfers
     * host claims so the tab docks into this window's extra/main slice.
     */
    onDocked: (viewId: String) -> Unit = {},
    /**
     * Live tear-off (desktop): this layer registers itself as a place a dragged tab can land, and
     * hands a mouse drag over once it leaves the window (or, for a window's only tab, once it
     * starts). Null keeps every drag inside this layer.
     */
    tearOff: TabTearOff? = null,
    /**
     * The tone [viewId]'s content is drawn on — the sheet under its pane's strip, which its tab,
     * while active, is cut from so the two read as one piece. Null: a pane showing nothing.
     */
    sheetColor: @Composable (viewId: String?) -> Color = { MaterialTheme.colorScheme.surfaceContainerLow },
    content: @Composable (viewId: String) -> Unit,
) {
    val ownedDrag = remember { PaneDragController() }
    val drag = dragState ?: ownedDrag
    val layoutState = rememberUpdatedState(layout)
    val onLayoutChangeState = rememberUpdatedState(onLayoutChange)
    val onEditState = rememberUpdatedState(onEdit)
    val onMoveToWorkspaceState = rememberUpdatedState(onMoveToWorkspace)
    val onDragEndMissState = rememberUpdatedState(onDragEndMiss)
    val onDockedState = rememberUpdatedState(onDocked)

    // EVERY edit goes through here, and every edit is a function of the CURRENT
    // tree — never of a node captured when this composition ran.
    //
    // The tree used to travel back UP through these callbacks, each parent
    // rebuilding itself from the child it was handed. A transform carries only
    // an address — a group id, a split path — so it can be REPLAYED onto the
    // broker's tree when a workspace_changed frame lands mid-edit, which is what
    // WorkspaceLayoutState does with it and why the shape matters. Handing back a
    // rebuilt node cannot be replayed: it drags along every sibling it happened
    // to hold at the time, so a frame that deleted a view would be undone by it.
    //
    // (An earlier version of this comment blamed stale `pointerInput` captures.
    // That was wrong — a probe showed the handler does receive the fresh callback
    // — and the real cause was the sync layer dropping frames. Addressing edits
    // is still right, for the replay reason above.)
    fun applyEdit(edit: (LayoutNode) -> LayoutNode) {
        // Hand the FUNCTION up when the caller can take one; it is the only form
        // that survives a `workspace_changed` frame landing mid-edit.
        onEditState.value?.let { it(edit); return }
        val tree = layoutState.value
        val next = normalizeLayout(edit(tree)) ?: return
        if (next != tree) onLayoutChangeState.value(next)
    }

    fun applyDrop(target: PaneDropTarget) =
        applyPaneDrop(target, ::applyEdit, onDockedState.value, onMoveToWorkspaceState.value)

    // This layer as a place a tab dragged in another window can land (live tear-off). Built once:
    // every callback reads the current state through the holders above.
    val endpoint = remember(drag) {
        TabDropEndpoint(
            drag = drag,
            onDrop = { applyDrop(it) },
            onDocked = { onDockedState.value(it) },
            onRestore = { viewId, groupId, index, activeViewId ->
                applyEdit { tree -> restoreTab(tree, viewId, groupId, index, activeViewId) }
                onDockedState.value(viewId)
            },
        )
    }
    if (tearOff != null) {
        DisposableEffect(tearOff, endpoint) {
            tearOff.register(endpoint)
            onDispose { tearOff.unregister(endpoint) }
        }
    }

    // Root box owns the floating drag ghost (session-list style). Ghost coords are
    // root-space; subtract this box's origin so the chip tracks the pointer.
    var hostRoot by remember { mutableStateOf(Offset.Zero) }
    // Whole-area resizes (window edge, sidebar) keep every seam still on screen;
    // see PaneAreaFit. The fit is drawn at once and written to the tree once the
    // resize settles, so the broker sees one PATCH, not one per frame.
    val fit = remember { PaneAreaFit() }
    val windowBounds = rememberWindowScreenBounds()
    val minPanePx = with(LocalDensity.current) { PaneMinSize.toPx() }.toDouble()
    LaunchedEffect(fit) {
        snapshotFlow { fit.tick }.collectLatest {
            delay(PaneFitCommitDelayMs)
            val pending = fit.adjusted ?: return@collectLatest
            val writes = splitSizesByPath(pending)
            applyEdit { root -> writes.fold(root) { t, (p, sizes) -> setSplitSizes(t, p, sizes) } }
        }
    }

    Box(
        modifier
            // The frame every pane's sheet sits in, the tone of the sidebar's rail.
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .onGloballyPositioned { hostRoot = it.positionInRoot() },
    ) {
        CompositionLocalProvider(
            LocalPaneAreaFit provides fit,
            LocalTabTearOff provides tearOff,
            LocalSoleView provides (layout as? LayoutNode.Group)?.viewIds?.singleOrNull(),
            LocalSheetColor provides sheetColor,
        ) {
        PaneHostNode(
            layout = layout,
            path = emptyList(),
            applyEdit = { applyEdit(it) },
            dragState = drag,
            onDrop = { applyDrop(it) },
            onDragEndMiss = { onDragEndMissState.value(it) },
            modifier = Modifier
                .fillMaxSize()
                // Half of every gutter here, half around each pane: 6 dp at the frame's edges and
                // between two panes alike.
                .padding(horizontal = PaneGutter)
                .layout { measurable, constraints ->
                    if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
                        fit.measure(layoutState.value, constraints.maxWidth, constraints.maxHeight, windowBounds(), minPanePx)
                    }
                    val p = measurable.measure(constraints)
                    layout(p.width, p.height) { p.place(0, 0) }
                },
            titleFor = titleFor,
            onCloseView = onCloseView,
            addSlot = addSlot,
            emptyGroupSlot = emptyGroupSlot,
            tabSlot = tabSlot,
            labelFont = labelFont,
            chrome = chrome,
            content = content,
        )
        }
        // The tab being dragged, and one easing into its slot after a drop, drawn above every
        // strip as the tab itself — so it reads as the same tab from press to landing.
        val activeIds = remember(layout) { collectActiveViewIds(layout).toSet() }
        val lifted: @Composable (String) -> Unit = { id ->
            val state = TabSlotState(selected = id in activeIds, dragged = false)
            tabSlot?.invoke(id, state) ?: DefaultTabChip(id, titleFor(id), state, labelFont, onCloseView)
        }
        val liftedTone: @Composable (String) -> Color = { id -> sheetColor(id) }
        val held = drag.ghost?.takeIf { drag.isDragging && it.visible }
        val heldId = drag.draggingViewId
        if (held != null && heldId != null) {
            key(heldId) {
                // Lifts off its strip as the drag starts, rather than popping up.
                val lift = remember { Animatable(0f) }
                LaunchedEffect(Unit) { lift.animateTo(1f, tween(durationMillis = 120)) }
                // As wide as the slot it leaves: the tab it is becomes the active one, and grows.
                val heldWidth = drag.slotOf(heldId)?.width ?: held.width
                LiftedTab(
                    bounds = Rect(held.x, held.y, held.x + heldWidth, held.y + held.height),
                    hostRoot = hostRoot,
                    lift = lift.value,
                    tone = liftedTone(heldId),
                    toneAtRest = if (heldId in activeIds) 1f else 0f,
                    modifier = Modifier.testTag("tab-drag-ghost"),
                ) { lifted(heldId) }
            }
        } else {
            drag.settling?.let { settle ->
                key(settle) {
                    SettlingTab(settle, drag, hostRoot, liftedTone(settle.viewId), if (settle.viewId in activeIds) 1f else 0f) {
                        lifted(settle.viewId)
                    }
                }
            }
        }
    }
}

/** Smallest a pane gets when the whole pane area shrinks; past it the next pane inward gives. */
private val PaneMinSize = 120.dp

/** Quiet time after a whole-area resize before the re-fit is written to the tree. */
private const val PaneFitCommitDelayMs = 300L

/**
 * A tab drawn above the strips at [bounds] (root px), lifted off its strip by [lift] (0 to 1): a
 * shadow and a touch of scale, cut from its sheet's [tone] with its bottom corners rounding as it
 * rises. At rest it wears [toneAtRest] of the tone — all of it for the active tab, which is part of
 * its sheet, none for any other.
 */
@Composable
private fun LiftedTab(
    bounds: Rect,
    hostRoot: Offset,
    lift: Float,
    tone: Color,
    toneAtRest: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val shape = remember(lift) { SheetTabShape(bottomCorner = TabCorner * lift) }
    Box(
        Modifier
            .zIndex(20f)
            .offset { IntOffset((bounds.left - hostRoot.x).roundToInt(), (bounds.top - hostRoot.y).roundToInt()) }
            .size(with(density) { DpSize(bounds.width.toDp(), bounds.height.toDp()) })
            .graphicsLayer {
                shadowElevation = TabLiftElevation.toPx() * lift
                this.shape = shape
                clip = true
                scaleX = 1f + TabLiftScale * lift
                scaleY = 1f + TabLiftScale * lift
            }
            .drawBehind {
                val a = toneAtRest + (1f - toneAtRest) * lift
                if (a > 0f) drawRect(tone.copy(alpha = tone.alpha * a))
            }
            // The caller's modifier (a test tag) on the tab where it is drawn.
            .then(modifier)
            .padding(top = TabTopInset),
        propagateMinConstraints = true,
    ) {
        content()
    }
}

/**
 * A sheet tab's outline in its strip slot: [TabCorner] top corners, starting [TabTopInset] below the
 * strip's top, and [bottomCorner] corners where it meets — or, lifted, leaves — its sheet.
 */
private class SheetTabShape(private val bottomCorner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        with(density) {
            val top = CornerRadius(TabCorner.toPx())
            val bottom = CornerRadius(bottomCorner.toPx())
            Outline.Rounded(
                RoundRect(
                    left = 0f, top = TabTopInset.toPx(), right = size.width, bottom = size.height,
                    topLeftCornerRadius = top, topRightCornerRadius = top,
                    bottomRightCornerRadius = bottom, bottomLeftCornerRadius = bottom,
                ),
            )
        }
}

/**
 * A tab just let go: drawn where it was ([TabSettle.from]) and eased, in one short motion, into the
 * slot its strip now gives it — in this strip or another, or this window after a dock from
 * another. A tab that lands in no strip here (moved to another workspace or window) stops being
 * drawn.
 */
@Composable
private fun SettlingTab(
    settle: TabSettle,
    drag: PaneDragController,
    hostRoot: Offset,
    tone: Color,
    toneAtRest: Float,
    content: @Composable () -> Unit,
) {
    val at = remember { Animatable(settle.from.topLeft, Offset.VectorConverter) }
    val size = remember { Animatable(settle.from.size, Size.VectorConverter) }
    val lift = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        val slot = awaitSlot(drag, settle.viewId)
        if (slot != null) {
            launch { lift.animateTo(0f, tabSettle()) }
            launch { size.animateTo(slot.size, tabSettle()) }
            at.animateTo(slot.topLeft, tabSettle())
        }
        drag.endSettle(settle)
    }
    LiftedTab(
        bounds = Rect(at.value, size.value),
        hostRoot = hostRoot,
        lift = lift.value,
        tone = tone,
        toneAtRest = toneAtRest,
        modifier = Modifier.testTag("tab-drag-settle"),
        content = content,
    )
}

/** The slot [viewId]'s strip gives it once laid out after the drop: the next frame, or a few for a new strip. */
private suspend fun awaitSlot(drag: PaneDragController, viewId: String): Rect? {
    repeat(SettleWaitFrames) {
        withFrameNanos { }
        drag.slotOf(viewId)?.let { return it }
    }
    return null
}

/** A tab easing into its slot: one short motion, slowing as it lands. */
private fun <T> tabSettle(): FiniteAnimationSpec<T> = tween(durationMillis = 180, easing = FastOutSlowInEasing)

/** How long a dropped tab waits for a strip to lay out its slot before giving up on easing. */
private const val SettleWaitFrames = 3

/** A held tab's lift: its shadow and how much it grows. */
private val TabLiftElevation = 8.dp
private const val TabLiftScale = 0.02f

/** Half the gutter of frame around and between the panes' sheets: 6 dp in all. */
private val PaneGutter = 3.dp

/** A sheet's corners: the menus' radius. */
private val SheetCorner = 10.dp

/** A tab's top corners, and how far below the strip's top it starts (28 dp of a 32 dp strip). */
private val TabCorner = 9.dp
private val TabTopInset = 4.dp

/** The concave curves that join the active tab to its sheet. */
private val TabFlare = 8.dp

/** Tabs share the strip's width equally, never narrower or wider than this; the row's end padding. */
private val TabMinWidth = 112.dp
private val TabMaxWidth = 200.dp
private val TabRowPadding = 8.dp // after the last tab, before the +

/** A tab's room besides its title: 11 + 6 dp padding, 16 + 7 dp icon, 6 + 16 dp ×, 4 dp to fade. */
private val TabChromeWidth = 66.dp

/** How far an overflowing strip fades out at an end with more tabs past it. */
private val StripFade = 30.dp

/**
 * The active tab, cut from its sheet: [TabCorner] top corners from [TabTopInset] down to the strip's
 * bottom, where a [TabFlare] concave curve either side turns it into the sheet below.
 */
private fun DrawScope.drawSheetTab(tone: Color, leftFlare: Boolean = true) {
    val top = TabTopInset.toPx()
    val r = CornerRadius(TabCorner.toPx())
    val f = TabFlare.toPx()
    val w = size.width
    val h = size.height
    val path = Path().apply {
        addRoundRect(RoundRect(0f, top, w, h, topLeftCornerRadius = r, topRightCornerRadius = r))
        // Each flare is its corner square less a quarter circle: filled where tab meets sheet. None
        // on the left of a tab flush with its sheet's edge, which runs straight down into it.
        if (leftFlare) {
            moveTo(-f, h)
            arcTo(Rect(-2 * f, h - 2 * f, 0f, h), 90f, -90f, false)
            lineTo(0f, h)
            close()
        }
        moveTo(w + f, h)
        arcTo(Rect(w, h - 2 * f, w + 2 * f, h), 90f, 90f, false)
        lineTo(w, h)
        close()
    }
    drawPath(path, tone)
}

/** The tone the panes' sheets are drawn on, by view; provided by [PaneHost]. */
private val LocalSheetColor = staticCompositionLocalOf<@Composable (String?) -> Color> {
    { MaterialTheme.colorScheme.surfaceContainerLow }
}

/**
 * What a drop does, wherever it was resolved: the tree edit through [applyEdit], then
 * [onDocked] with the moved view so its window takes it — or, for a sidebar workspace row, the
 * cross-workspace move through [onMoveToWorkspace].
 */
internal fun applyPaneDrop(
    target: PaneDropTarget,
    applyEdit: ((LayoutNode) -> LayoutNode) -> Unit,
    onDocked: (viewId: String) -> Unit,
    onMoveToWorkspace: ((viewId: String, toWorkspaceId: String) -> Unit)?,
) {
    val viewId = when (target) {
        is PaneDropTarget.MoveToWorkspace -> {
            onMoveToWorkspace?.invoke(target.viewId, target.toWorkspaceId)
            return
        }
        is PaneDropTarget.Reorder -> target.viewId
        is PaneDropTarget.MoveToGroup -> target.viewId
        is PaneDropTarget.Split -> target.viewId
    }
    // Minted once, outside the transform: a replay must land on the same group
    // id, not invent a new one each time the edit is rebased onto a frame.
    val newGroupId = newPaneKey()
    applyEdit { tree ->
        when (target) {
            is PaneDropTarget.Reorder ->
                reorderWithinGroup(tree, target.groupId, target.viewId, target.index)
            is PaneDropTarget.MoveToGroup ->
                moveViewToGroup(tree, target.viewId, target.toGroupId, target.index) ?: tree
            is PaneDropTarget.Split -> {
                // splitGroup requires the view to already live in the target group
                // with ≥2 tabs. Cross-group edge drops move the tab in first — that
                // turns a 1-tab neighbour into 2, then splits cleanly. Same-group
                // sole-tab edge drops no-op inside splitGroup (UI hides those edges).
                val owner = groupIdOf(tree, target.viewId)
                val withView = if (owner == target.groupId) {
                    tree
                } else {
                    moveViewToGroup(tree, target.viewId, target.groupId, Int.MAX_VALUE) ?: tree
                }
                val split = splitGroup(
                    withView,
                    target.groupId,
                    target.viewId,
                    target.direction,
                    newGroupId = newGroupId,
                )
                if (target.newFirst) reverseNewSplit(split, target.groupId) else split
            }
            is PaneDropTarget.MoveToWorkspace -> tree // handled above
        }
    }
    onDocked(viewId)
}

/**
 * [viewId] back at [index] of [groupId], with [activeViewId] (when it is still there) the group's
 * active tab again — undoing a drag that moved the tab out. A group that is gone leaves the tree
 * as it is.
 */
internal fun restoreTab(
    tree: LayoutNode,
    viewId: String,
    groupId: String,
    index: Int,
    activeViewId: String?,
): LayoutNode {
    val moved = moveViewToGroup(tree, viewId, groupId, index) ?: return tree
    return if (activeViewId != null && groupIdOf(moved, activeViewId) == groupId) {
        setActiveViewInGroup(moved, groupId, activeViewId)
    } else {
        moved
    }
}

/**
 * After [splitGroup] the new group is always the second child. For left/top
 * edges we want it first — reverse that split's children where the original
 * group id is still the first child's id.
 */
private fun reverseNewSplit(node: LayoutNode, originalGroupId: String): LayoutNode = when (node) {
    is LayoutNode.Group -> node
    is LayoutNode.Split -> {
        val c0 = node.children.getOrNull(0)
        val c1 = node.children.getOrNull(1)
        if (
            node.children.size == 2 &&
            c0 is LayoutNode.Group && c0.id == originalGroupId &&
            c1 is LayoutNode.Group
        ) {
            node.copy(
                children = listOf(c1, c0),
                sizes = node.sizes.reversed(),
            )
        } else {
            node.copy(children = node.children.map { reverseNewSplit(it, originalGroupId) })
        }
    }
}

/**
 * [path] is this node's address: the child indices to walk from the root. Only
 * splits need it (a group is named by its id), and it is what [setSplitSizes]
 * takes. It is derived purely from the tree's shape, so a handler may hold one
 * indefinitely — the worst a stale path does is miss.
 */
@Composable
private fun PaneHostNode(
    layout: LayoutNode,
    path: List<Int>,
    applyEdit: ((LayoutNode) -> LayoutNode) -> Unit,
    dragState: PaneDragController,
    onDrop: (PaneDropTarget) -> Unit,
    onDragEndMiss: (String) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    addSlot: (@Composable (String) -> Unit)?,
    emptyGroupSlot: (@Composable () -> Unit)?,
    tabSlot: (@Composable (String, TabSlotState) -> Unit)?,
    labelFont: FontFamily,
    chrome: PaneStripChrome,
    content: @Composable (String) -> Unit,
) {
    when (layout) {
        is LayoutNode.Group -> GroupHost(
            layout, applyEdit, dragState, onDrop, onDragEndMiss, modifier, titleFor, onCloseView, addSlot,
            emptyGroupSlot, tabSlot, labelFont, chrome, content,
        )
        is LayoutNode.Split -> SplitHost(
            layout, path, applyEdit, dragState, onDrop, onDragEndMiss, modifier, titleFor, onCloseView, addSlot,
            emptyGroupSlot, tabSlot, labelFont, chrome, content,
        )
    }
}

@Composable
private fun GroupHost(
    group: LayoutNode.Group,
    applyEdit: ((LayoutNode) -> LayoutNode) -> Unit,
    dragState: PaneDragController,
    onDrop: (PaneDropTarget) -> Unit,
    onDragEndMiss: (String) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    addSlot: (@Composable (String) -> Unit)?,
    emptyGroupSlot: (@Composable () -> Unit)?,
    tabSlot: (@Composable (String, TabSlotState) -> Unit)?,
    labelFont: FontFamily,
    chrome: PaneStripChrome,
    content: @Composable (String) -> Unit,
) {
    if (group.viewIds.isEmpty()) {
        // A workspace whose last view just closed. Valid, not an error (spec §9.3
        // answer 3: the workspace stays open). Register pane bounds so a tab can
        // dock back onto this empty canvas (main after canvas tear-out).
        DisposableEffect(group.id) {
            onDispose { dragState.unregisterGroup(group.id) }
        }
        val stripHeightPx = with(LocalDensity.current) { TAB_STRIP_HEIGHT.toPx() }
        val sheet = LocalSheetColor.current(null)
        Box(
            modifier
                .fillMaxSize()
                .testTag("layout-empty")
                .onGloballyPositioned { coords ->
                    val bounds = coords.boundsInRoot()
                    dragState.registerPane(group.id, bounds)
                    dragState.registerEmptyPane(group.id, bounds, stripHeightPx)
                },
        ) {
            // An empty sheet below where a strip would be, in the frame like any pane's.
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = PaneGutter, end = PaneGutter, top = TAB_STRIP_HEIGHT, bottom = PaneGutter * 2)
                    .clip(RoundedCornerShape(SheetCorner))
                    .background(sheet),
                contentAlignment = Alignment.Center,
            ) {
                emptyGroupSlot?.invoke()
            }
            // The whole canvas is one target: a tab dropped anywhere on it lands here.
            if (dragState.hoverPaneId == group.id) {
                PaneDropOverlay(activeZone = DropZone.Centre, edgesEnabled = false)
            }
        }
        return
    }
    val active = group.activeViewId ?: group.viewIds.first()
    // The tone of the active view's content: its sheet, and the tab cut from it.
    val sheet = LocalSheetColor.current(active)
    var activeFlushStart by remember { mutableStateOf(false) }
    val sheetStartCorner by animateDpAsState(
        targetValue = if (activeFlushStart) 0.dp else SheetCorner,
        animationSpec = Motion.stateChange(),
        label = "sheet-start-corner",
    )
    Column(modifier.fillMaxSize().padding(horizontal = PaneGutter)) {
        PaneTabStrip(
            groupId = group.id,
            viewIds = group.viewIds,
            activeViewId = active,
            sheetColor = sheet,
            labelFont = labelFont,
            onActiveFlushStart = { activeFlushStart = it },
            titleFor = titleFor,
            // Name the group; do NOT hand back a rebuilt `group`. This lambda is
            // captured by the tab's pointer handler and can outlive this composition.
            onSelect = { viewId -> applyEdit { setActiveViewInGroup(it, group.id, viewId) } },
            dragState = dragState,
            onDrop = onDrop,
            onDragEndMiss = onDragEndMiss,
            addSlot = addSlot?.let { slot -> { slot(group.id) } },
            chrome = chrome,
            tabSlot = tabSlot ?: { itemId, state ->
                DefaultTabChip(itemId, titleFor(itemId), state, labelFont, onCloseView)
            },
        )
        // A group that leaves composition (workspace switch, session switch, a split
        // collapsing) must take its registered bounds with it. Otherwise resolveDrop
        // hit-tests against panes that are no longer on screen, matches a group id
        // absent from the current tree, and every drop silently no-ops.
        DisposableEffect(group.id) {
            onDispose { dragState.unregisterGroup(group.id) }
        }
        // Content always stays mounted. Drop preview is a translucent Popup so
        // it paints *above* SwingPanel (Compose siblings cannot).
        var paneSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = PaneGutter * 2)
                // The sheet: the content's own surface, rounded like a menu, in the frame — square
                // at the top-start under a first tab cut from it at the very edge.
                .clip(RoundedCornerShape(topStart = sheetStartCorner, topEnd = SheetCorner, bottomEnd = SheetCorner, bottomStart = SheetCorner))
                .background(sheet)
                .onGloballyPositioned { coords ->
                    dragState.registerPane(group.id, coords.boundsInRoot())
                }
                .onSizeChanged { paneSize = it },
        ) {
            content(active)
            val hoverThis = dragState.hoverPaneId == group.id
            if (hoverThis && paneSize.width > 0 && paneSize.height > 0) {
                val foreignDrag = dragState.originGroupId != null &&
                    dragState.originGroupId != group.id
                val density = LocalDensity.current
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset.Zero,
                    properties = PopupProperties(
                        focusable = false,
                        dismissOnBackPress = false,
                        dismissOnClickOutside = false,
                        clippingEnabled = false,
                    ),
                ) {
                    Box(
                        Modifier
                            .width(with(density) { paneSize.width.toDp() })
                            .height(with(density) { paneSize.height.toDp() }),
                    ) {
                        PaneDropOverlay(
                            activeZone = dragState.zoneOver(group.id),
                            edgesEnabled = foreignDrag || group.viewIds.size >= 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SplitHost(
    split: LayoutNode.Split,
    path: List<Int>,
    applyEdit: ((LayoutNode) -> LayoutNode) -> Unit,
    dragState: PaneDragController,
    onDrop: (PaneDropTarget) -> Unit,
    onDragEndMiss: (String) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    addSlot: (@Composable (String) -> Unit)?,
    emptyGroupSlot: (@Composable () -> Unit)?,
    tabSlot: (@Composable (String, TabSlotState) -> Unit)?,
    labelFont: FontFamily,
    chrome: PaneStripChrome,
    content: @Composable (String) -> Unit,
) {
    val currentSplit by rememberUpdatedState(split)
    val fit = LocalPaneAreaFit.current
    // (pending area fit, this split) captured at drag start; see onSeamDrag below.
    val dragStart = remember { mutableStateOf<Pair<LayoutNode?, LayoutNode.Split>?>(null) }
    // Reuse the existing ResizableSplit drag chrome rather than writing new
    // splitter hit-testing: it already handles the handle and the pointer
    // cursor, and it is the widget the rest of the app drags.
    PaneSplit(
        direction = split.direction,
        sizes = split.sizes,
        // Address this split by path and let the root apply it. Writing back
        // `split.copy(sizes = ...)` was the bug Ahmet hit: PaneSplit keys
        // its drag handler on `pointerInput(totalPx, index)`, so the handler kept
        // whichever `split` was current when it was last built, and every resize
        // restored that node's children — reviving a closed view or dropping a
        // new one.
        // The resize is computed here against the current node, then written
        // back as absolute per-path sizes: a delta would compound when a pending
        // edit is replayed onto the broker's echo. Nested same-direction splits
        // are included so only the dragged seam moves on screen.
        // A window/sidebar re-fit still pending is drawn but not yet in the tree:
        // commit it in the same edit, and resize from what is on screen.
        //
        // Every move is computed from the split AS IT WAS when the drag started plus the
        // pointer's total travel, then clamped — so dragging past a limit and back leaves
        // the seam pinned until the pointer reaches it again, instead of losing the overshoot.
        onSeamDragStart = { dragStart.value = fit?.adjusted to currentSplit },
        onSeamDrag = { seam, travel ->
            val (pending, own) = dragStart.value ?: (fit?.adjusted to currentSplit)
            val base = pending?.let { layoutNodeAt(it, path) as? LayoutNode.Split } ?: own
            val writes = (pending?.let { splitSizesByPath(it) } ?: emptyList()) +
                splitSizesByPath(resizeSplitSeam(base, seam, travel)).map { (rel, sizes) -> (path + rel) to sizes }
            applyEdit { root -> writes.fold(root) { t, (p, sizes) -> setSplitSizes(t, p, sizes) } }
        },
        liveSizes = { fit?.sizesAt(path) },
        modifier = modifier,
    ) { index ->
        PaneHostNode(
            layout = split.children[index],
            path = path + index,
            applyEdit = applyEdit,
            dragState = dragState,
            onDrop = onDrop,
            onDragEndMiss = onDragEndMiss,
            titleFor = titleFor,
            onCloseView = onCloseView,
            addSlot = addSlot,
            emptyGroupSlot = emptyGroupSlot,
            tabSlot = tabSlot,
            labelFont = labelFont,
            chrome = chrome,
            content = content,
            modifier = Modifier,
        )
    }
}

@Composable
fun PaneTabStrip(
    viewIds: List<String>,
    activeViewId: String,
    titleFor: (String) -> String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * "+" at the end of the strip, drawn by the caller. Null hides the button
     * entirely (tests and any caller that cannot create views).
     */
    addSlot: (@Composable () -> Unit)? = null,
    chrome: PaneStripChrome = PaneStripChrome.None,
    labelFont: FontFamily = FontFamily.Monospace,
    /** Overrides how one tab is drawn, as on [PaneHost]; null is [DefaultTabChip]. */
    tabSlot: (@Composable (itemId: String, state: TabSlotState) -> Unit)? = null,
) {
    // Back-compat for call sites that do not participate in drag (previews, older tests).
    PaneTabStrip(
        groupId = "",
        viewIds = viewIds,
        activeViewId = activeViewId,
        titleFor = titleFor,
        onSelect = onSelect,
        dragState = null,
        onDrop = {},
        modifier = modifier,
        addSlot = addSlot,
        chrome = chrome,
        tabSlot = tabSlot ?: { itemId, state -> DefaultTabChip(itemId, titleFor(itemId), state, labelFont, onClose) },
    )
}

/**
 * A row of tabs for a group's views.
 *
 * Tabs support press-and-drag reorder (and cross-strip / edge-split via the
 * shared [dragState]). A plain click still selects — movement below
 * [PANE_DRAG_THRESHOLD_PX] is treated as a click, not a drag. No animation on the
 * strip itself — it changes many times a day.
 *
 * Public rather than internal: this is the full-control entry point into a strip, and it is what
 * a caller assembling its own strip uses — the editor's file tabs will, once they have their own
 * tree. `PaneHost` is the convenience wrapper over a whole layout.
 *
 * There is no close callback here. This layer draws no chip of its own: it owns
 * each tab's position, size, gestures, and `view-tab-<id>` tag, and [tabSlot]
 * draws everything inside — label, colours, and whatever affordances that
 * content wants (see DefaultTabChip).
 */
@Composable
fun PaneTabStrip(
    groupId: String,
    viewIds: List<String>,
    activeViewId: String,
    titleFor: (String) -> String,
    onSelect: (String) -> Unit,
    dragState: PaneDragController?,
    onDrop: (PaneDropTarget) -> Unit,
    onDragEndMiss: (String) -> Unit = {},
    chrome: PaneStripChrome,
    tabSlot: @Composable (itemId: String, state: TabSlotState) -> Unit,
    modifier: Modifier = Modifier,
    addSlot: (@Composable () -> Unit)? = null,
    /** The active view's sheet below the strip: the active tab is cut from the same tone. */
    sheetColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    /** The tabs' label font: the active tab is as wide as its title needs, measured in it. */
    labelFont: FontFamily = FontFamily.Default,
    /**
     * Whether the active tab is the first and starts flush with the sheet's start edge (nothing,
     * such as window controls, sets the row in): its sheet's top-start corner squares off then, so
     * the tab's edge runs straight down into the sheet's.
     */
    onActiveFlushStart: (Boolean) -> Unit = {},
) = BoxWithConstraints(modifier.fillMaxWidth()) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val stripWidthPx = constraints.maxWidth.toFloat()
    // Unique, stable key for this strip's PaneStripChrome registrations (groupId can be "" on
    // back-compat call sites, and groups can recompose across workspaces).
    val chromeKey = remember { newPaneKey() }
    // Live shuffle while dragging (origin strip); otherwise committed order.
    val displayIds = dragState?.displayOrder(groupId, viewIds) ?: viewIds
    val showInsertCaret = dragState?.isDragging == true &&
        dragState.hoverStripGroupId == groupId &&
        dragState.draggingViewId !in viewIds
    val insertAt = dragState?.hoverInsertIndex ?: 0
    // Touch gets a + as roomy as its tab chips (see DefaultTabChip).
    val addSlotWidth = if (dev.supermux.ui.adaptive.LocalPointerAvailable.current) 36.dp else 64.dp
    val addSlotPx = if (addSlot != null) with(density) { addSlotWidth.toPx() } else 0f
    val rowPadPx = with(density) { TabRowPadding.toPx() }
    // Chrome's widths: the strip shared out equally between TabMinWidth and TabMaxWidth, and past
    // that it scrolls. While the pointer is on the strip, a closed tab's room is not handed out
    // (room is only taken, for a new tab), so the next tab's × stays under the pointer.
    // The active tab is as wide as its title needs (up to TabMaxWidth) when an equal share would
    // cut it, and the others split what is left.
    val minPx = with(density) { TabMinWidth.toPx() }
    val maxPx = with(density) { TabMaxWidth.toPx() }
    val availablePx = stripWidthPx - addSlotPx - rowPadPx
    val tabCount = displayIds.size.coerceAtLeast(1)
    val textMeasurer = rememberTextMeasurer()
    val activeTitle = titleFor(activeViewId)
    val activeWantPx = remember(activeTitle, labelFont, density) {
        val title = textMeasurer.measure(
            activeTitle,
            TextStyle(fontFamily = labelFont, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            softWrap = false,
        ).size.width
        (title + with(density) { TabChromeWidth.toPx() }).coerceIn(minPx, maxPx)
    }
    val equalPx = availablePx / tabCount
    val sharedPx = if (activeWantPx <= equalPx || tabCount == 1) {
        equalPx.coerceIn(minPx, maxPx)
    } else {
        ((availablePx - activeWantPx) / (tabCount - 1)).coerceIn(minPx, maxPx)
    }
    val stripHover = remember { MutableInteractionSource() }
    val stripHovered by stripHover.collectIsHoveredAsState()
    var heldPx by remember { mutableStateOf<Float?>(null) }
    val tabPx = heldPx?.let { minOf(it, sharedPx) } ?: sharedPx
    SideEffect { heldPx = if (stripHovered) tabPx else null }
    val activeTabPx = maxOf(tabPx, if (activeWantPx > equalPx) activeWantPx else tabPx)
    fun widthOf(id: String): Float = if (id == activeViewId) activeTabPx else tabPx
    fun targetXForIndex(index: Int): Float {
        var x = 0f
        for (i in 0 until index.coerceIn(0, displayIds.size)) x += widthOf(displayIds[i])
        return x
    }
    val stripContentWidthPx = rowPadPx + displayIds.sumOf { widthOf(it).toDouble() }.toFloat() + addSlotPx
    // How far the platform's window controls set the row in (the collapsed sidebar's band): none
    // means the first tab starts at the sheet's very edge.
    var rowInsetPx by remember { mutableStateOf(0f) }
    val activeHidden = dragState?.let { d -> (d.isDragging && d.draggingViewId == activeViewId) || d.settling?.viewId == activeViewId } == true
    val activeFlushStart = rowInsetPx < 1f && displayIds.firstOrNull() == activeViewId && !activeHidden
    SideEffect { onActiveFlushStart(activeFlushStart) }
    // The tab under the pointer: no separator beside it, and the ghost of a hover on it.
    var hoveredTab by remember { mutableStateOf<String?>(null) }
    val dragging = dragState?.isDragging == true

    val tearOffNow = rememberUpdatedState(LocalTabTearOff.current)
    val windowSizeNow = rememberUpdatedState(LocalWindowInfo.current.containerSize)
    // Read by the tab gestures at press time; they outlive the composition that built them.
    val viewIdsNow = rememberUpdatedState(viewIds)
    val activeNow = rememberUpdatedState(activeViewId)
    val soleViewNow = rememberUpdatedState(LocalSoleView.current)
    val scroll = rememberScrollState()
    // The strip's on-screen viewport, for edge auto-scroll. Read only by the effect below, never
    // by composition, so writing it on every placement recomposes nothing.
    val viewport = remember { mutableStateOf<Rect?>(null) }
    if (dragState != null && groupId.isNotEmpty()) {
        val edgePx = with(density) { StripAutoScrollEdge.toPx() }
        val maxStepPx = with(density) { StripAutoScrollMaxStep.toPx() }
        // A drag held near either end of an overflowing strip scrolls it, so a tab can be dropped
        // on a slot that is off screen. Frames are asked for only while the pointer is in an end
        // zone and the strip still moves — not for the whole of every drag.
        LaunchedEffect(dragState, groupId, edgePx, maxStepPx) {
            snapshotFlow { viewport.value?.let { dragState.stripAutoScrollStep(it, edgePx, maxStepPx) } ?: 0f }
                .collectLatest { first ->
                    if (first == 0f) return@collectLatest
                    while (true) {
                        withFrameNanos { }
                        val r = viewport.value ?: break
                        val step = dragState.stripAutoScrollStep(r, edgePx, maxStepPx)
                        if (step == 0f) break
                        val used = scroll.scrollBy(step)
                        if (used == 0f) break
                        if (dragState.originGroupId == groupId) dragState.originStripScrolled(used)
                    }
                }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(TAB_STRIP_HEIGHT)
            .hoverable(stripHover)
            .onGloballyPositioned { coords ->
                viewport.value = coords.boundsInRoot()
                if (groupId.isNotEmpty()) {
                    dragState?.registerStrip(groupId, coords.boundsInRoot())
                }
            }
            // Strip-level platform chrome (e.g. macOS native window-drag on the empty tail); the
            // tabs+"+" row below carries its own chrome.tabs(...) call so it is excluded from
            // this surface. A no-op unless the caller opts in — see PaneStripChrome.kt.
            .then(chrome.strip("strip-$chromeKey"))
            .testTag("view-tab-strip"),
    ) {
        // Absolute-positioned tabs: each id keeps composition identity and slides
        // (graphicsLayer translation) to its live-order slot — "transforms into"
        // the place the neighbour vacated.
        //
        // Scrolls (and clips) once the tabs are wider than the strip: horizontalScroll
        // measures its child with unbounded width, so the fixed-width Box below still
        // gets its full intrinsic width; this Box only scrolls + clips the viewport.
        Box(
            Modifier
                .fillMaxHeight()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = StripFade.toPx()
                    if (scroll.value > 0) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                            size = Size(fade, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    if (scroll.value < scroll.maxValue) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                            topLeft = Offset(size.width - fade, 0f),
                            size = Size(fade, size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .onGloballyPositioned { coords ->
                    val inset = coords.positionInRoot().x - (viewport.value?.left ?: coords.positionInRoot().x)
                    if (inset != rowInsetPx) rowInsetPx = inset
                }
                .horizontalScroll(scroll),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(with(density) { stripContentWidthPx.toDp().coerceAtLeast(1.dp) })
                    .then(chrome.tabs("strip-tabs-$chromeKey")),
            ) {
                // Compose in a stable order (committed viewIds) so keys never reshuffle
                // under the active pointerInput; visual order is purely animated X.
                val stableIds = viewIds.ifEmpty { displayIds }
                fun targetXForOrder(order: List<String>, index: Int): Float {
                    var x = 0f
                    for (i in 0 until index.coerceIn(0, order.size)) x += widthOf(order[i])
                    return x
                }
                for (id in stableIds) {
                    key(id) {
                        val isDragged = dragState?.isDragging == true && dragState.draggingViewId == id
                        // The held tab, and one settling after a drop, are drawn above the strips
                        // (PaneHost): here their slot stays empty.
                        val hidden = isDragged || dragState?.settling?.viewId == id
                        // Pin the dragged tab at its press-time slot so its coordinate
                        // system never rides the morph animation (that caused the
                        // reorder loop). Neighbours animate to live-order positions;
                        // the lifted copy is the moving visual.
                        val targetX = if (isDragged) {
                            val pin = viewIds.indexOf(id).let {
                                if (it >= 0) it else dragState?.dragOriginIndex ?: 0
                            }
                            targetXForOrder(viewIds, pin)
                        } else {
                            val live = displayIds.indexOf(id).let { if (it < 0) 0 else it }
                            targetXForOrder(displayIds, live)
                        }
                        val animatedX by animateFloatAsState(
                            targetValue = targetX,
                            // An empty slot jumps: nothing shows it moving, and the tab settling
                            // into it must find it there.
                            animationSpec = if (hidden) snap() else TabSlide,
                            label = "tab-morph-x-$id",
                        )
                        val selected = id == activeViewId
                        val animatedWidth by animateFloatAsState(
                            targetValue = widthOf(id),
                            animationSpec = if (hidden) snap() else TabSlide,
                            label = "tab-width-$id",
                        )
                        val z = when {
                            hidden -> 0f
                            selected -> 1.5f
                            else -> 1f
                        }
                        val hover = remember { MutableInteractionSource() }
                        val tabHovered by hover.collectIsHoveredAsState()
                        LaunchedEffect(hover) {
                            hover.interactions.collect {
                                when (it) {
                                    is HoverInteraction.Enter -> hoveredTab = id
                                    is HoverInteraction.Exit -> if (hoveredTab == id) hoveredTab = null
                                }
                            }
                        }
                        val at = displayIds.indexOf(id)
                        val next = displayIds.getOrNull(at + 1)
                        // Hairlines between quiet tabs only: none beside the active or the hovered
                        // tab, after the last, or while a tab is being dragged.
                        val separator = !hidden && !dragging && next != null &&
                            id != activeViewId && next != activeViewId &&
                            id != hoveredTab && next != hoveredTab
                        val ghost = cs.onSurface.copy(alpha = 0.065f)
                        val hairline = cs.onSurface.copy(alpha = 0.13f)
                        // Where this tab rests, for one settling into it: its drawn bounds less
                        // the slide still to go. Published after every composition (a new slot
                        // from a drop) and every placement (a slide moving it).
                        val placed = remember { PlacedTab() }
                        fun publishSlot() {
                            val coords = placed.coords?.takeIf { it.isAttached } ?: return
                            if (groupId.isEmpty()) return
                            dragState?.registerSlot(groupId, id, coords.boundsInRoot().translate(targetX - animatedX, 0f))
                        }
                        SideEffect { publishSlot() }
                        if (dragState != null && groupId.isNotEmpty()) {
                            DisposableEffect(groupId, id) { onDispose { dragState.unregisterSlot(groupId, id) } }
                        }
                        Box(
                            Modifier
                                .zIndex(z)
                                .graphicsLayer { translationX = animatedX }
                                .width(with(density) { animatedWidth.toDp() })
                                .fillMaxHeight()
                                .onGloballyPositioned { coords ->
                                    placed.coords = coords
                                    if (groupId.isNotEmpty()) {
                                        dragState?.registerTab(groupId, id, coords.boundsInRoot())
                                    }
                                    publishSlot()
                                }
                                .then(
                                    if (dragState != null && groupId.isNotEmpty()) {
                                        Modifier.tabDragGestures(
                                            viewId = id,
                                            groupId = groupId,
                                            stripOrderProvider = { viewIdsNow.value },
                                            activeProvider = { activeNow.value },
                                            labelProvider = { titleFor(id) },
                                            dragState = dragState,
                                            onSelect = onSelect,
                                            onDrop = onDrop,
                                            onDragEndMiss = onDragEndMiss,
                                            tearOff = { tearOffNow.value },
                                            windowSize = { windowSizeNow.value },
                                            soleView = { soleViewNow.value },
                                        )
                                    } else {
                                        Modifier.clickable { onSelect(id) }
                                    },
                                )
                                // Fully hide the source slot — the lifted copy is the moving tab.
                                // Keeping a dimmed chip that also translated was feeding
                                // pointer local-coords and looping the live order.
                                //
                                // The slot's background is now INSIDE this alpha layer, where it
                                // was outside before the chip moved out of this chain. That makes
                                // the hide complete: dragging the ACTIVE tab used to leave its
                                // 0.14α primary tint painted at the source slot, because
                                // Modifier.alpha only fades what is inner to it. "Fully hide"
                                // above was always the intent; it is now also the behaviour.
                                .alpha(if (hidden) 0f else 1f)
                                .hoverable(hover)
                                .drawBehind {
                                    when {
                                        hidden -> Unit
                                        selected -> drawSheetTab(sheetColor, leftFlare = !(at == 0 && rowInsetPx < 1f))
                                        tabHovered && !dragging -> drawRoundRect(
                                            color = ghost,
                                            topLeft = Offset(1.dp.toPx(), TabTopInset.toPx() + 2.dp.toPx()),
                                            size = Size(size.width - 2.dp.toPx(), size.height - TabTopInset.toPx() - 5.dp.toPx()),
                                            cornerRadius = CornerRadius(8.dp.toPx()),
                                        )
                                    }
                                    if (separator) {
                                        drawRect(
                                            color = hairline,
                                            topLeft = Offset(size.width - 0.5.dp.toPx(), TabTopInset.toPx() + 8.dp.toPx()),
                                            size = Size(1.dp.toPx(), 14.dp.toPx()),
                                        )
                                    }
                                }
                                .testTag("view-tab-$id")
                                // The tab's 28 dp, at the strip's bottom where it meets its sheet.
                                .padding(top = TabTopInset),
                            // The slot fills the tab, background and all.
                            propagateMinConstraints = true,
                        ) {
                            tabSlot(id, TabSlotState(selected = selected, dragged = hidden))
                        }
                    }
                }

                if (showInsertCaret) {
                    val caretX = targetXForIndex(insertAt.coerceIn(0, displayIds.size))
                    val animatedCaretX by animateFloatAsState(
                        targetValue = caretX,
                        animationSpec = TabSlide,
                        label = "tab-insert-caret-x",
                    )
                    TabInsertCaret(
                        Modifier
                            .zIndex(3f)
                            .graphicsLayer { translationX = animatedCaretX },
                    )
                }

                if (addSlot != null) {
                    val addTargetX = targetXForIndex(displayIds.size)
                    val animatedAddX by animateFloatAsState(
                        targetValue = addTargetX,
                        animationSpec = TabSlide,
                        label = "tab-add-x",
                    )
                    Box(
                        Modifier
                            .zIndex(0.5f)
                            .graphicsLayer { translationX = animatedAddX }
                            .fillMaxHeight()
                            .padding(top = TabTopInset)
                            .width(addSlotWidth),
                    ) {
                        addSlot()
                    }
                }
            }
        }
    }
}

@Composable
private fun TabInsertCaret(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .padding(horizontal = 1.dp)
            .width(2.dp)
            .fillMaxHeight(0.7f)
            .background(cs.primary, RoundedCornerShape(1.dp))
            .testTag("tab-insert-caret"),
    )
}

/**
 * Press-to-drag on a tab, matching the session-list drag reorder:
 * mouse-friendly grab after a small move, click when movement stays under the
 * threshold. Drop resolution is delegated to [PaneDragController] so cross-strip and
 * edge targets share one pointer.
 *
 * A mouse drags and selects with its PRIMARY button only: a secondary press belongs to the tab's
 * context menu and a middle press to nothing here, so neither moves nor selects the tab.
 *
 * A finger (any non-mouse pointer) must HOLD the tab first. A finger that moves before the
 * long-press timeout is swiping, and the gesture is left untouched so the strip's
 * horizontalScroll takes it — otherwise an overflowing strip could not be scrolled by touch at
 * all, since every swipe began on some tab. A finger that lets go before it is a tap.
 *
 * With a [tearOff] host (desktop), a mouse drag can leave this layer: the host takes over the
 * window's only tab as soon as it moves (a window that can, follows the pointer), and otherwise a
 * tab once the pointer is [TearOffMargin] past the window's edge — or, for the window's only tab,
 * [TearOffMargin] above or below its strip, since nowhere else in the window can take it — and
 * opens it in a window of its own. Taken over, the drag ends here — no drop, no click — and the
 * host carries the press. A pull sideways out of the strip (toward a sidebar) stays here.
 */
private fun Modifier.tabDragGestures(
    viewId: String,
    groupId: String,
    stripOrderProvider: () -> List<String>,
    activeProvider: () -> String,
    labelProvider: () -> String,
    dragState: PaneDragController,
    onSelect: (String) -> Unit,
    onDrop: (PaneDropTarget) -> Unit,
    onDragEndMiss: (String) -> Unit = {},
    tearOff: () -> TabTearOff? = { null },
    windowSize: () -> IntSize = { IntSize.Zero },
    soleView: () -> String? = { null },
): Modifier = this
    // performClick() uses the semantics onClick action, not a real pointer stream.
    .semantics {
        onClick(label = "Select tab") {
            onSelect(viewId)
            true
        }
    }
    // Keys: only viewId + groupId. stripOrder/label are read at press so a live
    // reorder recompose does not cancel this coroutine and freeze the drag.
    .pointerInput(viewId, groupId) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val mouse = down.type == PointerType.Mouse
            if (mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
            val touch = !mouse
            val slop = viewConfiguration.touchSlop
            var downLocal = down.position
            if (touch) {
                // `withTimeoutOrNull` is AwaitPointerEventScope's own: null means the finger was
                // HELD in place for the whole timeout. Nothing is consumed while waiting.
                val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var end: TouchEnd? = null
                    while (end == null) {
                        val change = awaitPointerEvent(PointerEventPass.Main).changes
                            .firstOrNull { it.id == down.id }
                        end = when {
                            change == null -> TouchEnd.Swipe
                            change.changedToUpIgnoreConsumed() -> TouchEnd.Tap
                            (change.position - down.position).getDistance() > slop -> TouchEnd.Swipe
                            else -> { downLocal = change.position; null }
                        }
                    }
                    end
                }
                when (endedEarly) {
                    TouchEnd.Tap -> { onSelect(viewId); return@awaitEachGesture }
                    TouchEnd.Swipe -> return@awaitEachGesture
                    null -> Unit
                }
            }
            val boundsAtDown = dragState.tabBoundsFor(groupId, viewId)
            val startRoot = if (boundsAtDown != null) {
                Offset(boundsAtDown.left + downLocal.x, boundsAtDown.top + downLocal.y)
            } else {
                Offset(downLocal.x, downLocal.y)
            }
            val stripOrder = stripOrderProvider()
            // The group's active tab before this press: a drag makes the dragged tab active (below),
            // and Esc on a torn-off tab puts this one back.
            val activeAtPress = activeProvider()
            dragState.begin(
                viewId = viewId,
                groupId = groupId,
                rootPos = startRoot,
                stripOrder = stripOrder,
                label = labelProvider(),
                bounds = boundsAtDown,
            )
            var passed = false
            // Accumulate pointer deltas into root space. Do NOT recompute from
            // local position on a translating tab — that re-fed the live order
            // and looped the strip.
            var pointerRoot = startRoot
            // A finger past its hold uses the platform slop: a raw 8 px is under 3 dp on a phone.
            val threshold = if (touch) slop else PANE_DRAG_THRESHOLD_PX
            val marginPx = TearOffMargin.toPx()
            // A host that would not take the drag out of the window is not asked again this press.
            var outsideRefused = false
            fun handoff() = TabHandoff(
                viewId = viewId,
                label = dragState.label,
                groupId = groupId,
                index = stripOrder.indexOf(viewId).coerceAtLeast(0),
                activeViewId = activeAtPress,
                grab = dragState.grab,
                tabSize = boundsAtDown?.size ?: Size(56.dp.toPx(), 32.dp.toPx()),
                groupSize = dragState.groupAreaOf(groupId)?.size ?: Size.Zero,
                pointer = pointerRoot,
            )

            // `finally`: a tab that leaves composition mid-drag cancels this coroutine, and the
            // shared controller must not keep a ghost for a drag nobody is finishing.
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    // Esc ended the drag: the rest of this press is nobody's.
                    if (dragState.aborted) break
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.changedToUpIgnoreConsumed()) {
                        if (!passed) {
                            dragState.cancel()
                            onSelect(viewId)
                        } else {
                            val target = dragState.finish()
                            when {
                                target != null -> onDrop(target)
                                // Only a host without live tear-off still gets the old verb, and
                                // only for a release past the window's edge.
                                tearOff() == null && outsideWindow(pointerRoot, windowSize(), 0f) ->
                                    onDragEndMiss(viewId)
                            }
                        }
                        break
                    }
                    if (change.pressed) {
                        pointerRoot += change.positionChange()
                        dragState.updatePointer(pointerRoot, startRoot, threshold)
                        val starting = !passed && dragState.pastThreshold
                        if (dragState.pastThreshold) passed = true
                        // The tab you move is the tab you're on, as in a browser. Only once it moves:
                        // a press on its × must not switch to it first.
                        if (starting) onSelect(viewId)
                        // A held finger owns the gesture: the strip must not start scrolling under it.
                        if (passed || touch) change.consume()
                        val host = if (mouse && passed) tearOff() else null
                        if (host != null) {
                            val takenOver = when {
                                starting && host.takeOverOnStart(handoff()) -> true
                                !outsideRefused && (
                                    outsideWindow(pointerRoot, windowSize(), marginPx) ||
                                        (soleView() == viewId && pulledOutOfStrip(pointerRoot, dragState.stripBoundsOf(groupId), marginPx))
                                    ) ->
                                    host.takeOverOutside(handoff()).also { outsideRefused = !it }
                                else -> false
                            }
                            if (takenOver) {
                                dragState.cancel()
                                break
                            }
                        }
                    }
                }
            } finally {
                if (dragState.draggingViewId == viewId) {
                    dragState.cancel()
                }
            }
        }
    }

/** Whether [p] (root px) is more than [margin] px above or below [strip], within its width. */
private fun pulledOutOfStrip(p: Offset, strip: Rect?, margin: Float): Boolean =
    strip != null && p.x >= strip.left && p.x <= strip.right &&
        (p.y < strip.top - margin || p.y > strip.bottom + margin)

/** Whether [p] (root px) is more than [margin] px outside a window of [size]. False while the size is unknown. */
private fun outsideWindow(p: Offset, size: IntSize, margin: Float): Boolean =
    size.width > 0 && size.height > 0 &&
        (p.x < -margin || p.y < -margin || p.x >= size.width + margin || p.y >= size.height + margin)

/**
 * How far past the window's edge a mouse drag has to go before the tab is torn off into a window
 * of its own. Some distance, not the edge itself: a drop on an edge pane's outer zone often
 * overshoots the window by a few points, and a strip at the window's edge must not open and close
 * a window as the pointer wobbles across the line.
 */
private val TearOffMargin = 16.dp

/** A tab giving way to the held one, or closing a gap: quick, and never past its slot. */
private val TabSlide = Motion.stateChange<Float>()

/** Where a tab was last placed, for [PaneTabStrip]'s slot bookkeeping. */
private class PlacedTab {
    var coords: LayoutCoordinates? = null
}

/** How close to a strip's end a held drag starts scrolling it, and its fastest step per frame. */
private val StripAutoScrollEdge = 40.dp
private val StripAutoScrollMaxStep = 12.dp

/** The tear-off seam [PaneHost] hands its strips; null outside a PaneHost that has one. */
private val LocalTabTearOff = staticCompositionLocalOf<TabTearOff?> { null }

/** The one view a [PaneHost] shows, when it shows exactly one; null otherwise. */
private val LocalSoleView = compositionLocalOf<String?> { null }

/** How a finger's press on a tab ended before it was held long enough to drag. */
private enum class TouchEnd { Tap, Swipe }

/**
 * N-pane generalisation of [ResizableSplit]. [n] children means [n]-1 splitters,
 * tagged `splitter-0` … `splitter-(n-2)`. A drag on splitter [i] reports
 * [onSeamDragStart] (i) and then [onSeamDrag] (i, the pointer's TOTAL travel since the
 * press, as a fraction of this split's extent); the caller
 * decides the new sizes — see [resizeSplitSeam], which also keeps nested seams
 * in place.
 *
 * Same seam model as [SidebarDivider] / [ResizableSplit]: panes abut (no layout
 * gap); overlay hairlines sit on cumulative fraction boundaries.
 */
@Composable
fun PaneSplit(
    direction: String,
    sizes: List<Double>,
    onSeamDrag: (seam: Int, totalFraction: Double) -> Unit,
    modifier: Modifier = Modifier,
    onSeamDragStart: (seam: Int) -> Unit = {},
    /**
     * Sizes to draw instead of [sizes], read in the LAYOUT pass — how a pending
     * whole-area re-fit ([PaneAreaFit]) reaches this split in the same frame the
     * area was resized. Null or a wrong-length list falls back to [sizes].
     */
    liveSizes: () -> List<Double>? = { null },
    child: @Composable (index: Int) -> Unit,
) {
    val n = sizes.size
    if (n <= 0) return
    if (n == 1) {
        Box(modifier.fillMaxSize()) { child(0) }
        return
    }

    val horizontal = direction == "row"
    // Read by the seams' drag handlers, which outlive recompositions; only used to
    // turn a pixel drag into a fraction, so the value from the last layout is fine.
    val totalPx = remember { IntArray(1) }
    // The seam's drag handler lives in a long-lived `pointerInput`, so it is
    // not rebuilt when the tree changes. Anything it captures directly goes
    // stale, so the callback goes through rememberUpdatedState, or a caller that
    // closes over tree state sends yesterday's tree.
    val currentOnSeamDrag by rememberUpdatedState(onSeamDrag)
    val currentOnSeamDragStart by rememberUpdatedState(onSeamDragStart)
    val sizesState = rememberUpdatedState(sizes)
    val liveState = rememberUpdatedState(liveSizes)

    // One layout for panes AND seams: pane boundaries and seam positions come
    // from the same numbers in the same pass, so a seam can never sit a frame
    // (or a rounding pixel) away from the edge it resizes.
    Layout(
        content = {
            for (i in 0 until n) {
                Box { child(i) }
            }
            for (i in 0 until n - 1) {
                SplitSeamOverlay(
                    horizontal = horizontal,
                    onDragStart = { currentOnSeamDragStart(i) },
                    onDragPx = { travelPx ->
                        val total = totalPx[0]
                        if (total > 0) currentOnSeamDrag(i, (travelPx / total).toDouble())
                    },
                    testTag = "splitter-$i",
                    // The gutter between two sheets is the seam; the line only shows to be grabbed.
                    idleColor = Color.Transparent,
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val extent = if (horizontal) w else h
        totalPx[0] = extent
        val fractions = liveState.value()?.takeIf { it.size == n } ?: sizesState.value
        val sum = fractions.sum().takeIf { it > 0.0 } ?: 1.0
        // Boundaries rounded from cumulative fractions: pane i spans edges[i]..edges[i+1].
        val edges = IntArray(n + 1)
        var cum = 0.0
        for (i in 0 until n) {
            cum += fractions[i]
            edges[i + 1] = if (i == n - 1) extent else (extent * cum / sum).roundToInt().coerceIn(edges[i], extent)
        }
        val panes = (0 until n).map { i ->
            val len = edges[i + 1] - edges[i]
            measurables[i].measure(
                if (horizontal) Constraints.fixed(len, h) else Constraints.fixed(w, len),
            )
        }
        val seams = (0 until n - 1).map { i ->
            measurables[n + i].measure(
                if (horizontal) Constraints(maxWidth = w, minHeight = h, maxHeight = h)
                else Constraints(minWidth = w, maxWidth = w, maxHeight = h),
            )
        }
        layout(w, h) {
            panes.forEachIndexed { i, p ->
                if (horizontal) p.place(edges[i], 0) else p.place(0, edges[i])
            }
            seams.forEachIndexed { i, p ->
                val at = edges[i + 1] - (if (horizontal) p.width else p.height) / 2
                if (horizontal) p.place(at, 0, zIndex = 20f) else p.place(0, at, zIndex = 20f)
            }
        }
    }
}
