package dev.supermux.desktop.shell

import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds
import dev.supermux.workspace.groupIdOf
import dev.supermux.workspace.hideClaimed
import dev.supermux.workspace.splitGroup
import dev.supermux.workspace.subtreeCovering

data class WindowBounds(val x: Float, val y: Float, val width: Float, val height: Float)

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
        bounds = WindowBounds(0f, 0f, 0f, 0f),
        isMain = true,
    )
    private val extraHosts = linkedMapOf<String, WindowHost>()

    fun main(): WindowHost = mainHost

    fun extras(workspaceId: String): List<WindowHost> =
        extraHosts.values.filter { it.workspaceId == workspaceId }

    fun claimedUnion(workspaceId: String): Set<String> =
        extras(workspaceId).flatMap { it.claimedViewIds }.toSet()

    fun layoutFor(host: WindowHost, tree: LayoutNode): LayoutNode? {
        if (host.isMain) {
            if (extras(host.workspaceId).any { it.claimedViewIds.isEmpty() }) return null
            return hideClaimed(tree, claimedUnion(host.workspaceId))
        }
        if (host.claimedViewIds.isEmpty()) return tree
        return subtreeCovering(tree, host.claimedViewIds)
    }

    fun tryClaim(
        workspaceId: String,
        viewIds: Set<String>,
        bounds: WindowBounds,
        id: String,
    ): WindowHost? {
        if (viewIds.isEmpty()) return null
        if (id == mainHost.id || extraHosts.containsKey(id)) return null
        val existing = extras(workspaceId)
        if (existing.any { it.claimedViewIds.isEmpty() }) return null
        val union = claimedUnion(workspaceId)
        if (viewIds.any { it in union }) return null
        val host = WindowHost(
            id = id,
            workspaceId = workspaceId,
            claimedViewIds = viewIds,
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
        val snapshot = extras(workspaceId)
        for (host in snapshot) {
            if (host.claimedViewIds.isEmpty()) continue
            val remaining = host.claimedViewIds.filter { it in live }.toSet()
            if (remaining.isEmpty()) {
                extraHosts.remove(host.id)
                continue
            }
            val covering = subtreeCovering(tree, remaining)
            if (covering == null) {
                extraHosts.remove(host.id)
                continue
            }
            extraHosts[host.id] = host.copy(claimedViewIds = remaining)
        }
    }

    fun setWorkspaceOnMain(workspaceId: String) {
        mainHost = mainHost.copy(workspaceId = workspaceId)
    }
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
