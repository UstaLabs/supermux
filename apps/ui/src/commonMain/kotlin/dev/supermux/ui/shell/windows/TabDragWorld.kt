// Live tear-off across windows: a tab drag from the moment it leaves the pane layer of the window
// it started in (see TabTearOff) until the press ends — the window that carries the tab, the
// window it is over, and what Esc puts back.
package dev.supermux.ui.shell.windows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import dev.supermux.ui.panes.PaneDropTarget
import dev.supermux.ui.panes.TabDropEndpoint
import dev.supermux.ui.panes.TabHandoff
import dev.supermux.ui.panes.TabTearOff
import dev.supermux.ui.workspace.WorkspaceLayoutState
import dev.supermux.workspace.collectViewIds

/**
 * The platform side of live tear-off: real windows, where they are, and the pointer between them.
 *
 * Positions are SCREEN units — what the platform places windows in (AWT points on desktop). A
 * window's content is what its Compose root fills; root px = (screen − [contentOrigin]) × [scale].
 */
interface TearOffPlatform {
    /** Screen position of [hostId]'s content top-left, its Compose root's (0, 0); null while not on screen. */
    fun contentOrigin(hostId: String): Offset?

    /** Size of [hostId]'s content; null while not on screen. */
    fun contentSize(hostId: String): Size?

    /** [hostId]'s whole window, frame included; null while not on screen. */
    fun windowFrame(hostId: String): Rect?

    /** Root px per screen unit in [hostId]'s window; null while not on screen. */
    fun scale(hostId: String): Float?

    /** The windows on screen, front-most first (best effort). */
    fun frontToBack(): List<String>

    /** Move [hostId]'s window so its content's top-left is at [contentTopLeft]. False when it has no window yet. */
    fun moveContentTo(hostId: String, contentTopLeft: Offset): Boolean

    /** The bounds, frame included, a NEW window needs for its content to be [contentSize] at [contentTopLeft]. */
    fun frameForContent(contentTopLeft: Offset, contentSize: Size): WindowBounds

    /** Hide a window this drag opened while the tab is over another window — or show it again. */
    fun setHidden(hostId: String, hidden: Boolean)

    /** Put [hostId]'s window behind every other, while the tab it carries is over another window. */
    fun sendToBack(hostId: String)

    /** Bring [hostId]'s window back in front. */
    fun bringToFront(hostId: String)

    /** Raise and focus [hostId]'s window: the drag ended with the tab there. */
    fun focus(hostId: String)

    /** A live drag began: feed [TabDragWorld.move] / [TabDragWorld.release] from the pointer until [liveDragEnded]. */
    fun liveDragStarted()

    fun liveDragEnded()

    /** [hostId]'s window follows the pointer (or stopped): keep its moves out of the saved window state meanwhile. */
    fun setFollowing(hostId: String, following: Boolean)
}

/**
 * Every window's pane layer, and the one tab drag that has left the window it started in.
 *
 * The drag is in one of two places. Most of the time a window CARRIES the tab and follows the
 * pointer: either a window this drag opened for it ([TabTearOff.takeOverOutside]) or the tab's own
 * one-tab window ([TabTearOff.takeOverOnStart]). Brought over a tab strip — or the empty canvas —
 * of another window of the same workspace, the tab is OVER that window instead: the carrier gets
 * out of the way (hidden, or sent back when it is the window the press began in, which must keep
 * receiving it) and that window's pane layer previews the drop like one of its own drags, until
 * the pointer goes [OverMargin] past its edge again.
 *
 * Nothing moves into the window the tab is over until the press ends there, so the carrier — and
 * the press — survives every pass over another window, and Esc ([cancel]) can always put it all
 * back. Release over a target docks the tab and the carrier, left empty, closes.
 *
 * Only a strip pulls the tab in — or, for a window showing nothing, the band across the top of
 * its canvas where a strip would be. Anywhere else, a window opened for a tab can be dropped ON
 * another window, which is where people put it.
 */
class TabDragWorld(
    private val registry: WindowHostRegistry,
    private val platform: TearOffPlatform,
) {
    private class Pane(
        val workspaceId: String,
        val endpoint: TabDropEndpoint,
    )

    private class Origin(val hostId: String, val groupId: String, val index: Int, val activeViewId: String?)

    private class Session(
        val viewId: String,
        val label: String,
        val workspaceId: String,
        val layoutSync: WorkspaceLayoutState,
        /** Where the pointer holds the tab, from its top-left (screen units). */
        val grab: Offset,
        val tabSize: Size,
        /** Content size of a window opened for the tab. */
        val contentSize: Size,
        val origin: Origin,
        /** True when this drag opened [carrier]; false when it is the tab's own one-tab window. */
        val torn: Boolean,
        /** The window holding the tab. */
        var carrier: String,
        /** [carrier]'s content top-left before the drag (its own window): where Esc puts it back. */
        val carrierStart: Offset?,
        var pointer: Offset,
        /** The window the tab is over, previewing the drop; null while the carrier follows the pointer. */
        var over: String? = null,
        /**
         * The empty canvas the tab joined [over] by (its strip band), if that is how: the tab
         * would be that window's only one, so — as with a strip of a one-tab window — pulling it
         * away from where the strip would be takes it out again.
         */
        var overEmptyGroup: String? = null,
    )

    /** Where a dragged tab would join a window: its strip, or the strip band of its empty canvas [emptyGroup]. */
    private class Attach(val hostId: String, val emptyGroup: String?)

    private val panes = mutableMapOf<String, Pane>()
    private var session: Session? = null

    /** A tab drag is out of its window now. */
    val active: Boolean get() = session != null

    /** The window carrying the dragged tab, and the window it is over (tests and diagnostics). */
    val carrierHostId: String? get() = session?.carrier
    val overHostId: String? get() = session?.over

    /** The [TabTearOff] of window [hostId], showing workspace [workspaceId] edited through [layoutSync]. */
    fun tearOffFor(hostId: String, layoutSync: WorkspaceLayoutState, workspaceId: String): TabTearOff =
        object : TabTearOff {
            override fun register(endpoint: TabDropEndpoint) {
                panes[hostId] = Pane(workspaceId, endpoint)
            }

            override fun unregister(endpoint: TabDropEndpoint) {
                if (panes[hostId]?.endpoint === endpoint) panes.remove(hostId)
            }

            override fun takeOverOnStart(handoff: TabHandoff): Boolean =
                startOwnWindow(hostId, workspaceId, layoutSync, handoff)

            override fun takeOverOutside(handoff: TabHandoff): Boolean =
                startTorn(hostId, workspaceId, layoutSync, handoff)
        }

    // ── Starting ─────────────────────────────────────────────────────────────────────

    /** A window's only tab started moving: the window itself follows the pointer. */
    private fun startOwnWindow(
        hostId: String,
        workspaceId: String,
        layoutSync: WorkspaceLayoutState,
        h: TabHandoff,
    ): Boolean {
        val host = registry.extras().firstOrNull { it.id == hostId } ?: return false
        if (host.claimedViewIds != setOf(h.viewId)) return false
        val scale = platform.scale(hostId) ?: return false
        val origin = platform.contentOrigin(hostId) ?: return false
        begin(
            Session(
                viewId = h.viewId,
                label = h.label,
                workspaceId = workspaceId,
                layoutSync = layoutSync,
                grab = h.grab / scale,
                tabSize = h.tabSize / scale,
                contentSize = platform.contentSize(hostId) ?: DefaultContentSize,
                origin = Origin(hostId, h.groupId, h.index, h.activeViewId),
                torn = false,
                carrier = hostId,
                carrierStart = origin,
                pointer = origin + h.pointer / scale,
            ),
        )
        return true
    }

    /** A tab was dragged out of its window: open it in a window of its own, under the pointer. */
    private fun startTorn(
        hostId: String,
        workspaceId: String,
        layoutSync: WorkspaceLayoutState,
        h: TabHandoff,
    ): Boolean {
        val scale = platform.scale(hostId) ?: return false
        val origin = platform.contentOrigin(hostId) ?: return false
        val group = h.groupSize / scale
        val s = Session(
            viewId = h.viewId,
            label = h.label,
            workspaceId = workspaceId,
            layoutSync = layoutSync,
            grab = h.grab / scale,
            tabSize = h.tabSize / scale,
            // The window gets the size the tab's group had, so its content does not reflow.
            contentSize = if (group.width < MinContentSize.width || group.height < MinContentSize.height) {
                Size(maxOf(group.width, MinContentSize.width), maxOf(group.height, MinContentSize.height))
            } else {
                group
            },
            origin = Origin(hostId, h.groupId, h.index, h.activeViewId),
            torn = true,
            carrier = "",
            carrierStart = null,
            pointer = origin + h.pointer / scale,
        )
        // A fresh window shows the tab at its content's top-left: open it so the pointer holds it
        // where it held the tab. Once it is on screen, follow() uses where the tab really is.
        val bounds = platform.frameForContent(s.pointer - s.grab, s.contentSize)
        val torn = tearOutTab(
            registry = registry,
            tree = layoutSync.tree,
            viewId = h.viewId,
            workspaceId = workspaceId,
            newGroupId = newWindowId(),
            bounds = bounds,
            hostId = newWindowId(),
        ) { transform ->
            layoutSync.edit(transform)
            layoutSync.tree
        } ?: return false
        s.carrier = torn.id
        begin(s)
        return true
    }

    private fun begin(s: Session) {
        // One pointer, one drag: a session still open here lost its release somewhere.
        session?.let { release(it.pointer) }
        session = s
        platform.setFollowing(s.carrier, true)
        platform.liveDragStarted()
    }

    // ── The pointer ──────────────────────────────────────────────────────────────────

    /** The pointer moved to [screen] with the button down. */
    fun move(screen: Offset) {
        val s = session ?: return
        s.pointer = screen
        if (s.viewId !in collectViewIds(s.layoutSync.tree)) {
            // Closed elsewhere mid-drag: nothing left to carry.
            abandon(s)
            return
        }
        val over = s.over
        if (over != null) {
            val pane = panes[over]
            val p = toRoot(over, screen)
            if (pane != null && p != null && withinContent(over, screen, OverMargin) && !pulledFromBand(s, over, p)) {
                pane.endpoint.drag.updatePointer(p, pane.endpoint.drag.dragStartRoot, thresholdPx = 0f)
                return
            }
            leave(s)
        }
        val target = attachTargetAt(s, screen)
        if (target != null) enter(s, target, screen) else follow(s, screen)
    }

    /** The press ended at [screen]. */
    fun release(screen: Offset) {
        val s = session ?: return
        s.pointer = screen
        val over = s.over
        if (over == null) {
            platform.focus(s.carrier)
            end(s)
            return
        }
        val pane = panes[over]
        val target = pane?.endpoint?.drag?.finish()
        if (pane == null || target == null) {
            // Over a window but on nothing there that takes a tab: the carrier stays, here.
            s.over = null
            reveal(s)
            platform.focus(s.carrier)
        } else {
            val group = target.groupId()
            if (group != null && pane.endpoint.drag.isEmptyGroup(group)) {
                // An empty canvas has no group of its own to move the tab into (its placeholder
                // names the tree's first group, which may be another window's): the tab stays
                // where the tree has it and this window takes it.
                pane.endpoint.docked(s.viewId)
            } else {
                pane.endpoint.drop(target)
            }
            // The carrier closes once the tab has left it; a carrier that does not (a move to
            // another workspace still on its way) must not be left hidden.
            if (s.torn) {
                follow(s, s.pointer)
                platform.setHidden(s.carrier, false)
            }
            platform.focus(over)
        }
        end(s)
    }

    /** Esc: put the tab back where the drag found it. False when no drag was out of its window. */
    fun cancel(): Boolean {
        val s = session ?: return false
        s.over?.let { panes[it]?.endpoint?.drag?.cancel() }
        s.over = null
        if (s.torn) {
            val origin = panes[s.origin.hostId]
            if (origin != null) {
                origin.endpoint.restore(s.viewId, s.origin.groupId, s.origin.index, s.origin.activeViewId)
            } else {
                registry.unclaim(s.carrier)
            }
            platform.setHidden(s.carrier, false)
        } else {
            s.carrierStart?.let { platform.moveContentTo(s.carrier, it) }
            platform.bringToFront(s.carrier)
        }
        end(s)
        return true
    }

    /**
     * Esc anywhere: end whichever tab drag is live — one out of its window ([cancel]), or one still
     * inside a window's pane layer. True when there was one.
     */
    fun cancelActiveDrag(): Boolean {
        if (cancel()) return true
        val dragging = panes.values.firstOrNull { it.endpoint.drag.isDragging } ?: return false
        return dragging.endpoint.drag.abort()
    }

    // ── Over another window ──────────────────────────────────────────────────────────

    /**
     * The window whose strip (or empty canvas) the tab at [screen] would join: the front-most of
     * our windows under the pointer decides, so a strip hidden behind another window never takes
     * it. Only windows showing the tab's workspace take it.
     */
    private fun attachTargetAt(s: Session, screen: Offset): Attach? {
        for (hostId in platform.frontToBack()) {
            if (hostId == s.carrier) continue
            val frame = platform.windowFrame(hostId) ?: continue
            if (!frame.contains(screen)) continue
            val pane = panes[hostId] ?: return null
            if (pane.workspaceId != s.workspaceId) return null
            val p = toRoot(hostId, screen) ?: return null
            val drag = pane.endpoint.drag
            if (drag.stripUnderPointer(p) != null) return Attach(hostId, emptyGroup = null)
            return drag.emptyGroupAt(p)?.let { Attach(hostId, emptyGroup = it) }
        }
        return null
    }

    /** The tab joined [hostId] by an empty canvas's strip band, and [p] is now well above or below it. */
    private fun pulledFromBand(s: Session, hostId: String, p: Offset): Boolean {
        val group = s.overEmptyGroup ?: return false
        val band = panes[hostId]?.endpoint?.drag?.emptyBandOf(group) ?: return false
        val margin = OverMargin * (platform.scale(hostId) ?: 1f)
        return p.y < band.top - margin || p.y > band.bottom + margin
    }

    private fun enter(s: Session, target: Attach, screen: Offset) {
        val hostId = target.hostId
        val pane = panes[hostId] ?: return
        val scale = platform.scale(hostId) ?: return
        val p = toRoot(hostId, screen) ?: return
        pane.endpoint.drag.beginIncoming(s.viewId, p, s.label, s.grab * scale, s.tabSize * scale)
        s.over = hostId
        s.overEmptyGroup = target.emptyGroup
        // A carrier this drag opened goes away while the tab is here; the tab's own window cannot
        // (the press began in it and has to keep arriving there), so it steps behind instead.
        if (s.torn) platform.setHidden(s.carrier, true) else platform.sendToBack(s.carrier)
    }

    private fun leave(s: Session) {
        val over = s.over ?: return
        panes[over]?.endpoint?.drag?.cancel()
        s.over = null
        s.overEmptyGroup = null
        reveal(s)
    }

    /** The carrier comes back under the pointer, in front. */
    private fun reveal(s: Session) {
        follow(s, s.pointer)
        if (s.torn) platform.setHidden(s.carrier, false) else platform.bringToFront(s.carrier)
    }

    // ── The carrier ──────────────────────────────────────────────────────────────────

    /** Put the carrier where the pointer holds its tab. */
    private fun follow(s: Session, screen: Offset) {
        val topLeft = screen - s.grab - tabOffset(s)
        if (!platform.moveContentTo(s.carrier, topLeft)) {
            // Not on screen yet: it opens where it has got to.
            registry.updateBounds(s.carrier, platform.frameForContent(topLeft, s.contentSize))
        }
    }

    /** Where the carrier shows the tab, from its content's top-left; the top-left until it has drawn it. */
    private fun tabOffset(s: Session): Offset {
        val scale = platform.scale(s.carrier) ?: return Offset.Zero
        val tab = panes[s.carrier]?.endpoint?.drag?.tabBoundsOf(s.viewId) ?: return Offset.Zero
        return tab.topLeft / scale
    }

    // ── Ending ───────────────────────────────────────────────────────────────────────

    private fun abandon(s: Session) {
        s.over?.let { panes[it]?.endpoint?.drag?.cancel() }
        s.over = null
        if (s.torn) platform.setHidden(s.carrier, false) else platform.bringToFront(s.carrier)
        end(s)
    }

    private fun end(s: Session) {
        if (session !== s) return
        session = null
        platform.setFollowing(s.carrier, false)
        // Where the carrier settled is where it reopens next time (it may have closed already).
        platform.windowFrame(s.carrier)?.let { f ->
            registry.updateBounds(s.carrier, WindowBounds(f.left, f.top, f.width, f.height))
        }
        platform.liveDragEnded()
    }

    // ── Coordinates ──────────────────────────────────────────────────────────────────

    private fun toRoot(hostId: String, screen: Offset): Offset? {
        val origin = platform.contentOrigin(hostId) ?: return null
        val scale = platform.scale(hostId) ?: return null
        return (screen - origin) * scale
    }

    private fun withinContent(hostId: String, screen: Offset, margin: Float): Boolean {
        val origin = platform.contentOrigin(hostId) ?: return false
        val size = platform.contentSize(hostId) ?: return false
        return Rect(origin, size).inflate(margin).contains(screen)
    }

    private companion object {
        /** How far past the edge of the window it is over the pointer goes before the tab leaves it again (screen units). */
        const val OverMargin = 16f
        /** Smallest content a window opened for a tab gets. */
        val MinContentSize = Size(360f, 240f)
        val DefaultContentSize = Size(960f, 640f)
    }
}

/** The group a drop lands in, if it lands in a group. */
private fun PaneDropTarget.groupId(): String? = when (this) {
    is PaneDropTarget.Reorder -> groupId
    is PaneDropTarget.MoveToGroup -> toGroupId
    is PaneDropTarget.Split -> groupId
    is PaneDropTarget.MoveToWorkspace -> null
}
