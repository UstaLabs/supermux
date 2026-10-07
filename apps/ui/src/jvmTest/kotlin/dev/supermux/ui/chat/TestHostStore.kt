package dev.supermux.ui.chat

import dev.supermux.proto.ServerFrame
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.SettingsStore
import dev.supermux.state.WalkthroughSeam
import dev.supermux.ui.editor.WalkthroughState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.time.Clock
import kotlin.time.Instant

/** In-memory settings for a test store. */
internal class FakeSettingsStore : SettingsStore {
    val map = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun string(key: String): Flow<String?> = map.map { it[key] }
    override suspend fun putString(key: String, value: String?) {
        map.value = if (value == null) map.value - key else map.value + (key to value)
    }
}

internal class FixedClock(var now: Instant = Instant.parse("2026-09-05T12:00:00Z")) : Clock {
    override fun now(): Instant = now
}

/** `:ui`'s half of the walkthrough seam — the same five lines each app installs. */
internal object TestWalkthroughSeam : WalkthroughSeam<WalkthroughState> {
    override fun create(sessionId: String) = WalkthroughState(sessionId)
    override fun apply(state: WalkthroughState, frame: ServerFrame) = state.applyServerFrame(frame)
}

/**
 * A [HostStore] with a mock HTTP engine and `connectOnInit = false`, so no WebSocket or socket is
 * ever opened. The chat suites that drive the real panel over a real store use this.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun testHostStore(): HostStore = HostStore(
    baseUrl = "ws://test:9898",
    token = "t",
    scope = TestScope(UnconfinedTestDispatcher()),
    deps = HostStoreDeps(
        httpFactory = { HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) },
        settings = FakeSettingsStore(),
        clock = FixedClock(),
    ),
    connectOnInit = false,
    walkthroughSeam = TestWalkthroughSeam,
)
