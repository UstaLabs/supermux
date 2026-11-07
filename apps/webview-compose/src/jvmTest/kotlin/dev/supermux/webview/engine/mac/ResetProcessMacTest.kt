package dev.supermux.webview.engine.mac

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.engine.EngineEvent
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A reset between two pages of a bundle keeps the page in its WebContent process (a user's report, 2026-11-05: "a kind of
 * freeze" scrolling the chat). WebKit gives a navigation to another scheme a new WebContent process, and starting one can
 * hold AppKit's thread in the kernel for seconds (it issues the new process its sandbox extensions for the user's fonts):
 * with the floor on a scheme of its own, every reset of a chat preview and the load after it started one, and two resets
 * timed out at 4 s in such a stall. On real WebKit, on a Mac only.
 */
class ResetProcessMacTest {
    private val origin = WebOrigin("reset-process")

    /** Every path a small page naming itself. */
    private val files = WebResourceProvider { path ->
        WebResource("<!doctype html><title>$path</title><p>$path".encodeToByteArray(), "text/html")
    }

    @Test fun aResetBetweenTwoPagesOfABundleKeepsTheirWebContentProcess() = RealGlue().use { real ->
        real.run {
            val (engine, glue) = real.engineAndGlue()
            val events = Events(engine, real.scope)
            engine.configure(realSetup)
            engine.load(WebContent.Bundle(origin, files, "a.html"))
            events.next("a.html's commit") { it == EngineEvent.UrlChanged("${origin.url}a.html") }
            val first = glue.webProcess()
            assumeTrue("WebKit names the page's WebContent process", first > 0)
            assertTrue(engine.reset(), "the reset")
            val floor = glue.webProcess()
            engine.configure(realSetup)
            engine.load(WebContent.Bundle(origin, files, "b.html"))
            events.next("b.html's commit") { it == EngineEvent.UrlChanged("${origin.url}b.html") }
            val second = glue.webProcess()
            assertEquals(listOf(first, first), listOf(floor, second), "the floor's process, then the next page's: a.html's was $first")
            engine.release()
        }
    }
}
