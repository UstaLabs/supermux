package dev.supermux.editor.spike

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowDiffTest {
    @Test fun insertInMiddle() = assertEquals(Edit(2, 2, "X"), diffField("abcd", "abXcd"))
    // M0: the plan expected Edit(4, 7, "the"), a whole-word replacement, but diffField is documented
    // as the SMALLEST single replacement, and "t" is a common prefix. Both rebuild the same document,
    // which is what the probe needs; the word-level shape was never implemented, so this asserts both.
    @Test fun autocorrectReplacesWord() {
        val e = diffField("hi, teh.", "hi, the.")!!
        assertEquals(Edit(5, 7, "he"), e)
        assertEquals("hi, the.", "hi, teh.".replaceRange(e.from, e.to, e.insert))
    }
    @Test fun deleteAtStart() = assertEquals(Edit(0, 1, ""), diffField("ab", "b"))
    @Test fun identical() = assertEquals(null, diffField("ab", "ab"))
    @Test fun repeatedCharsPreferTheCursorSide() =
        // "aa" -> "aaa" with the cursor at 2 is an insert AT 2, not at 0.
        assertEquals(Edit(2, 2, "a"), diffField("aa", "aaa", cursorAfter = 3))

    @Test fun windowMapsBackToDocument() {
        val doc = "0123456789abcdef"
        val w = FieldWindow.around(doc, cursor = 10, radius = 3)   // "789abc", base 7
        assertEquals("789abc", w.text)
        val edit = diffField(w.text, "789Zabc")!!                   // user typed Z at doc 10
        assertEquals("0123456789Zabcdef", w.applyTo(doc, edit))
    }
}
