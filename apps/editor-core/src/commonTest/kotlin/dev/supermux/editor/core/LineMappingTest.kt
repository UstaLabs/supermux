package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LineMappingTest {
    @Test fun aMappingIsDataFromAFacet() {
        val m = LineMapping(listOf(LineMapping.Hunk(2, 4, 2, 2), LineMapping.Hunk(10, 10, 8, 11)))
        val st = EditorState.create("x", extensions = lineMappingFacet.of(m))
        assertEquals(m, st.facet(lineMappingFacet))
        assertEquals(null, EditorState.create("x").facet(lineMappingFacet))
    }

    @Test fun hunksMustBeInOrder() {
        assertFailsWith<IllegalArgumentException> { LineMapping(listOf(LineMapping.Hunk(10, 12, 10, 12), LineMapping.Hunk(2, 3, 2, 3))) }
        assertFailsWith<IllegalArgumentException> { LineMapping.Hunk(5, 4, 0, 0) }
    }
}
