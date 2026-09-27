package dev.supermux.editor.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class LineMappingTest {
    // A: 50..54 deleted in B; 3 lines inserted in B after A's 100; A's 150..151 became 4 lines in B.
    private val m = LineMapping(listOf(
        LineMapping.Hunk(50, 55, 50, 50),
        LineMapping.Hunk(101, 101, 96, 99),
        LineMapping.Hunk(150, 152, 148, 152),
    ))

    @Test fun equalRunsPairLineForLine() {
        assertEquals(Triple(10, 10, false), m.pair(LinkedSide.A, 10))
        assertEquals(Triple(55, 50, false), m.pair(LinkedSide.A, 55))
        assertEquals(Triple(100, 95, false), m.pair(LinkedSide.A, 100))
        assertEquals(Triple(101, 99, false), m.pair(LinkedSide.A, 101), "the line after an insertion in B")
        assertEquals(Triple(149, 147, false), m.pair(LinkedSide.A, 149))
        assertEquals(Triple(152, 152, false), m.pair(LinkedSide.A, 152))
        assertEquals(Triple(200, 200, false), m.pair(LinkedSide.A, 200))
        assertEquals(Triple(55, 50, false), m.pair(LinkedSide.B, 50), "B's line after a deletion")
        assertEquals(Triple(101, 99, false), m.pair(LinkedSide.B, 99))
        assertEquals(Triple(200, 200, false), m.pair(LinkedSide.B, 200))
    }

    @Test fun changedRunsOfDifferentLengthsPairAtTheirStart() {
        assertEquals(Triple(50, 50, true), m.pair(LinkedSide.A, 52), "inside A's deleted run")
        assertEquals(Triple(101, 96, true), m.pair(LinkedSide.B, 97), "inside B's inserted run")
        assertEquals(Triple(150, 148, true), m.pair(LinkedSide.A, 151))
        assertEquals(Triple(150, 148, true), m.pair(LinkedSide.B, 151))
    }

    @Test fun theIdentityMapsEveryLineToItself() {
        assertEquals(Triple(7, 7, false), LineMapping.IDENTITY.pair(LinkedSide.B, 7))
    }
}
