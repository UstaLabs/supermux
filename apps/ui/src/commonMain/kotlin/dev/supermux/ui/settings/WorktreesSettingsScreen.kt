// Settings › Worktrees (cluster part 2, task 6 of the explicit-worktree-cleanup plan): the
// tens-of-thousands-of-rows deletion screen. Rows group by project, a live-owned worktree is
// locked (checkbox disabled, a note instead), "Select all without changes" trusts the broker's
// own `hasChanges` (it already reports true for anything uncertain — repo gone, uninspectable,
// unknown base), and one confirm warns how many selected rows have changes that would be lost.
// The list only ever loads on open or a manual refresh — no polling; live sizes/removals ride in
// from HostState (`worktree_sizes` / `worktrees_removed`) instead.
package dev.supermux.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.WorktreeChangesDto
import dev.supermux.net.WorktreeDeleteResultDto
import dev.supermux.net.WorktreeSummaryDto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.ui.worktree.WorktreeChangesList
import dev.supermux.ui.worktree.formatAge
import dev.supermux.ui.worktree.formatBytes
import dev.supermux.ui.worktree.plural
import kotlin.time.Clock
import kotlinx.coroutines.launch

/**
 * Every broker call the Worktrees screen makes, in one holder — same shape as
 * [DevicesSettingsActions]/[ProxiesSettingsActions]: a `null` load is a transport/decode failure,
 * not "no worktrees".
 */
@Immutable
class WorktreesSettingsActions(
    val load: suspend () -> List<WorktreeSummaryDto>? = { null },
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
        load = { app.worktrees() },
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
        load = { fleet.worktrees() },
        changes = { fleet.worktreeChanges(it) },
        delete = { fleet.deleteWorktrees(it) },
        sizes = { fleet.activeApp()?.state?.collectAsState()?.value?.worktreeSizes ?: emptyMap() },
        removedIds = { fleet.activeApp()?.state?.collectAsState()?.value?.removedWorktreeIds ?: emptySet() },
    )
}

private enum class WtSort(val label: String) { Size("Size"), Age("Age"), Project("Project") }

/**
 * Settings › Worktrees: list every worktree the active host knows about, grouped by project, and
 * delete a batch explicitly. No auto-delete anywhere — the whole point of this screen (and the
 * archive-dialog checkbox) is that a worktree only ever goes away because a person said so.
 *
 * @param onBack leave the screen; only reachable from the top bar this screen paints for itself
 *   when [topBarShown] is false.
 * @param topBarShown the hub already painted a top bar for this detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorktreesSettingsScreen(
    actions: WorktreesSettingsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableStateOf(0) }
    var items by remember { mutableStateOf<List<WorktreeSummaryDto>?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var rowErrors by remember { mutableStateOf(mapOf<String, String>()) }
    var sort by remember { mutableStateOf(WtSort.Size) }
    var confirming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    /** (done, total) while a chunked delete runs, else null. */
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /** Deleted from this screen since the last load — filtered out without re-sorting. */
    var deleted by remember { mutableStateOf(setOf<String>()) }
    /** Bumped per successful load, so a refresh re-sorts even when the list comes back equal. */
    var loadEpoch by remember { mutableStateOf(0) }
    val sizes = actions.sizes()
    val removed = actions.removedIds()

    LaunchedEffect(reloadKey) {
        val r = actions.load()
        loadError = r == null
        if (r != null) { items = r; loadEpoch++; deleted = emptySet(); selected = emptySet(); rowErrors = emptyMap() }
    }

    // Row ORDER depends only on the loaded list and the sort — never on the live `sizes`, or every
    // `worktree_sizes` frame would move rows under the user's finger (final review I-3). The size
    // sort reads a snapshot of the sizes known at load/refresh/sort-change time; a manual refresh
    // re-sorts. Sizes themselves are looked up per row at draw time ([sizeOf]).
    // Sorted once per list change, not per row/recomposition — the list can run into the tens of
    // thousands of rows (mostly "repo gone" test leftovers), so this must stay O(n log n) per change.
    val ordered = remember(items, loadEpoch, sort) {
        val list = items ?: emptyList()
        when (sort) {
            WtSort.Size -> list.sortedByDescending { sizes[it.id] ?: it.bytes ?: -1 }
            WtSort.Age -> list.sortedBy { it.mtime }
            WtSort.Project -> list.sortedBy { it.repoName }
        }
    }
    // A removal only filters (keeps the order); groups are per repository ROOT, not per name.
    val visible = remember(ordered, removed, deleted) { ordered.filter { it.id !in removed && it.id !in deleted } }
    val groups = remember(visible) { groupByRepo(visible) }
    val sizeOf: (WorktreeSummaryDto) -> Long? = { w -> sizes[w.id] ?: w.bytes }
    val selectedSet = selected
    val selectedItems = remember(visible, selectedSet) { visible.filter { it.id in selectedSet } }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (!topBarShown) {
                TopAppBar(
                    title = { Text("Worktrees", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("worktrees_settings_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${visible.size} · ${formatBytes(visible.sumOf { sizeOf(it) ?: 0L })}",
                    Modifier.weight(1f),
                    fontSize = 13.sp,
                )
                TextButton(
                    modifier = Modifier.testTag("wt_select_clean"),
                    onClick = { selected = visible.filter { it.liveOwners.isEmpty() && !it.hasChanges }.map { it.id }.toSet() },
                ) { Text("Select all without changes") }
                var sortOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sortOpen = true }) { Text("Sort: ${sort.label} ▾") }
                    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                        WtSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { sort = s; sortOpen = false })
                        }
                    }
                }
                IconButton(onClick = { reloadKey++ }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
            }
            when {
                items == null && loadError ->
                    Text("Couldn't load worktrees.", Modifier.padding(16.dp), color = cs.error)
                items == null -> CircularProgressIndicator(Modifier.padding(16.dp))
                visible.isEmpty() -> Text("No worktrees.", Modifier.padding(16.dp))
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 72.dp)) {
                    groups.forEach { g ->
                        val rows = g.rows
                        item(key = "g_${g.key}") {
                            Text(
                                "${g.label}  ${rows.size} · ${formatBytes(rows.sumOf { sizeOf(it) ?: 0L })}",
                                Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                            )
                        }
                        items(rows, key = { it.id }) { w ->
                            WorktreeRow(
                                w = w,
                                bytes = sizeOf(w),
                                checked = w.id in selectedSet,
                                error = rowErrors[w.id],
                                onCheck = { on -> selected = if (on) selected + w.id else selected - w.id },
                                loadChanges = { actions.changes(w.id) },
                            )
                        }
                    }
                }
            }
        }
        // The bar reflects only the VISIBLE selection (a row removed elsewhere drops out) and hides
        // at 0 (m7). While a batch runs its progress shows in the confirm dialog instead.
        if (progress == null && selectedItems.isNotEmpty()) {
            Button(
                onClick = { confirming = true },
                colors = ButtonDefaults.buttonColors(containerColor = cs.error),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().testTag("wt_delete_bar"),
            ) { Text("Delete ${selectedItems.size} worktrees · ${formatBytes(selectedItems.sumOf { sizeOf(it) ?: 0L })}") }
        }
    }

    if (confirming) {
        val withChanges = selectedItems.count { it.hasChanges }
        AlertDialog(
            onDismissRequest = { if (!deleting) confirming = false },
            title = { Text(if (progress != null) "Deleting worktrees" else "Delete ${selectedItems.size} worktrees?") },
            text = {
                val p = progress
                if (p != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text(deletingLabel(p), Modifier.testTag("wt_delete_progress"))
                    }
                } else Text(
                    buildString {
                        append("Deletes the folders and their mux/ branches. This can't be undone.")
                        if (withChanges > 0) {
                            append("\n\n$withChanges of them ${if (withChanges == 1) "has" else "have"} changes that will be lost.")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    modifier = Modifier.testTag("wt_delete_confirm"),
                    onClick = {
                        deleting = true
                        val ids = selectedItems.map { it.id }
                        progress = 0 to ids.size
                        scope.launch {
                            // Chunks of [DELETE_CHUNK], one after another: the broker deletes a
                            // batch sequentially, so a big one would otherwise sit silent for
                            // minutes. Each chunk's outcome is applied at once (rows removed /
                            // inline errors), so partial progress is never lost (final review I-1).
                            try {
                                var done = 0
                                for (chunk in ids.chunked(DELETE_CHUNK)) {
                                    val res = actions.delete(chunk)
                                    if (res == null) {
                                        // Transport failure: mark this chunk, stop; the rest stay selected.
                                        rowErrors = rowErrors + chunk.associateWith { "Broker unreachable" }
                                        break
                                    }
                                    val ok = res.filter { it.ok }.map { it.id }.toSet()
                                    deleted = deleted + ok
                                    rowErrors = rowErrors - ok + res.filter { !it.ok }.associate { r ->
                                        r.id to if (r.error == "in_use") "In use by ${r.inUseBy.joinToString()}" else (r.error ?: "Failed")
                                    }
                                    selected = selected - ok
                                    done += chunk.size
                                    progress = done to ids.size
                                }
                            } finally {
                                deleting = false
                                confirming = false
                                progress = null
                            }
                        }
                    },
                ) { Text("Delete", color = cs.error) }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/** Rows per DELETE /worktrees request (final review I-1). */
private const val DELETE_CHUNK = 20

private fun deletingLabel(p: Pair<Int, Int>) = "Deleting ${p.first} / ${p.second}…"

private class RepoGroup(val key: String, val label: String, val rows: List<WorktreeSummaryDto>)

/** Group by repository ROOT (two clones both called "app" are different projects), labelled by
 *  repo name; names shared by several roots get the root's parent dir to tell them apart. Keeps
 *  the incoming row order within and across groups (first appearance). */
private fun groupByRepo(rows: List<WorktreeSummaryDto>): List<RepoGroup> {
    val byKey = rows.groupBy { it.repoRoot ?: "name:${it.repoName}" }
    val rootsPerName = byKey.values.groupBy { it.first().repoName }.mapValues { it.value.size }
    return byKey.map { (key, groupRows) ->
        val first = groupRows.first()
        val label = if ((rootsPerName[first.repoName] ?: 1) > 1) {
            "${first.repoName} (${first.repoRoot?.substringBeforeLast('/')?.ifEmpty { "/" } ?: "unknown location"})"
        } else {
            first.repoName
        }
        RepoGroup(key, label, groupRows)
    }
}

@Composable
private fun WorktreeRow(
    w: WorktreeSummaryDto,
    bytes: Long?,
    checked: Boolean,
    error: String?,
    onCheck: (Boolean) -> Unit,
    loadChanges: suspend () -> WorktreeChangesDto?,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember(w.id) { mutableStateOf(false) }
    var changes by remember(w.id) { mutableStateOf<WorktreeChangesDto?>(null) }
    LaunchedEffect(open) { if (open && changes == null) changes = loadChanges() }
    val live = w.liveOwners
    Column(
        Modifier.fillMaxWidth().testTag("wt_row_${w.id}").clickable { open = !open }.padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                enabled = live.isEmpty(),
                onCheckedChange = onCheck,
                modifier = Modifier.testTag("wt_check_${w.id}"),
            )
            Column(Modifier.weight(1f)) {
                Text(w.branch ?: w.id.substringAfter('/'), fontSize = 14.sp)
                val owner = when {
                    live.isNotEmpty() -> "In use by ${live.joinToString { it.name }}"
                    w.owners.isNotEmpty() -> "${w.owners.first().name} (archived)"
                    else -> "No session"
                }
                val summary = when {
                    w.error != null -> "Couldn't inspect: ${w.error}"
                    !w.hasChanges -> "No changes"
                    else -> listOfNotNull(
                        w.uncommitted.takeIf { it > 0 }?.let { plural(it, "change", "changes") },
                        w.unmerged?.takeIf { it > 0 }?.let { plural(it, "unmerged commit", "unmerged commits") },
                        w.ignored.filterNot { it.wellKnown }.takeIf { it.isNotEmpty() }
                            ?.let { entries -> "ignored " + entries.joinToString { e -> "${e.name}/" } },
                    ).joinToString(" · ").ifEmpty { "Changes" }
                }
                Text(
                    "$owner · $summary · ${formatAge(nowMs(), w.mtime)} · ${formatBytes(bytes)}",
                    fontSize = 12.sp,
                    color = cs.onSurfaceVariant,
                )
                if (error != null) {
                    Text(error, fontSize = 12.sp, color = cs.error, modifier = Modifier.testTag("wt_error_${w.id}"))
                }
            }
            if (live.isNotEmpty()) Icon(Icons.Filled.Lock, contentDescription = "In use", tint = cs.onSurfaceVariant)
        }
        if (open) changes?.let { WorktreeChangesList(it, Modifier.padding(start = 48.dp)) }
    }
}

private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
