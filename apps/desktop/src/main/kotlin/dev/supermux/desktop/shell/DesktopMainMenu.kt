// The app's main menu as data, rendered two ways: the native menu bar (macOS's global bar, and the
// window menu bar on Windows / Linux with the system frame), or — when the Linux custom chrome
// hides the system frame — a "main menu" button in the top band that opens the same entries as a
// dropdown, the way IntelliJ's new UI and VS Code's custom title bar do on Linux.
package dev.supermux.desktop.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
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
 * Open / closed state of the main-menu dropdown, shared by its button and the window's key
 * handler (F10, Alt+mnemonic). [attached] is true while a [MainMenuButton] is composed, so the
 * shortcuts do nothing when there is no menu to open (before pairing, system frame).
 */
@Stable
class MainMenuState {
    /** The group title the open menu starts at; null when closed. */
    var openSection: String? by mutableStateOf(null)
        private set

    /** True when opened from the keyboard: the first item of [openSection] takes the focus. */
    var focusOnOpen: Boolean by mutableStateOf(false)
        private set

    var attached: Boolean = false
        internal set

    /** The menu the attached button shows (what the mnemonics map onto). */
    var groups: List<MainMenuGroup> = emptyList()
        internal set

    val isOpen: Boolean get() = openSection != null

    fun open(section: String, fromKeyboard: Boolean) {
        focusOnOpen = fromKeyboard
        openSection = section
    }

    fun close() {
        openSection = null
    }

    /**
     * Handles a window key-down: F10 opens the menu at its first group, Alt+&lt;mnemonic&gt; at
     * that group. True when it opened the menu (consume the event).
     */
    fun onWindowKey(key: Key, alt: Boolean, ctrl: Boolean, shift: Boolean, meta: Boolean): Boolean {
        if (!attached) return false
        val section = mainMenuShortcutSection(key, alt, ctrl, shift, meta, groups) ?: return false
        open(section, fromKeyboard = true)
        return true
    }
}

/**
 * The group a key-down opens the main menu at: F10 alone → the first group; Alt + a group's
 * mnemonic (and no other modifier) → that group; anything else → null.
 */
fun mainMenuShortcutSection(
    key: Key,
    alt: Boolean,
    ctrl: Boolean,
    shift: Boolean,
    meta: Boolean,
    menu: List<MainMenuGroup>,
): String? {
    if (ctrl || shift || meta) return null
    if (!alt && key == Key.F10) return menu.firstOrNull()?.title
    if (!alt) return null
    return menu.firstOrNull { KeyEvent.getExtendedKeyCodeForChar(it.mnemonic.uppercaseChar().code) == key.nativeKeyCode }?.title
}

/**
 * The main-menu button and its dropdown: every group's entries, under the group's title,
 * separated by dividers. The button is a hole in the band's drag region. Opened from the keyboard
 * ([MainMenuState.onWindowKey]), the first item of the chosen group takes the focus; Up / Down
 * then move through the items, Enter / Space run one, Escape closes.
 */
@Composable
fun MainMenuButton(menu: List<MainMenuGroup>, state: MainMenuState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    DisposableEffect(state) {
        state.attached = true
        onDispose {
            state.attached = false
            state.close()
        }
    }
    SideEffect { state.groups = menu }
    // The first item of each group, so a keyboard open can land on its group.
    val firstItems = remember(menu.map { it.title }) { menu.associate { it.title to FocusRequester() } }
    Box(modifier) {
        IconButton(
            onClick = { state.open(menu.firstOrNull()?.title ?: return@IconButton, fromKeyboard = false) },
            modifier = Modifier
                .size(LinuxTitleBarHeight)
                .pointerHoverIcon(PointerIcon.Hand)
                .macTitleBarNoDragRegion("main-menu")
                .testTag("main_menu_button"),
        ) {
            Icon(Icons.Filled.Menu, contentDescription = "Main menu", tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = state.isOpen,
            onDismissRequest = { state.close() },
            modifier = Modifier.testTag("main_menu"),
        ) {
            // The popup's own focus owner: arrows move between the items.
            val focusManager = LocalFocusManager.current
            Column(
                Modifier.onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Next)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Previous)
                        Key.Escape -> {
                            state.close()
                            true
                        }
                        else -> false
                    }
                },
            ) {
            val section = state.openSection
            LaunchedEffect(section, state.focusOnOpen) {
                if (section == null || !state.focusOnOpen) return@LaunchedEffect
                // The popup attaches a frame after it is composed; retry until the item is placed.
                repeat(10) {
                    if (runCatching { firstItems[section]?.requestFocus() }.isSuccess) return@LaunchedEffect
                    withFrameNanos { }
                }
            }
            menu.forEachIndexed { index, group ->
                if (index > 0) HorizontalDivider()
                Text(
                    group.title,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                var first = true
                for (entry in group.entries) {
                    if (entry == MainMenuEntry.Separator) {
                        HorizontalDivider()
                        continue
                    }
                    // Desktop-dense rows: a menu, not a touch list.
                    val itemModifier = Modifier
                        .height(36.dp)
                        .then(if (first) Modifier.focusRequester(firstItems.getValue(group.title)) else Modifier)
                        .testTag("main_menu_item")
                    first = false
                    when (entry) {
                        is MainMenuEntry.Action -> DropdownMenuItem(
                            text = { Text(entry.label) },
                            trailingIcon = entry.shortcut?.let { s ->
                                { Text(s.label, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
                            },
                            onClick = {
                                state.close()
                                entry.onClick()
                            },
                            modifier = itemModifier,
                        )
                        is MainMenuEntry.Toggle -> DropdownMenuItem(
                            text = { Text(entry.label) },
                            leadingIcon = if (entry.checked) {
                                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else {
                                null
                            },
                            onClick = {
                                state.close()
                                entry.onToggle()
                            },
                            modifier = itemModifier,
                        )
                        MainMenuEntry.Separator -> Unit
                    }
                }
            }
            }
        }
    }
}

/**
 * The Linux top band over the sidebar: the main-menu button at the start, then (expanded only)
 * the sidebar collapse toggle — the Linux counterpart of [MacSidebarToggle] beside the traffic
 * lights. Composed only once paired (the shell), so the menu is always there.
 */
@Composable
fun LinuxSidebarChrome(
    menu: List<MainMenuGroup>,
    menuState: MainMenuState,
    collapsed: Boolean,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(LinuxTitleBarHeight).padding(start = 4.dp).testTag("linux_sidebar_chrome"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MainMenuButton(menu, menuState)
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
