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
}
