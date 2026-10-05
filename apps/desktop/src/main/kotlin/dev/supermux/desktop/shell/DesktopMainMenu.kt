// The app's main menu as data, rendered two ways: the native menu bar (macOS's global bar, and the
// window menu bar on Windows / Linux with the system frame), or — when the Linux custom chrome
// hides the system frame — a "main menu" button in the top band that opens the same entries as a
// dropdown, the way IntelliJ's new UI and VS Code's custom title bar do on Linux.
package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuOpen
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import java.awt.event.KeyEvent

/** A menu accelerator. Compose's [KeyShortcut] keeps its fields internal, so the menu keeps its own. */
data class MenuShortcut(
    val key: Key,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    fun toKeyShortcut(): KeyShortcut = KeyShortcut(key, ctrl = ctrl, meta = meta, alt = alt, shift = shift)

    /** "Ctrl+N", as a menu shows it. */
    val label: String
        get() = buildList {
            if (ctrl) add("Ctrl")
            if (alt) add("Alt")
            if (shift) add("Shift")
            if (meta) add("Super")
            add(KeyEvent.getKeyText(key.nativeKeyCode))
        }.joinToString("+")
}

sealed interface MainMenuEntry {
    data class Action(val label: String, val shortcut: MenuShortcut? = null, val onClick: () -> Unit) : MainMenuEntry
    data class Toggle(val label: String, val checked: Boolean, val onToggle: () -> Unit) : MainMenuEntry
    data object Separator : MainMenuEntry
}

data class MainMenuGroup(val title: String, val mnemonic: Char, val entries: List<MainMenuEntry>)

/** The native menu bar for [menu]. */
@Composable
fun FrameWindowScope.DesktopMenuBar(menu: List<MainMenuGroup>) {
    MenuBar {
        for (group in menu) {
            Menu(group.title, mnemonic = group.mnemonic) {
                for (entry in group.entries) {
                    when (entry) {
                        is MainMenuEntry.Action -> Item(entry.label, shortcut = entry.shortcut?.toKeyShortcut(), onClick = entry.onClick)
                        is MainMenuEntry.Toggle -> CheckboxItem(entry.label, checked = entry.checked) { entry.onToggle() }
                        MainMenuEntry.Separator -> Separator()
                    }
                }
            }
        }
    }
}

/**
 * The main-menu button and its dropdown: every group's entries, under the group's title,
 * separated by dividers. The button is a hole in the band's drag region.
 */
@Composable
fun MainMenuButton(menu: List<MainMenuGroup>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Box(modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier
                .size(LinuxTitleBarHeight)
                .pointerHoverIcon(PointerIcon.Hand)
                .macTitleBarNoDragRegion("main-menu")
                .testTag("main_menu_button"),
        ) {
            Icon(Icons.Filled.Menu, contentDescription = "Main menu", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            menu.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider()
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                for (entry in group.entries) {
                    when (entry) {
                        is MainMenuEntry.Action -> DropdownMenuItem(
                            text = { Text(entry.label) },
                            trailingIcon = entry.shortcut?.let { s ->
                                { Text(s.label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                            },
                            onClick = {
                                open = false
                                entry.onClick()
                            },
                            // Desktop-dense rows: a menu, not a touch list.
                            modifier = Modifier.height(36.dp).testTag("main_menu_item"),
                        )
                        is MainMenuEntry.Toggle -> DropdownMenuItem(
                            text = { Text(entry.label) },
                            leadingIcon = if (entry.checked) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                            onClick = {
                                open = false
                                entry.onToggle()
                            },
                            // Desktop-dense rows: a menu, not a touch list.
                            modifier = Modifier.height(36.dp).testTag("main_menu_item"),
                        )
                        MainMenuEntry.Separator -> HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * The Linux top band over the sidebar: the main-menu button at the start, then (expanded only)
 * the sidebar collapse toggle — the Linux counterpart of [MacSidebarToggle] beside the traffic
 * lights. [menu] is null before pairing (no menu then, as on the native menu bar).
 */
@Composable
fun LinuxSidebarChrome(
    menu: List<MainMenuGroup>?,
    collapsed: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(LinuxTitleBarHeight).padding(start = 4.dp).testTag("linux_sidebar_chrome"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (menu != null) MainMenuButton(menu)
        if (!collapsed) {
            IconButton(
                onClick = onCollapse,
                modifier = Modifier
                    .size(LinuxTitleBarHeight)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .macTitleBarNoDragRegion("sidebar-toggle")
                    .testTag("sidebar_collapse_title"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuOpen,
                    contentDescription = "Hide sidebar",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
