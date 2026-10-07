package dev.supermux.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FuzzyMatchTest {
    @Test fun empty_query_matches_with_zero_score() {
        assertEquals(FuzzyHit(0, emptyList()), fuzzyMatch("", "anything"))
    }

    @Test fun non_subsequence_is_null() {
        assertNull(fuzzyMatch("xyz", "supermux"))
    }

    @Test fun contiguous_substring_reports_its_range() {
        assertEquals(listOf(5, 6, 7), assertNotNull(fuzzyMatch("MUX", "supermux")).indices)
    }

    @Test fun prefix_beats_mid_word_substring() {
        val prefix = assertNotNull(fuzzyMatch("track", "tracker")).score
        val mid = assertNotNull(fuzzyMatch("track", "flighttrack")).score
        assertTrue(prefix > mid)
    }

    @Test fun word_start_letters_are_preferred() {
        // g̲reen-m̲ate: the m after the dash, not an earlier stray m.
        assertEquals(listOf(0, 6), assertNotNull(fuzzyMatch("gm", "green-mate")).indices)
    }

    @Test fun substring_beats_scattered_subsequence() {
        val substring = assertNotNull(fuzzyMatch("sup", "supercomment")).score
        val scattered = assertNotNull(fuzzyMatch("sup", "simple-sync-up")).score
        assertTrue(substring > scattered)
    }
}
