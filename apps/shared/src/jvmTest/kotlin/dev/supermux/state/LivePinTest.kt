package dev.supermux.state

import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Card #62: live text for a chat that is not on screen (the assistant overlay's Agent) is asked for with the `live`
 * frame, never with `viewing`, which is presence. The broker keeps the pins per socket, so every reconnect asks again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LivePinTest {
    private fun ClientFrame.isViewing() = this is ClientFrame.Viewing

    @Test fun pinningAsksForTheChatsLiveTextOnceAndClaimsNoPresence() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        app.pinLive(listOf("s1"))
        app.pinLive(listOf("s1"))
        assertEquals(listOf<ClientFrame>(ClientFrame.Live(listOf("s1"))), sent)
        assertEquals(emptyList(), sent.filter { it.isViewing() })
        app.close()
    }

    @Test fun aReconnectAsksAgain() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        app.pinLive(listOf("s1"))
        sent.clear()
        app.reduce(ServerFrame.Snapshot(sessions = emptyList()))
        assertEquals(listOf<ClientFrame>(ClientFrame.Live(listOf("s1"))), sent.filterIsInstance<ClientFrame.Live>())
        app.close()
    }

    @Test fun unpinningSaysSoAndAReconnectThenAsksForNothing() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore("https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false, sendFrameOverride = { sent.add(it) })
        app.pinLive(listOf("s1"))
        app.pinLive(emptyList())
        assertEquals(ClientFrame.Live(emptyList()), sent.last())
        sent.clear()
        app.reduce(ServerFrame.Snapshot(sessions = emptyList()))
        assertEquals(emptyList(), sent.filterIsInstance<ClientFrame.Live>())
        app.close()
    }
}
