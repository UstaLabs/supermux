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
