// The [ShellWindows] of a host that has more than one window: the shared shell's view of
// [WindowHostRegistry] (cluster G8; this was desktop's `DesktopShellWindows`).
//
// `ShellUiState` carries this object so the SHARED pane host can hide what another window claimed,
// claim a newly created view, and re-derive claims when the tree changes. How an extra window is
// actually shown — a desktop `Window {}`, an Android activity — is the host's business; a host with
// a single window installs `NoShellWindows` and none of this happens.
package dev.supermux.ui.shell.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.ui.shell.ShellWindows
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.collectViewIds

open class RegistryShellWindows(
    val registry: WindowHostRegistry = WindowHostRegistry(),
    /**
     * Whether a window waiting in [pending] keeps its workspace composed. Desktop: no — it
     * re-opens its saved windows only when the user gets back to their workspace. Android and
     * iPad: yes — there the SYSTEM restored the window, which is already on screen waiting for
     * the main window to compose its workspace so the registry can take the claim back.
     */
    private val keepPendingComposed: Boolean = false,
) : ShellWindows {

    /** Persisted extras not yet claimed (waiting for a matching workspace tree). */
    var pending by mutableStateOf<List<PersistedWindowHost>>(emptyList())

    override val mainHostId: String get() = registry.main().id

    override fun layoutFor(hostId: String, tree: LayoutNode): LayoutNode {
        val host = if (hostId == registry.main().id) {
            registry.main()
        } else {
            registry.extras().firstOrNull { it.id == hostId }
        } ?: return tree
        return registry.layoutFor(host, tree) ?: emptyHostLayout(tree)
    }

    override fun expandClaim(hostId: String, addedViewIds: Set<String>, tree: LayoutNode) {
        registry.expandClaim(hostId, addedViewIds, tree)
    }

    override fun transfer(viewId: String, toHostId: String, tree: LayoutNode) {
        registry.transfer(viewId, toHostId, tree)
    }

    override fun onWorkspaceTree(workspaceId: String, tree: LayoutNode) {
        registry.rebase(workspaceId, tree)
        tryRestore(workspaceId, tree)
    }

    override fun setWorkspaceOnMain(workspaceId: String) {
        registry.setWorkspaceOnMain(workspaceId)
    }

    override fun mainWorkspaceId(): String = registry.main().workspaceId

    override fun extraWorkspaceIds(): Set<String> =
        registry.extras().mapTo(mutableSetOf()) { it.workspaceId } +
            if (keepPendingComposed) pending.map { it.workspaceId } else emptyList()

    /** Hydrate persisted extras once the matching workspace tree is on screen. */
    fun tryRestore(workspaceId: String, tree: LayoutNode) {
        if (pending.isEmpty()) return
        val live = collectViewIds(tree).toSet()
        val leftover = mutableListOf<PersistedWindowHost>()
        for (p in pending) {
            if (p.workspaceId != workspaceId) {
                leftover += p
                continue
            }
            if (registry.extras().any { it.id == p.id }) continue
            // Views closed while the window was away are not coming back: claim what is left, and
            // a claim with nothing left is dropped — its window then closes, rather than waiting
            // forever for views that no longer exist. (An empty tree has not loaded yet: wait.)
            val surviving = p.claimedViewIds.filter { it in live }.toSet()
            if (p.claimedViewIds.isNotEmpty() && surviving.isEmpty() && live.isNotEmpty()) continue
            val bounds = WindowBounds(p.x, p.y, p.width, p.height)
            val claimed = if (p.claimedViewIds.isEmpty()) {
                registry.tryClaimCanvas(p.workspaceId, bounds, p.id)
            } else {
                registry.tryClaim(p.workspaceId, surviving, bounds, p.id, tree)
            }
            if (claimed == null) leftover += p
        }
        pending = leftover
    }

    /** What `ui-state.json` should carry now: unclaimed pending extras plus the live ones. */
    fun persistedExtras(): List<PersistedWindowHost> =
        mergePersistedWindowHosts(pending, registry.extras())
}
