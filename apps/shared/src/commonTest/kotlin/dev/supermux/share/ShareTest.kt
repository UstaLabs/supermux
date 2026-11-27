package dev.supermux.share

import dev.supermux.host.HostSnapshot
import dev.supermux.net.BrokerApi
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ShareTest {
    private fun s(id: String, name: String, agent: Boolean = false, default: Boolean = false) =
        SessionInfo(id = id, name = name, workdir = "/w", agent = "claude", role = if (agent) "personal_assistant" else null, isDefault = default)

    private fun msg(ts: String, text: String) = LogEntry(id = ts, ts = ts, direction = "outbound", text = text)

    @Test fun agents_first_default_first_then_most_recent() {
        val snap = HostSnapshot(
            recordId = "h1",
            sessions = listOf(s("w", "Worker"), s("a", "Atlas", agent = true), s("t", "Tally", agent = true, default = true), s("j", "Job", agent = true)),
            lastMessages = mapOf("a" to msg("2026-11-27T10:00:00Z", "\n  road pass 3\nmore"), "j" to msg("2026-11-27T11:00:00Z", "replies")),
        )
        val all = ShareTargets.from(listOf(snap), mapOf("h1" to "GMKtec"))
        assertEquals(listOf("t", "j", "a", "w"), all.map { it.sessionId })
        assertEquals("road pass 3", all.first { it.sessionId == "a" }.preview)
        assertEquals("GMKtec", all.first().hostName)
        // A share to Atlas just now makes it the most recent.
        val after = ShareTargets.from(listOf(snap), emptyMap(), mapOf("h1/a" to 1_900_000_000_000L))
        assertEquals(listOf("t", "a", "j", "w"), after.map { it.sessionId })
    }

    @Test fun filter_by_tab_host_and_search() {
        val all = ShareTargets.from(
            listOf(HostSnapshot("h1", listOf(s("a", "Atlas", true), s("w", "Worker"))), HostSnapshot("h2", listOf(s("b", "Beta", true)))),
            emptyMap(),
        )
        assertEquals(listOf("a", "b"), ShareTargets.filter(all, agents = true, hostId = null, query = "").map { it.sessionId })
        assertEquals(listOf("b"), ShareTargets.filter(all, agents = true, hostId = "h2", query = "").map { it.sessionId })
        assertEquals(listOf("a"), ShareTargets.filter(all, agents = true, hostId = null, query = " atl").map { it.sessionId })
        assertEquals(listOf("w"), ShareTargets.filter(all, agents = false, hostId = null, query = "").map { it.sessionId })
    }

    @Test fun job_text_is_the_message_then_each_shared_text() {
        val job = ShareJob("web-share-1", "h", "s", "T", message = " look ", items = listOf(ShareItem(text = "https://x.y/1"), ShareItem(path = "/f", name = "a.jpg"), ShareItem(text = "https://x.y/1")))
        assertEquals("look\n\nhttps://x.y/1", job.text())
        assertTrue(ShareJob.newId(5).startsWith("web-share-5-"))
    }

    @Test fun outbox_round_trips_and_replaces_by_id() {
        var stored: String? = null
        val box = ShareOutbox({ stored }, { stored = it })
        box.put(ShareJob("web-1", "h", "s", "T"))
        box.put(ShareJob("web-2", "h", "s", "T"))
        box.put(ShareJob("web-1", "h", "s", "T", attempts = 3))
        assertEquals(listOf("web-2" to 0, "web-1" to 3), box.all().map { it.clientMessageId to it.attempts })
        box.remove("web-2")
        assertEquals(listOf("web-1"), box.all().map { it.clientMessageId })
    }

    private fun api(reqs: MutableList<HttpRequestData>, sendStatus: HttpStatusCode = HttpStatusCode.OK, sendBody: String = """{"ok":true}""") =
        BrokerApi("http://h", "tok", HttpClient(MockEngine { req ->
            reqs.add(req)
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (req.url.encodedPath == "/upload") respond(ByteReadChannel("""{"file_id":"f${reqs.size}","size":3}"""), HttpStatusCode.OK, json)
            else respond(ByteReadChannel(sendBody), sendStatus, json)
        }))

    @Test fun sender_uploads_files_then_posts_the_message_with_their_ids() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val job = ShareJob("web-share-9", "h", "s1", "T", message = "hi", items = listOf(ShareItem(path = "/a", name = "a.jpg", mime = "image/jpeg", size = 3), ShareItem(text = "https://l")))
        val r = ShareSender.send(api(reqs), job, { ByteArrayChunkSource(byteArrayOf(1, 2, 3)) })
        assertEquals(ShareResult.Sent, r)
        assertEquals(listOf("/upload", "/sessions/s1/send"), reqs.map { it.url.encodedPath })
        val body = (reqs[1].body as TextContent).text
        assertEquals("""{"text":"hi\n\nhttps://l","attachments":["f1"],"client_message_id":"web-share-9"}""", body)
    }

    @Test fun a_file_already_up_is_not_uploaded_again() = runTest {
        val reqs = mutableListOf<HttpRequestData>()
        val job = ShareJob("web-share-9", "h", "s1", "T", items = listOf(ShareItem(path = "/a", name = "a.jpg", size = 3, fileId = "done")))
        assertEquals(ShareResult.Sent, ShareSender.send(api(reqs), job, { error("must not open") }))
        assertEquals(listOf("/sessions/s1/send"), reqs.map { it.url.encodedPath })
    }

    @Test fun a_refusal_fails_for_good_and_a_server_error_retries_with_the_uploads_kept() = runTest {
        val job = ShareJob("web-share-9", "h", "s1", "T", items = listOf(ShareItem(path = "/a", name = "a.jpg", size = 3)))
        val gone = ShareSender.send(api(mutableListOf(), HttpStatusCode.NotFound, """{"error":"session not found"}"""), job, { ByteArrayChunkSource(byteArrayOf(1, 2, 3)) })
        assertEquals(ShareResult.Failed("session not found"), gone)
        val busy = ShareSender.send(api(mutableListOf(), HttpStatusCode.ServiceUnavailable, """{"error":"not configured"}"""), job, { ByteArrayChunkSource(byteArrayOf(1, 2, 3)) })
        assertIs<ShareResult.Retry>(busy)
        assertEquals("f1", busy.job.items[0].fileId)
        assertEquals(1, busy.job.attempts)
    }

    @Test fun an_unreachable_host_retries() = runTest {
        val dead = BrokerApi("http://h", "tok", HttpClient(MockEngine { throw RuntimeException("connect refused") }))
        val r = ShareSender.send(dead, ShareJob("web-share-9", "h", "s1", "T", message = "hi"), { null })
        assertIs<ShareResult.Retry>(r)
    }

    @Test fun a_missing_file_fails() = runTest {
        val r = ShareSender.send(api(mutableListOf()), ShareJob("web-share-9", "h", "s1", "T", items = listOf(ShareItem(path = "/gone", name = "x.pdf"))), { null })
        assertEquals(ShareResult.Failed("x.pdf is no longer there"), r)
    }
}
