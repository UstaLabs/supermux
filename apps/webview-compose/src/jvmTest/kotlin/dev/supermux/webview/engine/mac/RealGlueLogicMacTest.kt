package dev.supermux.webview.engine.mac

import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.webkit.WebKitScripts
import dev.supermux.webview.engine.webkit.WebKitUrls
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The glue's own logic, through the real glue and WebKit, with no engine between (the review: only the windowed checks
 * exercised it): the epochs it stamps, the questions it holds and answers exactly once, the scheme tasks it serves. A
 * question answered twice, or never, or a task touched after WebKit stopped it, raises in WebKit: the process would end.
 * On a Mac only.
 */
class RealGlueLogicMacTest {
    /** A page of the glue's own, its upcalls queued, its navigation and response questions answered yes as they come. */
    private class Page(val glue: NativePageGlue) {
        val seen = mutableListOf<GlueEvent>()
        val incoming = Channel<GlueEvent>(Channel.UNLIMITED)

        init {
            MacPages.register(glue.token) { event ->
                seen += event
                when (event) {
                    is GlueEvent.Policy -> glue.answerPolicy(event.question, allow = true, javaScript = true)
                    is GlueEvent.Response -> glue.answerResponse(event.question, allow = true)
                    else -> Unit
                }
                incoming.trySend(event)
            }
        }

        suspend fun next(what: String, millis: Long = 15_000, matches: (GlueEvent) -> Boolean): GlueEvent {
            val found = withTimeoutOrNull(millis) {
                var event = incoming.receive()
                while (!matches(event)) event = incoming.receive()
                event
            }
            return found ?: throw AssertionError("no $what within $millis ms; saw $seen")
        }

        fun release() {
            glue.release()
            MacPages.unregister(glue.token)
        }
    }

    private suspend fun page(scripts: WebKitScripts = WebKitScripts.random()): Page {
        val glue = checkNotNull(NativePageGlue.create(EngineConfig(WebProfile.Ephemeral), scripts)) { "WebKit made no page" }
        return Page(glue)
    }

    @Test fun eachNavigationsCallbacksAndItsDocumentsMessagesCarryItsEpoch() = RealGlue().use { real ->
        real.run {
            val scripts = WebKitScripts.random()
            val page = page(scripts)
            page.glue.configure(false, false, null, false, scripts.relay(emptySet()), null)
            for ((epoch, name) in listOf(5 to "five", 6 to "six")) {
                val mark = page.seen.size
                page.glue.loadHtml(epoch, "<!doctype html><title>$name</title><p>$name", "https://$name.invalid/")
                page.next("$name's finish") { it is GlueEvent.Finished }
                // The relay's start may come before the finish or after it.
                if (page.seen.drop(mark).none { it is GlueEvent.Relay }) page.next("$name's relay") { it is GlueEvent.Relay }
                val stamped = page.seen.drop(mark).mapNotNull {
                    when (it) {
                        is GlueEvent.Started -> "started ${it.epoch}"
                        is GlueEvent.Committed -> "committed ${it.epoch} ${it.page.url}"
                        is GlueEvent.Finished -> "finished ${it.epoch}"
                        is GlueEvent.Relay -> "relay ${it.epoch}"
                        else -> null
                    }
                }
                val expected = setOf("started $epoch", "committed $epoch https://$name.invalid/", "finished $epoch", "relay $epoch")
                assertEquals(expected, stamped.toSet(), "$name's navigation and its document's messages carry its epoch")
            }
            page.release()
        }
    }

    @Test fun aQuestionIsAnsweredOnceWhateverItHearsAndByDefaultAtRelease() = RealGlue().use { real ->
        real.run {
            val page = page()
            page.glue.loadHtml(1, "<!doctype html><title>one</title><p>one", "https://one.invalid/")
            val policy = page.next("the load's question") { it is GlueEvent.Policy } as GlueEvent.Policy
            // Answered yes as it came; a second answer, and one of another kind, find nothing to answer.
            page.glue.answerPolicy(policy.question, allow = false, javaScript = true)
            page.glue.answerDialog(policy.question, confirmed = true, text = null)
            assertEquals("https://one.invalid/", (page.next("the commit") { it is GlueEvent.Committed } as GlueEvent.Committed).page.url)
            page.next("the finish") { it is GlueEvent.Finished }
            // A dialog the page opens waits on the glue: release answers it by default.
            page.glue.evaluate(1, "setTimeout(function () { alert('hi'); }, 0); 1", pageWorld = true)
            val dialog = page.next("the alert") { it is GlueEvent.Dialog } as GlueEvent.Dialog
            assertEquals("hi", dialog.message)
            page.release()
            // An answer after the release goes nowhere: the page is gone, and the glue refuses calls.
            page.glue.answerDialog(dialog.question, confirmed = true, text = null)
            delay(1_000) // WebKit would raise meanwhile at a handler left unanswered, or answered twice
        }
    }

    @Test fun aSchemeTaskIsServedOnceAndNeverTouchedAfterWebKitStopsIt() = RealGlue().use { real ->
        real.run {
            val page = page()
            page.glue.loadUrl(1, "${WebKitUrls.BUNDLE_SCHEME}://plugin.invalid/index.html", emptyMap())
            val entry = page.next("the entry's task") { it is GlueEvent.TaskStart } as GlueEvent.TaskStart
            assertEquals("${WebKitUrls.BUNDLE_SCHEME}://plugin.invalid/index.html", entry.url)
            val html = "<!doctype html><title>entry</title><img src=\"slow.png\">".encodeToByteArray()
            page.glue.respond(entry.task, 200, "text/html", html, emptyMap())
            page.glue.respond(entry.task, 200, "text/html", html, emptyMap()) // answered: nothing to answer again
            assertEquals("${WebKitUrls.BUNDLE_SCHEME}://plugin.invalid/index.html", (page.next("the entry's commit") { it is GlueEvent.Committed } as GlueEvent.Committed).page.url)
            val image = page.next("the image's task") { it is GlueEvent.TaskStart } as GlueEvent.TaskStart
            assertTrue(image.url!!.endsWith("/slow.png"), image.url)
            // Another document: WebKit stops the old one's task, and the glue says so.
            page.glue.loadHtml(2, "<!doctype html><p>next", "https://next.invalid/")
            assertEquals(image.task, (page.next("the image's stop") { it is GlueEvent.TaskStop } as GlueEvent.TaskStop).task)
            page.glue.respond(image.task, 200, "image/png", ByteArray(8), emptyMap()) // stopped: never touched
            page.glue.failTask(image.task)
            // The next document may have committed before the old one's task was stopped.
            val next = page.seen.firstOrNull { it is GlueEvent.Committed && it.epoch == 2 }
                ?: page.next("the next commit") { it is GlueEvent.Committed && it.epoch == 2 }
            assertEquals("https://next.invalid/", (next as GlueEvent.Committed).page.url)
            page.release()
        }
    }
}
