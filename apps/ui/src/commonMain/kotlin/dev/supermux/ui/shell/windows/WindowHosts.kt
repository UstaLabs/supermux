// The window-host registry: which OS window shows which slice of a workspace's layout tree.
//
// Moved out of desktop into `:ui` so every host with more than one window shares ONE claim
// algebra — desktop's `Window {}`s and Android's extra activities alike. Nothing here
// knows how a window is actually opened; the host does that once a claim succeeds.
package dev.supermux.ui.shell.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.firstGroupId
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.hideClaimed
import dev.supermux.workspace.splitGroup
import dev.supermux.workspace.subtreeCovering
import kotlinx.serialization.Serializable

data class WindowBounds(val x: Float, val y: Float, val width: Float, val height: Float) {
    fun contains(pointerX: Float, pointerY: Float): Boolean =
        pointerX >= x && pointerX < x + width && pointerY >= y && pointerY < y + height
}

fun dragEndedOutside(
    pointerX: Float,
    pointerY: Float,
    windows: List<WindowBounds>,
): Boolean = windows.none { it.contains(pointerX, pointerY) }

/**
 * Placeholder group when [WindowHostRegistry.layoutFor] is null (canvas claimed).
 * Keeps the empty [WorkspaceEmptyHint] inside [PaneHost] so it is a drop target
 * addressed at the first real group of [fullTree].
 */
fun emptyHostLayout(fullTree: LayoutNode): LayoutNode =
    LayoutNode.Group(firstGroupId(fullTree) ?: "empty-host", emptyList(), null)

/** Smallest node whose view ids contain [viewIds] (not necessarily equal). */
fun smallestContaining(node: LayoutNode, viewIds: Set<String>): LayoutNode? {
    if (viewIds.isEmpty()) return null
    val here = collectViewIds(node).toSet()
    if (!here.containsAll(viewIds)) return null
    if (node is LayoutNode.Split) {
        node.children.firstNotNullOfOrNull { smallestContaining(it, viewIds) }?.let { return it }
    }
    return node
}

/** Workspaces that must keep a `WorkspaceSession` composed: the selected one plus every extra window. */
fun workspaceIdsNeedingSession(
    selectedWorkspaceId: String?,
    extraWorkspaceIds: Collection<String>,
): Set<String> = buildSet {
    if (!selectedWorkspaceId.isNullOrEmpty()) add(selectedWorkspaceId)
    addAll(extraWorkspaceIds)
}

/**
 * One OS window hosting a workspace slice.
 *
 * [claimedViewIds] empty on an extra host means the extra owns the whole canvas
 * (the workspace root). Main ignores its own claim set.
 */
data class WindowHost(
    val id: String,
    val workspaceId: String,
    val claimedViewIds: Set<String>,
    val bounds: WindowBounds,
    val isMain: Boolean,
)

class WindowHostRegistry(mainId: String = "main") {
    private var mainHost by mutableStateOf(
        WindowHost(
            id = mainId,
            workspaceId = "",
            claimedViewIds = emptySet(),
            bounds = WindowBounds(0f, 0f, 1440f, 900f),
            isMain = true,
        ),
    )
    private val extraHosts = linkedMapOf<String, WindowHost>()
    /** Each workspace's view ids at the last [rebase] — what tells a newly opened tab apart. */
    private val lastSeen = mutableMapOf<String, Set<String>>()
    /** Compose / snapshotFlow subscription tick — [extraHosts] is not a snapshot collection. */
    private var extraGeneration by mutableStateOf(0)

    fun main(): WindowHost = mainHost

    fun extras(): List<WindowHost> {
        extraGeneration
        return extraHosts.values.toList()
    }

    fun extras(workspaceId: String): List<WindowHost> =
        extras().filter { it.workspaceId == workspaceId }

    fun claimedUnion(workspaceId: String): Set<String> =
        extras(workspaceId).flatMap { it.claimedViewIds }.toSet()

    fun layoutFor(host: WindowHost, tree: LayoutNode): LayoutNode? {
        val live = liveHost(host.id) ?: return null
        if (live.isMain) {
            val extras = extras(live.workspaceId)
            if (extras.any { it.claimedViewIds.isEmpty() }) return null
            val hide = extras
                .filter { subtreeCovering(tree, it.claimedViewIds) != null }
                .flatMap { it.claimedViewIds }
                .toSet()
            return hideClaimed(tree, hide)
        }
        if (live.claimedViewIds.isEmpty()) return tree
        return subtreeCovering(tree, live.claimedViewIds)
    }

    fun tryClaim(
        workspaceId: String,
        viewIds: Set<String>,
        bounds: WindowBounds,
        id: String,
        tree: LayoutNode,
    ): WindowHost? {
        val claim = viewIds.toSet()
        if (claim.isEmpty()) return null
        if (id == mainHost.id || extraHosts.containsKey(id)) return null
        if (subtreeCovering(tree, claim) == null) return null
        val existing = extras(workspaceId)
        if (existing.any { it.claimedViewIds.isEmpty() }) return null
        val union = claimedUnion(workspaceId)
        if (claim.any { it in union }) return null
        val host = WindowHost(
            id = id,
            workspaceId = workspaceId,
            claimedViewIds = claim,
            bounds = bounds,
            isMain = false,
        )
        extraHosts[id] = host
        bumpExtras()
        return host
    }

    fun tryClaimCanvas(workspaceId: String, bounds: WindowBounds, id: String): WindowHost? {
        if (id == mainHost.id || extraHosts.containsKey(id)) return null
        if (extras(workspaceId).isNotEmpty()) return null
        val host = WindowHost(
            id = id,
            workspaceId = workspaceId,
            claimedViewIds = emptySet(),
            bounds = bounds,
            isMain = false,
        )
        extraHosts[id] = host
        bumpExtras()
        return host
    }

    fun unclaim(hostId: String) {
        if (hostId == mainHost.id) return
        if (extraHosts.remove(hostId) != null) bumpExtras()
    }

    fun updateBounds(hostId: String, bounds: WindowBounds) {
        if (hostId == mainHost.id) {
            mainHost = mainHost.copy(bounds = bounds)
            return
        }
        val existing = extraHosts[hostId] ?: return
        if (existing.bounds == bounds) return
        extraHosts[hostId] = existing.copy(bounds = bounds)
        bumpExtras()
    }

    /**
     * Remove [viewId] from [hostId]'s claim. Unclaims the extra if the remainder is empty or
     * no longer covering.
     */
    fun shrinkClaim(hostId: String, viewId: String, tree: LayoutNode): Boolean {
        val host = extraHosts[hostId] ?: return false
        if (host.claimedViewIds.isEmpty()) return false
        if (viewId !in host.claimedViewIds) return false
        applyRemaining(host, host.claimedViewIds - viewId, tree)
        bumpExtras()
        return true
    }

    /**
     * After a split/add in an extra window, grow that host's claim so the new
     * view stays in this window instead of appearing on main.
     */
    fun expandClaim(hostId: String, addedViewIds: Set<String>, tree: LayoutNode): Boolean {
        val host = extraHosts[hostId] ?: return false
        if (host.claimedViewIds.isEmpty()) return true
        if (addedViewIds.isEmpty()) return true
        val next = host.claimedViewIds + addedViewIds
        val cover = smallestContaining(tree, next) ?: return false
        val ids = collectViewIds(cover).toSet()
        val others = claimedUnion(host.workspaceId) - host.claimedViewIds
        val claimed = if (ids.any { it in others }) {
            collectViewIds(subtreeCovering(tree, next) ?: return false).toSet()
        } else {
            ids
        }
        extraHosts[hostId] = host.copy(claimedViewIds = claimed)
        bumpExtras()
        return true
    }

    fun rebase(workspaceId: String, tree: LayoutNode) {
        val live = collectViewIds(tree).toSet()
        // Views that were not in this workspace's tree last time — a tab just opened.
        val fresh = lastSeen[workspaceId]?.let { live - it }.orEmpty()
        lastSeen[workspaceId] = live
        val taken = mutableSetOf<String>()
        val snapshot = extras(workspaceId)
        var changed = false
        for (host in snapshot) {
            if (host.claimedViewIds.isEmpty()) continue
            val others = snapshot.filter { it.id != host.id }.flatMap { it.claimedViewIds }.toSet() + taken
            val surviving = host.claimedViewIds.filter { it in live && it !in taken }.toSet()
            val remaining = if (surviving.isNotEmpty() && subtreeCovering(tree, surviving) == null) {
                grownByFresh(tree, surviving, fresh, others) ?: emptySet()
            } else {
                surviving
            }
            if (remaining.isEmpty()) {
                extraHosts.remove(host.id)
                changed = true
                continue
            }
            if (remaining != host.claimedViewIds) {
                extraHosts[host.id] = host.copy(claimedViewIds = remaining)
                changed = true
            }
            taken += remaining
        }
        if (changed) bumpExtras()
    }

    /**
     * [claim] no longer covers a subtree of its own. If only [fresh] views joined it — a tab
     * opened IN that window's group, which the broker placed before the window could claim it —
     * the window keeps the group, new tab and all. Anything else (a tab of the main window mixed
     * in, another window's claim) means the claim really broke: null.
     */
    private fun grownByFresh(
        tree: LayoutNode,
        claim: Set<String>,
        fresh: Set<String>,
        others: Set<String>,
    ): Set<String>? {
        val cover = smallestContaining(tree, claim) ?: return null
        val ids = collectViewIds(cover).toSet()
        if (ids.any { it in others }) return null
        if (!fresh.containsAll(ids - claim)) return null
        return ids
    }

    fun setWorkspaceOnMain(workspaceId: String) {
        mainHost = mainHost.copy(workspaceId = workspaceId)
    }

    /**
     * Move [viewId] membership from its current extra claim (or main, if unclaimed)
     * onto [toHostId]. Call after the layout tree already reflects the drop
     * ([moveViewToGroup] / split) so [subtreeCovering] can validate the new sets.
     */
    fun transfer(viewId: String, toHostId: String, tree: LayoutNode): Boolean {
        val target = liveHost(toHostId) ?: return false
        val sourceExtra = extras().firstOrNull { viewId in it.claimedViewIds }
        if (target.isMain) {
            if (sourceExtra == null) return false
            applyRemaining(sourceExtra, sourceExtra.claimedViewIds - viewId, tree)
            bumpExtras()
            return true
        }
        if (target.id == sourceExtra?.id) return false
        val newTargetClaim = if (target.claimedViewIds.isEmpty()) {
            emptySet()
        } else {
            target.claimedViewIds + viewId
        }
        if (newTargetClaim.isNotEmpty() && subtreeCovering(tree, newTargetClaim) == null) {
            return false
        }
        extraHosts[target.id] = target.copy(claimedViewIds = newTargetClaim)
        if (sourceExtra != null) {
            applyRemaining(sourceExtra, sourceExtra.claimedViewIds - viewId, tree)
        }
        bumpExtras()
        return true
    }

    private fun applyRemaining(host: WindowHost, remaining: Set<String>, tree: LayoutNode) {
        if (remaining.isEmpty() || subtreeCovering(tree, remaining) == null) {
            extraHosts.remove(host.id)
        } else {
            extraHosts[host.id] = host.copy(claimedViewIds = remaining)
        }
    }

    private fun liveHost(id: String): WindowHost? =
        if (id == mainHost.id) mainHost else extraHosts[id]

    private fun bumpExtras() {
        extraGeneration++
    }
}

/**
 * One extra (non-main) window host, in a shape a host can store — desktop writes these to
 * `ui-state.json`, Android puts one in the extra activity's intent. Separate from [WindowHost]
 * because [WindowBounds] is not `@Serializable`.
 */
@Serializable
data class PersistedWindowHost(
    val id: String,
    val workspaceId: String,
    val claimedViewIds: List<String> = emptyList(),
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

@OptIn(kotlin.uuid.ExperimentalUuidApi::class)
private fun newWindowId(): String = kotlin.uuid.Uuid.random().toString()

fun WindowHost.toPersisted(): PersistedWindowHost = PersistedWindowHost(
    id = id,
    workspaceId = workspaceId,
    claimedViewIds = claimedViewIds.toList(),
    x = bounds.x,
    y = bounds.y,
    width = bounds.width,
    height = bounds.height,
)

/** Pending extras for workspaces not restored this session, union live extras (live wins on id). */
fun mergePersistedWindowHosts(
    pending: List<PersistedWindowHost>,
    liveExtras: List<WindowHost>,
): List<PersistedWindowHost> {
    val live = liveExtras.map { it.toPersisted() }
    val liveIds = live.map { it.id }.toSet()
    return pending.filter { it.id !in liveIds } + live
}

fun planTearOutTab(
    tree: LayoutNode,
    viewId: String,
    newGroupId: String,
): Pair<(LayoutNode) -> LayoutNode, Set<String>>? {
    val groupId = groupIdOf(tree, viewId) ?: return null
    val group = groupById(tree, groupId) ?: return null
    val claim = setOf(viewId)
    if (group.viewIds.size == 1) return Pair({ it }, claim)
    return Pair({ n -> splitGroup(n, groupId, viewId, "row", newGroupId) }, claim)
}

fun defaultTearOutBounds(from: WindowBounds = WindowBounds(0f, 0f, 1440f, 900f)): WindowBounds =
    WindowBounds(from.x + 80f, from.y + 80f, 800f, 600f)

/**
 * Split [viewId] into its own group if needed, apply [edit] to the transformed tree, then claim
 * that view on an extra host. [edit] is typically `{ t -> layoutSync.edit { t }; layoutSync.tree }`
 * or `{ it }` in unit tests.
 */
fun tearOutTab(
    registry: WindowHostRegistry,
    tree: LayoutNode,
    viewId: String,
    workspaceId: String,
    newGroupId: String,
    bounds: WindowBounds,
    hostId: String,
    edit: (LayoutNode) -> LayoutNode,
): WindowHost? {
    val plan = planTearOutTab(tree, viewId, newGroupId) ?: return null
    val sourceExtra = registry.extras().firstOrNull { viewId in it.claimedViewIds }
    if (sourceExtra != null && sourceExtra.claimedViewIds == setOf(viewId)) {
        return null
    }
    val nextTree = edit(plan.first(tree))
    if (sourceExtra != null) {
        registry.shrinkClaim(sourceExtra.id, viewId, nextTree)
    }
    return registry.tryClaim(workspaceId, plan.second, bounds, hostId, nextTree)
}

fun tearOutGroup(
    registry: WindowHostRegistry,
    tree: LayoutNode,
    groupId: String,
    workspaceId: String,
    bounds: WindowBounds,
    hostId: String,
): WindowHost? {
    val group = groupById(tree, groupId) ?: return null
    val claim = group.viewIds.toSet()
    if (claim.isEmpty()) return null
    return registry.tryClaim(workspaceId, claim, bounds, hostId, tree)
}

fun tearOutCanvas(
    registry: WindowHostRegistry,
    workspaceId: String,
    bounds: WindowBounds,
    hostId: String,
): WindowHost? = registry.tryClaimCanvas(workspaceId, bounds, hostId)

fun tearOutTabLive(
    registry: WindowHostRegistry,
    tree: LayoutNode,
    viewId: String,
    workspaceId: String,
    edit: (LayoutNode) -> LayoutNode,
): WindowHost? = tearOutTab(
    registry = registry,
    tree = tree,
    viewId = viewId,
    workspaceId = workspaceId,
    newGroupId = newWindowId(),
    bounds = defaultTearOutBounds(registry.main().bounds),
    hostId = newWindowId(),
    edit = edit,
)

fun tearOutGroupLive(
    registry: WindowHostRegistry,
    tree: LayoutNode,
    groupId: String,
    workspaceId: String,
): WindowHost? = tearOutGroup(
    registry,
    tree,
    groupId,
    workspaceId,
    defaultTearOutBounds(registry.main().bounds),
    newWindowId(),
)

fun tearOutCanvasLive(
    registry: WindowHostRegistry,
    workspaceId: String,
): WindowHost? = tearOutCanvas(
    registry,
    workspaceId,
    defaultTearOutBounds(registry.main().bounds),
    newWindowId(),
)

private fun groupById(node: LayoutNode, groupId: String): LayoutNode.Group? = when (node) {
    is LayoutNode.Group -> node.takeIf { it.id == groupId }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { groupById(it, groupId) }
}
