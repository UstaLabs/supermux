package dev.supermux.desktop.shell

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

/** Workspaces that must keep a [WorkspaceSession] composed: the selected one plus every extra window. */
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

    fun rebase(workspaceId: String, tree: LayoutNode) {
        val live = collectViewIds(tree).toSet()
        val taken = mutableSetOf<String>()
        val snapshot = extras(workspaceId)
        var changed = false
        for (host in snapshot) {
            if (host.claimedViewIds.isEmpty()) continue
            val remaining = host.claimedViewIds.filter { it in live && it !in taken }.toSet()
            if (remaining.isEmpty() || subtreeCovering(tree, remaining) == null) {
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
    newGroupId = java.util.UUID.randomUUID().toString(),
    bounds = defaultTearOutBounds(registry.main().bounds),
    hostId = java.util.UUID.randomUUID().toString(),
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
    java.util.UUID.randomUUID().toString(),
)

fun tearOutCanvasLive(
    registry: WindowHostRegistry,
    workspaceId: String,
): WindowHost? = tearOutCanvas(
    registry,
    workspaceId,
    defaultTearOutBounds(registry.main().bounds),
    java.util.UUID.randomUUID().toString(),
)

private fun groupById(node: LayoutNode, groupId: String): LayoutNode.Group? = when (node) {
    is LayoutNode.Group -> node.takeIf { it.id == groupId }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { groupById(it, groupId) }
}
