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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The default tab chip: a label and a close affordance.
 *
 * This is what a pane strip draws when the caller supplies no `tabSlot`. It is
 * deliberately content-free — it takes the font rather than reaching for one, and its only colour
 * source is [MaterialTheme] — so it can live in the pane layer. A caller that needs more (an
 * editor's unsaved-changes dot, a loading spinner) passes its own `tabSlot` instead.
 *
 * The strip owns position, size, gestures, and the `view-tab-<id>` tag. This owns the look and the
 * `tab-close-<id>` tag.
 */
@Composable
fun DefaultTabChip(
    itemId: String,
    title: String,
    state: TabSlotState,
    labelFont: FontFamily,
    onClose: (String) -> Unit,
    /**
     * Accessibility label for the close affordance. The caller supplies it because only the caller
     * knows what a tab holds — "view", "file", "tab" are all content vocabulary.
     */
    closeLabel: String = "Close view",
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
            fontFamily = labelFont,
            fontSize = 11.sp,
            fontWeight = if (state.selected) FontWeight.Medium else FontWeight.Normal,
        )
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
                contentDescription = closeLabel,
                tint = fg,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
