package dev.supermux.editor.compose

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccessibleTextTest {
    private val doc = Rope.of((0 until 100).joinToString("\n") { "line $it" })

    @Test fun theVisibleLinesAreExposedAsTheDocumentHasThem() {
        val t = AccessibleText.build(doc, 3..5, caret = doc.lineStart(4) + 2)
        assertEquals("line 3\nline 4\nline 5", t.text)
        val at = doc.lineStart(4) + 2
        assertEquals("line 3\nli".length, t.toText(at))
        assertEquals(at, t.toDoc(t.toText(at)))
        // The newline between two neighbouring lines is the document's own.
        assertEquals(doc.lineStart(4), t.toDoc("line 3\n".length))
        assertEquals(doc.lineStart(4) - 1, t.toDoc("line 3".length))
    }

    @Test fun aCaretOffScreenAddsItsLineOnly() {
        val caret = doc.lineStart(90) + 3
        val t = AccessibleText.build(doc, 0..1, caret)
        assertEquals("line 0\nline 1\nline 90", t.text)
        assertEquals(3, t.segments)
        assertEquals("line 0\nline 1\nlin".length, t.toText(caret))
        assertEquals(caret, t.toDoc(t.toText(caret)))
        // A document offset between the segments clamps to the nearest exposed end.
        assertEquals("line 0\nline 1".length, t.toText(doc.lineStart(50)))
        assertTrue(t.text.length < 40, "the whole document was exposed")
    }

    @Test fun aHugeLineIsExposedAroundTheCaret() {
        val big = Rope.of("a\n" + "x".repeat(50_000) + "\nb")
        val caret = 2 + 30_000
        val t = AccessibleText.build(big, 0..2, caret)
        assertTrue(t.text.length <= 2 * AccessibleText.MAX_LINE + 10, "exposed ${t.text.length} units")
        assertEquals(caret, t.toDoc(t.toText(caret)))
        assertTrue(t.text.startsWith("a\n") && t.text.endsWith("\nb"))
    }
}
