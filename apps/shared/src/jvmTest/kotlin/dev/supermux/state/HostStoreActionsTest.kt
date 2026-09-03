package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.workspace.activeViewPatchBody
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreActionsTest {
    private data class Fixture(val store: HostStore, val seen: MutableList<String>)
    private fun fixture(scope: CoroutineScope, settings: SettingsStore = FakeSettingsStore()): Fixture {
        val seen = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            seen += "${req.method.value} ${req.url.encodedPath}"
            respond("{}", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        })
        val store = HostStore(
            "http://h", "t", scope, testDeps(http = http, settings = settings),
            connectOnInit = false,
            apiOverride = BrokerApi("http://h", "t", http),
        )
        return Fixture(store, seen)
    }

    @Test fun saveDraftWritesTheSettingsKeyAndBlankClearsIt() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettingsStore()
        val s = fixture(this, settings).store
        s.saveDraft("s1", "hello"); advanceUntilIdle()
        assertEquals("hello", settings.map.value[SettingsKeys.draft("s1")])
        s.saveDraft("s1", "  "); advanceUntilIdle()
        assertEquals(null, settings.map.value[SettingsKeys.draft("s1")])
        s.close()
    }

    @Test fun launcherPrefsRoundTrip() = runTest(UnconfinedTestDispatcher()) {
        val s = fixture(this).store
        s.saveLauncherPrefs(LauncherPrefs(agent = "codex", models = mapOf("codex" to "gpt"))); advanceUntilIdle()
        assertEquals("codex", s.launcherPrefs.first().agent)
        s.close()
    }

    @Test fun revokeHitsTheRevokeEndpoint() = runTest {
        val f = fixture(this)
        runCatching { f.store.revokeDevice("phone") }
        assertTrue(f.seen.any { it == "DELETE /devices/phone" }, f.seen.toString())
        f.store.close()
    }

    @Test fun saveNameHitsPutConfig() = runTest {
        val f = fixture(this)
        runCatching { f.store.api.putConfig("Ada") }
        assertTrue(f.seen.any { it == "PUT /settings/config" }, f.seen.toString())
        f.store.close()
    }

    @Test fun setActiveViewHitsPatchWorkspace() = runTest {
        val f = fixture(this)
        runCatching { f.store.api.patchWorkspace("ws1", activeViewPatchBody("v1")) }
        assertTrue(f.seen.any { it == "PATCH /workspaces/ws1" }, f.seen.toString())
        f.store.close()
    }

    @Test fun agentSendCodeHitsLoginCode() = runTest {
        val f = fixture(this)
        runCatching { f.store.sendAgentLoginCode("claude", "1234") }
        assertTrue(f.seen.any { it == "POST /agents/claude/login/code" }, f.seen.toString())
        f.store.close()
    }

    @Test fun refreshArchivedHitsArchivedSessions() = runTest {
        val f = fixture(this)
        runCatching { f.store.api.archived() }
        assertTrue(f.seen.any { it == "GET /archived-sessions" }, f.seen.toString())
        f.store.close()
    }

    @Test fun lastReadStillEmitsAfterClose() = runTest(UnconfinedTestDispatcher()) {
        val s = fixture(this).store
        s.markRead("s1")
        assertEquals("2026-09-03T12:00:00Z", s.lastRead.value["s1"])
        s.close()
        s.markRead("s2")
        assertEquals("2026-09-03T12:00:00Z", s.lastRead.value["s2"])
    }
}
