package dev.supermux.web

import dev.supermux.host.HostSnapshot
import dev.supermux.host.LocalStorageHostPersistence
import dev.supermux.host.LocalStorageSnapshotPersistence
import dev.supermux.host.PairedHost
import kotlinx.browser.localStorage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalStorageHostPersistenceTest {
    @BeforeTest fun clear() = localStorage.clear()

    @Test fun hostsRoundTrip() {
        val p = LocalStorageHostPersistence()
        val h = PairedHost(recordId = "web", hostId = "h1", displayName = "ustalabs", directUrl = "http://x", token = "", platform = "linux", version = "dev", lastSeenAt = 5L)
        p.saveAll(listOf(h))
        assertEquals(listOf(h), LocalStorageHostPersistence().loadAll())
    }

    @Test fun snapshotsRoundTrip() {
        val p = LocalStorageSnapshotPersistence()
        p.saveAll(listOf(HostSnapshot(recordId = "web", fetchedAt = 9L, brokerVersion = "dev")))
        assertEquals(listOf("web"), LocalStorageSnapshotPersistence().loadAll().map { it.recordId })
    }

    @Test fun corruptJsonLoadsEmpty() {
        localStorage.setItem("supermux:hosts", "{not json")
        assertEquals(emptyList(), LocalStorageHostPersistence().loadAll())
    }

    /** Signing out must leave no supermux keys behind, not an empty "[]" that reads as "set up". */
    @Test fun forgettingTheLastHostClearsBothKeys() {
        val p = LocalStorageHostPersistence()
        p.saveAll(listOf(PairedHost(recordId = "web", displayName = "origin", token = "")))
        p.saveAll(emptyList())
        assertNull(localStorage.getItem("supermux:hosts"))
        assertNull(localStorage.getItem("supermux:hostTokens"))
        assertEquals(emptyList(), LocalStorageHostPersistence().loadAll())
    }

    /**
     * A corrupt token map costs the TOKENS, never the fleet: the hosts still load (blank tokens,
     * re-pairable) exactly as a missing Keychain item behaves on iOS.
     */
    @Test fun corruptTokenMapKeepsHostsWithBlankTokens() {
        LocalStorageHostPersistence().saveAll(
            listOf(PairedHost(recordId = "web", displayName = "origin", token = "t")),
        )
        localStorage.setItem("supermux:hostTokens", "{not json")
        val loaded = LocalStorageHostPersistence().loadAll()
        assertEquals(listOf("web"), loaded.map { it.recordId })
        assertEquals(listOf(""), loaded.map { it.token })
    }
}
