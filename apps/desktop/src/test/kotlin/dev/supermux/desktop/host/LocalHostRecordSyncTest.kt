package dev.supermux.desktop.host

import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalHostRecordSyncTest {
    private val id = "abcdefghijklmnopqrstuvwxyz"
    private val now = "http://127.0.0.1:8787"

    private fun rec(recordId: String, hostId: String?, direct: String?, relay: String? = null) =
        PairedHost(recordId = recordId, hostId = hostId, displayName = "Box $recordId", token = "tok-$recordId", directUrl = direct, relayUrl = relay)

    private val remote = rec("remote", "otherhostotherhostotherhos", "http://10.0.0.7:9898", "https://h-x.relay.supermux.dev")

    @Test fun aPortChangeUpdatesThisComputer() {
        val fix = thisComputerRecordFix(listOf(remote, rec("me", id, "http://127.0.0.1:9898")), id, now)
        assertEquals(HostRecordFix("me", now, null), fix)
    }

    @Test fun theSameUrlIsANoOp() {
        assertNull(thisComputerRecordFix(listOf(rec("me", id, "http://127.0.0.1:8787/")), id, now))
    }

    @Test fun remoteHostsAreNeverTouched() {
        assertNull(thisComputerRecordFix(listOf(remote), id, now))
        // Even when the hostId matches, a non-loopback direct URL is the user's.
        assertNull(thisComputerRecordFix(listOf(rec("me", id, "http://192.168.1.4:9898")), id, now))
    }

    @Test fun aLoopbackRecordOfAnotherBrokerIsLeftAlone() {
        assertNull(thisComputerRecordFix(listOf(rec("old", "otherhostotherhostotherhos", "http://127.0.0.1:9898")), id, now))
    }

    @Test fun aLoopbackRecordWithNoHostIdIsUpdatedAndGetsTheHostId() {
        assertEquals(HostRecordFix("legacy", now, id), thisComputerRecordFix(listOf(remote, rec("legacy", null, "http://127.0.0.1:9898")), id, now))
        // Same port, but the hostId is still missing: fill it in.
        assertEquals(HostRecordFix("legacy", now, id), thisComputerRecordFix(listOf(rec("legacy", null, now)), id, now))
    }

    @Test fun nothingHappensWithoutARunningHostId() {
        assertNull(thisComputerRecordFix(listOf(rec("me", id, "http://127.0.0.1:9898")), null, now))
    }

    @Test fun theHostIdMatchWinsOverALegacyLoopbackRecord() {
        val fix = thisComputerRecordFix(listOf(rec("legacy", null, "http://127.0.0.1:9898"), rec("me", id, "http://127.0.0.1:9898")), id, now)
        assertEquals("me", fix?.recordId)
    }

    private class FakePersistence(var hosts: List<PairedHost>) : HostPersistence {
        override fun loadAll() = hosts
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts }
    }

    @Test fun applyKeepsTheTokenNameAndRelayAndLeavesOthersAlone() {
        val p = FakePersistence(listOf(remote, rec("me", id, "http://127.0.0.1:9898", "https://h-me.relay.supermux.dev")))
        val store = PairedHostStore(p) { "new" }
        val fix = thisComputerRecordFix(store.list(), id, now)!!
        assertTrue(applyHostRecordFix(store, fix, id))
        val me = store.list().single { it.recordId == "me" }
        assertEquals(now, me.directUrl)
        assertEquals("tok-me", me.token)
        assertEquals("Box me", me.displayName)
        assertEquals("https://h-me.relay.supermux.dev", me.relayUrl)
        assertEquals(remote, store.list().single { it.recordId == "remote" })
        assertEquals(2, store.list().size)
        assertNull(thisComputerRecordFix(store.list(), id, now))
    }

    @Test fun applyFillsTheHostIdOfALegacyRecordWithoutAddingOne() {
        val store = PairedHostStore(FakePersistence(listOf(rec("legacy", null, "http://127.0.0.1:9898")))) { "new" }
        val fix = thisComputerRecordFix(store.list(), id, now)!!
        assertTrue(applyHostRecordFix(store, fix, id))
        val only = store.list().single()
        assertEquals("legacy", only.recordId)
        assertEquals(id, only.hostId)
        assertEquals(now, only.directUrl)
        assertEquals("tok-legacy", only.token)
    }
}
