package dev.supermux.ui.overlay

import kotlin.test.Test
import kotlin.test.assertEquals

class OverlayStateTest {
    private fun state(
        listening: Boolean = false, speaking: Boolean = false, online: Boolean = true, working: Boolean = false,
        unread: Boolean = false, dozing: Boolean = false, awake: Boolean = false,
    ) = overlayAvatarState(listening, speaking, online, working, unread, dozing, awake)

    @Test fun holdingItAlwaysShowsItListening() = assertEquals("listening", state(listening = true, online = false, working = true))
    @Test fun aStoppedSessionSleeps() = assertEquals("asleep", state(online = false, unread = true))
    @Test fun aReplyTypingOutIsSpoken() = assertEquals("speaking", state(speaking = true, unread = true))
    @Test fun busyBeatsUnread() = assertEquals("working", state(working = true, unread = true))
    @Test fun anUnreadReplyWaitsForYou() = assertEquals("waiting", state(unread = true, dozing = true))
    @Test fun quietForAWhileItDozes() = assertEquals("asleep", state(dozing = true))
    @Test fun touchingOrOpeningItWakesItUp() = assertEquals("idle", state(dozing = true, awake = true))
    @Test fun otherwiseIdle() = assertEquals("idle", state())

    @Test fun silenceIsZeroAndLoudIsOne() {
        assertEquals(0f, voiceLevel(0f))
        assertEquals(0f, voiceLevel(0.001f))            // −60 dBFS
        assertEquals(1f, voiceLevel(1f))                // 0 dBFS
        assertEquals(0.5f, voiceLevel(0.031622775f), 0.001f) // −30 dBFS, halfway between −50 and −10
    }
}
