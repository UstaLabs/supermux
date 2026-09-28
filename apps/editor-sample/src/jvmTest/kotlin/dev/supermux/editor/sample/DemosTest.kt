package dev.supermux.editor.sample

import dev.supermux.editor.plugins.diff.DiffHunk
import dev.supermux.editor.plugins.diff.LineDiff
import kotlin.test.Test
import kotlin.test.assertEquals

class DemosTest {
    private val base = (0 until 300).joinToString("\n") { "line $it" }

    @Test fun theFakeWorkingCopyDiffsIntoItsFourHunks() {
        val hunks = LineDiff.diff(base.split('\n'), fakeWorkingCopy(base).split('\n')).hunks.map { it.copy(chars = null) }
        assertEquals(listOf(
            DiffHunk(25, 26, 25, 26),
            DiffHunk(40, 45, 40, 40),
            DiffHunk(101, 101, 96, 99),
            DiffHunk(150, 152, 148, 152),
        ), hunks)
    }
}
