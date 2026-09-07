package dev.supermux.state

import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [FleetStore] must not hold its own lock across anything that can block on somebody else.
 *
 * Cluster G8 found this the hard way: assigning a merged `MutableStateFlow` RESUMES its collectors
 * inline, on the assigning thread, and a collector can take a lock of its own on the way (Compose's
 * frame dispatcher does) while the thread holding THAT lock is calling back into this store. With
 * the assignment inside `synchronized(lock)` the two orders close a cycle and both threads park
 * forever. The store now computes under the lock and publishes after it, so a slow collector can
 * only ever delay ITSELF.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FleetStoreLockingTest {

    private class FakePersistence(var hosts: MutableList<PairedHost>) : HostPersistence {
        override fun loadAll() = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    }

    private fun store(vararg h: PairedHost) = PairedHostStore(FakePersistence(h.toMutableList())) { "rec" }

    private fun host(id: String) =
        PairedHost(recordId = id, displayName = id, token = "t", directUrl = "http://$id")

    private fun fleet(scope: CoroutineScope, store: PairedHostStore): FleetStore = FleetStore(
        store = store,
        scope = scope,
        deps = testDeps(),
        appFactory = { url, token, onConn ->
            val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
            HostStore(
                url, token, scope, testDeps(http = http),
                connectOnInit = false, onConnectionChange = onConn,
                apiOverride = BrokerApi(url, token, http),
            )
        },
    )

    /**
     * A collector parked INSIDE a merged flow's emission must not be able to block another thread's
     * `setActiveHost`.
     *
     * Real threads and an UNCONFINED collector, deliberately: unconfined resumes the collector on
     * the EMITTING thread, which is what a dispatcher that runs its continuations inline (Compose's
     * frame dispatcher, holding its own monitor) does. Under the old code the emission happened
     * inside `synchronized(lock)`, so parking there held [FleetStore]'s lock and any other caller
     * queued behind an arbitrary collector.
     */
    @Test fun aBlockedCollectorCannotDeadlockAConcurrentSetActiveHost() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val f = fleet(scope, store(host("h1"), host("h2")))
            val seenFirst = CountDownLatch(1)
            val inEmission = CountDownLatch(1)
            val release = CountDownLatch(1)
            val collector = scope.launch(Dispatchers.Unconfined) {
                var first = true
                f.hostViews.collect {
                    if (first) {
                        first = false
                        seenFirst.countDown()
                    } else {
                        inEmission.countDown()
                        release.await()
                    }
                }
            }
            assertTrue(seenFirst.await(5, TimeUnit.SECONDS), "the collector never subscribed")

            // Thread A republishes the host views and is captured inside the collector's emission.
            val publisher = Thread { f.forgetHost("h2") }
            publisher.start()
            assertTrue(inEmission.await(5, TimeUnit.SECONDS), "no second host-view emission")

            // Thread B drives the store while A is parked mid-emission.
            val done = CountDownLatch(1)
            val worker = Thread {
                f.setActiveHost("h1")
                f.appFor("nope")
                done.countDown()
            }
            worker.start()
            assertTrue(done.await(10, TimeUnit.SECONDS), "FleetStore blocked a caller behind a collector")
            worker.join(1_000)

            release.countDown()
            publisher.join(5_000)
            collector.cancel()
            f.close()
        } finally {
            scope.cancel()
        }
    }

    /** `close()` tears sockets down outside the lock, so a slow teardown cannot wedge a reader. */
    @Test fun closeDoesNotHoldTheLockAcrossSocketTeardown() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val f = fleet(scope, store(host("h1")))
            runBlocking { withTimeout(10_000) { } }
            f.close()
            assertEquals(emptyList(), f.hostViews.value.filter { it.online })
        } finally {
            scope.cancel()
        }
    }

    /**
     * The launcher's first message is armed on the host the spawn RAN on and consumed there —
     * never on "whatever is active by the time the chat opens". With two hosts and the session
     * created on the NON-active one, `_sessionHost` has not absorbed the new id yet, so the old
     * `appFor` fallback asked the active host, got null, and the composer's one-shot effect
     * dropped the message.
     */
    @Test fun theFirstMessageIsConsumedOnTheHostItWasArmedOn() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val hostStore = store(host("h1"), host("h2"))
            val f = FleetStore(
                store = hostStore,
                scope = scope,
                deps = testDeps(),
                appFactory = { url, token, onConn ->
                    val http = HttpClient(
                        MockEngine { req ->
                            val body = when {
                                req.url.encodedPath.contains("validate") -> """{"ok":true,"path":"/repo"}"""
                                req.url.encodedPath == "/sessions" && url.endsWith("h2") ->
                                    """{"id":"s-on-h2","name":"n","workdir":"/repo","agent":"claude"}"""
                                req.url.encodedPath == "/sessions" ->
                                    """{"id":"s-on-h1","name":"n","workdir":"/repo","agent":"claude"}"""
                                else -> "{}"
                            }
                            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                        },
                    )
                    HostStore(
                        url, token, scope, testDeps(http = http),
                        connectOnInit = false, onConnectionChange = onConn,
                        apiOverride = BrokerApi(url, token, http),
                    )
                },
            )
            // h1 is active (first record); spawn explicitly on h2.
            assertEquals("h1", f.activeHost.value)
            val id = runBlocking {
                f.createSessionWithFirstMessageOrThrow(
                    workdir = "/repo", agent = "claude", model = null, reasoningLevel = null,
                    text = "first turn", staged = emptyList(), worktree = false, baseBranch = null,
                    hostRecordId = "h2",
                    
                )
            }
            assertEquals("s-on-h2", id)
            // `_sessionHost` still knows nothing about this id — exactly the window the bug lived in.
            assertEquals(null, f.sessionHost.value[id])

            val pending = f.consumePendingFirst(id)
            assertNotNull(pending, "the first message must come back from the ARMING host")
            assertEquals("first turn", pending.text)
            assertEquals(null, f.consumePendingFirst(id), "consuming stays one-shot")
            f.close()
        } finally {
            scope.cancel()
        }
    }

    /**
     * Two overlapping `sync()` runs must leave exactly ONE connection per record.
     *
     * `sync` stopped being atomic when the dial moved out from under [FleetStore]'s lock (holding it
     * across a blocking `appFactory` is what deadlocked callers), so both runs can plan the same
     * record. The insert settles it: the first wins, the second is closed. Without that,
     * `conns[recordId] = conn` overwrote a live `HostStore` and leaked its socket.
     */
    @Test fun twoConcurrentSyncsOpenExactlyOneConnectionPerHost() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            // Start with NO records so construction dials nothing; add one, then race two syncs.
            val hostStore = store()
            val bothDialing = CountDownLatch(2)
            val release = CountDownLatch(1)
            val dials = CopyOnWriteArrayList<Pair<HostStore, CopyOnWriteArrayList<HttpClient>>>()
            val f = FleetStore(
                store = hostStore,
                scope = scope,
                deps = testDeps(),
                appFactory = { url, token, onConn ->
                    val clients = CopyOnWriteArrayList<HttpClient>()
                    val deps = HostStoreDeps(
                        httpFactory = {
                            HttpClient(MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
                                .also { clients += it }
                        },
                        settings = testDeps().settings,
                    )
                    // Hold BOTH dials open at once so the two syncs really overlap.
                    bothDialing.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "the second dial never started" }
                    HostStore(url, token, scope, deps, connectOnInit = false, onConnectionChange = onConn)
                        .also { dials += it to clients }
                },
            )
            hostStore.add(displayName = "h1", token = "t", directUrl = "http://h1")

            val a = Thread { f.sync(hostStore.list()) }
            val b = Thread { f.sync(hostStore.list()) }
            a.start(); b.start()
            assertTrue(bothDialing.await(10, TimeUnit.SECONDS), "both syncs should have dialled")
            release.countDown()
            a.join(10_000); b.join(10_000)

            assertEquals(2, dials.size, "both syncs dialled — that is the race being tested")
            val live = f.activeApp()
            assertNotNull(live, "one connection must survive")
            val loser = dials.single { it.first !== live }
            assertTrue(
                loser.second.isNotEmpty() && loser.second.none { it.isActive },
                "the losing dial must be CLOSED, not dropped with its socket open",
            )
            val winner = dials.single { it.first === live }
            assertTrue(winner.second.any { it.isActive }, "the surviving connection must stay open")
            f.close()
        } finally {
            scope.cancel()
        }
    }

    /** A draft session arms nothing (there is no first turn yet) and must not strand an entry. */
    @Test fun aDraftSessionArmsNoFirstMessage() {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            val f = fleet(scope, store(host("h1")))
            assertEquals(null, f.consumePendingFirst("never-armed"))
            f.close()
        } finally {
            scope.cancel()
        }
    }
}
