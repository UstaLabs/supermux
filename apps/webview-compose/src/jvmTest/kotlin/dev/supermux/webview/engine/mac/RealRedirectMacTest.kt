package dev.supermux.webview.engine.mac

import dev.supermux.webview.NavigationRequest
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebError
import dev.supermux.webview.engine.EngineEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * I6 on real WebKit, against a loopback [TestServer], as Android's EngineRedirectTest: server redirects of the
 * engine's own load are followed to the web without asking the client, which blocks everything here; one off the web
 * ends the load with an error. On a Mac only.
 */
class RealRedirectMacTest {
    private fun isCommitOrError(event: EngineEvent) = event is EngineEvent.UrlChanged || event is EngineEvent.Error

    @Test fun aLoadThatTheServerRedirectsLandsOnTheFinalPageWithoutAskingTheClient() = TestServer().use { server ->
        TestServer().use { other ->
            server.page("/final", "final")
            server.redirect("/moved", 301, "/final")
            server.redirect("/found", 302, "/final")
            server.redirect("/temporary", 307, "/final")
            server.redirect("/twice", 302, "/found")
            other.page("/elsewhere", "elsewhere")
            server.redirect("/away", 302, "http://localhost:${other.port}/elsewhere")
            RealGlue().use { glue ->
                glue.run {
                    val engine = glue.engine()
                    val client = BlockingClient()
                    engine.client = client
                    val events = Events(engine, glue.scope)
                    engine.configure(realSetup)
                    for (path in listOf("/moved", "/found", "/temporary", "/twice")) {
                        engine.load(WebContent.Url(server.origin + path))
                        assertEquals(EngineEvent.UrlChanged("${server.origin}/final"), events.next("$path's commit", matches = ::isCommitOrError))
                    }
                    engine.load(WebContent.Url(server.origin + "/away"))
                    assertEquals(EngineEvent.UrlChanged("http://localhost:${other.port}/elsewhere"), events.next("/away's commit", matches = ::isCommitOrError))
                    assertEquals(emptyList<NavigationRequest>(), client.navigations, "a redirect of the app's own load isn't the client's")
                    engine.release()
                }
            }
        }
    }

    @Test fun aRedirectOffTheWebEndsTheLoadWithAnError() = TestServer().use { server ->
        server.redirect("/app", 302, "myapp://callback?code=1")
        server.page("/after", "after")
        RealGlue().use { glue ->
            glue.run {
                val engine = glue.engine()
                engine.client = BlockingClient()
                val events = Events(engine, glue.scope)
                engine.configure(realSetup)
                engine.load(WebContent.Url(server.origin + "/app"))
                assertEquals(
                    EngineEvent.Error(WebError.BlockedByPolicy("myapp://callback?code=1")),
                    events.next("the load's end", matches = ::isCommitOrError),
                )
                engine.load(WebContent.Url(server.origin + "/after"))
                assertEquals(EngineEvent.UrlChanged("${server.origin}/after"), events.next("the next load's commit", matches = ::isCommitOrError))
                engine.release()
            }
        }
    }

    /** An expired session: the page reloaded is redirected to a login page, which loads. */
    @Test fun aReloadThatTheServerRedirectsLandsOnTheLoginPage() = TestServer().use { server ->
        server.page("/account", "account")
        server.page("/login", "login")
        RealGlue().use { glue ->
            glue.run {
                val engine = glue.engine()
                val client = BlockingClient()
                engine.client = client
                val events = Events(engine, glue.scope)
                engine.configure(realSetup)
                engine.load(WebContent.Url(server.origin + "/account"))
                assertEquals(EngineEvent.UrlChanged("${server.origin}/account"), events.next("the account's commit", matches = ::isCommitOrError))
                server.redirect("/account", 302, "/login")
                engine.reload()
                assertEquals(EngineEvent.UrlChanged("${server.origin}/login"), events.next("the login page's commit", matches = ::isCommitOrError))
                assertTrue(client.navigations.isEmpty(), "${client.navigations}")
                engine.release()
            }
        }
    }
}
