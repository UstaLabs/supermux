package dev.supermux.editor.sample

import dev.supermux.editor.core.LineMapping
import kotlin.test.Test
import kotlin.test.assertEquals

class DemosTest {
    private val base = (0 until 300).joinToString("\n") { "line $it" }

    @Test fun theFakeWorkingCopyDiffsIntoItsThreeHunks() {
        val hunks = lineDiff(base.split('\n'), fakeWorkingCopy(base).split('\n'))
        assertEquals(listOf(
            LineMapping.Hunk(40, 45, 40, 40),
            LineMapping.Hunk(101, 101, 96, 99),
            LineMapping.Hunk(150, 152, 148, 152),
        ), hunks)
    }

    @Test fun identicalTextsHaveNoHunksAndOneEditOne() {
        val a = base.split('\n')
        assertEquals(emptyList(), lineDiff(a, a))
        val b = a.toMutableList().apply { set(10, "edited"); add(200, "new") }
        assertEquals(listOf(LineMapping.Hunk(10, 11, 10, 11), LineMapping.Hunk(200, 200, 200, 201)), lineDiff(a, b))
    }
}
