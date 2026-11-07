package dev.supermux.state

import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Card #62: live text for a chat that is not on screen (the assistant overlay's Agent) is asked for with the `live`
 * frame, never with `viewing`, which is presence. Only a broker whose snapshot says it accepts `live` is sent it: an
 * older one answers any frame it doesn't know with an error. The broker keeps pins per socket, so a reconnect asks again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePinTest {
    /** A current broker's snapshot, and an older one's (which says nothing about `live`). */
    private val accepting = ServerFrame.Snapshot(sessions = emptyList(), accepts = listOf("live"))
    private val older = ServerFrame.Snapshot(sessions = emptyList())

    private fun TestScope.store(sent: MutableList<ClientFrame>) =
        HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })

    private fun List<ClientFrame>.live() = filterIsInstance<ClientFrame.Live>()

    @Test fun pinningAsksForTheChatsLiveTextOnceAndClaimsNoPresence() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = store(sent)
        app.reduce(accepting)
        sent.clear()
        app.pinLive(listOf("s1"))
        app.pinLive(listOf("s1"))
        assertEquals(listOf<ClientFrame>(ClientFrame.Live(listOf("s1"))), sent)
        app.close()
    }

    @Test fun anOlderBrokerIsNeverSentTheFrame() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = store(sent)
        app.reduce(older)
        app.pinLive(listOf("s1"))
        app.reduce(older)
        assertEquals(emptyList(), sent.live())
        app.close()
    }

    @Test fun aPinMadeBeforeTheBrokerAnswersWaitsForIt() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = store(sent)
        app.pinLive(listOf("s1"))
        assertEquals(emptyList(), sent.live())
        app.reduce(accepting)
        assertEquals(listOf(ClientFrame.Live(listOf("s1"))), sent.live())
        app.close()
    }

    @Test fun aReconnectAsksAgain() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = store(sent)
        app.reduce(accepting)
        app.pinLive(listOf("s1"))
        sent.clear()
        app.reduce(accepting)
        assertEquals(listOf(ClientFrame.Live(listOf("s1"))), sent.live())
        app.close()
    }

    @Test fun unpinningSaysSoAndAReconnectThenAsksForNothing() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = store(sent)
        app.reduce(accepting)
        app.pinLive(listOf("s1"))
        app.pinLive(emptyList())
        assertEquals(ClientFrame.Live(emptyList()), sent.last())
        sent.clear()
        app.reduce(accepting)
        assertEquals(emptyList(), sent.live())
        app.close()
    }
}
