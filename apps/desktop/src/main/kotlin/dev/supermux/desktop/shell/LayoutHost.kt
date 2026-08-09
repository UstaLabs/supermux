package dev.supermux.desktop.shell

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import dev.supermux.desktop.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Motion
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.moveViewToGroup
import dev.supermux.workspace.normalizeLayout
import dev.supermux.workspace.reorderWithinGroup
import dev.supermux.workspace.setActiveViewInGroup
import dev.supermux.workspace.setSplitSizes
import dev.supermux.workspace.splitGroup
import java.util.UUID
import kotlin.math.roundToInt

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
 * optimisation: JediTerm and KCEF are heavyweight AWT SwingPanel children, and
 * one live KCEF per background tab would exhaust memory.
 *
 * While a tab drag targets a pane body, content stays mounted and a translucent
 * [DropZoneOverlay] is shown in a [Popup] above SwingPanel (JediTerm / KCEF).
 * Compose siblings cannot paint over heavyweight children — Popup can.
 */
@Composable
fun LayoutHost(
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
     * "+" on each group's tab strip. Receives the GROUP the user clicked in, so a
     * new view lands as a tab in that group rather than somewhere arbitrary.
     * Null hides the button.
     */
    onAddView: ((groupId: String, kind: NewViewKind, placement: NewViewPlacement) -> Unit)? = null,
    /**
     * Shared drag state so the sidebar can register workspace-row drop targets.
     * When null, [LayoutHost] owns a private instance (standalone / tests).
     */
    dragState: TabDragState? = null,
    /**
     * Cross-workspace drop (sidebar row). Spec §9.4 — session workdir is unchanged.
     * Null ignores workspace drops.
     */
    onMoveToWorkspace: ((viewId: String, toWorkspaceId: String) -> Unit)? = null,
    content: @Composable (viewId: String) -> Unit,
) {
    val ownedDrag = remember { TabDragState() }
    val drag = dragState ?: ownedDrag
    val layoutState = rememberUpdatedState(layout)
    val onLayoutChangeState = rememberUpdatedState(onLayoutChange)
    val onEditState = rememberUpdatedState(onEdit)
    val onMoveToWorkspaceState = rememberUpdatedState(onMoveToWorkspace)

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

    fun applyDrop(target: TabDropTarget) {
        when (target) {
            is TabDropTarget.MoveToWorkspace -> {
                onMoveToWorkspaceState.value?.invoke(target.viewId, target.toWorkspaceId)
                return
            }
            else -> Unit
        }
        // Minted once, outside the transform: a replay must land on the same group
        // id, not invent a new one each time the edit is rebased onto a frame.
        val newGroupId = UUID.randomUUID().toString()
        applyEdit { tree ->
            when (target) {
                is TabDropTarget.Reorder ->
                    reorderWithinGroup(tree, target.groupId, target.viewId, target.index)
                is TabDropTarget.MoveToGroup ->
                    moveViewToGroup(tree, target.viewId, target.toGroupId, target.index) ?: tree
                is TabDropTarget.Split -> {
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
                is TabDropTarget.MoveToWorkspace -> tree // handled above
            }
        }
    }

    // Root box owns the floating drag ghost (session-list style). Ghost coords are
    // root-space; subtract this box's origin so the chip tracks the pointer.
    var hostRoot by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .onGloballyPositioned { hostRoot = it.positionInRoot() },
    ) {
        LayoutHostNode(
            layout = layout,
            path = emptyList(),
            applyEdit = { applyEdit(it) },
            dragState = drag,
            onDrop = { applyDrop(it) },
            modifier = Modifier.fillMaxSize(),
            titleFor = titleFor,
            onCloseView = onCloseView,
            onAddView = onAddView,
            content = content,
        )
        val g = drag.ghost
        if (drag.isDragging && g != null && g.visible) {
            TabDragGhostChip(
                label = g.label,
                widthPx = g.width,
                heightPx = g.height,
                modifier = Modifier
                    .zIndex(20f)
                    .offset {
                        IntOffset(
                            (g.x - hostRoot.x).toInt(),
                            (g.y - hostRoot.y).toInt(),
                        )
                    }
                    .testTag("tab-drag-ghost"),
            )
        }
    }
}

/** Floating tab chip that follows the pointer during a drag. */
@Composable
private fun TabDragGhostChip(
    label: String,
    widthPx: Float,
    heightPx: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .width(with(density) { widthPx.toDp() })
            .height(with(density) { heightPx.toDp().coerceAtLeast(28.dp) }),
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.45f)),
        color = cs.surfaceContainerHigh,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                color = cs.primary,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
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
private fun LayoutHostNode(
    layout: LayoutNode,
    path: List<Int>,
    applyEdit: ((LayoutNode) -> LayoutNode) -> Unit,
    dragState: TabDragState,
    onDrop: (TabDropTarget) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    onAddView: ((String, NewViewKind, NewViewPlacement) -> Unit)?,
    content: @Composable (String) -> Unit,
) {
    when (layout) {
        is LayoutNode.Group -> GroupHost(
            layout, applyEdit, dragState, onDrop, modifier, titleFor, onCloseView, onAddView, content,
        )
        is LayoutNode.Split -> SplitHost(
            layout, path, applyEdit, dragState, onDrop, modifier, titleFor, onCloseView, onAddView, content,
        )
    }
}

@Composable
private fun GroupHost(
    group: LayoutNode.Group,
    applyEdit: ((LayoutNode) -> LayoutNode) -> Unit,
    dragState: TabDragState,
    onDrop: (TabDropTarget) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    onAddView: ((String, NewViewKind, NewViewPlacement) -> Unit)?,
    content: @Composable (String) -> Unit,
) {
    if (group.viewIds.isEmpty()) {
        // A workspace whose last view just closed. Valid, not an error (spec §9.3
        // answer 3: the workspace stays open).
        Box(modifier.fillMaxSize().testTag("layout-empty"), contentAlignment = Alignment.Center) {
            EmptyWorkspaceHint()
        }
        return
    }
    val active = group.activeViewId ?: group.viewIds.first()
    Column(modifier.fillMaxSize()) {
        ViewTabStrip(
            groupId = group.id,
            viewIds = group.viewIds,
            activeViewId = active,
            titleFor = titleFor,
            // Name the group; do NOT hand back a rebuilt `group`. This lambda is
            // captured by the tab's pointer handler and can outlive this composition.
            onSelect = { viewId -> applyEdit { setActiveViewInGroup(it, group.id, viewId) } },
            onClose = onCloseView,
            dragState = dragState,
            onDrop = onDrop,
            onAddView = onAddView?.let { add -> { kind, place -> add(group.id, kind, place) } },
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
                        DropZoneOverlay(
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
    dragState: TabDragState,
    onDrop: (TabDropTarget) -> Unit,
    modifier: Modifier,
    titleFor: (String) -> String,
    onCloseView: (String) -> Unit,
    onAddView: ((String, NewViewKind, NewViewPlacement) -> Unit)?,
    content: @Composable (String) -> Unit,
) {
    // Reuse the existing ResizableSplit drag chrome rather than writing new
    // splitter hit-testing: it already handles the handle and the pointer
    // cursor, and it is the widget the rest of the app drags.
    ResizableSplitN(
        direction = split.direction,
        sizes = split.sizes,
        // Address this split by path and let the root apply it. Writing back
        // `split.copy(sizes = ...)` was the bug Ahmet hit: ResizableSplitN keys
        // its drag handler on `pointerInput(totalPx, index)`, so the handler kept
        // whichever `split` was current when it was last built, and every resize
        // restored that node's children — reviving a closed view or dropping a
        // new one.
        onSizesChange = { next -> applyEdit { setSplitSizes(it, path, next) } },
        modifier = modifier,
    ) { index ->
        LayoutHostNode(
            layout = split.children[index],
            path = path + index,
            applyEdit = applyEdit,
            dragState = dragState,
            onDrop = onDrop,
            titleFor = titleFor,
            onCloseView = onCloseView,
            onAddView = onAddView,
            content = content,
            modifier = Modifier,
        )
    }
}

/** Hint for a group with no views (workspace still open, last view closed). */
@Composable
internal fun EmptyWorkspaceHint() {
    val cs = MaterialTheme.colorScheme
    Text(
        "This workspace has no open views",
        color = cs.onSurfaceVariant,
        fontSize = 13.sp,
    )
}

/**
 * A row of tabs for a group's views. Each tab is a clickable label plus a close
 * affordance tagged `tab-close-<viewId>`. The active tab uses the teal primary
 * accent. No animation — this strip changes many times a day.
 *
 * Tabs support press-and-drag reorder (and cross-strip / edge-split via the
 * shared [dragState]). A plain click still selects — movement below
 * [TAB_DRAG_THRESHOLD_PX] is treated as a click, not a drag.
 */
/** The view kinds the "+" offers. Order is the order they appear in the popover. */
enum class NewViewKind(val wire: String, val label: String) {
    CHAT("chat", "Chat"),
    TERMINAL("terminal", "Terminal"),
    EDITOR("editor", "Editor"),
    DISPLAY("display", "Display"),
}

/**
 * Where a new view lands relative to the pane its "+" was clicked in.
 *
 * The "+" menu always uses [HERE] (tab in this pane). [SPLIT_RIGHT] / [SPLIT_DOWN]
 * remain for callers that still create splits programmatically; users split by
 * dragging a tab to a pane edge instead of a second menu step.
 */
enum class NewViewPlacement(val label: String) {
    HERE("In this pane"),
    SPLIT_RIGHT("Split right"),
    SPLIT_DOWN("Split down"),
}

/** "+" popover: pick a view kind; always lands as a tab in this pane. */
@Composable
private fun KindMenuItems(onPick: (NewViewKind) -> Unit) {
    for (k in NewViewKind.entries) {
        DropdownMenuItem(
            text = { Text(k.label, fontSize = 12.sp) },
            onClick = { onPick(k) },
            modifier = Modifier.testTag("tab-add-view-${k.wire}"),
        )
    }
}

@Composable
fun ViewTabStrip(
    viewIds: List<String>,
    activeViewId: String,
    titleFor: (String) -> String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * "+" at the end of the strip. Opens a popover of view kinds; picking one
     * adds it as a tab in THIS group ([NewViewPlacement.HERE]). Null hides the
     * button entirely (tests and any caller that cannot create views).
     */
    onAddView: ((NewViewKind, NewViewPlacement) -> Unit)? = null,
) {
    // Back-compat for call sites that do not participate in drag (previews, older tests).
    ViewTabStrip(
        groupId = "",
        viewIds = viewIds,
        activeViewId = activeViewId,
        titleFor = titleFor,
        onSelect = onSelect,
        onClose = onClose,
        dragState = null,
        onDrop = {},
        modifier = modifier,
        onAddView = onAddView,
    )
}

@Composable
internal fun ViewTabStrip(
    groupId: String,
    viewIds: List<String>,
    activeViewId: String,
    titleFor: (String) -> String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    dragState: TabDragState?,
    onDrop: (TabDropTarget) -> Unit,
    modifier: Modifier = Modifier,
    onAddView: ((NewViewKind, NewViewPlacement) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // Distinguishes this strip's macOS drag-region registrations (groupId can be "" on
    // back-compat call sites, and groups can recompose across workspaces).
    val chromeKey = remember { java.util.UUID.randomUUID().toString() }
    // Live shuffle while dragging (origin strip); otherwise committed order.
    val displayIds = dragState?.displayOrder(groupId, viewIds) ?: viewIds
    val showInsertCaret = dragState?.isDragging == true &&
        dragState.hoverStripGroupId == groupId &&
        dragState.draggingViewId !in viewIds
    val insertAt = dragState?.hoverInsertIndex ?: 0
    // Measured tab widths (px) so each tab can animate to a true pixel target —
    // they transform through each other on reorder instead of list-reflow.
    val tabWidthsPx = remember { mutableStateMapOf<String, Float>() }
    val minTabPx = with(density) { 56.dp.toPx() }
    fun widthOf(id: String) = tabWidthsPx[id]?.coerceAtLeast(minTabPx) ?: minTabPx
    fun targetXForIndex(index: Int): Float {
        var x = 0f
        for (i in 0 until index.coerceAtLeast(0)) {
            val id = displayIds.getOrNull(i) ?: break
            x += widthOf(id)
        }
        return x
    }
    val stripContentWidthPx = displayIds.sumOf { widthOf(it).toDouble() }.toFloat() +
        if (onAddView != null) with(density) { 36.dp.toPx() } else 0f

    Box(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(cs.surfaceContainerLow)
            .onGloballyPositioned { coords ->
                if (groupId.isNotEmpty()) {
                    dragState?.registerStrip(groupId, coords.boundsInRoot())
                }
            }
            // macOS: the strip's EMPTY TAIL is a native window-drag handle (browser-tab-bar
            // behavior); the tabs+"+" row below punches itself out of the region, so dragging a
            // tab never moves the window. No-op off macOS/JBR (see MacWindowChrome.kt).
            .macTitleBarDragRegion("strip-$chromeKey")
            .testTag("view-tab-strip"),
    ) {
        // Absolute-positioned tabs: each id keeps composition identity and slides
        // (graphicsLayer translation) to its live-order slot — "transforms into"
        // the place the neighbour vacated.
        Box(
            Modifier
                .fillMaxHeight()
                .width(with(density) { stripContentWidthPx.toDp().coerceAtLeast(1.dp) })
                .macTitleBarNoDragRegion("strip-tabs-$chromeKey"),
        ) {
            // Compose in a stable order (committed viewIds) so keys never reshuffle
            // under the active pointerInput; visual order is purely animated X.
            val stableIds = viewIds.ifEmpty { displayIds }
            fun targetXForOrder(order: List<String>, index: Int): Float {
                var x = 0f
                for (i in 0 until index.coerceAtLeast(0)) {
                    val tid = order.getOrNull(i) ?: break
                    x += widthOf(tid)
                }
                return x
            }
            for (id in stableIds) {
                key(id) {
                    val isDragged = dragState?.isDragging == true && dragState.draggingViewId == id
                    // Pin the dragged tab at its press-time slot so its coordinate
                    // system never rides the morph animation (that caused the
                    // reorder loop). Neighbours animate to live-order positions;
                    // the ghost is the moving visual.
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
                        animationSpec = Motion.spatial(),
                        label = "tab-morph-x-$id",
                    )
                    val selected = id == activeViewId
                    val bg = if (selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent
                    val fg = if (selected) cs.primary else cs.onSurfaceVariant
                    val z = if (isDragged) 0f else 1f
                    Row(
                        Modifier
                            .zIndex(z)
                            .graphicsLayer { translationX = animatedX }
                            // Min width keeps the geometric centre on the label so
                            // performClick("view-tab-x") selects instead of hitting ×.
                            .defaultMinSize(minWidth = 56.dp)
                            .fillMaxHeight()
                            .onSizeChanged { tabWidthsPx[id] = it.width.toFloat() }
                            .onGloballyPositioned { coords ->
                                if (groupId.isNotEmpty()) {
                                    dragState?.registerTab(groupId, id, coords.boundsInRoot())
                                }
                            }
                            .then(
                                if (dragState != null && groupId.isNotEmpty()) {
                                    Modifier.tabDragGestures(
                                        viewId = id,
                                        groupId = groupId,
                                        stripOrderProvider = { viewIds },
                                        labelProvider = { titleFor(id) },
                                        dragState = dragState,
                                        onSelect = onSelect,
                                        onDrop = onDrop,
                                    )
                                } else {
                                    Modifier.clickable { onSelect(id) }
                                },
                            )
                            .background(bg)
                            .padding(start = 14.dp, end = 8.dp)
                            // Fully hide the source slot — ghost is the moving tab.
                            // Keeping a dimmed chip that also translated was feeding
                            // pointer local-coords and looping the live order.
                            .alpha(if (isDragged) 0f else 1f)
                            .testTag("view-tab-$id"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = titleFor(id),
                            color = fg,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        )
                        Box(
                            Modifier
                                .size(16.dp)
                                .clickable { onClose(id) }
                                .alpha(if (selected) 0.85f else 0.5f)
                                .testTag("tab-close-$id"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close view",
                                tint = fg,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                }
            }

            if (showInsertCaret) {
                val caretX = targetXForIndex(insertAt.coerceIn(0, displayIds.size))
                val animatedCaretX by animateFloatAsState(
                    targetValue = caretX,
                    animationSpec = Motion.spatial(),
                    label = "tab-insert-caret-x",
                )
                TabInsertCaret(
                    Modifier
                        .zIndex(3f)
                        .graphicsLayer { translationX = animatedCaretX },
                )
            }

            if (onAddView != null) {
                val addTargetX = targetXForIndex(displayIds.size)
                val animatedAddX by animateFloatAsState(
                    targetValue = addTargetX,
                    animationSpec = Motion.spatial(),
                    label = "tab-add-x",
                )
                var pickerOpen by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .zIndex(0.5f)
                        .graphicsLayer { translationX = animatedAddX }
                        .fillMaxHeight()
                        .width(36.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .width(36.dp)
                            .clickable { pickerOpen = true }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .testTag("tab-add-view"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Add a view",
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = pickerOpen,
                        onDismissRequest = { pickerOpen = false },
                        modifier = Modifier.testTag("tab-add-view-menu"),
                    ) {
                        KindMenuItems(onPick = { kind ->
                            pickerOpen = false
                            onAddView(kind, NewViewPlacement.HERE)
                        })
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
 * Press-to-drag on a tab, matching [dev.supermux.desktop.session.SessionDragReorderState]:
 * mouse-friendly grab after a small move, click when movement stays under the
 * threshold. Drop resolution is delegated to [TabDragState] so cross-strip and
 * edge targets share one pointer.
 */
private fun Modifier.tabDragGestures(
    viewId: String,
    groupId: String,
    stripOrderProvider: () -> List<String>,
    labelProvider: () -> String,
    dragState: TabDragState,
    onSelect: (String) -> Unit,
    onDrop: (TabDropTarget) -> Unit,
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
            val downLocal = down.position
            val boundsAtDown = dragState.tabBoundsFor(groupId, viewId)
            val startRoot = if (boundsAtDown != null) {
                Offset(boundsAtDown.left + downLocal.x, boundsAtDown.top + downLocal.y)
            } else {
                Offset(downLocal.x, downLocal.y)
            }
            dragState.begin(
                viewId = viewId,
                groupId = groupId,
                rootPos = startRoot,
                stripOrder = stripOrderProvider(),
                label = labelProvider(),
                bounds = boundsAtDown,
            )
            var passed = false
            // Accumulate pointer deltas into root space. Do NOT recompute from
            // local position on a translating tab — that re-fed the live order
            // and looped the strip.
            var pointerRoot = startRoot

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull() ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    if (!passed) {
                        dragState.cancel()
                        onSelect(viewId)
                    } else {
                        val target = dragState.finish()
                        if (target != null) onDrop(target)
                    }
                    break
                }
                if (change.pressed) {
                    pointerRoot += change.positionChange()
                    dragState.updatePointer(pointerRoot, startRoot, TAB_DRAG_THRESHOLD_PX)
                    if (dragState.pastThreshold) {
                        passed = true
                        change.consume()
                    }
                }
            }
            if (dragState.draggingViewId == viewId) {
                dragState.cancel()
            }
        }
    }

/**
 * N-pane generalisation of [ResizableSplit]. [n] children means [n]-1 splitters,
 * tagged `splitter-0` … `splitter-(n-2)`. A drag on splitter [i] moves weight
 * between children [i] and [i]+1 only; the total stays 1.
 *
 * Same seam model as [SidebarDivider] / [ResizableSplit]: panes abut (no layout
 * gap); overlay hairlines sit on cumulative fraction boundaries.
 */
@Composable
fun ResizableSplitN(
    direction: String,
    sizes: List<Double>,
    onSizesChange: (List<Double>) -> Unit,
    modifier: Modifier = Modifier,
    child: @Composable (index: Int) -> Unit,
) {
    val n = sizes.size.coerceAtMost(
        // Prefer children count if the tree is inconsistent; never index OOB.
        sizes.size,
    )
    if (n <= 0) return
    if (n == 1) {
        Box(modifier.fillMaxSize()) { child(0) }
        return
    }

    val horizontal = direction == "row"
    var totalPx by remember { mutableStateOf(0) }
    val currentSizes by rememberUpdatedState(sizes)
    // The drag handler below lives in `pointerInput(totalPx, index)`, so it is
    // rebuilt only when the pane count or geometry changes — never when the tree
    // does. Anything it captures directly goes stale. `sizes` was already guarded
    // this way; the callback needs the same guard, or a caller that closes over
    // tree state sends yesterday's tree.
    val currentOnSizesChange by rememberUpdatedState(onSizesChange)
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { totalPx = if (horizontal) it.width else it.height },
    ) {
        if (horizontal) {
            Row(Modifier.fillMaxSize()) {
                for (i in 0 until n) {
                    val weight = sizes.getOrElse(i) { 1.0 / n }.toFloat().coerceAtLeast(0.001f)
                    Box(Modifier.weight(weight).fillMaxHeight()) { child(i) }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                for (i in 0 until n) {
                    val weight = sizes.getOrElse(i) { 1.0 / n }.toFloat().coerceAtLeast(0.001f)
                    Box(Modifier.weight(weight).fillMaxWidth()) { child(i) }
                }
            }
        }

        if (totalPx > 0) {
            // Cumulative fraction of the first (i+1) panes → seam after index i.
            var cum = 0.0
            for (i in 0 until n - 1) {
                cum += sizes.getOrElse(i) { 1.0 / n }
                val seamPx = (totalPx * cum).toFloat()
                val seamDp = with(density) { seamPx.toDp() }
                val index = i
                SplitSeamOverlay(
                    horizontal = horizontal,
                    onDragDeltaPx = { deltaPx ->
                        if (totalPx <= 0) return@SplitSeamOverlay
                        val delta = deltaPx / totalPx
                        val cur = currentSizes
                        if (index < 0 || index + 1 >= cur.size) return@SplitSeamOverlay
                        val a = cur[index]
                        val b = cur[index + 1]
                        val pair = a + b
                        val minFrac = 0.05
                        val nextA = (a + delta).coerceIn(minFrac * pair, (1.0 - minFrac) * pair)
                        val nextB = pair - nextA
                        currentOnSizesChange(
                            cur.toMutableList().also {
                                it[index] = nextA
                                it[index + 1] = nextB
                            },
                        )
                    },
                    modifier = if (horizontal) {
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(x = seamDp - SplitSeamCenterOffset)
                            .fillMaxHeight()
                    } else {
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(y = seamDp - SplitSeamCenterOffset)
                            .fillMaxWidth()
                    },
                    testTag = "splitter-$index",
                )
            }
        }
    }
}
