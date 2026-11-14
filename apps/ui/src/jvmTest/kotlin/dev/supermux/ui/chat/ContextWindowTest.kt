package dev.supermux.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** #144: how the ring and panel say sizes and how close the window is to where the agent compacts. */
class ContextWindowTest {
    @Test fun tokens_read_like_the_prototype() {
        assertEquals("961", tokensLabel(961))
        assertEquals("1.3k", tokensLabel(1300))
        assertEquals("6.9k", tokensLabel(6900))
        assertEquals("10k", tokensLabel(10_000))
        assertEquals("97.1k", tokensLabel(97_100))
        assertEquals("154k", tokensLabel(154_000))
        assertEquals("1000k", tokensLabel(1_000_000))
    }

    @Test fun amber_within_a_tenth_of_the_window_under_the_reported_point_red_past_it() {
        // Claude, 300k window, compacts at 267k.
        assertEquals(ContextLevel.Normal, contextLevel(150_000, 300_000, 267_000))
        assertEquals(ContextLevel.Near, contextLevel(240_000, 300_000, 267_000))
        assertEquals(ContextLevel.Over, contextLevel(268_000, 300_000, 267_000))
    }

    @Test fun without_a_reported_point_the_limit_is_95_percent_of_the_window() {
        // Codex: no threshold reported, so no guess at its own; warn near the window itself.
        assertEquals(ContextLevel.Normal, contextLevel(200_000, 258_400, null))
        assertEquals(ContextLevel.Near, contextLevel(226_000, 258_400, null))
        assertEquals(ContextLevel.Over, contextLevel(250_000, 258_400, null))
        assertEquals(ContextLevel.Normal, contextLevel(10, 0, null))
    }
}
