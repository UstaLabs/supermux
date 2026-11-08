package dev.supermux.webview.engine.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.supermux.webview.NavigationDecision
import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.engine.EngineEvent
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Server redirects of the engine's own navigations (review item 1), on the phone, against a [TestServer]: they are
 * followed to the web without asking the client, which blocks everything here, and nowhere else.
 */
@RunWith(AndroidJUnit4::class)
class EngineRedirectTest {
    /** Loads [content] and expects its commit at [finalUrl], titled [title], and its first paint. */
    private suspend fun EngineHarness.loadsAt(content: WebContent.Url, finalUrl: String, title: String) {
        val mark = loadMarked(content)
        val commit = await("the commit of ${content.url}") { it is EngineEvent.UrlChanged || it is EngineEvent.Error }
        assertEquals(EngineEvent.UrlChanged(finalUrl), commit, "${content.url} lands at the redirect's target; saw ${seen.drop(mark)}")
        await("$finalUrl's first paint") { it == EngineEvent.FirstPaint }
        seenOrAwait("$finalUrl's title") { it == EngineEvent.TitleChanged(title) }
    }

    private suspend fun EngineHarness.redirectsAsked(client: RecordingClient): List<NavigationRequest> =
        main { client.asked.filterIsInstance<NavigationRequest>() }

    @Test fun aLoadThatTheServerRedirectsLandsOnTheFinalPage() = TestServer().use { server ->
        TestServer().use { other ->
            server.page("/final", titledPage("final"))
            server.redirect("/moved", 301, "/final")
            server.redirect("/found", 302, "/final")
            server.redirect("/temporary", 307, "/final")
            server.redirect("/twice", 302, "/found")
            other.page("/elsewhere", titledPage("elsewhere"))
            server.redirect("/away", 302, "http://localhost:${other.port}/elsewhere")
            EngineHarness.run {
                val client = RecordingClient(navigation = NavigationDecision.Block)
                main { this.client = client }
                for (path in listOf("/moved", "/found", "/temporary", "/twice")) {
                    loadsAt(WebContent.Url(server.origin + path), "${server.origin}/final", "final")
                }
                loadsAt(WebContent.Url(server.origin + "/away"), "http://localhost:${other.port}/elsewhere", "elsewhere")
                assertEquals(emptyList<NavigationRequest>(), redirectsAsked(client), "a redirect of the app's own load isn't the client's")
            }
        }
    }

    @Test fun aRedirectOffTheWebEndsTheLoad() = TestServer().use { server ->
        server.redirect("/app", 302, "myapp://callback?code=1")
        server.page("/after", titledPage("after"))
        EngineHarness.run {
            val content = WebContent.Url(server.origin + "/app")
            val mark = loadMarked(content)
            await("the error") { it is EngineEvent.Error }
            assertTrue(seen.drop(mark).none { it is EngineEvent.UrlChanged }, "nothing committed: ${seen.drop(mark)}")
            loadsAt(WebContent.Url(server.origin + "/after"), "${server.origin}/after", "after")
        }
    }

    /** An expired session: the page reloaded is redirected to a login page, which loads. */
    @Test fun aReloadThatTheServerRedirectsLandsOnTheFinalPage() = TestServer().use { server ->
        server.page("/account", titledPage("account"))
        server.page("/login", titledPage("login"))
        EngineHarness.run {
            main { client = RecordingClient(navigation = NavigationDecision.Block) }
            loadsAt(WebContent.Url(server.origin + "/account"), "${server.origin}/account", "account")
            server.redirect("/account", 302, "/login")
            main { reload() }
            await("the login page's commit") { it == EngineEvent.UrlChanged("${server.origin}/login") }
            await("the login page's first paint") { it == EngineEvent.FirstPaint }
        }
    }

    /** Back to a page that redirects now: the app went back, so the redirect is followed without asking the client. */
    @Test fun backToAPageThatRedirectsNowLandsOnTheFinalPage() = TestServer().use { server ->
        server.page("/first", titledPage("first"))
        server.page("/second", titledPage("second"))
        server.page("/login", titledPage("login"))
        EngineHarness.run {
            val client = RecordingClient(navigation = NavigationDecision.Block)
            main { this.client = client }
            loadsAt(WebContent.Url(server.origin + "/first"), "${server.origin}/first", "first")
            loadsAt(WebContent.Url(server.origin + "/second"), "${server.origin}/second", "second")
            server.redirect("/first", 302, "/login")
            main { goBack() }
            await("the login page's commit") { it == EngineEvent.UrlChanged("${server.origin}/login") }
            assertEquals(emptyList<NavigationRequest>(), redirectsAsked(client), "back is the app's, and so is its redirect")
        }
    }
}
