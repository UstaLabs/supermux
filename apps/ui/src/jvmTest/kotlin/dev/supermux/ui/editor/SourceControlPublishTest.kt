package dev.supermux.ui.editor

import dev.supermux.net.BrokerApi
import dev.supermux.state.SourceControlApi
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #72: Publish Branch with no remote: the pick, and every way it can go, said in the pick (never a bare "not found"). */
class SourceControlPublishTest {
    private val noRemote = """{"isRepo":true,"hasRemote":false,"branch":"main","upstream":null,"project":"shop"}"""

    /** A broker whose publish-new / remote routes answer [code] with [body] (a plain "not found": a broker before them). */
    private fun state(code: HttpStatusCode, body: String, json: Boolean = true): Pair<SourceControlState, MutableList<String>> {
        val calls = mutableListOf<String>()
        val engine = MockEngine { req ->
            val p = req.url.encodedPath
            calls += "${req.method.value} $p"
            val (c, b) = when {
                p.endsWith("/git/publish-new") || p.endsWith("/git/remote") -> code to body
                p.endsWith("/git/status") -> HttpStatusCode.OK to noRemote
                p.endsWith("/changes") -> HttpStatusCode.OK to """{"repos":[{"repo":"","baseSha":"h","files":[]}]}"""
                p.endsWith("/git/history") -> HttpStatusCode.OK to """{"branch":"main","base":null,"chat":[{"sha":"c41e9d2","subject":"first"}],"baseCommits":[]}"""
                else -> HttpStatusCode.OK to "{}"
            }
            val type = if (json || c == HttpStatusCode.OK) "application/json" else "text/plain"
            respond(ByteReadChannel(b), c, headersOf(HttpHeaders.ContentType, type))
        }
        val s = SourceControlState(SourceControlApi(BrokerApi("http://h", "t", HttpClient(engine)), "w1", "s1"))
        runBlocking { s.refresh() }
        return s to calls
    }

    @Test fun publish_with_no_remote_opens_the_pick_named_after_the_project() {
        val (s, calls) = state(HttpStatusCode.OK, "{}")
        runBlocking { s.sync("publish") }
        assertTrue(s.publishing)
        assertEquals("shop", s.publishInput)
        assertTrue(s.publishDue)
        assertFalse(calls.any { it.endsWith("/git/publish") }, "no push that cannot work")
    }

    @Test fun a_broker_without_the_route_says_to_update_supermux_not_not_found() {
        val (s, _) = state(HttpStatusCode.NotFound, "not found", json = false)
        runBlocking { s.sync("publish"); s.publishTo("github", "private") }
        assertTrue(s.publishing, "the pick stays, with the reason in it")
        assertEquals(localized(Str.scm_needs_newer_host), s.publishError)
        assertNull(s.problem)
    }

    @Test fun no_cli_and_not_signed_in_say_what_to_run_on_the_host() {
        val (s, _) = state(HttpStatusCode.Conflict, """{"ok":false,"reason":"no_cli"}""")
        runBlocking { s.sync("publish"); s.publishTo("gitlab", "public") }
        assertEquals(localized(Str.scm_publish_no_cli, "GitLab", "glab"), s.publishError)
        val (t, _) = state(HttpStatusCode.Conflict, """{"ok":false,"reason":"not_authed"}""")
        runBlocking { t.sync("publish"); t.publishTo("github", "private") }
        assertEquals(localized(Str.scm_publish_not_authed, "GitHub", "gh"), t.publishError)
    }

    @Test fun a_published_repository_closes_the_pick_and_says_where() {
        val (s, calls) = state(HttpStatusCode.OK, """{"ok":true,"url":"https://github.com/me/shop"}""")
        runBlocking { s.sync("publish"); s.publishInput = "shop-web"; s.publishTo("github", "public") }
        assertFalse(s.publishing)
        assertEquals(localized(Str.scm_published_to, "https://github.com/me/shop"), s.note)
        assertTrue(calls.contains("POST /workspaces/w1/git/publish-new"))
    }

    @Test fun a_bad_name_is_refused_before_asking_the_host() {
        val (s, calls) = state(HttpStatusCode.OK, "{}")
        runBlocking { s.sync("publish"); s.publishInput = "my shop"; s.publishTo("github", "private") }
        assertEquals(localized(Str.scm_publish_bad_name), s.publishError)
        assertFalse(calls.any { it.endsWith("/git/publish-new") })
    }

    @Test fun add_remote_failures_stay_in_the_pick() {
        val (s, _) = state(HttpStatusCode.Conflict, """{"ok":false,"reason":"exists"}""")
        runBlocking { s.sync("publish"); s.publishByUrl(true); s.publishInput = "git@github.com:me/shop.git"; s.addRemote() }
        assertTrue(s.publishing && s.publishUrl)
        assertEquals(localized(Str.scm_remote_exists), s.publishError)
    }

    @Test fun publish_to_github_sends_only_the_branch_never_a_base() {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            val p = req.url.encodedPath
            val b = when {
                p.endsWith("/git/publish-new") -> { bodies += String((req.body as io.ktor.http.content.TextContent).bytes()); """{"ok":true,"url":"https://github.com/me/shop"}""" }
                p.endsWith("/git/status") -> noRemote
                p.endsWith("/changes") -> """{"repos":[{"repo":"","baseSha":"h","files":[]}]}"""
                p.endsWith("/git/history") -> """{"branch":"fix","base":"main","chat":[{"sha":"c41e9d2","subject":"x"}],"baseCommits":[]}"""
                else -> "{}"
            }
            respond(ByteReadChannel(b), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val s = SourceControlState(SourceControlApi(BrokerApi("http://h", "t", HttpClient(engine)), "w1", "s1"))
        runBlocking { s.refresh(); s.sync("publish"); s.publishTo("github", "private") }
        assertFalse(bodies.single().contains("base"), bodies.single())
        assertEquals(localized(Str.scm_published_to, "https://github.com/me/shop"), s.note)
    }

    @Test fun a_failure_says_it_plainly_and_keeps_the_tools_words_for_details() {
        val (s, _) = state(HttpStatusCode.Conflict, """{"ok":false,"reason":"error","message":"GraphQL: Something unexpected happened (createRepository)"}""")
        runBlocking { s.sync("publish"); s.publishTo("github", "private") }
        assertEquals(localized(Str.scm_publish_failed_plain, "GitHub"), s.publishError)
        assertEquals("GraphQL: Something unexpected happened (createRepository)", s.publishDetails)
    }

    /** A chat on `fix` whose host answers the PR calls as given; [create] answers POST …/git/pr in turn. */
    private fun prState(
        defaultCheck: String,
        create: List<Pair<HttpStatusCode, String>> = emptyList(),
        status: String = """{"isRepo":true,"hasRemote":true,"remotes":["origin"],"branch":"fix","upstream":"origin/fix"}""",
    ): Triple<SourceControlState, MutableList<String>, MutableList<String>> {
        val calls = mutableListOf<String>()
        val posted = mutableListOf<String>()
        var n = 0
        val engine = MockEngine { req ->
            val p = req.url.encodedPath
            calls += p + "?" + req.url.encodedQuery
            val body = (req.body as? io.ktor.http.content.TextContent)?.let { String(it.bytes()) }
            if (req.method.value != "GET" && body != null) posted += "$p $body"
            val (code, b) = when {
                p.endsWith("/git/pr") && req.method.value == "POST" -> create.getOrElse(n++) { HttpStatusCode.OK to """{"status":"opened","url":"u","number":1}""" }
                p.endsWith("/git/pr/base") -> HttpStatusCode.OK to defaultCheck
                p.endsWith("/git/pr/text") -> HttpStatusCode.OK to """{"title":"Add the search box","body":"- a box","by":"agent"}"""
                p.endsWith("/git/pr") -> HttpStatusCode.OK to """{"status":"none","forge":"github"}"""
                p.endsWith("/git/status") -> HttpStatusCode.OK to status
                p.endsWith("/changes") -> HttpStatusCode.OK to """{"repos":[{"repo":"","baseSha":"h","files":[]}]}"""
                p.endsWith("/git/history") -> HttpStatusCode.OK to """{"branch":"fix","base":"main","chat":[{"sha":"c41e9d2","subject":"x"}],"baseCommits":[]}"""
                else -> HttpStatusCode.OK to "{}"
            }
            respond(ByteReadChannel(b), code, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val s = SourceControlState(SourceControlApi(BrokerApi("http://h", "t", HttpClient(engine)), "w1", "s1"))
        runBlocking { s.refresh() }
        return Triple(s, calls, posted)
    }

    @Test fun the_form_opens_on_the_hosts_default_target_with_the_agents_text() {
        val (s, calls, _) = prState("""{"target":"main","onRemote":true,"ahead":1}""")
        runBlocking { s.openPrForm() }
        assertEquals("main", s.prForm?.target)
        assertEquals("Add the search box", s.prForm?.title)
        assertEquals(1, s.prFormCheck?.ahead)
        assertTrue(calls.any { it.contains("/git/pr/base?target=") }, "the host works out the default")
    }

    @Test fun a_target_not_on_github_still_opens_the_form_with_the_extensions_check() {
        val (s, _, _) = prState("""{"target":"main","onRemote":false}""")
        runBlocking { s.openPrForm() }
        assertEquals("main", s.prForm?.target)
        assertFalse(s.prFormCheck!!.onRemote)
    }

    @Test fun unpushed_commits_are_asked_about_and_push_is_sent_only_when_answered() {
        val (s, _, posted) = prState("""{"target":"main","onRemote":true,"ahead":3}""",
            create = listOf(HttpStatusCode.Conflict to """{"status":"ask","ask":"push","count":2}"""))
        runBlocking { s.openPrForm(); s.createPr() }
        assertEquals("push", s.prAsk?.ask)
        assertEquals(2, s.prAsk?.count)
        assertTrue(posted.last().contains("\"push\":false"), posted.last())
        runBlocking { s.createPr(push = true) }
        assertTrue(posted.last().contains("\"push\":true"), posted.last())
        assertNull(s.prForm, "opened")
    }

    @Test fun continue_without_pushing_and_always_are_sent_as_such_and_always_is_remembered() {
        val (s, _, posted) = prState("""{"target":"main","onRemote":true,"ahead":3}""",
            create = listOf(HttpStatusCode.Conflict to """{"status":"ask","ask":"push","count":1}""", HttpStatusCode.Conflict to """{"status":"ask","ask":"push","count":1}"""))
        runBlocking { s.openPrForm(); s.createPr(); s.createPr(skipPush = true) }
        assertTrue(posted.last().contains("\"skipPush\":true"), posted.last())
        runBlocking { s.openPrForm(); s.alwaysPushForPr() }
        assertEquals("always", s.prPush)
        assertTrue(posted.any { it.contains("/settings/config") && it.contains("\"gitPrPush\":\"always\"") }, posted.joinToString())
        assertTrue(posted.last().contains("\"publish\":true") && posted.last().contains("\"push\":true"), posted.last())
    }

    @Test fun publish_with_several_remotes_asks_which_and_publishes_there() {
        val (s, _, posted) = prState("{}", status = """{"isRepo":true,"hasRemote":true,"remotes":["fork","origin"],"branch":"fix","upstream":null}""")
        runBlocking { s.sync("publish") }
        assertTrue(s.pickingRemote)
        runBlocking { s.publishToRemote("fork") }
        assertFalse(s.pickingRemote)
        assertTrue(posted.any { it.endsWith("/git/publish {\"remote\":\"fork\"}") }, posted.joinToString())
    }

    /** Ahmet's asdasdwqe: master and qweqweqweqw began with their own first commits; GitHub refuses the PR. */
    @Test fun branches_with_no_history_in_common_are_said_plainly_with_gh_words_under_details() {
        val gh = "pull request create failed: GraphQL: The qweqweqweqw branch has no history in common with master (createPullRequest)"
        val engine = MockEngine { req ->
            val p = req.url.encodedPath
            val (code, b) = when {
                p.endsWith("/git/pr") && req.method.value == "POST" -> HttpStatusCode.Conflict to """{"status":"unavailable","forge":"github","reason":"error","message":"$gh"}"""
                p.endsWith("/git/pr/base") -> HttpStatusCode.OK to """{"target":"master","onRemote":true,"local":true,"ahead":3}"""
                p.endsWith("/git/pr/text") -> HttpStatusCode.OK to """{"title":"Add test files","body":"- api.ts","by":"agent"}"""
                p.endsWith("/git/pr") -> HttpStatusCode.OK to """{"status":"none","forge":"github"}"""
                p.endsWith("/git/status") -> HttpStatusCode.OK to """{"isRepo":true,"hasRemote":true,"branch":"qweqweqweqw","upstream":"origin/qweqweqweqw"}"""
                p.endsWith("/changes") -> HttpStatusCode.OK to """{"repos":[{"repo":"","baseSha":"h","files":[]}]}"""
                p.endsWith("/git/history") -> HttpStatusCode.OK to """{"branch":"qweqweqweqw","base":"master","chat":[{"sha":"c41e9d2","subject":"x"}],"baseCommits":[]}"""
                else -> HttpStatusCode.OK to "{}"
            }
            respond(ByteReadChannel(b), code, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val s = SourceControlState(SourceControlApi(BrokerApi("http://h", "t", HttpClient(engine)), "w1", "s1"))
        runBlocking { s.refresh(); s.openPrForm(); s.createPr() }
        val said = localized(Str.scm_pr_unrelated, "qweqweqweqw", "master")
        assertEquals(said, s.prProblem)
        assertEquals(said to gh, s.details)
    }
}
