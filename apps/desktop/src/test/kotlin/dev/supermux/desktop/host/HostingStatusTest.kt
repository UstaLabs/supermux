package dev.supermux.desktop.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostingStatusTest {
    private val bg = HostingPrefs(background = true)
    private val fg = HostingPrefs(background = false)

    @Test fun runningHeader() = assertEquals(
        "supermux is running · 3 sessions",
        TrayModel.of(HostingStatus.Running(port = 9898, readOnly = false), bg, sessions = 3, remoteName = null).header,
    )

    @Test fun singularHeader() = assertEquals(
        "supermux is running · 1 session",
        TrayModel.of(HostingStatus.Running(port = 9898, readOnly = false), bg, sessions = 1, remoteName = null).header,
    )

    @Test fun movedPortHeaderKeepsTheSessionCount() {
        val m = TrayModel.of(HostingStatus.Running(port = 60094, readOnly = false), bg, 3, null)
        assertEquals("supermux is running · 3 sessions · port 60094", m.header)
        assertEquals(Dot.GREEN, m.dot)
        assertTrue(m.restartEnabled)
        assertEquals(
            "supermux is running · 1 session · port 9912",
            TrayModel.of(HostingStatus.Running(port = 9912, readOnly = false), bg, 1, null).header,
        )
    }

    @Test fun movedPortNeverClaimsTheDefaultIsInUse() {
        val h = TrayModel.of(HostingStatus.Running(port = 9912, readOnly = false), bg, 0, null).header
        assertFalse("in use" in h)
        assertEquals("supermux is running · 0 sessions · port 9912", h)
    }

    @Test fun trayAndSettingsSayTheSameCount() {
        val s = HostingStatus.Running(port = 60094, readOnly = false)
        val tray = TrayModel.of(s, bg, 4, null).header
        val settings = hostingStatusLine(s, bg, 4, null).text
        assertTrue(sessionCountText(4) in tray)
        assertTrue(sessionCountText(4) in settings)
    }

    @Test fun readOnlyDisablesKeepRunning() {
        val m = TrayModel.of(HostingStatus.Running(9898, readOnly = true), bg, 2, null)
        assertEquals("supermux is running · 2 sessions · managed outside the app", m.header)
        assertFalse(m.keepRunningEnabled)
    }

    @Test fun crashLoop() {
        val m = TrayModel.of(HostingStatus.CantStart("exit 1"), bg, 0, null)
        assertEquals(Dot.RED, m.dot); assertTrue(m.showLog); assertEquals("Try again", m.restartLabel)
    }

    @Test fun notHostingIsJustConnected() {
        val m = TrayModel.of(HostingStatus.NotHosting, bg.copy(hosting = false), 0, remoteName = "ustalabs-linux")
        assertEquals("Connected to ustalabs-linux", m.header); assertNull(m.restartLabel)
    }

    @Test fun notHostingUnreachableRemoteIsRetrying() {
        val m = TrayModel.of(HostingStatus.NotHosting, bg.copy(hosting = false), 0, "ustalabs-linux", remoteReachable = false)
        assertEquals("Can't reach ustalabs-linux · retrying", m.header)
        assertEquals(Dot.YELLOW, m.dot)
        assertNull(m.restartLabel); assertFalse(m.showKeepRunning)
    }

    @Test fun notHostingReachableRemoteStaysGrey() {
        val m = TrayModel.of(HostingStatus.NotHosting, bg.copy(hosting = false), 0, "ustalabs-linux", remoteReachable = true)
        assertEquals(Dot.GREY, m.dot)
    }

    @Test fun notHostingWithNoRemoteIgnoresReachability() {
        val m = TrayModel.of(HostingStatus.NotHosting, bg.copy(hosting = false), 0, null, remoteReachable = false)
        assertEquals("Not hosting", m.header); assertEquals(Dot.GREY, m.dot)
    }

    @Test fun quitTextFollowsTheCheckbox() {
        assertEquals("supermux will keep running in the background.", QuitText.of(HostingStatus.Running(9898, false), bg, 3))
        assertEquals("This stops supermux and your 3 running sessions.", QuitText.of(HostingStatus.Running(9898, false), fg, 3))
        assertEquals("This stops supermux and your 1 running session.", QuitText.of(HostingStatus.Running(9898, false), fg, 1))
        assertNull(QuitText.of(HostingStatus.Running(9898, readOnly = true), fg, 3))   // app only, no prompt
        assertNull(QuitText.of(HostingStatus.NotHosting, fg, 0))
    }

    @Test fun quitTextWithNoSessions() {
        assertEquals("This stops supermux.", QuitText.stops(0))
        assertEquals("This stops supermux.", QuitText.of(HostingStatus.Running(9898, false), fg, 0))
    }
}
