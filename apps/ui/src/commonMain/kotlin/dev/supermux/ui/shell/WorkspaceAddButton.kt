package dev.supermux.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.workspace.NewViewKind
import dev.supermux.workspace.NewViewPlacement

/**
 * The workspace's "+" — the visual and interactive half of the tab strip's add button, split out
 * of the pane layer so that layer carries no icon, no menu chrome, and no dependency on
 * [dev.supermux.ui.widgets.DropdownMenu]'s `ModalOpen()` side effect (it pins terminals hidden
 * while the menu is open).
 *
 * The layer owns position and size: it places this inside an animated 36 dp slot at the end of
 * the strip. This owns everything drawn inside that slot — the icon, the popover of view kinds,
 * and the `tab-add-view`, `tab-add-view-menu`, and `tab-add-view-<tag>` tags. Picking a kind
 * reports that kind's own [NewViewKind.placement]; picking is the only step the popover offers.
 */
@Composable
fun WorkspaceAddButton(onPick: (NewViewKind, NewViewPlacement) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var pickerOpen by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxHeight()
            .width(36.dp)
            .clickable { pickerOpen = true }
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("tab-add-view"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "Add a view",
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
    DropdownMenu(
        expanded = pickerOpen,
        onDismissRequest = { pickerOpen = false },
        modifier = Modifier.testTag("tab-add-view-menu"),
    ) {
        for (k in NewViewKind.entries) {
            DropdownMenuItem(
                text = { Text(k.label, fontSize = 12.sp) },
                onClick = {
                    pickerOpen = false
                    onPick(k, k.placement)
                },
                modifier = Modifier.testTag("tab-add-view-${k.tag}"),
            )
        }
    }
}
