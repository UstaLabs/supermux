package dev.supermux.state

import dev.supermux.proto.CardRef
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.FromCardDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #104: a click in a card sends the user's message as a `send` frame with from_card, shown at once with its card. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSendTest {
    @Test fun aSendFromACardGoesOutWithItsIdAndShowsWhereItCameFrom() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        async { app.sendFromCard("s1", FromCardDto("abc", "Sales"), "Use B") }
        val frame = sent.filterIsInstance<ClientFrame.Send>().single()
        assertEquals("Use B", frame.args.text)
        assertEquals(CardRef("abc"), frame.args.fromCard)
        assertNull(frame.args.attachments)
        val echo = app.state.value.messages["s1"]!!.single()
        assertEquals("Use B", echo.text)
        assertEquals(FromCardDto("abc", "Sales"), echo.fromCard)
        app.close()
    }

    private fun brokerCopy(id: String, text: String) =
        LogEntry(id = "in:web:$id", ts = "2026-11-10T22:00:00Z", direction = "inbound", text = text, channel = "web", chat_id = "web", message_id = id)

    // The page is told what the broker did with the send, not that it left.

    @Test fun theBrokersCopyOfTheMessageIsTheAnswerSent() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        val answer = async { app.sendFromCard("s1", FromCardDto("abc", "Sales"), "Use B") }
        val id = sent.filterIsInstance<ClientFrame.Send>().single().clientMessageId!!
        assertTrue(answer.isActive)
        app.reduce(ServerFrame.MessageAppend("s1", brokerCopy(id, "Use B")))
        assertNull(answer.await())
        app.close()
    }

    @Test fun aRefusalForThisSendIsTheAnswerAndItsBubbleGoes() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        val answer = async { app.sendFromCard("s1", FromCardDto("abc", "Sales"), "Use B") }
        val id = sent.filterIsInstance<ClientFrame.Send>().single().clientMessageId!!
        // Another send's refusal is not this one's.
        app.reduce(ServerFrame.Error("one send a second", clientMessageId = "web-other"))
        assertTrue(answer.isActive)
        app.reduce(ServerFrame.Error("one send a second", clientMessageId = id))
        assertEquals("one send a second", answer.await())
        assertTrue(app.state.value.messages["s1"].orEmpty().isEmpty())
        app.close()
    }

    @Test fun aSendThatCantLeaveIsRefusedNotQueued() = runTest(UnconfinedTestDispatcher()) {
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false)
        // Not connected: a click's send is not kept for later.
        assertEquals("not connected", app.sendFromCard("s1", FromCardDto("abc", "Sales"), "Use B"))
        assertTrue(app.state.value.messages["s1"].orEmpty().isEmpty())
        app.close()
    }

    @Test fun noAnswerInTimeIsSaidSo() = runTest {
        val app = HostStore("https://h.relay.supermux.dev", "t", backgroundScope, testDeps(), connectOnInit = false, sendFrameOverride = { })
        assertEquals("no answer from the host", app.sendFromCard("s1", FromCardDto("abc", "Sales"), "Use B"))
        app.close()
    }
}
