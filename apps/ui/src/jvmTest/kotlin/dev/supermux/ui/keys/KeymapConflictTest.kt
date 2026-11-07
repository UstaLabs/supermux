package dev.supermux.ui.keys

import dev.supermux.editor.core.KeyChord
import kotlin.test.Test
import kotlin.test.assertTrue

/** Spec "Testing": the default keys never fight each other, text editing, the terminal or the editor. */
class KeymapConflictTest {
    private fun chords(os: KeyOs, vararg specs: String): Set<KeyChord> =
        specs.map { KeyChord.parse(it, apple = os == KeyOs.Apple) }.toSet()

    /** Keys Compose's text fields edit with (read from the 1.12 library), plus the editor's swallowed chords. */
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

    /** The terminal's own keys (terminal-compose TerminalShortcuts.kt). */
    private val terminalOwn = mapOf(
        KeyOs.Apple to chords(KeyOs.Apple, "Mod-c", "Mod-v", "Mod-a", "Mod-f", "Mod-=", "Mod--", "Mod-0", "Mod-+"),
        KeyOs.Other to chords(
            KeyOs.Other, "Ctrl-Shift-c", "Ctrl-Shift-v", "Ctrl-Shift-a", "Ctrl-Shift-f", "Ctrl-Shift-=",
            "Ctrl-=", "Ctrl--", "Ctrl-0",
        ),
    )

    /** The editor's Mod keys (editor-compose DefaultCommands.kt and editor-plugins; spec audit). */
    private val editorOwn = listOf(
        "Mod-s", "Mod-a", "Mod-c", "Mod-x", "Mod-v", "Mod-z", "Mod-y", "Mod-Shift-z", "Mod-u", "Mod-d", "Mod-g",
        "Mod-Shift-g", "Mod-Shift-l", "Mod-Alt-g", "Mod-i", "Mod-/", "Mod-Shift-\\", "Mod-Shift-k", "Mod-Shift-m",
        "Mod-Enter", "Mod-]", "Mod-[", "Mod-=", "Mod--", "Mod-0", "Mod-f", "Mod-Shift-Space",
    )

    /**
     * Deliberate overlaps (spec "Testing"): ⌘/ and ⌘F stay the editor's inside it; find-in-chat and paste-image are
     * handled only by a chat and a composer; split right takes "deselect" (Ctrl+\) from text boxes off Apple.
     */
    private val allowed = setOf(
        AppCommands.ShowShortcuts.id to "Mod-/",
        AppCommands.FindInChat.id to "Mod-f",
        AppCommands.PasteImage.id to "Mod-v",
        AppCommands.SplitRight.id to "Ctrl-\\",
    )

    private fun isAllowed(b: AppBinding, os: KeyOs) =
        allowed.any { (cmd, spec) -> cmd == b.command && KeyChord.parse(spec, apple = os == KeyOs.Apple) == b.chord(os) }

    private fun clashes(os: KeyOs, taken: Set<KeyChord>, filter: (AppBinding) -> Boolean = { true }) =
        DefaultKeymap.bindings.filter { b -> filter(b) && b.chord(os) in taken && !isAllowed(b, os) }

    @Test fun noKeyRunsTwoCommands() {
        for (os in KeyOs.entries) {
            val byChord = DefaultKeymap.bindings.filter { it.chord(os) != null }.groupBy { it.chord(os) }
            val twice = byChord.filterValues { list -> list.map { it.command }.toSet().size > 1 }
            assertTrue(twice.isEmpty(), "$os: $twice")
        }
    }

    @Test fun appKeysAreNotTextEditingKeys() {
        for (os in KeyOs.entries) assertTrue(clashes(os, textEditing.getValue(os)).isEmpty(), "$os: ${clashes(os, textEditing.getValue(os))}")
    }

    @Test fun keysThatPassATerminalAreNotItsOwn() {
        for (os in KeyOs.entries) {
            val bad = clashes(os, terminalOwn.getValue(os)) { b -> b.chord(os)?.let { passesOwner(b, it, os) } == true }
            assertTrue(bad.isEmpty(), "$os: $bad")
        }
    }

    @Test fun appKeysAreNotEditorKeys() {
        for (os in KeyOs.entries) {
            val editor = chords(os, *editorOwn.toTypedArray())
            assertTrue(clashes(os, editor).isEmpty(), "$os: ${clashes(os, editor)}")
        }
    }
}
