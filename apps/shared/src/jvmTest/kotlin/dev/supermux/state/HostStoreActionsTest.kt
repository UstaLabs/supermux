package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostStoreActionsTest {
    private data class Fixture(
        val store: HostStore,
        val seen: MutableList<String>,
        val bodies: MutableList<String>,
        val settings: FakeSettingsStore,
    )

    private fun fixture(
        scope: CoroutineScope,
        settings: FakeSettingsStore = FakeSettingsStore(),
        bodyFor: (String) -> String = { "{}" },
    ): Fixture {
        val seen = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            val path = "${req.method.value} ${req.url.encodedPath}"
            synchronized(seen) { seen += path }
            synchronized(bodies) { bodies += (req.body as? TextContent)?.text.orEmpty() }
            respond(bodyFor(path), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val store = HostStore(
            "http://h", "t", scope, testDeps(http = http, settings = settings),
            connectOnInit = false,
            apiOverride = BrokerApi("http://h", "t", http),
        )
        return Fixture(store, seen, bodies, settings)
    }

    private fun httpScope() = CoroutineScope(Dispatchers.Default)

    @Test fun saveDraftWritesTheSettingsKeyAndBlankClearsIt() = runTest(UnconfinedTestDispatcher()) {
        val settings = FakeSettingsStore()
        val s = fixture(this, settings).store
        s.saveDraft("s1", "hello"); advanceUntilIdle()
        assertEquals("hello", settings.map.value[SettingsKeys.draft("s1")])
        s.saveDraft("s1", "  "); advanceUntilIdle()
        assertEquals(null, settings.map.value[SettingsKeys.draft("s1")])
        s.close()
    }

    // `launcherPrefsRoundTrip` / `launcherDraftRoundTripAndEmptyClearsKey` moved to `:ui`'s
    // UiPrefsLauncherTest with the values themselves (cluster F1 gave the two launcher keys one
    // owner: `dev.supermux.ui.prefs.UiPrefs`, which both apps read).

    @Test fun revokeHitsTheRevokeEndpoint() = runBlocking {
        val f = fixture(httpScope())
        f.store.revoke("phone")
        waitUntil { f.seen.any { it == "DELETE /devices/phone" } }
        f.store.close()
    }

    @Test fun saveNameHitsPutConfig() = runBlocking {
        val f = fixture(httpScope())
        f.store.saveName("Ada")
        waitUntil { f.seen.any { it == "PUT /settings/config" } }
        f.store.close()
    }

    @Test fun setActiveViewHitsPatchWorkspace() = runBlocking {
        val f = fixture(httpScope())
        f.store.setActiveView("ws1", "v1")
        waitUntil { f.seen.any { it == "PATCH /workspaces/ws1" } }
        f.store.close()
    }

    @Test fun agentSendCodeHitsLoginCode() = runTest {
        val f = fixture(this)
        runCatching { f.store.sendAgentLoginCode("claude", "1234") }
        assertTrue(f.seen.any { it == "POST /agents/claude/login/code" }, f.seen.toString())
        f.store.close()
    }

    @Test fun refreshArchivedHitsArchivedSessionsAndPopulatesState() = runBlocking {
        val archivedJson = """[{"id":"sess-1","name":"fix bug","workdir":"/repo","agent":"claude",
            |"killed_at":"2026-07-01T00:00:00Z","repo_root":"/repo"}]""".trimMargin()
        val f = fixture(httpScope(), bodyFor = { if (it == "GET /archived-sessions") archivedJson else "{}" })
        f.store.refreshArchived()
        waitUntil { f.store.archivedSessions.value.any { it.id == "sess-1" } }
        assertTrue(f.seen.any { it == "GET /archived-sessions" }, f.seen.toString())
        f.store.close()
    }

    @Test fun spawnPostsTrimmedWorkdir() = runBlocking {
        val f = fixture(httpScope())
        f.store.spawn("  /tmp/proj  ", "  n  ", "claude")
        waitUntil { f.seen.any { it == "POST /sessions" } }
        assertTrue(f.bodies.any { it.contains("\"workdir\":\"/tmp/proj\"") }, f.bodies.toString())
        f.store.close()
    }

    @Test fun sendWithBlankTextAndNoAttachmentsIsNoOp() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture(this)
        f.store.sendWith("s1", "  ", emptyList()); advanceUntilIdle()
        assertTrue(f.seen.isEmpty(), f.seen.toString())
        f.store.close()
    }

    @Test fun sessionRemovedRefreshesArchived() = runBlocking {
        val f = fixture(httpScope(), bodyFor = { if (it == "GET /archived-sessions") "[]" else "{}" })
        f.store.reduce(ServerFrame.SessionRemoved("gone"))
        waitUntil { f.seen.any { it == "GET /archived-sessions" } }
        f.store.close()
    }

    @Test fun lastReadStillEmitsAfterClose() = runTest(UnconfinedTestDispatcher()) {
        val s = fixture(this).store
        s.markRead("s1")
        assertEquals("2026-09-03T12:00:00Z", s.lastRead.value["s1"])
        s.close(cancelProjections = false)
        s.markRead("s2")
        assertEquals("2026-09-03T12:00:00Z", s.lastRead.value["s2"])
        s.close()
    }

    @Test fun closeCancelsProjections() = runTest(UnconfinedTestDispatcher()) {
        val s = fixture(this).store
        assertTrue(s.projectionsActive)
        s.close()
        assertFalse(s.projectionsActive)
    }

    // Cluster C1 follow-up (a): fsList used to run through runApi, which log-and-nulls a failure
    // into an empty list — so the file tree could not tell a broken listing from an empty directory
    // and its inline error row was unreachable. fsListResult keeps the failure, like fsRead.
    @Test fun fsListResultReturnsAFailureOnA500WhileFsListStillDegradesToEmpty() = runTest(UnconfinedTestDispatcher()) {
        val http = HttpClient(MockEngine { respond("boom", HttpStatusCode.InternalServerError) })
        val s = HostStore(
            "http://h", "t", this, testDeps(http = http),
            connectOnInit = false,
            apiOverride = BrokerApi("http://h", "t", http),
        )
        val session = SessionInfo(id = "s1", name = "S", workdir = "/w", agent = "claude")

        val result = s.fsListResult(session, "src")

        assertTrue(result.isFailure)
        assertTrue(s.fsList(session, "src").isEmpty()) // the old shape is unchanged for its callers
        assertTrue(s.workspaceFsListResult("w1", "src").isFailure)
        s.close()
    }

    private suspend fun waitUntil(timeoutMs: Long = 5_000, pred: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!pred()) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                throw AssertionError("timed out waiting; pred still false")
            }
            delay(20)
        }
    }
}
