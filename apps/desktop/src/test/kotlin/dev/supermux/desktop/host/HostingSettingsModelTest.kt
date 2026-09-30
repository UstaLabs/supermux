package dev.supermux.desktop.host

import java.net.InetAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostingSettingsModelTest {
    private val prefs = HostingPrefs()
    private val port = HostingPrefs.DEFAULT_PORT

    @Test fun running_says_sessions_and_version() {
        val l = hostingStatusLine(HostingStatus.Running(port, readOnly = false), prefs, 3, "1.5.0")
        assertEquals(HostingStatusLine("🟢", "Running · 3 sessions · v1.5.0"), l)
    }

    @Test fun running_one_session_without_a_version() {
        val l = hostingStatusLine(HostingStatus.Running(port, readOnly = false), prefs, 1, null)
        assertEquals("Running · 1 session", l.text)
    }

    @Test fun read_only_says_set_up_outside_the_app() {
        val l = hostingStatusLine(HostingStatus.Running(port, readOnly = true), prefs, 4, "1.5.0")
        assertEquals(HostingStatusLine("🟢", "Running · set up outside the app"), l)
    }

    @Test fun the_other_states_use_the_tray_wording() {
        assertEquals(HostingStatusLine("🟡", "Starting supermux…"), hostingStatusLine(HostingStatus.Starting, prefs, 0, null))
        assertEquals(
            HostingStatusLine("🟡", "supermux stopped unexpectedly · restarting (attempt 2)"),
            hostingStatusLine(HostingStatus.Restarting(2), prefs, 0, null),
        )
        assertEquals(HostingStatusLine("🔴", "supermux can't start"), hostingStatusLine(HostingStatus.CantStart("x"), prefs, 0, null))
        assertEquals(HostingStatusLine("⚪", "Not hosting"), hostingStatusLine(HostingStatus.NotHosting, prefs, 0, null))
    }

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun lan_picks_the_first_site_local_ipv4() {
        val addrs = sequenceOf(ip("127.0.0.1"), ip("fe80::1"), ip("8.8.8.8"), ip("192.168.1.20"), ip("10.0.0.5"))
        assertEquals("192.168.1.20", lanIpv4(addrs))
    }

    @Test fun lan_is_null_without_a_site_local_ipv4() {
        assertNull(lanIpv4(sequenceOf(ip("127.0.0.1"), ip("::1"), ip("100.64.0.1"))))
        assertNull(lanIpv4(emptySequence()))
    }

    @Test fun the_local_url_uses_the_lan_ip_when_there_is_one() {
        assertEquals("http://192.168.1.20:7777", displayLocalUrl("http://127.0.0.1:7777", "192.168.1.20"))
        assertEquals("http://127.0.0.1:7777", displayLocalUrl("http://127.0.0.1:7777", null))
    }

    @Test fun tail_reads_the_last_lines() {
        val f = Files.createTempFile("log", ".txt")
        try {
            Files.writeString(f, (1..30).joinToString("\n") { "line $it" } + "\n")
            assertEquals((11..30).map { "line $it" }, tailLines(f))
            // A short read window drops the cut first line.
            assertEquals(listOf("line 30"), tailLines(f, n = 20, maxBytes = 10))
        } finally {
            Files.deleteIfExists(f)
        }
    }

    @Test fun tail_of_a_missing_file_is_empty() {
        assertEquals(emptyList(), tailLines(Files.createTempDirectory("d").resolve("nope.log")))
    }
}
