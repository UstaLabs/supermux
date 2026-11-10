package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #113: the agents' keepers run from the broker's copy in desktop-assets, not from the app's folder, so an MSI update
// needs no agent stopped. The updated app finds the old broker running and restarts it onto its own build, announced:
// the old broker leaves its agents running and the new one re-attaches them, as on Linux and macOS.
class WindowsUpdateRestartTest {
    @Test fun theUpdatedAppAnnouncesTheRestartBeforeStoppingTheOldBroker() = runTest {
        val h = Harness(
            this, os = DesktopOs.WINDOWS, prefs = HostingPrefs(background = true),
            captures = mapOf(BrokerService.listWindowsProcessesArgv() to ""),
        )
        h.writeOurWindowsTask()
        Files.writeString(h.state.resolve("stop-token"), "tok")
        var restarted = false
        h.probeFn = { if (restarted) h.desktop() else h.desktop(build = "1.4.0 (old)") }
        h.env.postAnswer = { url ->
            when {
                url.endsWith("/internal/expect-restart") -> 204
                else -> { restarted = true; 202 }
            }
        }
        h.sup.ensure()
        val expect = h.env.log.indexOf("POST http://127.0.0.1:9898/internal/expect-restart")
        val stop = h.env.log.indexOf("POST http://127.0.0.1:9898/internal/stop")
        assertTrue(expect >= 0, "the old broker hears that its stop is a restart: ${h.env.log}")
        assertTrue(stop < 0 || expect < stop, "announced before it is stopped")
        assertEquals(HostingStatus.Running(9898, readOnly = false), h.sup.status.value)
    }
}
