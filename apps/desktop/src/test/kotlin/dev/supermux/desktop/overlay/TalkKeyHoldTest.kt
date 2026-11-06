package dev.supermux.desktop.overlay

import dev.supermux.desktop.overlay.TalkKeyHold.Out
import kotlin.test.Test
import kotlin.test.assertEquals

class TalkKeyHoldTest {
    private fun key() = TalkKeyHold(holdMs = 300)

    @Test
    fun heldAloneItTalksAndLettingGoSends() {
        val k = key()
        assertEquals(emptyList(), k.keyDown(at = 0))
        assertEquals(emptyList(), k.tick(at = 200))
        assertEquals(listOf(Out.Start), k.tick(at = 300))
        assertEquals(emptyList(), k.tick(at = 900))
        assertEquals(listOf(Out.Send), k.keyUp(at = 2_000))
    }

    @Test
    fun aQuickTapDoesNothing() {
        val k = key()
        k.keyDown(at = 0)
        assertEquals(emptyList(), k.keyUp(at = 120))
        assertEquals(emptyList(), k.tick(at = 400))
    }

    // ⌥ characters, and AltGr on a Turkish keyboard (AltGr+Q is @): the key was a shortcut, not a talk.
    @Test
    fun anotherKeyBeforeItTalksMakesItAShortcut() {
        val k = key()
        k.keyDown(at = 0)
        assertEquals(emptyList(), k.otherKey(at = 100))
        assertEquals(emptyList(), k.tick(at = 400))
        assertEquals(emptyList(), k.keyUp(at = 600))
    }

    @Test
    fun anotherKeyWhileTalkingCancelsTheTalk() {
        val k = key()
        k.keyDown(at = 0)
        k.tick(at = 300)
        assertEquals(listOf(Out.Cancel), k.otherKey(at = 800))
        assertEquals(emptyList(), k.keyUp(at = 900))
    }

    @Test
    fun aRepeatedDownDoesNotRestartTheHold() {
        val k = key()
        k.keyDown(at = 0)
        k.keyDown(at = 250)
        assertEquals(listOf(Out.Start), k.tick(at = 300))
    }

    @Test
    fun afterLettingGoItCanTalkAgain() {
        val k = key()
        k.keyDown(at = 0); k.otherKey(at = 50); k.keyUp(at = 100)
        k.keyDown(at = 1_000)
        assertEquals(listOf(Out.Start), k.tick(at = 1_300))
        assertEquals(listOf(Out.Send), k.keyUp(at = 1_500))
    }
}
