package dev.supermux.editor.compose

import dev.supermux.editor.core.Command
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.KeyChord
import dev.supermux.editor.core.SelectionRange
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.runKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultCommandsTest {
    /**
     * Test states are written with the selection inline: `|` is a cursor, `[` / `]` the anchor and
     * head of a range (anchor first as written: `[ab]` is forward, `]ab[` backward).
     */
    private fun view(marked: String, vararg ext: dev.supermux.editor.core.Extension): EditorView {
        val text = StringBuilder()
        val ranges = ArrayList<SelectionRange>()
        var anchor = -1
        for (c in marked) when (c) {
            '|' -> ranges += SelectionRange(text.length)
            '[' -> if (anchor < 0) anchor = text.length else { ranges += SelectionRange(text.length, anchor); anchor = -1 }
            ']' -> if (anchor < 0) anchor = text.length else { ranges += SelectionRange(anchor, text.length); anchor = -1 }
            else -> text.append(c)
        }
        return EditorView(EditorState.create(text.toString(), EditorSelection.create(ranges.ifEmpty { listOf(SelectionRange(0)) }), extensionOf(*ext)))
    }

    private fun EditorView.marked(): String {
        val doc = state.doc.toString()
        val marks = ArrayList<Pair<Int, Char>>()
        for (r in state.selection.ranges) {
            if (r.empty) marks += r.head to '|' else if (r.anchor < r.head) { marks += r.anchor to '['; marks += r.head to ']' } else { marks += r.head to ']'; marks += r.anchor to '[' }
        }
        val sb = StringBuilder(doc)
        for ((pos, c) in marks.sortedWith(compareBy({ -it.first }, { if (it.second == ']' || it.second == '|') 0 else 1 }))) sb.insert(pos, c)
        return sb.toString()
    }

    private fun check(before: String, cmd: Command, after: String) {
        val v = view(before)
        cmd.run(v)
        assertEquals(after, v.marked(), "from $before")
    }

    @Test fun horizontalMovesStepWholeGraphemesAndCollapseSelections() {
        check("a|b", DefaultCommands.cursorLeft, "|ab")
        check("|ab", DefaultCommands.cursorLeft, "|ab")
        check("ab|", DefaultCommands.cursorRight, "ab|")
        check("a😀|b", DefaultCommands.cursorLeft, "a|😀b")
        check("a|😀b", DefaultCommands.cursorRight, "a😀|b")
        check("é|x", DefaultCommands.cursorLeft, "|éx")
        check("|éx", DefaultCommands.cursorRight, "é|x")
        check("|👩‍💻x", DefaultCommands.cursorRight, "👩‍💻|x")
        check("🇹🇷🇯🇵|", DefaultCommands.cursorLeft, "🇹🇷|🇯🇵")
        check("a[bc]d", DefaultCommands.cursorLeft, "a|bcd")
        check("a[bc]d", DefaultCommands.cursorRight, "abc|d")
        check("a|b\nc", DefaultCommands.selectRight, "a[b]\nc")
        check("a[b]\nc", DefaultCommands.selectRight, "a[b\n]c")
        check("ab|\ncd", DefaultCommands.cursorRight, "ab\n|cd")
    }

    @Test fun everyCommandAppliesToEveryRange() {
        check("a|b\nc|d", DefaultCommands.cursorRight, "ab|\ncd|")
        check("a|b\nc|d", DefaultCommands.deleteBackward, "|b\n|d")
        check("a|b\nc|d", DefaultCommands.deleteForward, "a|\nc|")
        check("[ab]c\n[de]f", DefaultCommands.deleteBackward, "|c\n|f")
        check("x|y\nx|y", DefaultCommands.insertNewline, "x\n|y\nx\n|y")
        check("ab|\ncd|", DefaultCommands.selectLeft, "a]b[\nc]d[")
    }

    @Test fun deletingRemovesAWholeGraphemeNeverHalfOfIt() {
        check("a😀|", DefaultCommands.deleteBackward, "a|")
        check("|😀a", DefaultCommands.deleteForward, "|a")
        check("café|", DefaultCommands.deleteBackward, "caf|")
        check("x👩‍💻|", DefaultCommands.deleteBackward, "x|")
        check("🇹🇷🇯🇵|", DefaultCommands.deleteBackward, "🇹🇷|")
        check("👍🏽|", DefaultCommands.deleteBackward, "|")
        check("|", DefaultCommands.deleteBackward, "|")
        check("|", DefaultCommands.deleteForward, "|")
    }

    @Test fun wordMovesFollowALetterDigitUnderscoreRuleInEveryScript() {
        check("|foo_bar1 baz", DefaultCommands.cursorWordRight, "foo_bar1| baz")
        check("foo_bar1| baz", DefaultCommands.cursorWordRight, "foo_bar1 baz|")
        check("foo.bar|", DefaultCommands.cursorWordLeft, "foo.|bar")
        check("foo.|bar", DefaultCommands.cursorWordLeft, "foo|.bar")
        check("|ığüşöç İstanbul", DefaultCommands.cursorWordRight, "ığüşöç| İstanbul")
        check("ığüşöç İstanbul|", DefaultCommands.cursorWordLeft, "ığüşöç |İstanbul")
        check("|日本語 テキスト", DefaultCommands.cursorWordRight, "日本語| テキスト")
        check("a 😀😀| b", DefaultCommands.cursorWordLeft, "a |😀😀 b")
        check("|😀😀 b", DefaultCommands.cursorWordRight, "😀😀| b")
        check("ab|\ncd", DefaultCommands.cursorWordRight, "ab\n|cd")
        check("ab\n|cd", DefaultCommands.cursorWordLeft, "ab|\ncd")
        check("one two|", DefaultCommands.deleteWordBackward, "one |")
        check("one  |", DefaultCommands.deleteWordBackward, "|")
        check("|one two", DefaultCommands.selectWordRight, "[one] two")
        // Two cursors whose word deletions overlap merge into one change.
        check("abc|de|f", DefaultCommands.deleteWordBackward, "|f")
    }

    @Test fun lineAndDocumentBoundaries() {
        check("    fo|o", DefaultCommands.cursorLineStart, "    |foo")
        check("    |foo", DefaultCommands.cursorLineStart, "|    foo")
        check("  |  foo", DefaultCommands.cursorLineStart, "    |foo")
        check("a\nb|c\nd", DefaultCommands.cursorLineEnd, "a\nbc|\nd")
        check("a\nb|c\nd", DefaultCommands.selectLineEnd, "a\nb[c]\nd")
        check("a\nb|c\nd", DefaultCommands.cursorDocStart, "|a\nbc\nd")
        check("a\nb|c\nd", DefaultCommands.cursorDocEnd, "a\nbc\nd|")
        check("a\nb|c\nd", DefaultCommands.selectDocEnd, "a\nb[c\nd]")
        check("a\nb|c\nd", DefaultCommands.selectAll, "[a\nbc\nd]")
    }

    @Test fun verticalMovesWithoutASurfaceKeepTheColumn() {
        // No geometry yet (nothing composed): logical lines and character columns.
        check("abc|d\nx\nabcdef", DefaultCommands.cursorDown, "abcd\nx|\nabcdef")
        val v = view("abc|d\nx\nabcdef")
        DefaultCommands.cursorDown.run(v)
        DefaultCommands.cursorDown.run(v)
        assertEquals("abcd\nx\nabc|def", v.marked(), "the goal column survived the short line")
        check("a|b\ncd", DefaultCommands.cursorUp, "|ab\ncd")
        check("ab\nc|d", DefaultCommands.cursorDown, "ab\ncd|")
        check("ab\nc|d", DefaultCommands.selectUp, "a]b\nc[d")
        val many = view("|" + (0 until 100).joinToString("\n") { "l$it" })
        DefaultCommands.cursorPageDown.run(many)
        assertTrue(many.state.doc.lineIndexAt(many.state.selection.main.head) > 5)
        DefaultCommands.cursorPageUp.run(many)
        assertEquals(0, many.state.selection.main.head)
    }

    @Test fun newlineKeepsTheIndentationAndTabIndents() {
        check("    foo|", DefaultCommands.insertNewline, "    foo\n    |")
        check("  a|b", DefaultCommands.insertNewline, "  a\n  |b")
        check("fo[o]", DefaultCommands.insertNewline, "fo\n|")
        check("|x", DefaultCommands.insertTab, "    |x")
        check("ab|x", DefaultCommands.insertTab, "ab  |x")
        check("a[b\nc]d", DefaultCommands.insertTab, "    a[b\n    c]d")
        val tabs = view("|x", indentUnitFacet.of("\t"))
        DefaultCommands.insertTab.run(tabs)
        assertEquals("\t|x", tabs.marked())
    }

    @Test fun commandsNameTheirUserEvents() {
        val v = view("a|b")
        val seen = ArrayList<Transaction>()
        v.addListener { seen += it }
        DefaultCommands.cursorRight.run(v)
        DefaultCommands.insertNewline.run(v)
        DefaultCommands.deleteBackward.run(v)
        assertTrue(seen[0].isUserEvent("select"))
        assertTrue(seen[1].isUserEvent("input"))
        assertTrue(seen[2].isUserEvent("delete"))
        assertTrue(seen.all { it.scrollIntoView })
    }

    @Test fun theDefaultKeymapBindsThePlatformsConventions() {
        fun run(apple: Boolean, marked: String, chord: KeyChord): String {
            val v = view(marked, defaultKeymap(apple))
            assertTrue(runKey(v, chord, apple), "$chord was not bound (apple=$apple)")
            return v.marked()
        }
        val left = KeyChord("ArrowLeft")
        assertEquals("one |two", run(true, "one two|", left.copy(alt = true)))
        assertEquals("|one two", run(true, "one two|", left.copy(meta = true)))
        assertEquals("one |two", run(false, "one two|", left.copy(ctrl = true)))
        assertEquals("one ]two[", run(false, "one two|", left.copy(ctrl = true, shift = true)))
        assertEquals("[one two]", run(true, "one| two", KeyChord("a", meta = true)))
        assertEquals("[one two]", run(false, "one| two", KeyChord("a", ctrl = true)))
        assertEquals("|one\ntwo", run(false, "one\ntw|o", KeyChord("Home", ctrl = true)))
        assertEquals("one |", run(true, "one two|", KeyChord("Backspace", alt = true)))
        assertEquals("one\n|", run(false, "one|", KeyChord("Enter")))
        assertEquals("on|", run(false, "one|", KeyChord("Backspace")))
        assertEquals("    |one", run(false, "|one", KeyChord("Tab")))
        // A key nothing binds is left alone (it reaches the hidden field as typed text).
        val v = view("|", defaultKeymap(false))
        assertFalse(runKey(v, KeyChord("x"), false))
    }
}
