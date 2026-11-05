package dev.supermux.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.ui.editor.isMarkdownPath
import dev.supermux.ui.panes.TabChipRow
import dev.supermux.ui.panes.TabClose
import dev.supermux.ui.panes.TabIcon
import dev.supermux.ui.panes.TabTitle
import dev.supermux.ui.theme.GeistFontFamily
import dev.supermux.ui.panes.TabSlotState
import dev.supermux.ui.session.RowContextMenu
import dev.supermux.ui.session.RowContextMenuEntry

/**
 * A tab for a `file` pane: the per-file controls, on the file's own tab.
 *
 * `FilePane` used to carry a 32dp action row holding exactly two buttons — save and the markdown
 * preview toggle — above every open document. Both are per-file, so they belong to the thing that
 * identifies the file. Moving them here buys back a strip of vertical space on every pane and puts
 * the unsaved state where you look for it, which is what a tab is for.
 *
 * This is the reason `PaneHost` takes a `tabSlot` rather than drawing tabs itself.
 */
@Composable
fun WorkspaceFileTab(
    itemId: String,
    title: String,
    path: String,
    state: TabSlotState,
    dirty: Boolean,
    saving: Boolean,
    previewMode: Boolean,
    onSave: () -> Unit,
    onTogglePreview: () -> Unit,
    onClose: (String) -> Unit,
    onMoveToNewWindow: () -> Unit = {},
    /** More right-click rows after "Move to New Window" (the strip's bulk closes). */
    extraMenu: () -> List<RowContextMenuEntry> = { emptyList() },
) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()

    // Cluster G8: the shared right-click wrapper (F3's `RowContextMenu`), so this tab compiles on
    // a host with no `ContextMenuArea`. Inert under touch — the file tab's save / preview / close
    // are all visible affordances, and tear-out needs a second OS window anyway.
    RowContextMenu(
        items = { listOf(RowContextMenuEntry("Move to New Window", onMoveToNewWindow)) + extraMenu() },
    ) {
    TabChipRow(hover, Modifier.testTag("tab-move-to-window-$itemId")) {
        TabIcon(badge = null) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = if (state.selected) cs.onSurface else cs.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
        TabTitle(title, state, GeistFontFamily)

        // Markdown only, and only on the active tab — a row of toggles across every background tab
        // would be noise.
        if (state.selected && isMarkdownPath(path)) {
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
                    .clickable { onTogglePreview() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag("tab-preview-$itemId"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (previewMode) Icons.Filled.Edit else Icons.Filled.Visibility,
                    contentDescription = if (previewMode) "Edit" else "Preview",
                    tint = if (previewMode) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        // The save affordance replaces nothing — it appears only while there is something to save,
        // so a clean tab looks exactly as it did before.
        when {
            saving -> Box(Modifier.padding(start = 4.dp).size(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = cs.primary)
            }
            dirty -> Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(16.dp)
                    .clickable { onSave() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag("tab-save-$itemId"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Save",
                    tint = cs.primary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        TabClose(
            visible = state.selected || hovered,
            onClick = { onClose(itemId) },
            label = "Close view",
            modifier = Modifier.testTag("tab-close-$itemId"),
        )
    }
    }
}
