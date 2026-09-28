package dev.supermux.fs

import dev.supermux.net.BrokerApi
import dev.supermux.net.FsEntry
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FileSystemServiceTest {
    private fun TestScope.service(body: String = "{}"): Pair<FileSystemService, MutableList<ClientFrame>> {
        val sent = mutableListOf<ClientFrame>()
        val http = HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        val fs = FileSystemService(BrokerApi("http://h", "t", http), send = { sent += it }, scope = backgroundScope, graceMs = 10_000)
        return fs to sent
    }

    private fun dir(path: String, v: String, vararg names: String) =
        ServerFrame.FsDir(path = path, version = v, entries = names.map { FsEntry(name = it, type = "file") })

    @Test fun firstSubscriberSendsOneFsSubAndSharesTheState() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        val a = fs.subscribe("/p")
        val b = fs.subscribe("/p")
        runCurrent()
        assertEquals(listOf<ClientFrame>(ClientFrame.FsSub("/p")), sent)
        assertEquals(DirState.Loading(null), fs.dir("/p").value)
        fs.onFrame(dir("/p", "b:1", "x"))
        val ready = assertIs<DirState.Ready>(fs.dir("/p").value)
        assertEquals(listOf("x"), ready.snap.entries.map { it.name })
        a.close(); b.close()
    }

    @Test fun lastCloseUnsubscribesOnlyAfterTheGracePeriod() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        val s = fs.subscribe("/p"); runCurrent()
        fs.onFrame(dir("/p", "b:1"))
        s.close()
        advanceTimeBy(9_000); runCurrent()
        assertEquals(1, sent.size)
        val again = fs.subscribe("/p"); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, sent.size) // re-subscribed within grace: no frames at all
        again.close()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(ClientFrame.FsUnsub("/p"), sent.last())
        // the snapshot stays cached; a later subscribe sends since
        fs.subscribe("/p"); runCurrent()
        assertEquals(ClientFrame.FsSub("/p", since = "b:1"), sent.last())
    }

    @Test fun reconnectResubscribesWithSinceAndKeepsTheCachedRows() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        fs.subscribe("/a"); fs.subscribe("/b"); runCurrent()
        fs.onFrame(dir("/a", "b:3", "one"))
        sent.clear()
        fs.onReconnect(); runCurrent()
        assertEquals(setOf<ClientFrame>(ClientFrame.FsSub("/a", "b:3"), ClientFrame.FsSub("/b")), sent.toSet())
        assertIs<DirState.Ready>(fs.dir("/a").value)
        fs.onFrame(ServerFrame.FsDir(path = "/a", version = "b:3", unchanged = true))
        assertEquals(listOf("one"), (fs.dir("/a").value as DirState.Ready).snap.entries.map { it.name })
    }

    @Test fun goneAndErrorStates() = runTest(StandardTestDispatcher()) {
        val (fs, _) = service()
        fs.subscribe("/a"); fs.subscribe("/b"); runCurrent()
        fs.onFrame(dir("/a", "b:1", "x"))
        fs.onFrame(ServerFrame.FsGone("/a"))
        assertEquals(DirState.Gone, fs.dir("/a").value)
        fs.onFrame(ServerFrame.FsErr("/b", "EACCES", "denied"))
        assertEquals(DirState.Failed("EACCES", "denied", null), fs.dir("/b").value)
    }

    @Test fun eviction_keeps_subscribed_folders() = runTest(StandardTestDispatcher()) {
        val sent = mutableListOf<ClientFrame>()
        val http = HttpClient(MockEngine { respond("{}") })
        val fs = FileSystemService(BrokerApi("http://h", "t", http), send = { sent += it }, scope = backgroundScope, graceMs = 0, maxCached = 2)
        val keep = fs.subscribe("/keep"); runCurrent()
        fs.onFrame(dir("/keep", "b:1"))
        for (p in listOf("/x", "/y", "/z")) { val s = fs.subscribe(p); runCurrent(); fs.onFrame(dir(p, "b:1")); s.close(); advanceTimeBy(1); runCurrent() }
        assertTrue(fs.cachedCount <= 2) // before dir("/x") below, which re-creates an empty slot
        assertIs<DirState.Ready>(fs.dir("/keep").value)
        assertEquals(DirState.Unloaded, fs.dir("/x").value)
        keep.close()
    }

    // ── decision-order frame delivery (grace-driven unsub racing a re-subscribe) ────────────────

    @Test fun graceExpiryThenImmediateResubscribe_endsWithFsSubNeverUnsubLast() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        val s = fs.subscribe("/p"); runCurrent()
        fs.onFrame(dir("/p", "b:1"))
        s.close()
        advanceTimeBy(10_001); runCurrent() // grace fires: FsUnsub decided (refs still 0 at this instant)
        val again = fs.subscribe("/p") // decided in the very next step, before the unsub's send could reorder
        runCurrent()
        again.close()
        assertEquals(ClientFrame.FsSub("/p", since = "b:1"), sent.last())
    }

    @Test fun resubscribeBeforeGraceFires_cancelsGraceSoNoUnsubIsEverSent() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        val s = fs.subscribe("/p"); runCurrent()
        fs.onFrame(dir("/p", "b:1"))
        s.close()
        advanceTimeBy(5_000) // grace pending, not yet due
        val again = fs.subscribe("/p"); runCurrent() // cancels the grace job before it decides anything
        advanceTimeBy(20_000); runCurrent() // grace would have fired by now if not cancelled
        assertEquals(listOf<ClientFrame>(ClientFrame.FsSub("/p")), sent)
        again.close()
    }

    @Test fun slowUnsubSendDoesNotLetALaterFsSubOvertakeIt() = runTest(StandardTestDispatcher()) {
        // A `send` that suspends for FsUnsub but not FsSub simulates a broker call that reorders
        // on the wire if frames are launched independently. With a single serial consumer draining
        // frames in decision order, the recorded order must still match decision order.
        val sent = mutableListOf<ClientFrame>()
        val http = HttpClient(MockEngine { respond("{}") })
        val fs = FileSystemService(
            BrokerApi("http://h", "t", http),
            send = { frame ->
                if (frame is ClientFrame.FsUnsub) delay(5_000)
                sent += frame
            },
            scope = backgroundScope,
            graceMs = 1_000,
        )
        val s = fs.subscribe("/p"); runCurrent()
        fs.onFrame(dir("/p", "b:1"))
        s.close()
        advanceTimeBy(1_001); runCurrent() // grace fires: FsUnsub decided; its send is now delaying
        fs.subscribe("/p") // decided right after, while the unsub's slow send is still in flight
        advanceTimeBy(6_000); runCurrent()
        assertEquals(
            listOf<ClientFrame>(ClientFrame.FsSub("/p"), ClientFrame.FsUnsub("/p"), ClientFrame.FsSub("/p", since = "b:1")),
            sent,
        )
    }

    // ── reconnect ────────────────────────────────────────────────────────────────────────────

    @Test fun reconnectCancelsGraceAndClearsSubscribedForRefsZeroSlots() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        val s = fs.subscribe("/p"); runCurrent()
        fs.onFrame(dir("/p", "b:1"))
        s.close() // refs == 0, subscribed == true, grace pending
        sent.clear()
        fs.onReconnect(); runCurrent()
        assertEquals(emptyList<ClientFrame>(), sent) // broker never knew this slot post-reconnect: nothing to resend
        // a new subscriber arriving during the (now-cancelled) grace window must get a fresh fs_sub,
        // not silently ride along on a subscription the broker no longer has.
        fs.subscribe("/p"); runCurrent()
        assertEquals(listOf<ClientFrame>(ClientFrame.FsSub("/p", since = "b:1")), sent)
    }

    @Test fun refreshResendsFsSubWithoutSinceOnlyWhileSubscribed() = runTest(StandardTestDispatcher()) {
        val (fs, sent) = service()
        // no subscriber yet: refresh is a no-op
        fs.refresh("/p"); runCurrent()
        assertEquals(emptyList<ClientFrame>(), sent)

        val s = fs.subscribe("/p"); runCurrent()
        fs.onFrame(dir("/p", "b:1"))
        sent.clear()
        fs.refresh("/p"); runCurrent()
        assertEquals(listOf<ClientFrame>(ClientFrame.FsSub("/p")), sent)

        s.close()
        advanceTimeBy(10_001); runCurrent() // grace elapses: refs == 0, subscribed == false
        sent.clear()
        fs.refresh("/p"); runCurrent()
        assertEquals(emptyList<ClientFrame>(), sent)
    }
}
