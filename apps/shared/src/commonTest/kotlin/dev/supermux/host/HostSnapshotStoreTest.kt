package dev.supermux.host

import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun session(id: String, name: String = id) =
    SessionInfo(id = id, name = name, workdir = "/w/$name", agent = "claude")

private class FakeSnapshotPersistence(
    var stored: MutableList<HostSnapshot> = mutableListOf(),
) : SnapshotPersistence {
    var saves = 0
    override fun loadAll(): List<HostSnapshot> = stored.toList()
    override fun saveAll(snapshots: List<HostSnapshot>) {
        saves++
        stored = snapshots.toMutableList()
    }
}

class HostSnapshotCodecTest {
    @Test fun roundTripsVersionedEnvelope() {
        val snaps = listOf(
            HostSnapshot("r1", listOf(session("s1"), session("s2")), fetchedAt = 1000L, brokerVersion = "0.11.0"),
            HostSnapshot("r2", listOf(session("s3")), fetchedAt = 2000L),
        )
        val decoded = HostSnapshotCodec.decode(HostSnapshotCodec.encode(snaps))
        assertEquals(snaps, decoded)
    }

    @Test fun blankOrCorruptDecodesEmpty() {
        assertEquals(emptyList(), HostSnapshotCodec.decode(null))
        assertEquals(emptyList(), HostSnapshotCodec.decode(""))
        assertEquals(emptyList(), HostSnapshotCodec.decode("{not json"))
    }

    @Test fun unknownSchemaVersionDecodesEmpty() {
        // A future/foreign version must NOT be trusted — decode to empty so the app re-fetches.
        val foreign = """{"version":999,"snapshots":[{"recordId":"r1","sessions":[],"fetchedAt":0}]}"""
        assertEquals(emptyList(), HostSnapshotCodec.decode(foreign))
    }

    @Test fun encodesCurrentSchemaVersion() {
        assertTrue(HostSnapshotCodec.encode(emptyList()).contains("\"version\":${HostSnapshotCodec.VERSION}"))
    }
}

class HostSnapshotStoreTest {
    @Test fun loadsPersistedSnapshotsAtConstruction() {
        val p = FakeSnapshotPersistence(mutableListOf(HostSnapshot("r1", listOf(session("s1")), 10L)))
        val store = HostSnapshotStore(p)
        assertEquals(1, store.all().size)
        assertEquals("s1", store.get("r1")!!.sessions.single().id)
    }

    @Test fun replaceStoresAndPersists() {
        val p = FakeSnapshotPersistence()
        val store = HostSnapshotStore(p)
        store.replace("r1", listOf(session("s1")), fetchedAt = 5L, brokerVersion = "1.2.3")
        assertEquals(listOf("s1"), store.get("r1")!!.sessions.map { it.id })
        assertEquals(5L, store.get("r1")!!.fetchedAt)
        assertEquals("1.2.3", store.get("r1")!!.brokerVersion)
        assertEquals(1, p.stored.size)
    }

    @Test fun replaceIsWholesaleNotMerge() {
        val p = FakeSnapshotPersistence()
        val store = HostSnapshotStore(p)
        store.replace("r1", listOf(session("s1"), session("s2")), 1L)
        store.replace("r1", listOf(session("s3")), 2L)
        // The second full snapshot SUPERSEDES the first — s1/s2 are gone, only s3 remains.
        assertEquals(listOf("s3"), store.get("r1")!!.sessions.map { it.id })
    }

    @Test fun perHostReplaceLeavesOtherHostsUntouched() {
        val p = FakeSnapshotPersistence()
        val store = HostSnapshotStore(p)
        store.replace("r1", listOf(session("s1")), 1L)
        store.replace("r2", listOf(session("s2")), 1L)
        store.replace("r1", listOf(session("s1b")), 2L)
        assertEquals(listOf("s1b"), store.get("r1")!!.sessions.map { it.id })
        assertEquals(listOf("s2"), store.get("r2")!!.sessions.map { it.id }) // r2 survived
    }

    @Test fun removeDropsAndPersists() {
        val p = FakeSnapshotPersistence(mutableListOf(HostSnapshot("r1", listOf(session("s1")), 1L)))
        val store = HostSnapshotStore(p)
        store.remove("r1")
        assertNull(store.get("r1"))
        assertTrue(p.stored.isEmpty())
    }

    @Test fun removeUnknownDoesNotFlush() {
        val p = FakeSnapshotPersistence()
        val store = HostSnapshotStore(p)
        val before = p.saves
        store.remove("nope")
        assertEquals(before, p.saves) // no-op, no write
    }

    @Test fun retainOnlyPrunesForgottenHosts() {
        val p = FakeSnapshotPersistence(
            mutableListOf(
                HostSnapshot("r1", listOf(session("s1")), 1L),
                HostSnapshot("r2", listOf(session("s2")), 1L),
                HostSnapshot("r3", listOf(session("s3")), 1L),
            ),
        )
        val store = HostSnapshotStore(p)
        store.retainOnly(listOf("r1", "r3"))
        assertEquals(setOf("r1", "r3"), store.all().map { it.recordId }.toSet())
    }

    @Test fun retainOnlyNoChangeDoesNotFlush() {
        val p = FakeSnapshotPersistence(mutableListOf(HostSnapshot("r1", emptyList(), 1L)))
        val store = HostSnapshotStore(p)
        val before = p.saves
        store.retainOnly(listOf("r1"))
        assertEquals(before, p.saves)
    }

    @Test fun preservesInsertionOrderForStableHostOrder() {
        val p = FakeSnapshotPersistence()
        val store = HostSnapshotStore(p)
        store.replace("rB", emptyList(), 1L)
        store.replace("rA", emptyList(), 1L)
        store.replace("rC", emptyList(), 1L)
        assertEquals(listOf("rB", "rA", "rC"), store.all().map { it.recordId })
    }
}
