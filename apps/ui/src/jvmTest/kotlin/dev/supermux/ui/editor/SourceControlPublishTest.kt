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
}
