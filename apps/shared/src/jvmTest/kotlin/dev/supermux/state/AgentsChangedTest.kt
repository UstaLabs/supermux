package dev.supermux.state

import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** `agents_changed` (the broker's Agent list changed) reaches the Agents screen as a reload signal. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsChangedTest {
    @Test fun the_frame_decodes() {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        assertEquals(ServerFrame.AgentsChanged, json.decodeFromString<ServerFrame>("""{"type":"agents_changed"}"""))
    }

    @Test fun the_frame_is_emitted_on_agentsChanged() = runBlocking {
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
        )
        val got = async { withTimeout(5_000) { app.agentsChanged.first() } }
        delay(50)
        app.reduce(ServerFrame.AgentsChanged)
        assertEquals(Unit, got.await())
    }
}
