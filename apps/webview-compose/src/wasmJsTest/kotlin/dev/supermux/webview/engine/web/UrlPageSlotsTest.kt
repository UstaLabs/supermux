package dev.supermux.webview.engine.web

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An engine as [UrlPageSlots] sees it, recording what it is told. */
private class FakeSlotUser(
    val name: String,
    override var visible: Boolean = true,
    override var hasSurface: Boolean = true,
) : UrlSlotUser {
    val told = ArrayList<String>()

    override fun slotGranted() {
        told += "granted"
    }

    override fun yieldSlot() {
        told += "yielded"
    }

    override fun toString() = name
}

/**
 * Each rule of [UrlPageSlots], one at a time: who gives a slot up, to whom, and who waits. Only views off screen ever
 * give theirs up, so a view on screen never goes blank for another's sake.
 */
class UrlPageSlotsTest {
    private val users = ArrayList<FakeSlotUser>()

    private fun user(name: String, visible: Boolean = true, hasSurface: Boolean = true) =
        FakeSlotUser(name, visible, hasSurface).also { users += it }

    /** The budget's worth of holders, all given their slot at once. */
    private fun holders(vararg all: FakeSlotUser) {
        assertEquals(WebEngineDecisions.MAX_LIVE_URL, all.size)
        all.forEach { assertTrue(UrlPageSlots.take(it), "$it took a free slot") }
    }

    @AfterTest fun giveEverythingBack() {
        users.forEach { UrlPageSlots.give(it) }
        assertEquals(0, UrlPageSlots.held)
    }

    @Test fun aPageOnScreenTakesTheSlotOfAParkedPageBeforeAHiddenOne() {
        val hidden = user("hidden", visible = false)
        val parked = user("parked", visible = false, hasSurface = false)
        holders(hidden, user("a"), parked, user("b"))
        assertTrue(UrlPageSlots.take(user("newcomer")))
        assertEquals(listOf("yielded"), parked.told, "the parked page, which no view shows")
        assertEquals(emptyList(), hidden.told, "and not the hidden one, whose view may come back any moment")
    }

    @Test fun aPageOnScreenTakesTheSlotOfAHiddenOneWhenNoneIsParked() {
        val hidden = user("hidden", visible = false)
        holders(user("a"), hidden, user("b"), user("c"))
        assertTrue(UrlPageSlots.take(user("newcomer")))
        assertEquals(listOf("yielded"), hidden.told)
    }

    @Test fun aPageOnScreenNeverLosesItsSlot() {
        val onScreen = List(WebEngineDecisions.MAX_LIVE_URL) { user("on screen $it") }
        holders(*onScreen.toTypedArray())
        assertFalse(UrlPageSlots.take(user("newcomer")), "it waits")
        onScreen.forEach { assertEquals(emptyList(), it.told, "$it gave its slot up") }
    }

    /** A spare, or a page whose view isn't on screen yet, waits: it never takes a slot from a page that has one. */
    @Test fun aPageOffScreenNeverTakesAnotherPagesSlot() {
        val hidden = List(WebEngineDecisions.MAX_LIVE_URL) { user("hidden $it", visible = false) }
        holders(*hidden.toTypedArray())
        assertFalse(UrlPageSlots.take(user("spare", visible = false)))
        hidden.forEach { assertEquals(emptyList(), it.told, "$it gave its slot up") }
    }

    @Test fun aWaitingPageKeepsItsPlaceInLineWhenItAsksAgain() {
        val first = user("first holder")
        holders(first, user("b"), user("c"), user("d"))
        val early = user("early")
        val late = user("late")
        assertFalse(UrlPageSlots.take(early))
        assertFalse(UrlPageSlots.take(late))
        assertFalse(UrlPageSlots.take(early), "asked again")
        UrlPageSlots.give(first)
        assertEquals(listOf("granted"), early.told, "the first in line got the slot")
        assertEquals(emptyList(), late.told)
    }

    @Test fun aSlotThatFreesGoesToAPageOnScreenFirst() {
        val first = user("first holder")
        holders(first, user("b"), user("c"), user("d"))
        val hiddenWaiter = user("hidden waiter", visible = false)
        val onScreenWaiter = user("on-screen waiter")
        assertFalse(UrlPageSlots.take(hiddenWaiter))
        assertFalse(UrlPageSlots.take(onScreenWaiter))
        UrlPageSlots.give(first)
        assertEquals(listOf("granted"), onScreenWaiter.told)
        assertEquals(emptyList(), hiddenWaiter.told)
    }

    @Test fun aHolderLeavingTheScreenGivesItsSlotToAPageOnScreenThatWaits() {
        val leaving = user("leaving")
        holders(leaving, user("b"), user("c"), user("d"))
        val waiter = user("waiter")
        assertFalse(UrlPageSlots.take(waiter))
        leaving.visible = false
        UrlPageSlots.visibilityChanged(leaving)
        assertEquals(listOf("yielded"), leaving.told)
        assertEquals(listOf("granted"), waiter.told)
    }

    @Test fun aHolderLeavingTheScreenKeepsItsSlotWhenNoPageOnScreenWaits() {
        val leaving = user("leaving")
        holders(leaving, user("b"), user("c"), user("d"))
        assertFalse(UrlPageSlots.take(user("hidden waiter", visible = false)))
        leaving.visible = false
        UrlPageSlots.visibilityChanged(leaving)
        assertEquals(emptyList(), leaving.told)
    }
}
