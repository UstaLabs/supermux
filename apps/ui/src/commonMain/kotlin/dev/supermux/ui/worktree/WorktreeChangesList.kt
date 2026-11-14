package dev.supermux.ui.worktree

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.WorktreeChangesDto
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural

/** What would be lost with a worktree: uncommitted files and unmerged commits. Gitignored
 *  entries are listed too, as ignored — they never count as changes.
 *  "No changes" is shown ONLY when the broker verified it: no inspection [WorktreeChangesDto.error]
 *  and a known base branch ([WorktreeChangesDto.unmergedKnown]) — otherwise unmerged commits
 *  could be hiding behind empty lists. */
@Composable
fun WorktreeChangesList(changes: WorktreeChangesDto, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    changes.error?.let { err ->
        Text(localized(Str.worktree_couldnt_inspect, err), color = cs.error, fontSize = 13.sp, modifier = modifier.testTag("wt_changes_error"))
        return
    }
    Column(modifier) {
        if (changes.unmergedKnown && changes.files.isEmpty() && changes.commits.isEmpty()) {
            Text(localized(Str.worktree_no_changes), color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
        if (changes.files.isNotEmpty()) {
            val n = changes.files.size + changes.truncated.files
            ExpandRow(localizedPlural(Plurals.worktree_files_changed, n, n), "wt_files_summary") {
                changes.files.forEach { f ->
                    Row {
                        Mono(f.status, Modifier.padding(end = 8.dp))
                        Mono(f.path)
                    }
                }
                More(changes.truncated.files)
            }
        }
        if (changes.commits.isNotEmpty()) {
            val n = changes.commits.size + changes.truncated.commits
            ExpandRow(localizedPlural(Plurals.worktree_unmerged_commits, n, n), "wt_commits_summary") {
                changes.commits.forEach { Mono("${it.sha}  ${it.subject}") }
                More(changes.truncated.commits)
            }
        }
        if (!changes.unmergedKnown) {
            Text(
                localized(Str.worktree_unmerged_unknown),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 4.dp).testTag("wt_commits_unknown"),
            )
        }
        if (changes.ignored.isNotEmpty()) {
            val label = localized(Str.worktree_ignored, changes.ignored.joinToString(" · ") { "${it.name}/" })
            ExpandRow(label, "wt_ignored_summary") {
                changes.ignored.forEach { e ->
                    Text(
                        "${e.name}/",
                        color = cs.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.testTag("wt_ignored_${tagSafe(e.name)}"),
                    )
                }
                More(changes.truncated.ignored)
            }
        }
    }
}

@Composable
private fun ExpandRow(label: String, tag: String, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp).testTag(tag)) {
            Text((if (open) "▾ " else "▸ ") + label, fontSize = 13.sp)
        }
        if (open) {
            Column(Modifier.padding(start = 16.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) { content() }
        }
    }
}

@Composable private fun Mono(text: String, modifier: Modifier = Modifier) =
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = modifier)

@Composable private fun More(n: Int) {
    if (n > 0) Text(localized(Str.worktree_more, n), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}
