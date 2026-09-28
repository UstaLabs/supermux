package dev.supermux.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §8: the rope knows only `\n`; the host normalizes on load and restores the file's ending on save. */
class LineEndingsTest {
    @Test fun an_lf_file_is_kept_as_it_is() {
        val l = LineEndings.load("a\nb\n")
        assertEquals("a\nb\n", l.text)
        assertFalse(l.crlf)
        assertEquals("a\nb\n", LineEndings.save(l.text, l.crlf))
    }

    @Test fun a_crlf_file_loads_as_lf_and_saves_as_crlf_byte_for_byte() {
        val raw = "fun a() {\r\n    b()\r\n}\r\n"
        val l = LineEndings.load(raw)
        assertEquals("fun a() {\n    b()\n}\n", l.text)
        assertTrue(l.crlf)
        assertEquals(raw, LineEndings.save(l.text, l.crlf))
    }

    @Test fun a_line_typed_into_a_crlf_file_is_saved_with_crlf_too() {
        val l = LineEndings.load("a\r\nb\r\n")
        assertEquals("a\r\nnew\r\nb\r\n", LineEndings.save(l.text.replace("a\n", "a\nnew\n"), l.crlf))
    }

    @Test fun a_mixed_file_takes_its_majority_ending() {
        assertTrue(LineEndings.load("a\r\nb\r\nc\n").crlf)
        assertFalse(LineEndings.load("a\nb\nc\r\n").crlf)
        // Every \r\n is normalized either way: the rope never sees one.
        assertEquals("a\nb\nc\n", LineEndings.load("a\nb\nc\r\n").text)
    }

    @Test fun a_lone_cr_and_an_empty_file_are_left_alone() {
        assertEquals("a\rb", LineEndings.load("a\rb").text)
        assertFalse(LineEndings.load("").crlf)
        assertEquals("", LineEndings.save("", crlf = true))
    }
}
