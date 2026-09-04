package dev.supermux.state

import dev.supermux.host.HostPersistence
import dev.supermux.host.HostSnapshot
import dev.supermux.host.HostSnapshotStore
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.host.SnapshotPersistence
import dev.supermux.net.BrokerApi
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The launcher path through [FleetStore]: the first message + its uploads must reach the composer,
 * the broker's own refusal must reach the screen, and the offline cache must seed the merged list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreLauncherTest {

    private class FakePersistence(var hosts: MutableList<PairedHost> = mutableListOf()) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    private class FakeSnapshots(var rows: MutableList<HostSnapshot> = mutableListOf()) : SnapshotPersistence {
        override fun loadAll() = rows.toList()
        override fun saveAll(snapshots: List<HostSnapshot>) { rows = snapshots.toMutableList() }
    }

    private fun hostStore(vararg h: PairedHost) =
        PairedHostStore(FakePersistence(h.toMutableList())) { "rec" }

    private fun host(id: String = "h1") =
        PairedHost(recordId = id, displayName = id, token = "t", directUrl = "http://$id")

    private fun fleet(
        scope: CoroutineScope,
        store: PairedHostStore = hostStore(host()),
        snapshots: HostSnapshotStore? = null,
        bodyFor: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" },
    ): FleetStore = FleetStore(
        store = store,
        scope = scope,
        deps = testDeps(),
        appFactory = { url, token, onConn ->
            val http = HttpClient(MockEngine { req ->
                val (code, body) = bodyFor("${req.method.value} ${req.url.encodedPath}")
                respond(body, code, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            HostStore(
                url, token, scope, testDeps(http = http),
                connectOnInit = false, onConnectionChange = onConn,
                apiOverride = BrokerApi(url, token, http),
            )
        },
        snapshots = snapshots,
    )

    private fun spawnOk(path: String): Pair<HttpStatusCode, String> = when {
        path.endsWith("/paths/validate") || path.contains("validate") ->
            HttpStatusCode.OK to """{"ok":true,"path":"/repo"}"""
        path == "POST /sessions" -> HttpStatusCode.OK to """{"id":"s-new","name":"n","workdir":"/repo","agent":"claude"}"""
        path.contains("/upload") -> HttpStatusCode.OK to """{"file_id":"f1"}"""
        else -> HttpStatusCode.OK to "{}"
    }

    @Test fun launcherArmsThePendingFirstMessageSoTheComposerSendsIt() = runTest(UnconfinedTestDispatcher()) {
        val f = fleet(this, bodyFor = ::spawnOk)
        val id = withContext(Dispatchers.Default) {
            f.createSessionWithFirstMessageOrThrow(
                workdir = "/repo", agent = "claude", model = null, reasoningLevel = null,
                text = "first turn", staged = emptyList(), worktree = false, baseBranch = null,
            )
        }
        assertEquals("s-new", id)
        val pending = f.consumePendingFirst(id)
        assertNotNull(pending, "the composer must find a pending first message")
        assertEquals("first turn", pending.text)
        assertNull(f.consumePendingFirst(id), "consuming is one-shot")
        f.close()
    }

    @Test fun brokerDeliveredFirstMessageIsNotQueuedAgain() = runTest(UnconfinedTestDispatcher()) {
        val f = fleet(this, bodyFor = ::spawnOk)
        val id = withContext(Dispatchers.Default) {
            f.createSessionWithFirstMessageOrThrow(
                workdir = "/repo", agent = "claude", model = null, reasoningLevel = null,
                text = "handoff", staged = emptyList(), worktree = false, baseBranch = null,
                firstMessage = "handoff",
            )
        }
        assertNull(f.consumePendingFirst(id), "the broker delivers firstMessage; do not also Send it")
        f.close()
    }

    @Test fun invalidWorkdirSurfacesTheBrokersOwnReason() = runTest(UnconfinedTestDispatcher()) {
        val f = fleet(this, bodyFor = { path ->
            if (path.contains("validate")) HttpStatusCode.OK to """{"ok":false,"error":"not a directory"}"""
            else HttpStatusCode.OK to "{}"
        })
        val e = assertFailsWith<IllegalArgumentException> {
            withContext(Dispatchers.Default) {
                f.createSessionWithFirstMessageOrThrow(
                    workdir = "/nope", agent = "claude", model = null, reasoningLevel = null,
                    text = "x", staged = emptyList(), worktree = false, baseBranch = null,
                )
            }
        }
        assertEquals("not a directory", e.message)
        f.close()
    }

    @Test fun refusedSpawnSurfacesTheBrokersErrorField() = runTest(UnconfinedTestDispatcher()) {
        val f = fleet(this, bodyFor = { path ->
            when {
                path.contains("validate") -> HttpStatusCode.OK to """{"ok":true,"path":"/repo"}"""
                path == "POST /sessions" -> HttpStatusCode.BadRequest to """{"error":"agent not installed"}"""
                else -> HttpStatusCode.OK to "{}"
            }
        })
        val e = assertFailsWith<IllegalStateException> {
            withContext(Dispatchers.Default) {
                f.createSessionWithFirstMessageOrThrow(
                    workdir = "/repo", agent = "claude", model = null, reasoningLevel = null,
                    text = "x", staged = emptyList(), worktree = false, baseBranch = null,
                )
            }
        }
        assertEquals("agent not installed", e.message)
        f.close()
    }

    @Test fun offlineCacheSeedsTheMergedListBeforeAnyHostConnects() = runTest(UnconfinedTestDispatcher()) {
        val cache = HostSnapshotStore(
            FakeSnapshots(
                mutableListOf(
                    HostSnapshot("h1", listOf(SessionInfo(id = "s-cached", name = "Cached", agent = "claude", workdir = "/a"))),
                    HostSnapshot("gone", listOf(SessionInfo(id = "s-stale", name = "Stale", agent = "claude", workdir = "/b"))),
                ),
            ),
        )
        val f = fleet(this, snapshots = cache)
        assertEquals(listOf("s-cached"), f.sessions.value.map { it.id })
        assertNull(cache.get("gone"), "a host forgotten while the app was dead is pruned")
        f.close()
    }

    @Test fun forgettingAHostDropsItsCachedSnapshot() = runTest(UnconfinedTestDispatcher()) {
        val cache = HostSnapshotStore(
            FakeSnapshots(mutableListOf(HostSnapshot("h1", listOf(SessionInfo(id = "s1", name = "S", agent = "claude", workdir = "/a"))))),
        )
        val store = hostStore(host())
        val f = fleet(this, store = store, snapshots = cache)
        f.forgetHost("h1")
        assertNull(cache.get("h1"))
        assertEquals(emptyList(), f.sessions.value)
        f.close()
    }

    @Test fun settingsWritesPersistWithNoHostConnected() = runTest(UnconfinedTestDispatcher()) {
        val f = FleetStore(store = hostStore(), scope = this, deps = testDeps())
        f.saveDraft("s1", "typed offline")
        f.saveLauncherPrefs(LauncherPrefs(agent = "codex"))
        f.saveLauncherDraft(LauncherDraft(workdir = "/x"))
        advanceUntilIdle()
        assertEquals("typed offline", f.draft("s1").first())
        assertEquals("codex", f.launcherPrefs.first().agent)
        assertEquals("/x", f.launcherDraft.first().workdir)
        f.clearLauncherDraft(); advanceUntilIdle()
        assertNull(f.launcherDraft.first().workdir)
        f.close()
    }
}
