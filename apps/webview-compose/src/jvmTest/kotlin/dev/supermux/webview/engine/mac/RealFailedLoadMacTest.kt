package dev.supermux.webview.engine.mac

import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.webkit.SavedWebKitState
import dev.supermux.webview.engine.webkit.WebKitScripts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * iOS's failed-load rules (final review, item 3) on real WebKit, in the test process: a page whose first load failed
 * (nothing listens on its port) loads again at a retry, saves no state, and a state whose page is the floor restores
 * nothing. On a Mac only.
 */
class RealFailedLoadMacTest {
    /** A loopback URL nothing answers: its load fails at once, as an app started offline sees its pages fail. */
    private fun unreachable(): WebContent.Url = WebContent.Url("http://127.0.0.1:${ServerSocket(0).use { it.localPort }}/page")

    @Test fun aRetryAfterAFailedFirstLoadLoadsTheContentAgain() = RealGlue().use { glue ->
        glue.run {
            val engine = glue.engine()
            val events = Events(engine, glue.scope)
            val content = unreachable()
            engine.configure(realSetup)
            engine.load(content)
            val first = events.next("the load's error") { it is EngineEvent.Error } as EngineEvent.Error
            assertEquals(content.url, first.error.url)
            assertNull(engine.saveState(), "WebKit's state is the floor's: restored, it would show nothing of the page")
            val before = events.seen.size
            // WebKit's own reload would reload the floor, the committed document, which the rules refuse: silence.
            engine.reload()
            val again = events.next("the retry's error") { it is EngineEvent.Error } as EngineEvent.Error
            assertEquals(content.url, again.error.url, "the retry loads the content: ${events.seen.drop(before)}")
            engine.release()
        }
    }

    @Test fun aStateSavedOnTheFloorRestoresNothing() = RealGlue().use { glue ->
        glue.run {
            val floor = floorState(glue)
            val engine = glue.engine()
            Events(engine, glue.scope)
            val content = unreachable()
            engine.configure(realSetup)
            assertFalse(engine.restoreState(SavedWebKitState.encode(content.key, floor), content), "a state whose page is the floor")
            engine.release()
        }
    }

    /** What WebKit holds for a warm engine's page, the floor alone: the glue's saveState, read past the engine's rules. */
    private suspend fun floorState(glue: RealGlue): ByteArray {
        val config = EngineConfig(WebProfile.Ephemeral)
        val scripts = WebKitScripts.random()
        val page = checkNotNull(NativePageGlue.create(config, scripts)) { "WebKit made no page" }
        val engine = MacWebEngine(
            config = config,
            glue = page,
            scripts = scripts,
            scope = CoroutineScope(SupervisorJob() + glue.dispatcher),
            windowHandle = { 0L },
            onReleased = { MacPages.unregister(page.token) },
        )
        val answer = CompletableDeferred<ByteArray?>()
        MacPages.register(page.token) { event ->
            if (event is GlueEvent.Result && event.request == FLOOR_REQUEST) answer.complete(event.data) else engine.onGlueEvent(event)
        }
        check(engine.warmUp()) { "the engine's floor didn't load" }
        page.saveState(FLOOR_REQUEST)
        val state = withTimeout(10_000) { answer.await() }
        engine.release()
        return checkNotNull(state) { "WebKit has no state for the floor" }
    }

    private companion object {
        /** A request id the engine never makes: its own count from 1. */
        const val FLOOR_REQUEST = Long.MAX_VALUE - 7
    }
}
