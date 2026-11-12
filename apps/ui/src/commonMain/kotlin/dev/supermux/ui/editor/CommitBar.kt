package dev.supermux.ui.editor

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
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.git_amend
import dev.supermux.ui.resources.git_commit
import dev.supermux.ui.resources.git_commit_all
import dev.supermux.ui.resources.git_commit_failed
import dev.supermux.ui.resources.git_commit_hint
import dev.supermux.ui.resources.git_committed
import dev.supermux.ui.resources.git_more_commit_actions
import dev.supermux.ui.resources.git_nothing_to_commit
import dev.supermux.ui.resources.git_undo_commit
import dev.supermux.ui.resources.git_undo_merge
import dev.supermux.ui.resources.git_undo_none
import dev.supermux.ui.resources.git_undo_pushed
import dev.supermux.ui.resources.git_undone
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
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(cs.surfaceContainer).padding(Space.md).testTag("commit_bar"),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (uncommitted > 0) {
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
                        if (message.isEmpty()) Text(localized(Res.string.git_commit_hint), color = cs.onSurfaceVariant, fontSize = 13.sp)
                        inner()
                    }
                },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                last?.let { (op, r) -> outcome(op, r) }.orEmpty(),
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = Space.sm).testTag("commit_outcome"),
            )
            val canCommit = uncommitted > 0 && !busy
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
                            if (included > 0 || uncommitted == 0) localized(Res.string.git_commit)
                            else localizedPlural(Res.plurals.git_commit_all, uncommitted, uncommitted),
                            color = cs.onPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Box(Modifier.width(1.dp).height(20.dp).background(cs.onPrimary.copy(alpha = 0.25f)))
                    val more = localized(Res.string.git_more_commit_actions)
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
                    DropdownMenuItem(
                        text = { Text(localized(Res.string.git_amend)) },
                        onClick = { menu = false; onAmend() },
                        modifier = Modifier.testTag("commit_amend"),
                    )
                    DropdownMenuItem(
                        text = { Text(localized(Res.string.git_undo_commit)) },
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
        r != null && r.ok && op == "uncommit" -> localized(Res.string.git_undone, r.subject.orEmpty())
        r != null && r.ok -> localized(Res.string.git_committed, r.subject.orEmpty())
        r?.reason == "nothing" -> localized(Res.string.git_nothing_to_commit)
        r?.reason == "pushed" -> localized(Res.string.git_undo_pushed)
        r?.reason == "merge" -> localized(Res.string.git_undo_merge)
        r?.reason == "none" -> localized(Res.string.git_undo_none)
        else -> localized(Res.string.git_commit_failed, why)
    }
}
