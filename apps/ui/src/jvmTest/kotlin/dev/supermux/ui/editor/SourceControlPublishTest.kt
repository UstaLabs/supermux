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

    @Test fun publish_from_a_chat_sends_its_base_and_says_it_became_the_default() {
        val bodies = mutableListOf<String>()
        val engine = MockEngine { req ->
            val p = req.url.encodedPath
            val b = when {
                p.endsWith("/git/publish-new") -> { bodies += String((req.body as io.ktor.http.content.TextContent).bytes()); """{"ok":true,"url":"https://github.com/me/shop","base":"main"}""" }
                p.endsWith("/git/status") -> noRemote
                p.endsWith("/changes") -> """{"repos":[{"repo":"","baseSha":"h","files":[]}]}"""
                p.endsWith("/git/history") -> """{"branch":"fix","base":"main","chat":[{"sha":"c41e9d2","subject":"x"}],"baseCommits":[]}"""
                else -> "{}"
            }
            respond(ByteReadChannel(b), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val s = SourceControlState(SourceControlApi(BrokerApi("http://h", "t", HttpClient(engine)), "w1", "s1"))
        runBlocking { s.refresh(); s.sync("publish"); s.publishTo("github", "private") }
        assertTrue(bodies.single().contains("\"base\":\"main\""), bodies.single())
        assertEquals(localized(Str.scm_published_to_with_base, "https://github.com/me/shop", "main"), s.note)
    }

    @Test fun a_failure_says_it_plainly_and_keeps_the_tools_words_for_details() {
        val (s, _) = state(HttpStatusCode.Conflict, """{"ok":false,"reason":"error","message":"GraphQL: Something unexpected happened (createRepository)"}""")
        runBlocking { s.sync("publish"); s.publishTo("github", "private") }
        assertEquals(localized(Str.scm_publish_failed_plain, "GitHub"), s.publishError)
        assertEquals("GraphQL: Something unexpected happened (createRepository)", s.publishDetails)
    }

    /**
     * Ahmet's repo: published from the chat's branch (qweqweqweqw), so GitHub's default IS that branch, and main is only
     * here. The PR's target is never the branch itself: main, and since it isn't on GitHub, "Publish main", no form.
     */
    private fun prState(baseChecks: Map<String, String>, history: String = """{"branch":"qweqweqweqw","base":null,"chat":[{"sha":"c41e9d2","subject":"x"}],"baseCommits":[]}"""): Pair<SourceControlState, MutableList<String>> {
        val calls = mutableListOf<String>()
        val engine = MockEngine { req ->
            val p = req.url.encodedPath
            calls += p + "?" + req.url.encodedQuery
            val b = when {
                p.endsWith("/git/pr/base") -> baseChecks[req.url.parameters["target"].orEmpty()] ?: "{}"
                p.endsWith("/git/pr/text") -> """{"title":"Add the search box","body":"## What","by":"agent"}"""
                p.endsWith("/git/pr") -> """{"status":"none","forge":"github"}"""
                p.endsWith("/git/status") -> """{"isRepo":true,"hasRemote":true,"branch":"qweqweqweqw","upstream":"origin/qweqweqweqw"}"""
                p.endsWith("/changes") -> """{"repos":[{"repo":"","baseSha":"h","files":[]}]}"""
                p.endsWith("/git/history") -> history
                p.endsWith("/git/branches") -> """{"current":"qweqweqweqw","local":[{"name":"qweqweqweqw"},{"name":"main"}],"remote":[]}"""
                else -> "{}"
            }
            respond(ByteReadChannel(b), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val s = SourceControlState(SourceControlApi(BrokerApi("http://h", "t", HttpClient(engine)), "w1", "s1"))
        runBlocking { s.refresh() }
        return s to calls
    }

    private val originDefaultIsHead = """{"target":"qweqweqweqw","onRemote":true,"local":true,"ahead":0}"""

    @Test fun the_target_is_never_the_branch_itself_and_a_base_not_on_github_offers_publish_not_the_form() {
        val (s, _) = prState(mapOf("" to originDefaultIsHead, "main" to """{"target":"main","onRemote":false,"local":true,"ahead":1}"""))
        assertEquals("main", s.prTarget?.target)
        assertFalse(s.prTarget!!.onRemote)
        runBlocking { s.openPrForm() }
        assertNull(s.prForm, "no form that can only fail")
    }

    @Test fun once_the_target_is_on_github_the_form_opens_on_it_with_the_agents_text() {
        val (s, calls) = prState(mapOf("" to originDefaultIsHead, "main" to """{"target":"main","onRemote":true,"local":true,"ahead":1}"""))
        runBlocking { s.openPrForm() }
        assertEquals("main", s.prForm?.target)
        assertEquals("Add the search box", s.prForm?.title)
        assertEquals("## What", s.prForm?.body)
        assertTrue(calls.any { it.endsWith("/git/pr/text?") }, "the text is written for main")
    }

    @Test fun a_base_equal_to_the_branch_is_skipped_and_nothing_ahead_opens_no_form() {
        val (s, _) = prState(
            mapOf("" to originDefaultIsHead, "main" to """{"target":"main","onRemote":true,"local":true,"ahead":0}"""),
            history = """{"branch":"qweqweqweqw","base":"qweqweqweqw","chat":[{"sha":"c41e9d2","subject":"x"}],"baseCommits":[]}""",
        )
        assertEquals("main", s.prTarget?.target)
        runBlocking { s.openPrForm() }
        assertNull(s.prForm)
    }

    @Test fun the_forms_check_survives_the_poll_and_a_self_target_is_never_checked() {
        val (s, _) = prState(mapOf("" to originDefaultIsHead, "main" to """{"target":"main","onRemote":true,"local":true,"ahead":2}"""))
        runBlocking { s.openPrForm(); s.refresh() }
        assertEquals(2, s.prFormCheck?.ahead)
        runBlocking { s.checkPrTarget("qweqweqweqw") }
        assertNull(s.prFormCheck, "the branch itself: Create stays off")
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
        // A broker that says so up front: no form at all.
        assertFalse(gh.contains("Couldn't"))
    }
}
