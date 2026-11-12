// Keyboard shortcuts (#97 unit L; #96 spec §6, prototype `B.keys`): every app command, under its group's head, with
// the keys this device uses for it, written as this device writes them (⇧⌘N on Apple, Ctrl+Shift+N elsewhere).
// Generated from the command registry and the keymap, so it is never stale. Read-only for now: rebinding comes later.
package dev.supermux.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.ui.keys.AppCommand
import dev.supermux.ui.keys.AppCommands
import dev.supermux.ui.keys.CommandGroup
import dev.supermux.ui.keys.DefaultKeymap
import dev.supermux.ui.keys.KeyOs
import dev.supermux.ui.keys.Keymap
import dev.supermux.ui.keys.currentKeyOs
import dev.supermux.ui.keys.formatChord
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.kit.KitDeviceScope
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitValueRow
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.settings_shortcuts_or
import dev.supermux.ui.settings.localizedSettingsTitle

/** A command row's search anchor (the search index declares the same one). */
fun shortcutAnchor(command: AppCommand): String = "key-${command.id}"

/**
 * The Keyboard shortcuts page.
 *
 * @param os whose conventions the keys are drawn in (this device's by default).
 * @param keymap where the keys come from; a command with none on [os] has no row, a group with no rows no head.
 */
@Composable
fun KeyboardShortcutsPage(
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
    os: KeyOs = currentKeyOs,
    keymap: Keymap = DefaultKeymap,
) {
    val orWord = localized(Res.string.settings_shortcuts_or)
    KitPage(
        localizedSettingsTitle(SettingsPlace(SettingsSection.KeyboardShortcuts)),
        modifier,
        scope = { KitDeviceScope() },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "shortcuts_page",
    ) {
        for (group in CommandGroup.entries) {
            val rows = AppCommands.all
                .filter { it.group == group }
                .mapNotNull { cmd ->
                    keymap.chordsFor(cmd, os).takeIf { it.isNotEmpty() }?.let { chords ->
                        cmd to chords.joinToString(" $orWord ") { formatChord(it, os) }
                    }
                }
            if (rows.isEmpty()) continue
            KitSectionHead(group.title, Modifier.testTag("shortcuts_group_${group.name.lowercase()}"))
            for ((cmd, keys) in rows) {
                KitValueRow(
                    cmd.title,
                    keys,
                    Modifier.kitAnchor(shortcutAnchor(cmd)).testTag("shortcut_${cmd.id}"),
                    mono = true,
                )
            }
        }
    }
}
