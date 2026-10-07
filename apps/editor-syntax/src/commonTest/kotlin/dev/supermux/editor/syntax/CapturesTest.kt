package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturesTest {
    @Test fun longestDottedPrefixWins() {
        assertEquals("tok-method", tokenClassFor("function.method.builtin"))
        assertEquals("tok-string-special", tokenClassFor("string.special.key"))
        assertEquals("tok-punctuation", tokenClassFor("punctuation.bracket"))
        assertEquals("tok-function-builtin", tokenClassFor("function.builtin"))
        assertEquals("tok-function", tokenClassFor("function.call"))
        assertEquals("tok-keyword", tokenClassFor("keyword.control.conditional"))
        assertEquals("tok-number", tokenClassFor("constant.numeric.integer"))
        assertEquals("tok-escape", tokenClassFor("constant.character.escape"))
        assertEquals("tok-constant-builtin", tokenClassFor("constant.builtin.boolean"))
        assertEquals("tok-property", tokenClassFor("variable.other.member"))
        assertEquals("tok-parameter", tokenClassFor("variable.parameter"))
        assertEquals("tok-markup-heading", tokenClassFor("markup.heading.1"))
        assertEquals("tok-regexp", tokenClassFor("string.regexp"))
        assertEquals("tok-type-builtin", tokenClassFor("type.builtin"))
        assertEquals("tok-attribute", tokenClassFor("tag.attribute"))
    }

    @Test fun privateAndUnknownCapturesAreNotDrawn() {
        assertNull(tokenClassFor("_private"))
        assertNull(tokenClassFor("_"))
        assertNull(tokenClassFor("none"))
        assertNull(tokenClassFor("spell"))
        assertNull(tokenClassFor("totally.unknown"))
        assertNull(tokenClassFor(""))
    }

    @Test fun everyClassIsInTheVocabulary() {
        val used = CAPTURE_CLASSES.values.filter { it.isNotEmpty() }.toSet()
        assertTrue(TokenClasses.ALL.containsAll(used), (used - TokenClasses.ALL.toSet()).toString())
        assertEquals(29, TokenClasses.ALL.size)
    }
}
