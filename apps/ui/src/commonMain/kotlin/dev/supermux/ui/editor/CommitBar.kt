package dev.supermux.ui.editor

import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.Plurals
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.GitWriteResult
import dev.supermux.net.LatestResult
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural

import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem

/**
 * #72: the foot of the Changes view — a message field shaped like the composer, and one split button.
 * Its label says what a click does: "Commit" what is ticked, or "Commit all N" when nothing is.
 * The ▾ holds amend and undo. What the last action did shows as one quiet line beside it.
 */
@Composable
internal fun CommitBar(
    uncommitted: Int,
    included: Int,
    busy: Boolean,
    message: String,
    onMessage: (String) -> Unit,
    last: Pair<String, GitWriteResult?>?,
    onCommit: () -> Unit,
    onAmend: () -> Unit,
    onUndo: () -> Unit,
    /** A line that wins over [last] (what "Get latest" or a cancel did). */
    note: String? = null,
    /** Mid-merge: the button continues it, and only once nothing clashes. */
    merging: Boolean = false,
    clashing: Int = 0,
    /** "Get latest from <base>" in the ▾; null: not offered. */
    getLatestLabel: String? = null,
    onGetLatest: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(Space.md).testTag("commit_bar"),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (uncommitted > 0 && !merging) {
            BasicTextField(
                value = message,
                onValueChange = onMessage,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("commit_message"),
                decorationBox = { inner ->
                    Box {
                        if (message.isEmpty()) Text(localized(Str.git_commit_hint), color = cs.onSurfaceVariant, fontSize = 13.sp)
                        inner()
                    }
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note ?: last?.let { (op, r) -> outcome(op, r) }.orEmpty(),
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = Space.sm).testTag("commit_outcome"),
            )
            val canCommit = (uncommitted > 0 || merging) && clashing == 0 && !busy
            Box {
                Row(
                    Modifier.height(34.dp).clip(RoundedCornerShape(17.dp)).background(cs.primary),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .clickable(enabled = canCommit, onClick = onCommit)
                            .alpha(if (canCommit) 1f else 0.55f)
                            .padding(horizontal = 14.dp)
                            .height(34.dp)
                            .testTag("commit_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                        Text(
                            when {
                                merging -> localized(Str.git_continue_merge)
                                included > 0 || uncommitted == 0 -> localized(Str.git_commit)
                                else -> localizedPlural(Plurals.git_commit_all, uncommitted, uncommitted)
                            },
                            color = cs.onPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Box(Modifier.width(1.dp).height(20.dp).background(cs.onPrimary.copy(alpha = 0.25f)))
                    val more = localized(Str.git_more_commit_actions)
                    Box(
                        Modifier
                            .clickable(enabled = !busy) { menu = true }
                            .semantics { contentDescription = more }
                            .padding(horizontal = 9.dp)
                            .height(34.dp)
                            .testTag("commit_more"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (getLatestLabel != null) {
                        DropdownMenuItem(
                            text = { Text(getLatestLabel) },
                            onClick = { menu = false; onGetLatest() },
                            modifier = Modifier.testTag("commit_get_latest"),
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(localized(Str.git_amend)) },
                        onClick = { menu = false; onAmend() },
                        modifier = Modifier.testTag("commit_amend"),
                    )
                    DropdownMenuItem(
                        text = { Text(localized(Str.git_undo_commit)) },
                        onClick = { menu = false; onUndo() },
                        modifier = Modifier.testTag("commit_undo"),
                    )
                }
            }
        }
    }
}

@Composable
private fun outcome(op: String, r: GitWriteResult?): String {
    val why = (r?.message ?: r?.error).orEmpty().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    return when {
        r != null && r.ok && op == "uncommit" -> localized(Str.git_undone, r.subject.orEmpty())
        r != null && r.ok -> localized(Str.git_committed, r.subject.orEmpty())
        r?.reason == "nothing" -> localized(Str.git_nothing_to_commit)
        r?.reason == "pushed" -> localized(Str.git_undo_pushed)
        r?.reason == "merge" -> localized(Str.git_undo_merge)
        r?.reason == "none" -> localized(Str.git_undo_none)
        else -> localized(Str.git_commit_failed, why)
    }
}

/** What "Get latest from <base>" did, as one line. A clash is said by the banner above, in full. */
@Composable
internal fun latestNote(r: LatestResult, base: String): String = when (r.status) {
    "up_to_date" -> localized(Str.git_latest_up_to_date, base)
    "clean" -> localizedPlural(Plurals.git_latest_got, r.commits, r.commits, base)
    "conflict" -> localizedPlural(Plurals.git_conflict_banner, r.files.size, r.files.size, base)
    else -> localized(Str.git_latest_failed, r.message.orEmpty().lineSequence().firstOrNull().orEmpty())
}
