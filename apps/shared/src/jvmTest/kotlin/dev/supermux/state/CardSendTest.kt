package dev.supermux.state

import dev.supermux.proto.CardRef
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.FromCardDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #104: a click in a card sends the user's message as a `send` frame with from_card, shown at once with its card. */
@OptIn(ExperimentalCoroutinesApi::class)
class CardSendTest {
    @Test fun aSendFromACardGoesOutWithItsIdAndShowsWhereItCameFrom() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        app.sendFromCard("s1", FromCardDto("abc", "Sales"), "Use B")
        val frame = sent.filterIsInstance<ClientFrame.Send>().single()
        assertEquals("Use B", frame.args.text)
        assertEquals(CardRef("abc"), frame.args.fromCard)
        assertNull(frame.args.attachments)
        val echo = app.state.value.messages["s1"]!!.single()
        assertEquals("Use B", echo.text)
        assertEquals(FromCardDto("abc", "Sales"), echo.fromCard)
        app.close()
    }
}
