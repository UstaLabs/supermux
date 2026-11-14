package dev.supermux.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** #72 Source Control: the broker's git answers as it sends them, refusals (409) included. */
class BrokerApiSourceControlTest {

    private val seen = mutableListOf<Triple<HttpMethod, String, String>>()

    private fun api(body: String, status: HttpStatusCode = HttpStatusCode.OK): BrokerApi {
        val engine = MockEngine { req ->
            seen += Triple(req.method, req.url.encodedPath + (req.url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""), req.body.toByteArray().decodeToString())
            respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return BrokerApi("http://h", "tok", HttpClient(engine))
    }

    @Test fun discard_sends_paths_or_all_never_both() = runTest {
        val r = api("""{"ok":true,"discarded":["a.txt"]}""").gitDiscard("workspaces", "w1", DiscardRequest("", paths = listOf("a.txt")))
        assertEquals(listOf("a.txt"), r.discarded)
        assertEquals("/workspaces/w1/git/discard", seen.last().second)
        assertEquals("""{"repo":"","paths":["a.txt"]}""", seen.last().third)
        api("""{"ok":true,"discarded":[]}""").gitDiscard("sessions", "s1", DiscardRequest("", all = true))
        assertEquals("""{"repo":"","all":true}""", seen.last().third)
    }

    @Test fun a_refused_discard_says_which_files_clash() = runTest {
        val r = api("""{"ok":false,"reason":"conflict","paths":["a.txt"]}""", HttpStatusCode.Conflict).gitDiscard("sessions", "s1", DiscardRequest("", paths = listOf("a.txt")))
        assertEquals(false, r.ok)
        assertEquals("conflict", r.reason)
        assertEquals(listOf("a.txt"), r.paths)
    }

    @Test fun latest_carries_before_and_after_for_its_undo() = runTest {
        val r = api("""{"status":"clean","from":"origin/dev","commits":3,"before":"aaa1111","after":"bbb2222"}""").gitLatest("s1")
        assertEquals("aaa1111" to "bbb2222", r.before to r.after)
        val u = api("""{"ok":false,"reason":"moved"}""", HttpStatusCode.Conflict).gitLatestUndo("s1", "aaa1111", "bbb2222")
        assertEquals("moved", u.reason)
        assertEquals("""{"before":"aaa1111","after":"bbb2222"}""", seen.last().third)
    }

    @Test fun branches_say_where_each_is_open() = runTest {
        val b = api("""{"current":"mux/one","detachedSha":null,
            "local":[{"name":"mux/one","isDefault":false,"upstream":"origin/mux/one","ahead":1,"behind":2,"openIn":{"kind":"this"}},
                     {"name":"mux/two","isDefault":false,"upstream":null,"ahead":0,"behind":0,"openIn":{"kind":"session","id":"s-2","name":"Two"}},
                     {"name":"dev","isDefault":true,"upstream":null,"ahead":0,"behind":0,"openIn":{"kind":"folder"}},
                     {"name":"feature/x","isDefault":false,"upstream":null,"ahead":0,"behind":0,"openIn":null}],
            "remote":[{"name":"origin/dev","isDefault":true}]}""").gitBranches("s1")
        assertEquals("mux/one", b.current)
        assertEquals(1 to 2, b.local[0].ahead to b.local[0].behind)
        assertEquals(GitOpenIn("session", "s-2", "Two"), b.local[1].openIn)
        assertEquals(true, b.local[2].isDefault)
        assertEquals(null, b.local[3].openIn)
        assertEquals(GitRemoteBranch("origin/dev", isDefault = true), b.remote.single())
        val sw = api("""{"status":"checked_out_elsewhere","path":"/w2","openIn":{"kind":"session","id":"s-2","name":"Two"}}""").gitSwitch("s1", "mux/two", create = false)
        assertEquals("checked_out_elsewhere", sw.status)
        assertEquals("s-2", sw.openIn?.id)
        assertEquals("""{"name":"mux/two","create":false}""", seen.last().third)
    }

    @Test fun a_pull_request_reads_with_its_review_checks_and_comments() = runTest {
        val r = api("""{"status":"found","pr":{"forge":"gitlab","number":7,"url":"u","title":"T","state":"open","draft":true,"target":"dev","head":"mux/x",
            "review":{"state":"required","by":["deniz"],"got":1,"need":2},
            "checks":[{"name":"test","state":"bad","url":"https://gitlab.com/a/b/-/jobs/5"}],
            "comments":[{"who":"deniz","body":"Debounce?","file":"src/App.jsx","line":17}]}}""").gitPr("sessions", "s1", fresh = true)
        assertEquals("/sessions/s1/git/pr?fresh=1", seen.last().second)
        val pr = r.pr!!
        assertEquals(1 to 2, pr.review.got to pr.review.need)
        assertEquals("bad", pr.checks.single().state)
        assertEquals(17, pr.comments.single().line)
        assertEquals("none", api("""{"status":"none","forge":"github"}""").gitPr("workspaces", "w1").status)
    }

    @Test fun create_text_ready_and_a_check_log() = runTest {
        val c = api("""{"status":"opened","url":"https://github.com/a/b/pull/13","number":13}""").gitPrCreate("sessions", "s1", PrCreateRequest("T", "B", "dev", draft = true))
        assertEquals(13, c.number)
        assertEquals("""{"title":"T","body":"B","target":"dev","draft":true}""", seen.last().third)
        val t = api("""{"title":"Search by name","body":"## What","by":"agent"}""").gitPrText("sessions", "s1", null)
        assertEquals("agent", t.by)
        assertEquals("{}", seen.last().third)
        assertTrue(api("""{"ok":true}""").gitPrReady("workspaces", "w1", 13).ok)
        val log = api("""{"ok":true,"log":"ERROR"}""").gitPrCheckLog("sessions", "s1", "https://github.com/a/b/actions/runs/1/job/2")
        assertEquals("ERROR", log.log)
        assertTrue(seen.last().second.startsWith("/sessions/s1/git/pr/check-log?url=https"), seen.last().second)
    }

    @Test fun the_changes_list_carries_the_index_blob_and_the_merge_flag_into_the_pane() {
        val r = ChangesResult(repos = listOf(RepoChanges(repo = "", merging = true, files = listOf(ChangedFile(path = "a.txt", status = "modified", staged = "partial", indexBlob = "abc")))))
            .toFsDiffResult()
        assertEquals(true, r.repos.single().merging)
        assertEquals("abc", r.repos.single().files.single().indexBlob)
    }
}
