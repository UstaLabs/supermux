package dev.supermux.desktop.host

import dev.supermux.host.HostView
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrayMenuTest {
    private fun session(id: String) = SessionInfo(id = id, name = id, workdir = "/w", agent = "claude")

    private val here = HostView(recordId = "r-here", hostId = "h-here", displayName = "mac", online = true)
    private val there = HostView(recordId = "r-there", hostId = "h-there", displayName = "ustalabs-linux", online = false)

    @Test fun dotGlyphs() {
        assertEquals("🟢", dotGlyph(Dot.GREEN))
        assertEquals("🟡", dotGlyph(Dot.YELLOW))
        assertEquals("🔴", dotGlyph(Dot.RED))
        assertEquals("⚪", dotGlyph(Dot.GREY))
    }

    @Test fun headerLineLeadsWithTheDot() {
        val m = TrayModel.of(HostingStatus.Running(9898, false), HostingPrefs(), 2, null)
        assertEquals("🟢 supermux is running · 2 sessions", trayHeaderLine(m))
    }

    @Test fun countsOnlyTheLocalHostsSessions() {
        val f = fleetFacts(
            localHostId = "h-here",
            hosts = listOf(there, here),
            sessions = listOf(session("a"), session("b"), session("c")),
            sessionHost = mapOf("a" to "r-here", "b" to "r-there", "c" to "r-here"),
        )
        assertEquals(2, f.localSessions)
        assertEquals("ustalabs-linux", f.remoteName)
        assertEquals(false, f.remoteReachable)
    }

    @Test fun zeroWhenTheLocalHostIsNotInTheFleet() {
        val f = fleetFacts("h-unknown", listOf(there), listOf(session("b")), mapOf("b" to "r-there"))
        assertEquals(0, f.localSessions)
        assertEquals("ustalabs-linux", f.remoteName)
    }

    @Test fun unknownLocalIdMeansEveryHostIsRemote() {
        val f = fleetFacts(null, listOf(here, there), emptyList(), emptyMap())
        assertEquals(0, f.localSessions)
        assertEquals("mac", f.remoteName)
        assertEquals(true, f.remoteReachable)
    }

    @Test fun noRemoteHost() {
        val f = fleetFacts("h-here", listOf(here), emptyList(), emptyMap())
        assertNull(f.remoteName)
        assertEquals(true, f.remoteReachable)
    }

    @Test fun factsFeedTheNotHostingHeader() {
        val f = fleetFacts("h-here", listOf(here, there), emptyList(), emptyMap())
        val m = TrayModel.of(HostingStatus.NotHosting, HostingPrefs(hosting = false), f.localSessions, f.remoteName, f.remoteReachable)
        assertEquals("🟡 Can't reach ustalabs-linux · retrying", trayHeaderLine(m))
    }

    // ── QuitAction ──

    private val bg = HostingPrefs(background = true)
    private val fg = HostingPrefs(background = false)
    private val running = HostingStatus.Running(9898, readOnly = false)

    @Test fun backgroundOnQuitsWithANoticeTheFirstTime() {
        assertEquals(QuitAction.Now("supermux will keep running in the background."), QuitAction.of(running, bg, 3, noticeShown = false))
        assertEquals(QuitAction.Now(null), QuitAction.of(running, bg, 3, noticeShown = true))
    }

    @Test fun backgroundOffRunningConfirms() {
        assertEquals(QuitAction.Confirm("This stops supermux and your 3 running sessions."), QuitAction.of(running, fg, 3, false))
    }

    @Test fun readOnlyAndNotHostingQuitSilently() {
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.Running(9898, readOnly = true), fg, 3, false))
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.NotHosting, fg, 0, false))
    }

    @Test fun nothingRunningYetQuitsWithoutAsking() {
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.CantStart("x"), fg, 0, false))
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.Starting, fg, 0, false))
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.AskTakeover(null), fg, 0, false))
    }
}
