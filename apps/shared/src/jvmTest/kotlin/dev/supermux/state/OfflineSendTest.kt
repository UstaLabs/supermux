package dev.supermux.state

import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A message sent while the broker is down used to be dropped by the socket and still set the
 * "Sending…" marker — and no agent_state ever came to clear it, so the chat sat on "Sending…" with
 * Send disabled until the app restarted. Now it waits for the reconnect snapshot and goes out then.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSendTest {
    @Test fun aMessageSentOfflineGoesOutAfterTheReconnectSnapshot() = runTest(UnconfinedTestDispatcher()) {
        var connected = false
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore(
            "https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false,
            sendFrameOverride = { if (!connected) error("not connected"); sent.add(it) },
        )
        app.sendMessage("s1", "hello")
        assertTrue(sent.isEmpty())
        assertEquals(setOf("s1"), app.pendingSend.value)

        connected = true
        app.reduce(ServerFrame.Snapshot())
        assertEquals(listOf("hello"), sent.filterIsInstance<ClientFrame.Send>().map { it.args.text })
        assertEquals(setOf("s1"), app.pendingSend.value, "still Sending… until its agent_state")
        assertEquals(listOf("hello"), app.state.value.messages["s1"]?.map { it.text }, "local echo survives the snapshot")

        app.reduce(ServerFrame.AgentState(session = "s1", phase = "thinking", state = "working", working = true))
        assertTrue(app.pendingSend.value.isEmpty())
        app.close()
    }

    @Test fun aSnapshotClearsAMarkerWhoseAgentStateWasMissed() = runTest(UnconfinedTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val app = HostStore(
            "https://h.relay.supermux.dev", "t", this, testDeps(), connectOnInit = false,
            sendFrameOverride = { sent.add(it) },
        )
        app.sendMessage("s1", "hello")
        assertEquals(1, sent.filterIsInstance<ClientFrame.Send>().size)
        // The socket dropped before the agent_state arrived; the reconnect snapshot is the truth.
        app.reduce(ServerFrame.Snapshot())
        assertTrue(app.pendingSend.value.isEmpty())
        assertEquals(1, sent.filterIsInstance<ClientFrame.Send>().size, "a delivered message is not sent twice")
        app.close()
    }
}
