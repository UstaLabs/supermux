package dev.supermux.desktop.host

import dev.supermux.host.PairedHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThisComputerTest {
    private fun rec(id: String, hostId: String?, direct: String?) =
        PairedHost(recordId = id, hostId = hostId, displayName = id, token = "t-$id", directUrl = direct)

    @Test fun theHostIdMatchWinsWhereverItPoints() {
        val mine = rec("mine", "h1", "http://127.0.0.1:1234")
        val legacy = rec("legacy", null, "http://127.0.0.1:9898")
        assertEquals("mine", thisComputerRecord(listOf(legacy, mine), "h1", 9898)?.recordId)
    }

    @Test fun aLoopbackRecordCountsOnlyWithNoHostIdAndOurPort() {
        val legacy = rec("legacy", null, "http://127.0.0.1:9898")
        assertEquals("legacy", thisComputerRecord(listOf(legacy), "h1", 9898)?.recordId)
        assertEquals("legacy", thisComputerRecord(listOf(legacy), null, 9898)?.recordId)
        assertNull(thisComputerRecord(listOf(legacy), "h1", 9899), "another port: maybe another broker")
        assertNull(thisComputerRecord(listOf(legacy), "h1", null))
        assertNull(thisComputerRecord(listOf(rec("other", "h2", "http://127.0.0.1:9898")), "h1", 9898), "another broker's record")
        assertNull(thisComputerRecord(listOf(rec("lan", null, "http://192.168.1.2:9898")), "h1", 9898))
        assertNull(thisComputerRecord(listOf(rec("blank", "", null)), "h1", 9898))
    }

    @Test fun portsDefaultFromTheScheme() {
        assertEquals(9898, urlPort("http://127.0.0.1:9898/"))
        assertEquals(80, urlPort("http://localhost"))
        assertEquals(443, urlPort("https://localhost"))
        assertNull(urlPort("not a url"))
        assertNull(urlPort(null))
    }
}
