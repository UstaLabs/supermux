package dev.supermux.state

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Instant

class FakeSettingsStore : SettingsStore {
    val map = MutableStateFlow<Map<String, String>>(emptyMap())
    override fun string(key: String): Flow<String?> = map.map { it[key] }
    override suspend fun putString(key: String, value: String?) {
        map.value = if (value == null) map.value - key else map.value + (key to value)
    }
}

class FixedClock(var now: Instant = Instant.parse("2026-09-03T12:00:00Z")) : Clock {
    override fun now(): Instant = now
}

fun testDeps(
    http: HttpClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }),
    settings: SettingsStore = FakeSettingsStore(),
    clock: Clock = FixedClock(),
) = HostStoreDeps(httpFactory = { http }, settings = settings, clock = clock)
