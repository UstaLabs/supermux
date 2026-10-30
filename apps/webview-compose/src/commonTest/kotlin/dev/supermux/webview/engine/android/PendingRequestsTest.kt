package dev.supermux.webview.engine.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingRequestsTest {
    @Test fun answerAllGivesEachOpenRequestItsSafeDefaultOnce() {
        val pending = PendingRequests()
        val answers = mutableListOf<String>()
        pending.open("alert") { answers += "alert dismissed" }
        pending.open("camera") { answers += "camera denied" }
        pending.answerAll()
        pending.answerAll()
        assertEquals(listOf("alert dismissed", "camera denied"), answers)
        assertEquals(0, pending.size)
    }

    @Test fun anAnswerClosesTheRequestAndALateOneReachesNothing() {
        val pending = PendingRequests()
        val answers = mutableListOf<String>()
        pending.open("confirm") { answers += "cancelled" }
        assertTrue(pending.answer("confirm") { answers += "confirmed" })
        assertFalse(pending.answer("confirm") { answers += "twice" })
        pending.answerAll()
        assertEquals(listOf("confirmed"), answers)
    }

    @Test fun aWithdrawnRequestGetsNoAnswer() {
        val pending = PendingRequests()
        var answered = false
        pending.open("geolocation") { answered = true }
        pending.withdraw("geolocation")
        pending.answerAll()
        assertFalse(answered)
        assertFalse(pending.answer("geolocation") { answered = true })
    }

    /**
     * A request closed without the client's answer (its document went, the page withdrew it) stops the asking too, so
     * the app's dialog or prompt goes with it; one the client answered has nothing left to stop.
     */
    @Test fun aRequestClosedWithoutAnAnswerStopsTheAsking() {
        val pending = PendingRequests()
        val stopped = mutableListOf<String>()
        for (key in listOf("dialog", "camera", "answered")) {
            pending.open(key) {}
            pending.asking(key) { stopped += key }
        }
        assertTrue(pending.answer("answered") {})
        pending.withdraw("camera")
        pending.answerAll()
        assertEquals(listOf("camera", "dialog"), stopped)
    }

    @Test fun anAnswerThatThrowsStopsNoOther() {
        val pending = PendingRequests()
        var second = false
        pending.open(1) { throw IllegalStateException("the page is gone") }
        pending.open(2) { second = true }
        pending.answerAll()
        assertTrue(second)
    }
}
