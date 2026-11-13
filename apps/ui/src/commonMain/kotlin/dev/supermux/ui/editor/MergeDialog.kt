package dev.supermux.ui.editor

import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.Plurals
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.FinishReadiness
import dev.supermux.proto.FinishJobDto
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.widgets.Dialog

/**
 * #72: "Merge into <base>" — what goes where, said plainly, and the one choice that matters (run the
 * project's checks first). Merging never closes the chat. [readiness] null = still loading.
 */
@Composable
internal fun MergeDialog(base: String, commits: Int, readiness: FinishReadiness?, onMerge: (runChecks: Boolean) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var runChecks by remember { mutableStateOf(false) }
    val uncommitted = readiness?.dirtyFiles.orEmpty().isNotEmpty()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(cs.surfaceContainerHigh)
                .padding(22.dp)
                .testTag("merge_dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(localizedPlural(Plurals.git_merge_title, commits, commits, base), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            Text(localized(Str.git_merge_body, base), fontSize = 13.5.sp, color = cs.onSurfaceVariant)
            if (readiness?.conflictPreflight == "will_conflict") Text(localized(Str.git_merge_clash_warning, base), fontSize = 13.5.sp, color = cs.onSurfaceVariant)
            if (uncommitted) Text(localized(Str.git_merge_uncommitted), fontSize = 13.5.sp, color = cs.error)
            Row(
                Modifier.clickable { runChecks = !runChecks }.padding(vertical = 6.dp).testTag("merge_run_checks"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val shape = RoundedCornerShape(4.dp)
                Box(
                    Modifier.size(16.dp).then(if (runChecks) Modifier.background(cs.primary, shape) else Modifier.border(1.5.dp, cs.onSurfaceVariant, shape)),
                    contentAlignment = Alignment.Center,
                ) { if (runChecks) Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(12.dp)) }
                Text(localized(Str.git_merge_run_checks), fontSize = 13.5.sp, color = cs.onSurface)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Pill(localized(Str.git_cancel), primary = false, enabled = true, tag = "merge_cancel", onClick = onDismiss)
                Pill(localized(Str.git_merge_confirm, base), primary = true, enabled = readiness != null && !uncommitted, tag = "merge_confirm") { onMerge(runChecks) }
            }
        }
    }
}

@Composable
private fun Pill(text: String, primary: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        color = if (primary) cs.onPrimary else cs.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(if (primary) cs.primary else cs.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .padding(horizontal = 16.dp, vertical = 9.dp)
            .testTag(tag),
    )
}

/** The merge job as one line: while it runs, and how it ended. */
@Composable
internal fun mergeNote(job: FinishJobDto, base: String): String {
    val o = job.outcome
    return when {
        job.status == "running" -> localized(Str.git_merging, base)
        o == null -> localized(Str.git_merge_failed, "")
        o.status == "integrated" -> localized(Str.git_merged, base)
        o.status == "sync_conflict" -> localizedPlural(Plurals.git_conflict_banner, o.files.size, o.files.size, base)
        o.status == "tests_failed" -> localized(Str.git_merge_tests_failed)
        o.status == "no_verify" -> localized(Str.git_merge_no_checks)
        o.status == "dirty_overlap" -> localized(Str.git_merge_dirty_base, base)
        o.status == "non_ff" -> localized(Str.git_merge_moved, base)
        o.status == "nothing_to_do" -> localized(Str.git_merge_nothing, base)
        o.status == "uncommitted" -> localized(Str.git_merge_uncommitted)
        else -> localized(Str.git_merge_failed, o.message.orEmpty().lineSequence().firstOrNull().orEmpty())
    }
}
