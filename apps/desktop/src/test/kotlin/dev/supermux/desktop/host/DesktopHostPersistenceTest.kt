package dev.supermux.desktop.host

import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHostPersistenceTest {
    private fun tempPersistence(): DesktopHostPersistence {
        val dir = Files.createTempDirectory("smx-fleet")
        return DesktopHostPersistence(dir.resolve("hosts.json"), dir.resolve("host-tokens.json"))
    }

    @Test fun startsEmpty() {
        assertTrue(tempPersistence().loadAll().isEmpty())
    }

    @Test fun roundTripsMetadataAndTokensAcrossInstances() {
        val p = tempPersistence()
        val hosts = listOf(
            PairedHost(recordId = "r1", hostId = "habc", displayName = "MacBook",
                relayUrl = "https://h-habc.relay.supermux.dev", token = "tok-1",
                platform = "macos", version = "0.11.0", lastSeenAt = 1234L),
            PairedHost(recordId = "r2", displayName = "Pi", directUrl = "http://10.0.0.9:9898", token = "tok-2"),
        )
        p.saveAll(hosts)

        // A fresh persistence over the SAME files reloads everything, tokens included.
        val again = DesktopHostPersistence(p.metaPath, p.tokenPath)
        val loaded = again.loadAll()
        assertEquals(hosts, loaded)
    }

    @Test fun tokenIsNeverWrittenIntoTheMetadataFile() {
        val p = tempPersistence()
        p.saveAll(listOf(PairedHost(recordId = "r1", displayName = "box", token = "SUPER-SECRET", relayUrl = "u")))
        val metaText = Files.readString(p.metaPath)
        assertTrue("SUPER-SECRET" !in metaText, "the token must not appear in the metadata file: $metaText")
    }

    @Test fun forgettingAHostPrunesItsToken() {
        val p = tempPersistence()
        p.saveAll(listOf(
            PairedHost(recordId = "r1", displayName = "a", token = "t1"),
            PairedHost(recordId = "r2", displayName = "b", token = "t2"),
        ))
        // Drop r1.
        p.saveAll(listOf(PairedHost(recordId = "r2", displayName = "b", token = "t2")))
        val again = DesktopHostPersistence(p.metaPath, p.tokenPath)
        assertEquals(listOf("r2"), again.loadAll().map { it.recordId })
        assertNull(HostTokenStore(p.tokenPath).get("r1"))
    }

    @Test fun corruptMetadataFileReadsAsEmpty() {
        val p = tempPersistence()
        Files.createDirectories(p.metaPath.parent)
        Files.writeString(p.metaPath, "{not json")
        assertTrue(p.loadAll().isEmpty())
    }

    // ── Store + migration (mirrors Android HostStores.migrateFromLegacyIfNeeded) ──
    @Test fun migratesLegacySingleHostToRecordZero() {
        val dir = Files.createTempDirectory("smx-migrate")
        val legacy = DesktopTokenStore(dir.resolve("auth.json"))
        legacy.saveBaseUrl("https://old.example.com")
        legacy.save("legacy-token")

        val store = DesktopHostStores.store(dir)
        val first = DesktopHostStores.migrateFromLegacyIfNeeded(store, legacy)
        assertEquals("legacy-token", first?.token)
        assertEquals(1, store.list().size)
        assertEquals("https://old.example.com", store.list()[0].directUrl)
    }

    @Test fun migrationIsIdempotentAndSkipsWhenHostsExist() {
        val dir = Files.createTempDirectory("smx-migrate2")
        val legacy = DesktopTokenStore(dir.resolve("auth.json"))
        legacy.saveBaseUrl("wss://h-x.relay.supermux.dev")
        legacy.save("legacy-token")

        val store = DesktopHostStores.store(dir)
        DesktopHostStores.migrateFromLegacyIfNeeded(store, legacy)
        DesktopHostStores.migrateFromLegacyIfNeeded(store, legacy)
        assertEquals(1, store.list().size)
        // A relay URL is stored as relayUrl, not directUrl.
        assertEquals("wss://h-x.relay.supermux.dev", store.list()[0].relayUrl)
    }

    @Test fun migrationNoOpsWithoutLegacyCredentials() {
        val dir = Files.createTempDirectory("smx-migrate3")
        val legacy = DesktopTokenStore(dir.resolve("auth.json"))
        val store = DesktopHostStores.store(dir)
        assertNull(DesktopHostStores.migrateFromLegacyIfNeeded(store, legacy))
        assertTrue(store.list().isEmpty())
    }
}
