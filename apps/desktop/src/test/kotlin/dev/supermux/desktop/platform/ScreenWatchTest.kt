package dev.supermux.desktop.platform

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Card #86 part 2: a focused window on a locked screen, or behind sleeping displays, counted as
 * someone reading the chat open there, and muted that chat's push on the phone in his pocket.
 */
class ScreenWatchTest {
    private class FakeProbe(var state: ScreenState? = ScreenState()) : ScreenProbe {
        var fails = false
        override fun read(): ScreenState? = if (fails) error("probe broke") else state
    }

    private fun seenAfterPoll(probe: ScreenProbe): Boolean = ScreenWatch(probe).also { it.pollOnce() }.seen.value

    @Test fun anAwakeUnlockedScreenIsSeen() {
        assertTrue(seenAfterPoll(FakeProbe(ScreenState(locked = false, displaysAsleep = false))))
    }

    @Test fun aLockedScreenIsNotSeen() {
        assertFalse(seenAfterPoll(FakeProbe(ScreenState(locked = true))))
    }

    @Test fun sleepingDisplaysAreNotSeen() {
        assertFalse(seenAfterPoll(FakeProbe(ScreenState(displaysAsleep = true))))
    }

    @Test fun unlockingMakesTheScreenSeenAgain() {
        val probe = FakeProbe(ScreenState(locked = true))
        val watch = ScreenWatch(probe)
        watch.pollOnce()
        assertFalse(watch.seen.value)
        probe.state = ScreenState()
        watch.pollOnce()
        assertTrue(watch.seen.value)
    }

    // Detection must never hide the desktop for good: an OS that cannot tell, or a probe that
    // breaks, leaves the screen counted as seen, which is how it behaved before this existed.
    @Test fun aProbeThatCannotTellLeavesTheScreenSeen() {
        assertTrue(seenAfterPoll(FakeProbe(state = null)))
    }

    @Test fun aProbeThatFailsLeavesTheScreenSeen() {
        assertTrue(seenAfterPoll(FakeProbe().apply { fails = true }))
    }

    // WTSSessionInfoEx's SessionFlags: WTS_SESSIONSTATE_LOCK is 0, _UNLOCK is 1, _UNKNOWN is -1.
    @Test fun windowsSessionFlagsSayWhetherTheSessionIsLocked() {
        assertEquals(true, windowsSessionLocked(0))
        assertEquals(false, windowsSessionLocked(1))
        assertNull(windowsSessionLocked(-1))
    }

    // GUID_CONSOLE_DISPLAY_STATE's data byte: 0 off, 1 on, 2 dimmed. A dimmed display still shows.
    @Test fun windowsDisplayStateSaysWhetherTheDisplaysSleep() {
        assertTrue(windowsDisplayAsleep(0))
        assertFalse(windowsDisplayAsleep(1))
        assertFalse(windowsDisplayAsleep(2))
    }

    @Test fun linuxCannotTell() {
        assertNull(screenProbe(DesktopOs.LINUX).read())
    }

    // The real probes on the OS they are for: a missing or misnamed export would throw here, and
    // the watch would silently count the screen as seen forever. CI's windows-desktop lane runs the
    // first; the second needs a Mac. A CI session may not be able to say, so only the call is checked.
    @Test fun theWindowsProbeCallsWindowsWithoutFailing() {
        assumeTrue("Windows only", DesktopOs.current == DesktopOs.WINDOWS)
        WindowsScreenProbe.readOrThrow()
    }

    @Test fun theMacProbeCallsMacOsWithoutFailing() {
        assumeTrue("macOS only", DesktopOs.current == DesktopOs.MACOS)
        MacScreenProbe.readOrThrow()
    }
}
