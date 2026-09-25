package dev.supermux.editor.spike

import kotlin.test.Test
import kotlin.test.assertEquals

// tree-sitter-json 0.24.8 queries/highlights.scm, verbatim.
const val JSON_HIGHLIGHTS = """
(pair
  key: (_) @string.special.key)

(string) @string

(number) @number

[
  (null)
  (true)
  (false)
] @constant.builtin

(escape_sequence) @escape

(comment) @comment
"""

// Raw string: the \n below is a backslash + n in the JSON (an escape_sequence), not a newline.
const val SAMPLE = """{"ağ": [1, true, null], "e😀": "x\n"}"""

class GoldenHighlightTest {
    @Test
    fun highlightsAreUtf16AndIdenticalOnEveryBackend() = runSpike {
        val h = openJsonHighlighter()
        try {
            h.parse(SAMPLE)
            assertEquals(
                listOf(
                    "1-5 string", "1-5 string.special.key",
                    "8-9 number",
                    "11-15 constant.builtin",
                    "17-21 constant.builtin",
                    "24-29 string", "24-29 string.special.key",
                    "31-36 string",
                    "33-35 escape",
                ),
                h.highlights(JSON_HIGHLIGHTS).map { it.toString() },
            )
        } finally { h.close() }
    }

    @Test
    fun incrementalEditShiftsLaterSpans() = runSpike {
        val h = openJsonHighlighter()
        try {
            h.parse(SAMPLE)
            // Replace `1` (8..9) with `12345`: +4 units. Everything after index 9 moves by 4.
            val next = h.edit(8, 9, "12345")
            assertEquals(SAMPLE.replaceRange(8, 9, "12345"), next)
            val spans = h.highlights(JSON_HIGHLIGHTS).map { it.toString() }
            assertEquals("8-13 number", spans[2])
            assertEquals("35-40 string", spans[7])
            assertEquals("37-39 escape", spans[8])
        } finally { h.close() }
    }
}
