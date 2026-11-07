package dev.supermux.ui.keys

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import dev.supermux.editor.compose.isApplePlatform
import dev.supermux.editor.compose.keyChordOf
import dev.supermux.editor.core.KeyChord

/**
 * The two keyboard conventions. On [Apple], ⌘ is the app's modifier and Ctrl belongs to the shell and to text
 * editing; everywhere else ([Other]) Ctrl is the app's.
 */
enum class KeyOs { Apple, Other }

/** This device's convention, from the one detector the editor already uses (#29 step 10 will feed it). */
val currentKeyOs: KeyOs get() = if (isApplePlatform) KeyOs.Apple else KeyOs.Other

/**
 * One default key for [commandId]: [apple] and [other] are its chord under each convention, null where it has none.
 *
 * [appFirst]: the app takes the key before a focused text box or editor sees it. Only for a key they would eat
 * (paste-image's ⌘V, and split right's Ctrl+\ off Apple, which text boxes use as "deselect").
 * [overOwner], off Apple only: the key also reaches the app while a terminal or remote display has the focus. On
 * Apple every ⌘ key does, and no other ([passesOwner]).
 */
data class AppBinding(
    val commandId: String,
    val apple: KeyChord?,
    val other: KeyChord?,
    val appFirst: Boolean = false,
    val overOwner: Boolean = false,
) {
    fun chord(os: KeyOs): KeyChord? = if (os == KeyOs.Apple) apple else other
}

/** [keys] in the editor's grammar (`"Mod-Shift-n"`; Mod is ⌘ on Apple and Ctrl elsewhere); [mac] replaces it on Apple. */
internal fun bind(keys: String, command: AppCommand, mac: String = keys, appFirst: Boolean = false, overOwner: Boolean = false) =
    AppBinding(command.id, KeyChord.parse(mac, apple = true), KeyChord.parse(keys, apple = false), appFirst, overOwner)

/** A key that exists only off Apple. */
internal fun bindOther(keys: String, command: AppCommand, appFirst: Boolean = false, overOwner: Boolean = false) =
    AppBinding(command.id, null, KeyChord.parse(keys, apple = false), appFirst, overOwner)

class Keymap(val bindings: List<AppBinding>) {
    fun bindingsFor(chord: KeyChord, os: KeyOs): List<AppBinding> = bindings.filter { it.chord(os) == chord }

    fun chordsFor(command: AppCommand, os: KeyOs): List<KeyChord> =
        bindings.filter { it.commandId == command.id }.mapNotNull { it.chord(os) }
}

/**
 * The default keys (spec, "Default keys (v1)"). Off Apple a focused terminal keeps every Ctrl key; the app's keys
 * there are Ctrl+Shift+T, Ctrl+Shift+N and Ctrl+, (`overOwner`).
 */
val DefaultKeymap = Keymap(
    listOf(
        bind("Mod-n", AppCommands.NewSession),
        bind("Mod-Shift-n", AppCommands.MoveToNewWindow, overOwner = true),
        bind("Mod-t", AppCommands.NewTerminal),
        bindOther("Ctrl-Shift-t", AppCommands.NewTerminal, overOwner = true),
        bind("Mod-b", AppCommands.ToggleSidebar),
        bind("Mod-p", AppCommands.GoToFile),
        bind("Mod-f", AppCommands.FindInChat),
        bind("Mod-,", AppCommands.OpenSettings, overOwner = true),
        bind("Mod-/", AppCommands.ShowShortcuts),
        // The composer runs this key itself (Composer.kt); listed for the shortcuts screen and the conflict test.
        // Never register it on a pane scope: appFirst would take ⌘V from every text box in that pane while the
        // clipboard holds an image.
        bind("Mod-v", AppCommands.PasteImage, appFirst = true),
        bind("Mod-\\", AppCommands.SplitRight, appFirst = true),
        bind("Mod-Alt-\\", AppCommands.SplitDown),
    ),
)

/**
 * Whether [event] is a key-down of one of this command's keys on [os] (for a widget that runs a command itself,
 * outside a [ShortcutRoot]).
 */
fun AppCommand.matchesKey(event: KeyEvent, os: KeyOs = currentKeyOs, keymap: Keymap = DefaultKeymap): Boolean =
    event.type == KeyEventType.KeyDown && keyChordOf(event)?.let { it in keymap.chordsFor(this, os) } == true
