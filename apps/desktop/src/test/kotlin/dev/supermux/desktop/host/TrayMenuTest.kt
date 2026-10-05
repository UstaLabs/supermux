package dev.supermux.desktop.host

import dev.supermux.host.HostView
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        assertEquals("supermux is running · 2 sessions", trayHeaderLine(m, dot = false), "Windows' AWT menu can't draw the emoji")
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

    @Test fun unknownLocalIdMeansEveryNonLoopbackHostIsRemote() {
        val f = fleetFacts(null, listOf(here, there), emptyList(), emptyMap())
        assertEquals(0, f.localSessions)
        assertEquals("mac", f.remoteName)
        assertEquals(true, f.remoteReachable)
    }

    @Test fun loopbackRecordIsNeverTheRemote() {
        // Hosting turned off: the supervisor reports no hostId, but "This computer" is a loopback record.
        val f = fleetFacts(
            localHostId = null,
            hosts = listOf(here, there),
            sessions = listOf(session("a"), session("b")),
            sessionHost = mapOf("a" to "r-here", "b" to "r-there"),
            loopbackRecordIds = setOf("r-here"),
        )
        assertEquals("ustalabs-linux", f.remoteName)
        assertEquals(false, f.remoteReachable)
        assertEquals(1, f.localSessions)
    }

    @Test fun onlyLoopbackRecordsMeansNoRemote() {
        val f = fleetFacts(null, listOf(here), emptyList(), emptyMap(), loopbackRecordIds = setOf("r-here"))
        assertNull(f.remoteName)
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

    @Test fun movedPortHeaderCarriesTheLocalCount() {
        // The VM case: the broker moved to 60094; the tray must still say how many sessions run here.
        val f = fleetFacts(
            localHostId = "h-here",
            hosts = listOf(here, there),
            sessions = listOf(session("a"), session("b")),
            sessionHost = mapOf("a" to "r-here", "b" to "r-here"),
        )
        val m = TrayModel.of(HostingStatus.Running(60094, readOnly = false), HostingPrefs(port = 60094), f.localSessions, f.remoteName)
        assertEquals("🟢 supermux is running · 2 sessions · port 60094", trayHeaderLine(m))
    }

    // ── QuitAction ──

    private val running = HostingStatus.Running(9898, readOnly = false)

    @Test fun brokerThatKeepsRunningQuitsWithANoticeTheFirstTime() {
        assertEquals(QuitAction.Now("supermux will keep running in the background."), QuitAction.of(running, 3, quitStopsBroker = false, noticeShown = false))
        assertEquals(QuitAction.Now(null), QuitAction.of(running, 3, quitStopsBroker = false, noticeShown = true))
    }

    @Test fun childBrokerConfirms() {
        assertEquals(QuitAction.Confirm("This stops supermux and your 3 running sessions."), QuitAction.of(running, 3, quitStopsBroker = true, noticeShown = false))
        assertEquals(QuitAction.Confirm("This stops supermux and your 1 running session."), QuitAction.of(running, 1, quitStopsBroker = true, noticeShown = false))
    }

    @Test fun backgroundInstallFellBackToAChildConfirms() {
        // prefs.background is ON, but the service install failed and the broker runs as our child.
        assertEquals(QuitAction.Confirm("This stops supermux."), QuitAction.of(running, 0, quitStopsBroker = true, noticeShown = true))
    }

    @Test fun readOnlyAndNotHostingQuitSilently() {
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.Running(9898, readOnly = true), 3, false, false))
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.NotHosting, 0, false, false))
    }

    @Test fun nothingRunningQuitsAtOnceWithNoNotice() {
        // Background ON + can't start: nothing keeps running, so no "keeps running" notice.
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.CantStart("x"), 0, quitStopsBroker = false, noticeShown = false))
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.Starting, 0, false, false))
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.AskTakeover(null), 0, false, false))
        assertEquals(QuitAction.Now(), QuitAction.of(HostingStatus.Restarting(2), 0, true, false))
    }

    // ── tray menu items ──

    private fun labels(items: List<TrayItem>) = items.map {
        when (it) {
            is TrayItem.Header -> "H:${it.text}"
            is TrayItem.Action -> if (it.enabled) it.label else "${it.label}(off)"
            is TrayItem.Checkbox -> "[${if (it.checked) "x" else " "}]${it.label}${if (it.enabled) "" else "(off)"}"
            TrayItem.Separator -> "--"
        }
    }

    @Test fun runningMenu() {
        val m = TrayModel.of(running, HostingPrefs(), 2, null)
        assertEquals(
            listOf("H:🟢 supermux is running · 2 sessions", "Open supermux", "--", "Restart", "[x]Keep running in the background", "--", "Quit supermux"),
            labels(trayMenuItems(m, background = true)),
        )
    }

    @Test fun crashLoopMenuHasShowLogAndTryAgain() {
        val m = TrayModel.of(HostingStatus.CantStart("x"), HostingPrefs(), 0, null)
        assertEquals(
            listOf("H:🔴 supermux can't start", "Open supermux", "Show log", "--", "Try again", "[ ]Keep running in the background", "--", "Quit supermux"),
            labels(trayMenuItems(m, background = false)),
        )
    }

    @Test fun startingDisablesRestart() {
        val items = labels(trayMenuItems(TrayModel.of(HostingStatus.Starting, HostingPrefs(), 0, null), true))
        assertTrue("Restart(off)" in items)
    }

    @Test fun readOnlyDisablesTheCheckbox() {
        val items = labels(trayMenuItems(TrayModel.of(HostingStatus.Running(9898, readOnly = true), HostingPrefs(), 0, null), true))
        assertTrue("[x]Keep running in the background(off)" in items)
    }

    @Test fun notHostingMenuIsHeaderOpenQuit() {
        val m = TrayModel.of(HostingStatus.NotHosting, HostingPrefs(hosting = false), 0, "ustalabs-linux")
        assertEquals(
            listOf("H:⚪ Connected to ustalabs-linux", "Open supermux", "--", "Quit supermux"),
            labels(trayMenuItems(m, background = true)),
        )
    }

    @Test fun quittingMenu() {
        assertEquals(
            listOf("H:🟡 Quitting…", "Open supermux", "--", "Quit supermux"),
            labels(trayMenuItems(TrayModel.QUITTING, background = true)),
        )
    }

    // ── keep awake ──

    private val awake = dev.supermux.net.KeepAwakeState(enabled = true, onBattery = true, active = true, supported = true)

    @Test fun backgroundAndAwakeSaysItKeepsTheComputerAwake() {
        val keeps = QuitAction.keepsAwake(background = true, keepAwake = awake)
        assertTrue(keeps)
        assertEquals(
            QuitAction.Now(
                "supermux keeps running in the background and keeps this computer awake. Turn off 'Keep this computer awake' to let it sleep.",
            ),
            QuitAction.of(running, 3, quitStopsBroker = false, noticeShown = false, keepsAwake = keeps),
        )
        // Still one-time.
        assertEquals(QuitAction.Now(null), QuitAction.of(running, 3, quitStopsBroker = false, noticeShown = true, keepsAwake = true))
        // A broker that stops with the app: the confirm, as before.
        assertEquals(QuitAction.Confirm("This stops supermux."), QuitAction.of(running, 0, quitStopsBroker = true, noticeShown = false, keepsAwake = true))
    }

    @Test fun keepsAwakeNeedsBackgroundEnabledAndActive() {
        assertFalse(QuitAction.keepsAwake(background = false, keepAwake = awake))
        assertFalse(QuitAction.keepsAwake(background = true, keepAwake = awake.copy(enabled = false)))
        assertFalse(QuitAction.keepsAwake(background = true, keepAwake = awake.copy(active = false, reasonCode = "denied")))
        assertFalse(QuitAction.keepsAwake(background = true, keepAwake = null))
    }

    private val lidOn = LidStatus(pref = true, state = LidSleepHelper.InstallState.INSTALLED)

    @Test fun theLidCheckmarkIsOnOnlyWhileSomethingHolds() {
        val m = TrayModel.of(running, HostingPrefs(), 0, null)
        fun item(lid: LidStatus) = labels(trayMenuItems(m, true, TrayPower.of(awake, true, true, lid))).single { "lid" in it }
        assertEquals("[x]Even with the lid closed", item(lidOn))
        assertEquals("[ ]Even with the lid closed", item(lidOn.copy(state = LidSleepHelper.InstallState.NOT_INSTALLED)))
        assertEquals("[ ]Even with the lid closed", item(lidOn.copy(state = LidSleepHelper.InstallState.OTHER_USER)))
        assertEquals("[ ]Even with the lid closed(off)", item(lidOn.copy(homeSupported = false)))
    }

    @Test fun theKeepAwakeNoticeHasItsOwnOneTimeKey() {
        assertEquals(BackgroundQuitNotice.SHOWN_KEY, BackgroundQuitNotice.keyFor(false))
        assertEquals("desktop:backgroundQuitAwakeNoticeShown", BackgroundQuitNotice.keyFor(true))
        assertTrue(BackgroundQuitNotice.SHOWN_KEY != BackgroundQuitNotice.SHOWN_AWAKE_KEY)
    }

    @Test fun runningMenuWithKeepAwakeAndLid() {
        val m = TrayModel.of(running, HostingPrefs(), 2, null)
        val power = TrayPower.of(awake, isMac = true, hasBattery = true, lid = LidStatus())
        assertEquals(
            listOf(
                "H:🟢 supermux is running · 2 sessions", "Open supermux", "--", "Restart",
                "[x]Keep running in the background", "[x]Keep this computer awake", "[ ]Even with the lid closed",
                "--", "Quit supermux",
            ),
            labels(trayMenuItems(m, background = true, power = power)),
        )
        val ids = trayMenuItems(m, true, power).filterIsInstance<TrayItem.Checkbox>().map { it.id }
        assertEquals(listOf(TrayToggle.BACKGROUND, TrayToggle.KEEP_AWAKE, TrayToggle.LID_CLOSED), ids)
    }

    @Test fun noLidOffAMacLaptopAndNoBatteryRowInTheTray() {
        val m = TrayModel.of(running, HostingPrefs(), 0, null)
        val desktopMac = labels(trayMenuItems(m, true, TrayPower.of(awake, isMac = true, hasBattery = false, lid = lidOn)))
        assertFalse(desktopMac.any { "lid" in it })
        val linuxLaptop = labels(trayMenuItems(m, true, TrayPower.of(awake, isMac = false, hasBattery = true, lid = lidOn)))
        assertFalse(linuxLaptop.any { "lid" in it })
        assertTrue("[x]Keep this computer awake" in linuxLaptop)
        assertFalse(linuxLaptop.any { "battery" in it.lowercase() })
    }

    @Test fun keepAwakeHiddenUntilTheBrokerSaysAndDisabledWhereUnsupported() {
        val m = TrayModel.of(running, HostingPrefs(), 0, null)
        assertFalse(labels(trayMenuItems(m, true, TrayPower.of(null, false, null, LidStatus()))).any { "awake" in it })
        val unsupported = awake.copy(enabled = true, active = false, supported = false, reason = "no inhibitor", reasonCode = "unsupported")
        assertTrue("[x]Keep this computer awake(off)" in labels(trayMenuItems(m, true, TrayPower.of(unsupported, false, null, LidStatus()))))
        // Busy installing the lid helper: the lid item is greyed.
        assertTrue("[ ]Even with the lid closed(off)" in labels(trayMenuItems(m, true, TrayPower.of(awake, true, true, LidStatus(busy = true)))))
    }

    @Test fun notHostingMenuHasNoPowerItems() {
        val m = TrayModel.of(HostingStatus.NotHosting, HostingPrefs(hosting = false), 0, null)
        val power = TrayPower.of(awake, isMac = true, hasBattery = true, lid = lidOn)
        assertEquals(listOf("H:⚪ Not hosting", "Open supermux", "--", "Quit supermux"), labels(trayMenuItems(m, true, power)))
    }

    @Test fun actionsCarryTheirIds() {
        val ids = trayMenuItems(TrayModel.of(HostingStatus.CantStart("x"), HostingPrefs(), 0, null), true)
            .filterIsInstance<TrayItem.Action>().map { it.id }
        assertEquals(listOf(TrayAction.OPEN, TrayAction.SHOW_LOG, TrayAction.RESTART, TrayAction.QUIT), ids)
    }
}
