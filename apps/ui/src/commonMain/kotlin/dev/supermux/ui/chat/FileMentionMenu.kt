package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.files.FileBadgeBox
import dev.supermux.ui.files.GoToEntry
import dev.supermux.ui.files.fileBadge
import dev.supermux.ui.files.highlighted

/**
 * The `@` file menu, drawn in the composer's menu strip where the `/` commands sit. One row per file
 * or folder: its badge, the name with the matched letters in bold, and (for a search) the folder it
 * is in, dimmed. Stateless like [SlashMenu]: the composer owns the rows, the highlight and the keys.
 * A row mentions its file or folder; a folder's chevron opens it instead, which is what Tab does.
 */
@Composable
fun FileMentionMenu(
    rows: List<GoToEntry>,
    selectedIndex: Int,
    onPick: (GoToEntry) -> Unit,
    onOpenFolder: (GoToEntry) -> Unit,
    modifier: Modifier = Modifier,
    /** Search rows come from anywhere in the project; a folder's own listing needs no folder line. */
    showFolders: Boolean = true,
    itemTagPrefix: String = "chat_mention_item_",
) {
    val cs = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    // ↑/↓ past the visible edge scroll the highlighted row back into view.
    LaunchedEffect(selectedIndex, rows) {
        val info = list.layoutInfo
        val fullyVisible = info.visibleItemsInfo.any {
            it.index == selectedIndex && it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset
        }
        if (!fullyVisible && selectedIndex in rows.indices) list.scrollToItem(selectedIndex)
    }
    LazyColumn(
        modifier = modifier.heightIn(max = 240.dp),
        state = list,
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.relativePath }) { i, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("$itemTagPrefix${row.relativePath}")
                    .background(if (i == selectedIndex) cs.surfaceContainerHighest else Color.Transparent)
                    // The field keeps focus, and with it the caret the pick lands at.
                    .focusProperties { canFocus = false }
                    .clickable { onPick(row) }
                    .padding(start = 14.dp, end = 6.dp)
                    .heightIn(min = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FileBadgeBox(fileBadge(row.name, row.isDir), alpha = 1f)
                Text(
                    text = highlighted(if (row.isDir) "${row.name}/" else row.name, row.nameHits),
                    color = cs.onSurface,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
                if (showFolders && row.folder.isNotEmpty()) {
                    Text(
                        text = highlighted(row.folder, row.folderHits),
                        color = cs.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (row.isDir) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open ${row.name}",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .focusProperties { canFocus = false }
                            .clickable { onOpenFolder(row) }
                            .padding(5.dp)
                            .testTag("$itemTagPrefix${row.relativePath}:open"),
                    )
                }
            }
        }
    }
}
