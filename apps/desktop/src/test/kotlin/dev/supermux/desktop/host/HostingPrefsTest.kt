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
        assertTrue(isLoopbackUrl("http://127.0.0.1:9898"))
        assertTrue(isLoopbackUrl("http://LOCALHOST:1"))
        assertTrue(isLoopbackUrl("http://[::1]:9898"))
        assertTrue(!isLoopbackUrl("http://192.168.1.5:9898"))
        assertTrue(!isLoopbackUrl(null))
        assertTrue(!isLoopbackUrl("not a url"))
    }

    @Test fun storeExists() {
        val dir = createTempDirectory()
        val store = HostingPrefsStore(dir.resolve("hosting.json"), legacyPortFile = dir.resolve("none.json"))
        assertTrue(!store.exists())
        store.save(HostingPrefs())
        assertTrue(store.exists())
    }
}
