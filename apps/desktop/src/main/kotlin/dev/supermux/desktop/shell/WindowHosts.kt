package dev.supermux.desktop.shell

import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.hideClaimed
import dev.supermux.workspace.splitGroup
import dev.supermux.workspace.subtreeCovering

data class WindowBounds(val x: Float, val y: Float, val width: Float, val height: Float)

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
    private var mainHost = WindowHost(
        id = mainId,
        workspaceId = "",
        claimedViewIds = emptySet(),
        bounds = WindowBounds(0f, 0f, 1440f, 900f),
        isMain = true,
    )
    private val extraHosts = linkedMapOf<String, WindowHost>()

    fun main(): WindowHost = mainHost

    fun extras(): List<WindowHost> = extraHosts.values.toList()

    fun extras(workspaceId: String): List<WindowHost> =
        extraHosts.values.filter { it.workspaceId == workspaceId }

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
        return host
    }

    fun unclaim(hostId: String) {
        if (hostId == mainHost.id) return
        extraHosts.remove(hostId)
    }

    fun rebase(workspaceId: String, tree: LayoutNode) {
        val live = collectViewIds(tree).toSet()
        val taken = mutableSetOf<String>()
        val snapshot = extras(workspaceId)
        for (host in snapshot) {
            if (host.claimedViewIds.isEmpty()) continue
            val remaining = host.claimedViewIds.filter { it in live && it !in taken }.toSet()
            if (remaining.isEmpty() || subtreeCovering(tree, remaining) == null) {
                extraHosts.remove(host.id)
                continue
            }
            extraHosts[host.id] = host.copy(claimedViewIds = remaining)
            taken += remaining
        }
    }

    fun setWorkspaceOnMain(workspaceId: String) {
        mainHost = mainHost.copy(workspaceId = workspaceId)
    }

    private fun liveHost(id: String): WindowHost? =
        if (id == mainHost.id) mainHost else extraHosts[id]
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

private fun groupById(node: LayoutNode, groupId: String): LayoutNode.Group? = when (node) {
    is LayoutNode.Group -> node.takeIf { it.id == groupId }
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { groupById(it, groupId) }
}
