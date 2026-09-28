package dev.supermux.editor.plugins.search

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Case folding outside the Basic Multilingual Plane: a literal search never folds there (one
 * UTF-16 unit at a time); a regex's IGNORE_CASE is the engine's. Printed per platform (the README
 * records what each engine did), asserted only where every platform agrees.
 */
class CaseFoldingTest {
    @Test fun supplementaryLettersFoldOnlyWhereTheEngineDoes() {
        val text = "𐐀 𐐨" // DESERET CAPITAL LONG I, DESERET SMALL LONG I
        val literal = SearchQuery("𐐨").cursor(Rope.of(text)).asSequence().count()
        val regex = SearchQuery("𐐨", regexp = true).cursor(Rope.of(text)).asSequence().count()
        println("SEARCH-CASEFOLD supplementary: literal $literal, regex $regex (of 2)")
        assertEquals(1, literal, "a literal search does not fold outside the BMP")
    }
}
