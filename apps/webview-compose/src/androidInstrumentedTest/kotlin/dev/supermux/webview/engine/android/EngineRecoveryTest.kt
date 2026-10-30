package dev.supermux.webview.engine.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.WebContent
import dev.supermux.webview.bridge.BridgeCodec
import dev.supermux.webview.bridge.BridgeMessage
import dev.supermux.webview.bridge.WebBridge
import dev.supermux.webview.engine.EngineEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recovering after the engine's own load fails without committing, or is still under way (review item 3), on the
 * phone, against a [TestServer]: a 204 ends a load without a commit. A retry loads the content again, never the page
 * before it, and a step back brings a page the runtime hears: its URL, its title and its bridge.
 */
@RunWith(AndroidJUnit4::class)
class EngineRecoveryTest {
    private fun echoPage(title: String): String = """<!doctype html><html><head><title>$title</title>
<script>hostBridge.handle('echo', function (p) { return p; });</script></head>
<body style="margin:0;padding:16px;font:16px sans-serif">$title</body></html>"""

    private fun nonceOf(event: EngineEvent): String? {
        val message = event as? EngineEvent.PageMessage ?: return null
        if (!message.json.startsWith(WebBridge.READY_PREFIX)) return null
        return BridgeCodec.decodeEnvelope(message.json)?.from
    }

    private fun replyOf(event: EngineEvent, id: Long): BridgeMessage.Reply? {
        val message = event as? EngineEvent.PageMessage ?: return null
        return (BridgeCodec.decodeEnvelope(message.json)?.message as? BridgeMessage.Reply)?.takeIf { it.id == id }
    }

    private fun TestServer.url(path: String) = WebContent.Url(origin + path)

    /** Loads [path], on a lease whose bridge is for the server's origin, and waits for its commit, paint and title. */
    private suspend fun EngineHarness.loadsAt(server: TestServer, path: String, title: String) {
        val content = server.url(path)
        loadMarked(content, setupFor(content, bridgeOrigins = setOf(server.origin)))
        await("the commit of $path") { it == EngineEvent.UrlChanged(server.origin + path) }
        await("$path's first paint") { it == EngineEvent.FirstPaint }
        seenOrAwait("$path's title") { it == EngineEvent.TitleChanged(title) }
    }

    /** Starts loading [path], which fails or hangs, and returns the mark of what came after. */
    private suspend fun EngineHarness.startLoading(server: TestServer, path: String): Int {
        val content = server.url(path)
        return loadMarked(content, setupFor(content, bridgeOrigins = setOf(server.origin)))
    }

    /** The first event since [mark] that matches [predicate], or the next one to arrive. */
    private suspend fun EngineHarness.since(mark: Int, what: String, predicate: (EngineEvent) -> Boolean): EngineEvent =
        seen.drop(mark).firstOrNull(predicate) ?: await(what, predicate = predicate)

    /** The page that came back since [mark] is [path], titled [title], and answers a bridge call. */
    private suspend fun EngineHarness.cameBack(server: TestServer, mark: Int, path: String, title: String) {
        since(mark, "$path's commit") { it == EngineEvent.UrlChanged(server.origin + path) }
        since(mark, "$path's first paint") { it == EngineEvent.FirstPaint }
        since(mark, "$path's title") { it == EngineEvent.TitleChanged(title) }
        val nonce = nonceOf(since(mark, "$path's \$ready") { nonceOf(it) != null })!!
        main { postMessage(BridgeCodec.encode(BridgeMessage.Call(7, "echo", JsonPrimitive("back")), to = nonce), server.origin) }
        val reply = await("$path's answer") { replyOf(it, 7) != null }
        assertEquals(JsonPrimitive("back"), replyOf(reply, 7)!!.result)
    }

    @Test fun aLoadThatFailedIsRetriedByAReload() = TestServer().use { server ->
        server.page("/first", echoPage("first"))
        server.route("/flaky", TestServer.Response(204))
        EngineHarness.run {
            loadsAt(server, "/first", "first")
            val mark = startLoading(server, "/flaky")
            await("the failed load's error") { it is EngineEvent.Error }
            server.page("/flaky", echoPage("flaky, up now"))
            main { reload() }
            await("the retried page's commit") { it == EngineEvent.UrlChanged(server.origin + "/flaky") }
            await("its first paint") { it == EngineEvent.FirstPaint }
            seenOrAwait("its title") { it == EngineEvent.TitleChanged("flaky, up now") }
            val after = seen.drop(mark)
            assertTrue(after.none { it == EngineEvent.UrlChanged(server.origin + "/first") }, "the page before was reloaded: $after")
        }
    }

    @Test fun aLeasesFirstLoadThatFailedIsRetriedByAReload() = TestServer().use { server ->
        server.page("/first", echoPage("first"))
        server.route("/flaky", TestServer.Response(204))
        EngineHarness.run {
            loadsAt(server, "/first", "first")
            assertTrue(main { reset() })
            val mark = startLoading(server, "/flaky")
            await("the failed load's error") { it is EngineEvent.Error }
            server.page("/flaky", echoPage("flaky, up now"))
            main { reload() }
            await("the retried page's commit") { it == EngineEvent.UrlChanged(server.origin + "/flaky") }
            await("its first paint") { it == EngineEvent.FirstPaint }
            val after = seen.drop(mark)
            assertTrue(after.none { (it as? EngineEvent.UrlChanged)?.url?.startsWith("about:") == true }, "the reset's page was reloaded: $after")
        }
    }

    /**
     * The final review's item 3 on Android: after a switch to content that never committed (a 204), WebView still shows
     * the page before it, whose history was saved under the new content's key, and a view restoring it showed the old
     * page in the new one's place. Nothing is saved until the content commits.
     */
    @Test fun aSwitchThatNeverCommittedSavesNoState() = TestServer().use { server ->
        server.page("/first", echoPage("first"))
        server.route("/gone", TestServer.Response(204))
        EngineHarness.run {
            loadsAt(server, "/first", "first")
            assertNotNull(main { saveState() }, "the first page's state")
            startLoading(server, "/gone")
            await("the failed load's error") { it is EngineEvent.Error }
            assertNull(main { saveState() }, "the page before's history, under the new content's key")
        }
    }

    /** A switch still under way saves nothing either: WebView's history is still the page before's. */
    @Test fun aSwitchStillLoadingSavesNoState() = TestServer().use { server ->
        server.page("/first", echoPage("first"))
        server.route("/slow", TestServer.Response(200, echoPage("slow"), delayMillis = 8_000))
        EngineHarness.run {
            loadsAt(server, "/first", "first")
            startLoading(server, "/slow")
            delay(300)
            assertNull(main { saveState() }, "the page before's history, under the new content's key")
        }
    }

    @Test fun backWhileALoadIsUnderWayBringsBackAPageTheRuntimeHears() = TestServer().use { server ->
        server.page("/first", echoPage("first"))
        server.page("/second", echoPage("second"))
        server.route("/slow", TestServer.Response(200, echoPage("slow"), delayMillis = 8_000))
        EngineHarness.run {
            loadsAt(server, "/first", "first")
            loadsAt(server, "/second", "second")
            startLoading(server, "/slow")
            delay(300)
            val mark = main {
                goBack()
                seen.size
            }
            cameBack(server, mark, "/first", "first")
        }
    }

    @Test fun backAfterALoadFailedBringsBackAPageTheRuntimeHears() = TestServer().use { server ->
        server.page("/first", echoPage("first"))
        server.page("/second", echoPage("second"))
        server.route("/gone", TestServer.Response(204))
        EngineHarness.run {
            loadsAt(server, "/first", "first")
            loadsAt(server, "/second", "second")
            startLoading(server, "/gone")
            await("the failed load's error") { it is EngineEvent.Error }
            val mark = main {
                goBack()
                seen.size
            }
            cameBack(server, mark, "/first", "first")
        }
    }
}
