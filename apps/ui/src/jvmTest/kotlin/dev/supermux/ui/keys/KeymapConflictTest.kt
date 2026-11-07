package dev.supermux.ui.keys

import dev.supermux.editor.compose.defaultKeymap
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapFacet
import dev.supermux.editor.plugins.autocomplete.autocompletion
import dev.supermux.editor.plugins.basics.basics
import dev.supermux.editor.plugins.diff.Diff
import dev.supermux.editor.plugins.fold.fold
import dev.supermux.editor.plugins.history.history
import dev.supermux.editor.plugins.lint.lint
import dev.supermux.editor.plugins.search.search
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Spec "Testing": the default keys never fight each other, text editing, the terminal or the editor. The terminal's
 * and the editor's keys are read from their own code; only text editing's are listed by hand.
 */
class KeymapConflictTest {
    private fun chords(os: KeyOs, vararg specs: String): Set<KeyChord> =
        specs.map { KeyChord.parse(it, apple = os == KeyOs.Apple) }.toSet()

    /** Keys Compose's text fields edit with, read from the 1.12 library: its key mapping is internal, so they are listed here. */
    private val textEditing = mapOf(
        KeyOs.Apple to chords(
            KeyOs.Apple, "Mod-a", "Mod-c", "Mod-v", "Mod-x", "Mod-z", "Mod-Shift-z", "Mod-y",
            "Ctrl-a", "Ctrl-e", "Ctrl-f", "Ctrl-b", "Ctrl-p", "Ctrl-n", "Ctrl-h", "Ctrl-d", "Ctrl-k", "Ctrl-o",
            "Mod-ArrowLeft", "Mod-ArrowRight", "Mod-ArrowUp", "Mod-ArrowDown", "Mod-Backspace", "Alt-Backspace",
        ),
        KeyOs.Other to chords(
            KeyOs.Other, "Mod-a", "Mod-c", "Mod-v", "Mod-x", "Mod-z", "Mod-Shift-z", "Mod-y",
            "Ctrl-h", "Ctrl-\\", "Ctrl-ArrowLeft", "Ctrl-ArrowRight", "Ctrl-Backspace", "Ctrl-Delete",
            "Ctrl-Home", "Ctrl-End", "Shift-Insert", "Ctrl-Insert",
        ),
    )

    /**
     * The editor's keys no keymap below lists: the surface's swallowed chords (editor-compose EditorInput.kt), the
     * host's save (NativeDocuments.kt) and the LSP plugin's keys, which exist only once a server is attached.
     */
    private val editorPrivate = arrayOf(
        "Mod-s", "Mod-Shift-v", "Mod-z", "Mod-Shift-z", "Mod-y", "Mod-c", "Mod-x", "Mod-v",
        "F12", "Shift-F12", "F2", "Shift-Alt-f", "Mod-Shift-Space", "Mod-Shift-ArrowUp", "Mod-Shift-ArrowDown",
    )

    /** Every key the editor binds on [os]: its default keys and the plugins the app's editor installs (NativeDocuments.kt), the diff's keys, and [editorPrivate]. */
    private fun editorKeys(os: KeyOs): Set<KeyChord> {
        val apple = os == KeyOs.Apple
        val state = EditorState.create(
            "",
            extensions = extensionOf(defaultKeymap(apple), basics(), history(), fold(), search(), autocompletion(), lint()),
        )
        return state.facet(keymapFacet).map { it.chord(apple) }.toSet() + Diff.keymap.map { it.chord(apple) } + chords(os, *editorPrivate)
    }

    /** Text boxes: paste-image declines without an image in the clipboard; split right takes "deselect" (Ctrl+\) off Apple. */
    private val textEditingAllowed = setOf(
        AppCommands.PasteImage.id to "Mod-v",
        AppCommands.SplitRight.id to "Ctrl-\\",
    )

    /** A focused terminal keeps its find and paste ([terminalKeeps]): these app keys reach the app only when it leaves them. */
    private val terminalAllowed = setOf(
        AppCommands.FindInChat.id to "Mod-f",
        AppCommands.PasteImage.id to "Mod-v",
    )

    /** ⌘/ and ⌘F stay the editor's (the deeper scope wins); paste-image is handled only by a composer. */
    private val editorAllowed = setOf(
        AppCommands.ShowShortcuts.id to "Mod-/",
        AppCommands.FindInChat.id to "Mod-f",
        AppCommands.PasteImage.id to "Mod-v",
    )

    private fun isAllowed(b: AppBinding, os: KeyOs, allowed: Set<Pair<String, String>>) =
        allowed.any { (id, spec) -> id == b.commandId && KeyChord.parse(spec, apple = os == KeyOs.Apple) == b.chord(os) }

    /** The default bindings whose chord on [os] is [taken], minus the [allowed] ones. */
    private fun clashes(os: KeyOs, allowed: Set<Pair<String, String>>, taken: (KeyChord) -> Boolean): List<AppBinding> =
        DefaultKeymap.bindings.filter { b -> b.chord(os)?.let(taken) == true && !isAllowed(b, os, allowed) }

    @Test fun noChordIsBoundTwice() {
        for (os in KeyOs.entries) {
            val twice = DefaultKeymap.bindings.mapNotNull { it.chord(os) }.groupingBy { it }.eachCount().filterValues { it > 1 }
            assertTrue(twice.isEmpty(), "$os: ${twice.keys}")
        }
    }

    @Test fun appKeysAreNotTextEditingKeys() {
        for (os in KeyOs.entries) {
            val bad = clashes(os, textEditingAllowed) { it in textEditing.getValue(os) }
            assertTrue(bad.isEmpty(), "$os: $bad")
        }
    }

    @Test fun keysThatPassATerminalAreNotItsOwn() {
        for (os in KeyOs.entries) {
            val bad = clashes(os, terminalAllowed) { terminalKeeps(it) }.filter { passesOwner(it, os) }
            assertTrue(bad.isEmpty(), "$os: $bad")
        }
    }

    @Test fun appKeysAreNotEditorKeys() {
        for (os in KeyOs.entries) {
            val editor = editorKeys(os)
            val bad = clashes(os, editorAllowed) { it in editor }
            assertTrue(bad.isEmpty(), "$os: $bad")
        }
    }

    @Test fun everyKeyNameIsARealKey() {
        val unknown = DefaultKeymap.bindings.flatMap { listOfNotNull(it.apple, it.other) }.filter { composeKeyOf(it.key) == null }
        assertTrue(unknown.isEmpty(), "no Compose key for $unknown")
    }

    @Test fun theModifierIsStrict() {
        val bad = DefaultKeymap.bindings.filter { it.apple?.ctrl == true || it.other?.meta == true }
        assertTrue(bad.isEmpty(), "Ctrl on Apple, or Meta elsewhere: $bad")
    }

    @Test fun offAppleTheTerminalKeepsEveryPlainCtrlKey() {
        val bad = DefaultKeymap.bindings.filter { b ->
            val c = b.other
            b.overOwner && c != null && c.ctrl && !c.shift && !c.alt && c.key.length == 1 && c.key.single() in 'a'..'z'
        }
        assertTrue(bad.isEmpty(), "Ctrl + a letter is the shell's: $bad")
    }
}
