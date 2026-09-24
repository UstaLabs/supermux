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

/** What would be lost with a worktree: uncommitted files, unmerged commits, ignored entries.
 *  Ignored build output (well-known) is gray; anything else ignored (e.g. docs/) is normal text,
 *  because that is where agent work hides from `git status` (2026-09-22).
 *  "No changes" is shown ONLY when the broker verified it: no inspection [WorktreeChangesDto.error]
 *  and a known base branch ([WorktreeChangesDto.unmergedKnown]) — otherwise unmerged commits
 *  could be hiding behind empty lists. */
@Composable
fun WorktreeChangesList(changes: WorktreeChangesDto, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    changes.error?.let { err ->
        Text("Couldn't inspect: $err", color = cs.error, fontSize = 13.sp, modifier = modifier.testTag("wt_changes_error"))
        return
    }
    if (changes.unmergedKnown && changes.files.isEmpty() && changes.commits.isEmpty() && changes.ignored.isEmpty()) {
        Text("No changes", color = cs.onSurfaceVariant, fontSize = 13.sp, modifier = modifier)
        return
    }
    Column(modifier) {
        if (changes.files.isNotEmpty()) {
            ExpandRow(plural(changes.files.size + changes.truncated.files, "file changed", "files changed"), "wt_files_summary") {
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
            ExpandRow(plural(changes.commits.size + changes.truncated.commits, "unmerged commit", "unmerged commits"), "wt_commits_summary") {
                changes.commits.forEach { Mono("${it.sha}  ${it.subject}") }
                More(changes.truncated.commits)
            }
        }
        if (!changes.unmergedKnown) {
            Text(
                "Unmerged commits: unknown (base branch not found)",
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 4.dp).testTag("wt_commits_unknown"),
            )
        }
        if (changes.ignored.isNotEmpty()) {
            val label = "Ignored: " + changes.ignored.joinToString(" · ") { "${it.name}/" }
            ExpandRow(label, "wt_ignored_summary") {
                changes.ignored.forEach { e ->
                    val tag = if (e.wellKnown) "wt_ignored_${tagSafe(e.name)}_muted" else "wt_ignored_${tagSafe(e.name)}"
                    Text(
                        "${e.name}/  ${formatBytes(e.bytes)}",
                        color = if (e.wellKnown) cs.onSurfaceVariant.copy(alpha = 0.6f) else cs.onSurface,
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        modifier = Modifier.testTag(tag),
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
    if (n > 0) Text("+$n more", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
}
