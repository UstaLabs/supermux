package dev.supermux.state

import dev.supermux.proto.ClientFrame
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Card #75: every message the app sends carries an id the app made (`web-<uuid>`). The broker logs and
 * dedupes the message under it, and the app's own copy is replaced by the broker's copy of THAT
 * message, found by the id, not by its text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientMessageIdTest {
    private fun brokerCopy(id: String, text: String) =
        LogEntry(id = "in:web:$id", ts = "2026-11-05T12:00:00Z", direction = "inbound", text = text, channel = "web", chat_id = "web", message_id = id)

    @Test fun twoMessagesWithTheSameTextKeepTheirOwnCopiesUntilTheBrokersCopyOfEachArrives() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        app.sendMessage("s1", "ok")
        app.sendMessage("s1", "ok")
        val ids = sent.filterIsInstance<ClientFrame.Send>().map { it.clientMessageId!! }
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("web-") })

        // The broker's copy of the second one arrives first: only the second one's own copy goes.
        app.reduce(ServerFrame.MessageAppend("s1", brokerCopy(ids[1], "ok")))
        assertEquals(listOf(localEchoId(ids[0]), "in:web:${ids[1]}"), app.state.value.messages["s1"]!!.map { it.id })
        app.reduce(ServerFrame.MessageAppend("s1", brokerCopy(ids[0], "ok")))
        assertEquals(listOf("in:web:${ids[1]}", "in:web:${ids[0]}"), app.state.value.messages["s1"]!!.map { it.id })
        app.close()
    }

    @Test fun anotherDevicesMessageWithTheSameTextLeavesThisDevicesCopyAlone() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        app.sendMessage("s1", "ok")
        app.reduce(ServerFrame.MessageAppend("s1", brokerCopy("web-from-the-phone", "ok")))
        val mine = sent.filterIsInstance<ClientFrame.Send>().single().clientMessageId!!
        assertEquals(listOf(localEchoId(mine), "in:web:web-from-the-phone"), app.state.value.messages["s1"]!!.map { it.id })
        app.close()
    }

    @Test fun theSameEntryAppendedTwiceIsShownOnce() = runTest(UnconfinedTestDispatcher()) {
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = {})
        val reply = LogEntry(id = "out:1", ts = "2026-11-05T12:00:01Z", direction = "outbound", text = "done")
        app.reduce(ServerFrame.MessageAppend("s1", reply))
        app.reduce(ServerFrame.MessageAppend("s1", reply.copy(text = "done!")))
        assertEquals(listOf("done!"), app.state.value.messages["s1"]!!.map { it.text })
        app.close()
    }

    @Test fun anOlderBrokerThatIgnoresTheIdStillReplacesOneCopyByItsText() = runTest(UnconfinedTestDispatcher()) {
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = {})
        app.sendMessage("s1", "ok")
        app.sendMessage("s1", "ok")
        app.reduce(ServerFrame.MessageAppend("s1", LogEntry(id = "in:web:1730-abc", ts = "2026-11-05T12:00:00Z", direction = "inbound", text = "ok", message_id = "1730-abc")))
        val ids = app.state.value.messages["s1"]!!.map { it.id }
        assertEquals(2, ids.size)
        assertTrue(ids[0].startsWith("local-"))
        assertEquals("in:web:1730-abc", ids[1])
        app.close()
    }

    @Test fun aMessageQueuedOfflineGoesOutUnderTheIdItWasShownWith() = runTest(UnconfinedTestDispatcher()) {
        var connected = false
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore(
            "https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false,
            sendFrameOverride = { if (!connected) error("not connected"); sent.add(it) },
        )
        app.sendMessage("s1", "hello")
        val shownAs = app.state.value.messages["s1"]!!.single().id
        connected = true
        app.reduce(ServerFrame.Snapshot())
        val id = sent.filterIsInstance<ClientFrame.Send>().single().clientMessageId!!
        assertEquals(localEchoId(id), shownAs)
        assertEquals(listOf(shownAs), app.state.value.messages["s1"]!!.map { it.id }, "its copy is put back under the same id after the snapshot")
        app.close()
    }
}
