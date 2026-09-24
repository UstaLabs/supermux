package dev.supermux.ui.worktree

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.WorktreeDeleteResultDto
import dev.supermux.net.WorktreeForWorkdirDto
import dev.supermux.ui.platform.NoticeChannel

/** What the three archive dialogs need to offer "also delete the worktree". */
@Immutable
class WorktreeCleanupActions(
    val forWorkdir: suspend (workdir: String) -> WorktreeForWorkdirDto? = { null },
)

/**
 * Spec 2026-09-22-explicit-worktree-cleanup §4.2. Unchecked by default, always. A worktree a
 * live session outside [archivingSessionIds] still uses is never offered — it gets a note.
 */
@Composable
fun WorktreeCleanupSection(
    workdirs: List<String>,
    archivingSessionIds: Set<String>,
    load: suspend (String) -> WorktreeForWorkdirDto?,
    /** `ids` = exactly the deletable worktree ids this section displayed; the dialog sends them
     *  as the archive route's repeated `deleteWorktree=<id>` params. */
    onDeleteChange: (checked: Boolean, ids: List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var loading by remember(workdirs) { mutableStateOf(true) }
    var found by remember(workdirs) { mutableStateOf<List<WorktreeForWorkdirDto>>(emptyList()) }
    var checked by remember(workdirs) { mutableStateOf(false) }
    LaunchedEffect(workdirs) {
        // A reload shows a fresh, unchecked section — tell the dialog too, so it can never send a
        // stale "checked" with the previous worktrees' ids (final review m2).
        onDeleteChange(false, emptyList())
        loading = true
        found = workdirs.distinct().mapNotNull { runCatching { load(it) }.getOrNull() }.distinctBy { it.id }
        loading = false
    }
    if (!loading && found.isEmpty()) return
    Column(modifier.padding(top = 12.dp)) {
        if (loading) {
            Row {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("  Checking worktree…", fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
            return@Column
        }
        // Every found worktree is rendered: a kept one gets its own note, the rest are deletable.
        val keptBy = found.associate { w -> w.id to w.owners.filter { it.isLive && it.id !in archivingSessionIds }.map { it.name }.distinct() }
        val deletable = found.filter { keptBy[it.id].isNullOrEmpty() }
        for (w in found) {
            val by = keptBy[w.id].orEmpty()
            if (by.isNotEmpty()) {
                Text(
                    "Worktree kept — also used by ${by.joinToString()}", fontSize = 12.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.testTag("wt_cleanup_kept"),
                )
            }
        }
        if (deletable.isEmpty()) return@Column
        val ids = deletable.map { it.id }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = { checked = it; onDeleteChange(it, ids) },
                modifier = Modifier.testTag("wt_cleanup_checkbox"),
            )
            val label = if (deletable.size == 1) {
                val w = deletable.single()
                "Also delete worktree · ${w.branch ?: w.id} · ${formatBytes(w.bytes)}"
            } else {
                val known = deletable.mapNotNull { it.bytes }
                "Also delete ${deletable.size} worktrees" + if (known.size == deletable.size) " · ${formatBytes(known.sum())}" else ""
            }
            Text(label, fontSize = 13.sp)
        }
        for (w in deletable) {
            if (deletable.size > 1) {
                Text("${w.branch ?: w.id} · ${formatBytes(w.bytes)}", fontSize = 12.sp, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
            }
            WorktreeChangesList(w.changes, Modifier.padding(start = 12.dp))
        }
    }
}

/** Which dialog ran the archive-and-delete, for the notice's wording. */
enum class WorktreeDeleteAction(val verb: String, val done: String) {
    Archive("archive", "Archived"),
    Close("close", "Closed"),
}

/** Snackbar/toast after "Archive & delete" (or Settle/Close & delete): silent on success, one
 *  line on any failure. `null` results = the archive/close request itself failed (so nothing was
 *  deleted either). `in_use` is not an error here — the dialog already told the user that
 *  worktree would be kept — so the notice names the first OTHER failure. */
fun reportWorktreeDelete(
    notices: NoticeChannel,
    results: List<WorktreeDeleteResultDto>?,
    action: WorktreeDeleteAction = WorktreeDeleteAction.Archive,
) {
    if (results == null) {
        notices.show("Couldn't ${action.verb}: broker unreachable or the request was refused")
        return
    }
    val failure = results.firstOrNull { !it.ok && it.error != "in_use" } ?: return
    notices.show("${action.done}, but couldn't delete worktree: ${failure.error ?: "unknown error"}")
}
