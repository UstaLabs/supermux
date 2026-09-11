package dev.supermux.web

import dev.supermux.host.HostSnapshot
import dev.supermux.host.LocalStorageHostPersistence
import dev.supermux.host.LocalStorageSnapshotPersistence
import dev.supermux.host.PairedHost
import kotlinx.browser.localStorage
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
