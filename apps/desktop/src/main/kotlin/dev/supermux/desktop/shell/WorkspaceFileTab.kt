package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.editor.isMarkdownPath
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.ui.panes.TabSlotState

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
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (state.selected) cs.primary.copy(alpha = 0.14f) else Color.Transparent
    val fg = if (state.selected) cs.primary else cs.onSurfaceVariant

    Row(
        Modifier
            .fillMaxHeight()
            .background(bg)
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            color = fg,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = if (state.selected) FontWeight.Medium else FontWeight.Normal,
        )

        // Markdown only, and only on the active tab — a row of toggles across every background tab
        // would be noise.
        if (state.selected && isMarkdownPath(path)) {
            Box(
                Modifier
                    .size(16.dp)
                    .clickable { onTogglePreview() }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag("tab-preview-$itemId"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (previewMode) Icons.Filled.Edit else Icons.Filled.Visibility,
                    contentDescription = if (previewMode) "Edit" else "Preview",
                    tint = if (previewMode) cs.primary else fg,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        // The save affordance replaces nothing — it appears only while there is something to save,
        // so a clean tab looks exactly as it did before.
        when {
            saving -> Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = cs.primary)
            }
            dirty -> Box(
                Modifier
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

        Box(
            Modifier
                .size(16.dp)
                .clickable { onClose(itemId) }
                .alpha(if (state.selected) 0.85f else 0.5f)
                .testTag("tab-close-$itemId"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close view",
                tint = fg,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
