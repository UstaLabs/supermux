package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The tab strip's "+": it opens a New tab in this pane (#91), whose page offers what the tab can be.
 * The pane layer places it in an animated slot at the end of the strip; this draws the icon and
 * carries the `tab-add-view` tag.
 */
@Composable
fun WorkspaceAddButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxHeight()
            .width(36.dp)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("tab-add-view"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "New tab", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** How much of the top-right strip [WorkspaceSplitButtons] takes. */
val WorkspaceSplitButtonsWidth = 66.dp

/**
 * The workspace's one pair of split buttons, at its top-right corner: split down adds a bottom row,
 * split right a rightmost column, each holding a New tab ([onSplit] with "column" / "row").
 */
@Composable
fun WorkspaceSplitButtons(onSplit: (direction: String) -> Unit) {
    Row(Modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        SplitButton(NewTabGlyphs.SplitDown, "Split down", "tab-split-down") { onSplit("column") }
        Spacer(Modifier.width(2.dp))
        SplitButton(NewTabGlyphs.SplitRight, "Split right", "tab-split-right") { onSplit("row") }
    }
}

/** A thin glyph, dim until the pointer is on it; the hover wash is instant like the New tab page's. */
@Composable
private fun SplitButton(glyph: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (hovered) cs.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(glyph, contentDescription = label, tint = if (hovered) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.size(17.dp))
    }
}
