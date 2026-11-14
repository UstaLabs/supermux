package dev.supermux.ui.editor

import dev.supermux.ui.i18n.Str
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.GitHistory
import dev.supermux.net.LogCommit
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.Dialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem

/**
 * #72: the History tab — this chat's commits (what its branch has that its base doesn't), then the
 * base's, newest first, on one thin lane. A commit's menu: copy its hash, revert it, branch or tag here.
 */
@Composable
internal fun HistoryList(
    h: GitHistory?,
    busy: Boolean,
    onRevert: (LogCommit) -> Unit,
    onName: (LogCommit, kind: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // Read here, not in DiffView: only the History tab needs the clipboard.
    val platform = dev.supermux.ui.platform.LocalPlatform.current
    val onCopy: (LogCommit) -> Unit = { c -> platform.copyToClipboard(c.sha) }
    LazyColumn(modifier.fillMaxWidth().testTag("git_history")) {
        item { GroupLabel(localized(Str.git_history_chat), h?.branch) }
        if (h != null && h.chat.isEmpty()) item {
            Text(localized(Str.git_history_empty), fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm))
        }
        items(h?.chat.orEmpty(), key = { "c:" + it.sha }) { CommitRow(it, own = true, busy, onCopy, onRevert, onName) }
        val base = h?.base
        if (base != null && h.baseCommits.isNotEmpty()) {
            item { GroupLabel(base, null) }
            items(h.baseCommits, key = { "b:" + it.sha }) { CommitRow(it, own = false, busy, onCopy, onRevert, onName) }
        }
    }
}

@Composable
private fun GroupLabel(text: String, right: String?) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = Space.md, top = Space.md, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (right != null) Text(right, fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CommitRow(
    c: LogCommit,
    own: Boolean,
    busy: Boolean,
    onCopy: (LogCommit) -> Unit,
    onRevert: (LogCommit) -> Unit,
    onName: (LogCommit, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp).clickable(enabled = !busy) { menu = true }.padding(horizontal = 14.dp).testTag("git_commit_${c.sha.take(7)}"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.width(14.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(cs.outlineVariant))
                Box(
                    Modifier.padding(top = 15.dp).size(9.dp).clip(CircleShape).background(cs.surface)
                        .border(2.dp, if (own) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.6f), CircleShape),
                )
            }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(c.subject, fontSize = 13.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.author} · ${c.sha.take(7)}", fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.onSurfaceVariant)
                    if (c.pushed == true) Text(
                        localized(Str.git_pushed), fontSize = 10.5.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(localized(Str.git_copy_hash)) }, onClick = { menu = false; onCopy(c) }, modifier = Modifier.testTag("git_copy_hash"))
            if (own) DropdownMenuItem(text = { Text(localized(Str.git_revert)) }, onClick = { menu = false; onRevert(c) }, modifier = Modifier.testTag("git_revert"))
            DropdownMenuItem(text = { Text(localized(Str.git_branch_here)) }, onClick = { menu = false; onName(c, "branch") }, modifier = Modifier.testTag("git_branch_here"))
            DropdownMenuItem(text = { Text(localized(Str.git_tag_here)) }, onClick = { menu = false; onName(c, "tag") }, modifier = Modifier.testTag("git_tag_here"))
        }
    }
}

/** Names a new branch or tag; nothing is checked out and nothing moves. */
@Composable
internal fun NameRefDialog(kind: String, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(26.dp)).background(cs.surfaceContainerHigh).padding(22.dp).testTag("git_name_dialog"),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(localized(if (kind == "branch") Str.git_name_branch else Str.git_name_tag), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            BasicTextField(
                value = name,
                onValueChange = { name = it.replace("\n", "") },
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontFamily = MonoFontFamily, fontSize = 13.sp),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 10.dp).testTag("git_name_field"),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                Text(
                    localized(Str.git_cancel), fontSize = 13.sp, color = cs.onSurface,
                    modifier = Modifier.clip(RoundedCornerShape(17.dp)).background(cs.surfaceContainerHighest).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 9.dp),
                )
                val ok = name.isNotBlank()
                Text(
                    localized(Str.git_create), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(17.dp)).background(cs.primary).clickable(enabled = ok) { onCreate(name.trim()) }
                        .alpha(if (ok) 1f else 0.55f).padding(horizontal = 16.dp, vertical = 9.dp).testTag("git_name_create"),
                )
            }
        }
    }
}
