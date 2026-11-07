package dev.supermux.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A `supermux://pair` link is handed to the connect page once per delivery (#89). An Android
 * recreation hands the activity the same intent again, and the connect page validating its link a
 * second time replaced the trust dialog the user had open, or a link they had pasted since.
 * [MainActivity] saves the link it handed over with its state, and a new delivery clears that mark.
 */
class MainActivityPairLinkTest {

    private val link = "supermux://pair?t=abc123&h=https%3A%2F%2Fhost%3A9898"

    @Test
    fun a_link_not_handed_over_yet_goes_to_the_connect_page() {
        assertEquals(link, pairLinkToApply(link, handedOver = null))
    }

    @Test
    fun the_same_delivery_after_a_recreation_is_not_applied_again() {
        // Handed over before the activity was recreated; the restored state says so.
        assertNull(pairLinkToApply(link, handedOver = link))
    }

    @Test
    fun another_link_is_applied() {
        val other = "supermux://pair?t=def456&h=https%3A%2F%2Fother%3A9898"
        assertEquals(other, pairLinkToApply(other, handedOver = link))
    }

    @Test
    fun the_same_link_delivered_again_is_applied_once_its_mark_is_cleared() {
        // onNewIntent clears the mark: tapping the link again is the user's, so it applies.
        assertEquals(link, pairLinkToApply(link, handedOver = null))
    }

    @Test
    fun an_intent_without_a_link_hands_nothing_over() {
        assertNull(pairLinkToApply(null, handedOver = null))
        assertNull(pairLinkToApply("", handedOver = null))
        assertNull(pairLinkToApply("  ", handedOver = link))
    }
}
