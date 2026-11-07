package dev.supermux.desktop.host

import java.nio.file.Files
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class HostingPrefsTest {
    @Test fun defaultsWhenNoFile() {
        val dir = createTempDirectory()
        val store = HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = dir.resolve("none.json"))
        assertEquals(HostingPrefs(hosting = true, background = true, relay = true, port = 9898, leftAloneHostIds = emptySet()), store.load())
    }

    @Test fun roundTrips() {
        val dir = createTempDirectory()
        val store = HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = dir.resolve("none.json"))
        val p = HostingPrefs(hosting = false, background = false, relay = false, port = 9912, leftAloneHostIds = setOf("abc"))
        store.save(p)
        assertEquals(p, store.load())
    }

    @Test fun migratesTheOldAlternatePortOnce() {
        val dir = createTempDirectory()
        val legacy = dir.resolve("desktop-sidecar.json")
        Files.writeString(legacy, """{"alternatePort":9899}""")
        val store = HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = legacy)
        assertEquals(9899, store.load().port)
    }

    @Test fun aCorruptFileFallsBackToDefaults() {
        val dir = createTempDirectory()
        Files.writeString(dir.resolve("hosting.json"), "{not json")
        val store = HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = dir.resolve("none.json"))
        assertEquals(9898, store.load().port)
    }

    @Test fun storedFileBeatsLegacy() {
        val dir = createTempDirectory()
        val legacy = dir.resolve("desktop-sidecar.json")
        Files.writeString(legacy, """{"alternatePort":9899}""")
        val store = HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = legacy)
        store.save(HostingPrefs(port = 9912))
        assertEquals(9912, store.load().port)
    }

    @Test fun saveLeavesNoTmpFiles() {
        val dir = createTempDirectory()
        HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = dir.resolve("none.json")).save(HostingPrefs())
        assertTrue(Files.list(dir).use { s -> s.noneMatch { it.fileName.toString().endsWith(".tmp") } })
    }

    @Test fun invalidStoredPortLoadsAsDefault() {
        val dir = createTempDirectory()
        Files.writeString(dir.resolve("hosting.json"), """{"port":0}""")
        assertEquals(9898, HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = dir.resolve("none.json")).load().port)
    }

    // ── initialHostingPrefs: no surprise hosting on upgrade ──

    private fun host(id: String, directUrl: String?) =
        dev.supermux.host.PairedHost(recordId = id, displayName = id, directUrl = directUrl, token = "t")

    @Test fun upgradePairedOnlyToOtherComputersStartsWithHostingOff() {
        val p = initialHostingPrefs(listOf(host("a", "http://192.168.1.5:9898"), host("b", null)), prefsFileExists = false)
        assertEquals(HostingPrefs(hosting = false), p)
    }

    @Test fun keepsTheLegacyPortWhenTurningHostingOff() {
        val p = initialHostingPrefs(listOf(host("a", null)), false, base = HostingPrefs(port = 9912))
        assertEquals(HostingPrefs(hosting = false, port = 9912), p)
    }

    @Test fun firstRunLeavesItToTheWizard() {
        assertEquals(null, initialHostingPrefs(emptyList(), prefsFileExists = false))
    }

    @Test fun aLocalRecordKeepsTheDefaults() {
        for (url in listOf("http://127.0.0.1:9898", "http://localhost:9898", "http://[::1]:9898")) {
            assertEquals(null, initialHostingPrefs(listOf(host("r", "https://h.relay.supermux.dev"), host("l", url)), false), url)
        }
    }

    @Test fun anExistingFileIsNeverOverwritten() {
        assertEquals(null, initialHostingPrefs(listOf(host("a", "http://192.168.1.5:9898")), prefsFileExists = true))
    }

    @Test fun loopbackUrls() {
        for (u in listOf(
            "http://127.0.0.1:9898", "http://127.5.6.7", "http://LOCALHOST:1", "http://[::1]:9898",
            "http://[0:0:0:0:0:0:0:1]:9898", "http://[0000:0000:0000:0000:0000:0000:0000:0001]/",
        )) assertTrue(isLoopbackUrl(u), u)
        for (u in listOf(
            "http://192.168.1.5:9898", null, "", "not a url", "http://127.example.com:9898", "http://127.0.0.1.nip.io/",
            "http://128.0.0.1/", "http://[::2]/", "http://localhost.evil.com/", "http://127.0.0.256/", "http:///nohost",
        )) assertTrue(!isLoopbackUrl(u), u.toString())
    }

    @Test fun storeExists() {
        val dir = createTempDirectory()
        val store = HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = dir.resolve("none.json"))
        assertTrue(!store.exists())
        store.save(HostingPrefs())
        assertTrue(store.exists())
    }
}

/**
 * hosting.json moves next to the app's other data (#77): every copy of the app has its own hosting
 * choices, and one copy's "hosting off" can no longer turn off another's. An update keeps reading
 * where older versions kept it until its first save.
 */
class HostingPrefsMoveTest {
    private fun store(dir: java.nio.file.Path) =
        HostingPrefsStore(dir.resolve("appdata/hosting.json"), legacyPortFile = dir.resolve("none.json"), oldFile = dir.resolve("home/.config/supermux-desktop/hosting.json"))

    private fun writeOld(dir: java.nio.file.Path, text: String) {
        val old = dir.resolve("home/.config/supermux-desktop/hosting.json")
        Files.createDirectories(old.parent)
        Files.writeString(old, text)
    }

    @Test fun anUpdateReadsTheOldFileUntilItsFirstSave() {
        val dir = createTempDirectory()
        writeOld(dir, """{"hosting":true,"background":true,"relay":false,"port":9912,"leftAloneHostIds":["h1"]}""")
        val s = store(dir)
        assertEquals(HostingPrefs(hosting = true, background = true, relay = false, port = 9912, leftAloneHostIds = setOf("h1")), s.load())
        assertTrue(s.exists(), "the user's choice stands: no upgrade seed over it")
        s.save(s.load().copy(relay = true))
        assertEquals(HostingPrefs(relay = true, port = 9912, leftAloneHostIds = setOf("h1")), s.load())
        assertTrue(Files.exists(dir.resolve("appdata/hosting.json")))
        assertEquals(
            """{"hosting":true,"background":true,"relay":false,"port":9912,"leftAloneHostIds":["h1"]}""",
            Files.readString(dir.resolve("home/.config/supermux-desktop/hosting.json")),
            "the old file is never written: an older copy may still read it",
        )
    }

    @Test fun theNewFileWinsOverTheOld() {
        val dir = createTempDirectory()
        writeOld(dir, """{"hosting":true,"port":9912}""")
        store(dir).save(HostingPrefs(hosting = false))
        assertEquals(HostingPrefs(hosting = false), store(dir).load())
    }

    @Test fun theUpgradeSeedNeverTurnsHostingOffForAUserWhoHosts() {
        // A user who hosts but unpaired "This computer": paired only to other computers now.
        val dir = createTempDirectory()
        writeOld(dir, """{"hosting":true,"background":true}""")
        val s = store(dir)
        DesktopHostBootstrap.seedHostingPrefs(
            listOf(dev.supermux.host.PairedHost(recordId = "r", displayName = "r", directUrl = "http://192.168.1.5:9898", token = "t")), s,
        )
        assertEquals(true, s.load().hosting)
        assertTrue(!Files.exists(dir.resolve("appdata/hosting.json")), "nothing written")
    }

    @Test fun onWindowsItMovesFromTheProfileToAppData() {
        val env = mapOf("APPDATA" to "C:\\Users\\T\\AppData\\Roaming")
        val (now, before) = HostingPrefsStore.paths("Windows 11", env::get, "C:\\Users\\T")
        assertEquals(java.nio.file.Path.of("C:\\Users\\T\\AppData\\Roaming").resolve("supermux-desktop").resolve("hosting.json"), now)
        assertEquals(java.nio.file.Path.of("C:\\Users\\T", ".config", "supermux-desktop", "hosting.json"), before)
    }

    @Test fun withXdgConfigHomeItMovesThere() {
        val (now, before) = HostingPrefsStore.paths("Linux", mapOf("XDG_CONFIG_HOME" to "/x")::get, "/h")
        assertEquals(java.nio.file.Path.of("/x/supermux-desktop/hosting.json"), now)
        assertEquals(java.nio.file.Path.of("/h/.config/supermux-desktop/hosting.json"), before)
    }

    @Test fun onAMacOrLinuxByDefaultNothingMoves() {
        for (os in listOf("Mac OS X", "Linux")) {
            val (now, before) = HostingPrefsStore.paths(os, { null }, "/h")
            assertEquals(java.nio.file.Path.of("/h/.config/supermux-desktop/hosting.json"), now, os)
            assertEquals(null, before, os)
        }
    }
}

class MovePortTest {
    @Test fun aMovedPortIsTheFirstFreeOneAboveTheDefault() {
        kotlin.test.assertEquals(9899, firstFreePort { true })
        kotlin.test.assertEquals(9901, firstFreePort { it > 9900 })
        kotlin.test.assertEquals(9899..9999, MOVE_PORTS)
    }

    @Test fun aTakenPortIsSkippedForReal() {
        java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress()).use { held ->
            val p = held.localPort
            val r = firstFreePort(p..p + 50)
            kotlin.test.assertTrue(r != p && r in p..p + 50, "got $r")
        }
    }

    @Test fun theOsPicksOnlyWhenTheWholeRangeIsTaken() {
        val p = firstFreePort(9899..9900) { false }
        kotlin.test.assertTrue(p !in 9899..9900 && p > 0)
    }
}
