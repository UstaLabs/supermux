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

/** A key that exists only on Apple. */
internal fun bindApple(keys: String, command: AppCommand) = AppBinding(command.id, KeyChord.parse(keys, apple = true), null)

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
        // Before New terminal's Ctrl+Shift+T: the reopen key's handler makes the terminal itself where it should (#122).
        bind("Mod-Shift-t", AppCommands.ReopenClosedTab, overOwner = true),
        bindOther("Ctrl-Shift-t", AppCommands.NewTerminal, overOwner = true),
        bind("Mod-b", AppCommands.ToggleSidebar),
        bind("Mod-p", AppCommands.GoToFile),
        bind("Mod-f", AppCommands.Find),
        bind("Mod-,", AppCommands.OpenSettings, overOwner = true),
        bind("Mod-/", AppCommands.ShowShortcuts),
        // The composer runs this key itself (Composer.kt); listed for the shortcuts screen and the conflict test.
        // Never register it on a pane scope: appFirst would take ⌘V from every text box in that pane while the
        // clipboard holds an image.
        bind("Mod-v", AppCommands.PasteImage, appFirst = true),
        bind("Mod-\\", AppCommands.SplitRight, appFirst = true),
        bind("Mod-Alt-\\", AppCommands.SplitDown),
        bind("Mod-w", AppCommands.CloseTab),
        bindOther("Ctrl-Shift-w", AppCommands.CloseTab, overOwner = true),
        // Chrome's tab keys (#122). Chrome's Ctrl+Tab on a Mac is left out: on Apple the app's keys are ⌘ only (#96).
        bindApple("Mod-Shift-]", AppCommands.TabNext),
        bindOther("Ctrl-Tab", AppCommands.TabNext),
        bindOther("Ctrl-PageDown", AppCommands.TabNext),
        bindApple("Mod-Shift-[", AppCommands.TabPrevious),
        bindOther("Ctrl-Shift-Tab", AppCommands.TabPrevious),
        bindOther("Ctrl-PageUp", AppCommands.TabPrevious),
        *(1..9).map { bind("Mod-$it", AppCommands.tabNumber(it)) }.toTypedArray(),
        // The Browser tab's keys: only its pane registers them, so they never reach a chat, editor or terminal.
        bind("Mod-l", AppCommands.BrowserFocusAddress),
        bind("Mod-r", AppCommands.BrowserReload),
        bind("Mod-[", AppCommands.BrowserBack),
        bind("Mod-]", AppCommands.BrowserForward),
        bind("Mod-=", AppCommands.BrowserZoomIn),
        bind("Mod--", AppCommands.BrowserZoomOut),
        bind("Mod-0", AppCommands.BrowserZoomReset),
        bind("Mod-Shift-o", AppCommands.BrowserOpenExternally),
        // The one key with two meanings by scope (#122, Q1): in a Browser tab ⌘T is a new Browser tab (the deeper scope
        // wins); everywhere else New terminal.
        bind("Mod-t", AppCommands.BrowserNewTab),
        // Chrome's own keys for a tab (#122), as aliases: its Windows/Linux list off Apple, its ⌘ keys on Apple.
        bindOther("F5", AppCommands.BrowserReload),
        bindOther("Alt-ArrowLeft", AppCommands.BrowserBack),
        bindOther("Alt-ArrowRight", AppCommands.BrowserForward),
        bindOther("Alt-d", AppCommands.BrowserFocusAddress),
        bindOther("F6", AppCommands.BrowserFocusAddress),
        bind("Mod-Shift-=", AppCommands.BrowserZoomIn),
        bind("Mod-+", AppCommands.BrowserZoomIn),
        bindOther("F12", AppCommands.BrowserInspect),
        bindOther("Ctrl-Shift-i", AppCommands.BrowserInspect),
        bindApple("Mod-Alt-i", AppCommands.BrowserInspect),
        bind("Escape", AppCommands.BrowserStop),
        bind("Mod-Shift-s", AppCommands.BrowserScreenshot),
        bind("Mod-Shift-m", AppCommands.BrowserDeviceMode),
        bind("Mod-Shift-c", AppCommands.BrowserPick),
        bind("Mod-g", AppCommands.FindNext),
        bindOther("F3", AppCommands.FindNext),
        bind("Mod-Shift-g", AppCommands.FindPrevious),
        bindOther("Shift-F3", AppCommands.FindPrevious),
    ),
)

/**
 * Whether [event] is a key-down of one of this command's keys on [os] (for a widget that runs a command itself,
 * outside a [ShortcutRoot]).
 */
fun AppCommand.matchesKey(event: KeyEvent, os: KeyOs = currentKeyOs, keymap: Keymap = DefaultKeymap): Boolean =
    event.type == KeyEventType.KeyDown && keyChordOf(event)?.let { it in keymap.chordsFor(this, os) } == true
