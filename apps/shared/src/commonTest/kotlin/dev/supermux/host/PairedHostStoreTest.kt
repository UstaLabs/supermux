package dev.supermux.host

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class PairedHostModelTest {
    @Test fun roundTripsThroughJson() {
        val h = PairedHost(recordId = "r1", hostId = "habc", displayName = "MacBook",
            directUrl = "http://192.168.1.2:9898", relayUrl = "https://h-habc.relay.supermux.dev",
            token = "tok", platform = "macos", version = "0.11.0", lastSeenAt = 1000L)
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(h, json.decodeFromString(PairedHost.serializer(), json.encodeToString(PairedHost.serializer(), h)))
    }
}

class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
    override fun loadAll() = hosts.toList()
    override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
}

class PairedHostStoreTest {
    private var counter = 0
    private fun store(vararg h: PairedHost) =
        PairedHostStore(FakePersistence(h.toMutableList())) { "gen-${counter++}" }

    @Test fun addAppendsAndPersists() {
        val s = store()
        s.add(displayName = "box", token = "t", relayUrl = "https://h-x.relay.supermux.dev", hostId = "x")
        assertEquals(1, s.list().size)
        assertEquals("box", s.list()[0].displayName)
    }

    @Test fun migrateFromSingleHostSeedsRecordZero() {
        val s = store()
        s.migrateFromSingleHost(token = "legacy", baseUrl = "https://old.example.com")
        assertEquals(1, s.list().size)
        assertEquals("legacy", s.list()[0].token)
        assertEquals("https://old.example.com", s.list()[0].relayUrl ?: s.list()[0].directUrl)
    }

    @Test fun migrateIsIdempotent() {
        val s = store()
        s.migrateFromSingleHost("legacy", "https://old.example.com")
        s.migrateFromSingleHost("legacy", "https://old.example.com")
        assertEquals(1, s.list().size)
    }

    @Test fun backfillHostIdSetsItWhenAbsent() {
        val s = store(PairedHost(recordId = "r1", displayName = "box", token = "t", relayUrl = "u"))
        s.backfillHostId("r1", "habc")
        assertEquals("habc", s.list()[0].hostId)
    }

    @Test fun backfillIdentityRepairsLegacyNameButPreservesUserRename() {
        val legacy = store(PairedHost(
            recordId = "r1", hostId = "habc", displayName = "This computer (Old Mac)", token = "t"
        ))
        legacy.backfillHostIdentity("r1", "habc", "broker-hostname")
        assertEquals("Old Mac", legacy.list()[0].displayName)

        val renamed = store(PairedHost(
            recordId = "r2", hostId = "hdef", displayName = "Studio", token = "t"
        ))
        renamed.backfillHostIdentity("r2", "hdef", "broker-hostname")
        assertEquals("Studio", renamed.list()[0].displayName)
    }

    @Test fun backfillMergesDuplicateHostKeepingValidToken() {
        val s = store(
            PairedHost(recordId = "r1", displayName = "MyMac", token = "old", relayUrl = "u1"),
            PairedHost(recordId = "r2", hostId = "habc", displayName = "auto", token = "new", relayUrl = "u2"),
        )
        s.backfillHostId("r1", "habc")
        assertEquals(1, s.list().size)
        assertEquals("habc", s.list()[0].hostId)
        assertEquals("MyMac", s.list()[0].displayName)
    }

    @Test fun addOrUpdateAddsWhenHostIdIsNew() {
        val s = store()
        s.addOrUpdate(displayName = "box", token = "t1", hostId = "habc")
        s.addOrUpdate(displayName = "other", token = "t2", hostId = "hdef")
        assertEquals(2, s.list().size)
    }

    @Test fun addOrUpdateSameHostTwiceYieldsOneRecord() {
        val s = store()
        s.addOrUpdate(displayName = "box", token = "old", relayUrl = "u1", hostId = "habc")
        val second = s.addOrUpdate(displayName = "box-again", token = "fresh", relayUrl = "u2", hostId = "habc")
        assertEquals(1, s.list().size)
        assertEquals("fresh", s.list()[0].token)          // token refreshed to the latest claim
        assertEquals("u2", s.list()[0].relayUrl)          // URL refreshed too
        assertEquals(s.list()[0].recordId, second.recordId)
    }

    @Test fun addOrUpdatePreservesUserRename() {
        val s = store(PairedHost(recordId = "r1", hostId = "habc", displayName = "My Laptop", token = "t"))
        s.addOrUpdate(displayName = "auto-name", token = "fresh", hostId = "habc")
        assertEquals(1, s.list().size)
        assertEquals("My Laptop", s.list()[0].displayName) // the rename is not clobbered
    }

    @Test fun addOrUpdateReplacesLegacyThisComputerName() {
        val s = store(PairedHost(
            recordId = "r1", hostId = "habc", displayName = "This computer (Old Mac)", token = "t"
        ))
        s.addOrUpdate(displayName = "New Mac", token = "fresh", hostId = "habc")
        assertEquals("New Mac", s.list()[0].displayName)
    }

    @Test fun addOrUpdateWithBlankHostIdAlwaysAdds() {
        val s = store()
        s.addOrUpdate(displayName = "a", token = "t1", hostId = null)
        s.addOrUpdate(displayName = "b", token = "t2", hostId = null)
        assertEquals(2, s.list().size) // can't dedup pre-Plan-1 hosts that have no id yet
    }

    @Test fun removeDropsByRecordId() {
        val s = store(PairedHost(recordId = "r1", displayName = "a", token = "t"))
        s.remove("r1")
        assertEquals(0, s.list().size)
    }

    @Test fun renamePersists() {
        val s = store(PairedHost(recordId = "r1", displayName = "a", token = "t"))
        s.rename("r1", "renamed")
        assertEquals("renamed", s.list()[0].displayName)
    }
}
