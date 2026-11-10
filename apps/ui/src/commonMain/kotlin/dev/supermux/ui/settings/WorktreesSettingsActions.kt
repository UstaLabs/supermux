// The active host's chat worktrees: list, the folders whose repository is gone, changes, delete (#97: the machine
// page's Disk). Nothing is deleted on its own: a worktree only goes away because a person said so.
package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.supermux.net.WorktreeChangesDto
import dev.supermux.net.WorktreeDeleteResultDto
import dev.supermux.net.WorktreeListDto
import dev.supermux.net.WorktreeOrphanPageDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore

/**
 * Every broker call the machine page's Disk makes, in one holder — same shape as
 * [DevicesSettingsActions]/[ProxiesSettingsActions]: a `null` load is a transport/decode failure,
 * not "no worktrees".
 */
@Immutable
class WorktreesSettingsActions(
    /** `refresh` = ask the broker to rescan in the background. */
    val load: suspend (refresh: Boolean) -> WorktreeListDto? = { null },
    val orphans: suspend (offset: Int, limit: Int) -> WorktreeOrphanPageDto? = { _, _ -> null },
    val changes: suspend (id: String) -> WorktreeChangesDto? = { null },
    val delete: suspend (ids: List<String>) -> List<WorktreeDeleteResultDto>? = { null },
    /** Live sizes and remote deletions from HostState (`worktree_sizes` / `worktrees_removed`). */
    val sizes: @Composable () -> Map<String, Long> = { emptyMap() },
    val removedIds: @Composable () -> Set<String> = { emptySet() },
)

/** [WorktreesSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberWorktreesSettingsActions(app: HostStore): WorktreesSettingsActions = remember(app) {
    WorktreesSettingsActions(
        load = { app.worktrees(it) },
        orphans = { offset, limit -> app.worktreeOrphans(offset, limit) },
        changes = { app.worktreeChanges(it) },
        delete = { app.deleteWorktrees(it) },
        sizes = { app.state.collectAsState().value.worktreeSizes },
        removedIds = { app.state.collectAsState().value.removedWorktreeIds },
    )
}

/**
 * [WorktreesSettingsActions] against the fleet's ACTIVE host — Android's wiring.
 *
 * `fleet.activeApp()` (not a dedicated `activeHostState()`): FleetStore exposes no live-HostState
 * passthrough for this yet — the closest existing ones ([FleetStore.onboarded],
 * [FleetStore.activeProjectCatalog]) are `combine(hostApps, activeHost)`-backed `StateFlow`s, not a
 * plain accessor — so this reads the active host's own `state` directly, the same
 * `HostStore.state: StateFlow<HostState>` the desktop wiring above uses. `collectAsState()` is
 * keyed on the flow instance, so a host switch (a new `HostStore`) resubscribes correctly.
 */
@Composable
fun rememberWorktreesSettingsActions(fleet: FleetStore): WorktreesSettingsActions = remember(fleet) {
    WorktreesSettingsActions(
        load = { fleet.worktrees(it) },
        orphans = { offset, limit -> fleet.worktreeOrphans(offset, limit) },
        changes = { fleet.worktreeChanges(it) },
        delete = { fleet.deleteWorktrees(it) },
        sizes = { fleet.activeApp()?.state?.collectAsState()?.value?.worktreeSizes ?: emptyMap() },
        removedIds = { fleet.activeApp()?.state?.collectAsState()?.value?.removedWorktreeIds ?: emptySet() },
    )
}
