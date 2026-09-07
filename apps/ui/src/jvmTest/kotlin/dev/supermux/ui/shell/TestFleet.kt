package dev.supermux.ui.shell

import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

/** In-memory settings for a shell test. */
internal class ShellFakeSettings : SettingsStore {
    private val map = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun string(key: String): Flow<String?> = map.map { it[key] }
    override suspend fun putString(key: String, value: String?) {
        map.value = if (value == null) map.value - key else map.value + (key to value)
    }
}

internal class ShellFixedClock(private val instant: Instant = Instant.parse("2026-09-07T12:00:00Z")) : Clock {
    override fun now(): Instant = instant
}

internal fun shellTestDeps(settings: SettingsStore = ShellFakeSettings()) = HostStoreDeps(
    httpFactory = { HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) },
    settings = settings,
    clock = ShellFixedClock(),
)

/** A one-record [PairedHostStore] backed by memory. */
internal fun testPairedHostStore(recordId: String = "h1"): PairedHostStore = PairedHostStore(
    object : HostPersistence {
        private var hosts = mutableListOf(
            PairedHost(
                recordId = recordId,
                hostId = "host-a",
                displayName = "Test host",
                token = "t",
                relayUrl = "https://test.relay.supermux.dev",
            ),
        )
        override fun loadAll(): List<PairedHost> = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    },
) { "rec-unused" }

/**
 * A [FleetStore] whose single host IS [app].
 *
 * `SupermuxApp` takes a fleet (both apps have one; desktop's `app` was always
 * `fleet.activeApp()`), so a suite that wants to drive one real [HostStore] wraps it here — the
 * fleet's per-session routing then resolves every id back to that store.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun testFleet(
    app: HostStore,
    /**
     * UNCONFINED: the fleet's per-host collectors are launched here, and a suite that pushes a
     * frame into the store one line after building it (the agent-reply notification cases) needs
     * them attached already. Not a deadlock workaround — the store publishes outside its lock
     * (`:shared`'s `FleetStoreLockingTest`), so a real dispatcher is safe too, just racy for those
     * two cases.
     */
    scope: CoroutineScope = TestScope(UnconfinedTestDispatcher()),
    settings: SettingsStore = ShellFakeSettings(),
): FleetStore = FleetStore(
    store = testPairedHostStore(),
    scope = scope,
    deps = shellTestDeps(settings),
    appFactory = { _, _, _ -> app },
)

/**
 * The composable form. A `FleetStore` built in a composable BODY is rebuilt on every
 * recomposition — new connections, new collector jobs, and every `remember(fleet)` holder in the
 * shell thrown away each frame. Always remember it.
 */
@Composable
internal fun rememberTestFleet(app: HostStore): FleetStore = remember(app) { testFleet(app) }
