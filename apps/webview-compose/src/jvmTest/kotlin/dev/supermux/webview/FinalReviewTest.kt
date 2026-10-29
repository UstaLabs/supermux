package dev.supermux.webview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.WebEngine
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngine
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The final review's probes (Task 16b), kept as tests: A, B, C, D and H, which reproduced items 1–5. Each
 * asserts what the requirements ask, and ends with its fakes' contract held. H looks for the new document on whichever
 * engine shows it, and also checks that what the app sent reached that document.
 */
@OptIn(ExperimentalTestApi::class)
class FinalReviewTest {
    private val url = WebContent.Url("https://example.test/")
    private val notes = WebContent.Html("<p>notes</p>", WebOrigin("notes"))

    private fun <T> ComposeUiTest.ui(block: () -> T): T = runOnUiThread(block)

    private fun ComposeUiTest.calls(engine: FakeWebEngine): List<String> = ui { engine.calls.toList() }

    /** The engine contract held: no engine was used against it (see [FakeWebEngine.violations]). */
    private fun ComposeUiTest.assertNoViolations(factory: FakeWebEngineFactory) = ui {
        factory.created.forEach { assertEquals(emptyList(), it.violations, "engine ${it.id}'s violations") }
    }

    private fun ComposeUiTest.waitFor(what: String, timeout: Long = TIMEOUT, condition: () -> Boolean) =
        waitUntil(conditionDescription = what, timeoutMillis = timeout, condition = condition)

    // A: rememberWebViewState must not undo what the app loaded into the state.
    @Test fun a_aRememberedStateKeepsWhatTheAppLoaded() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        var state: WebViewState? = null
        setContent {
            ProvideWebViewRuntime(runtime) {
                val s = rememberWebViewState(url)
                state = s
                Column {
                    // A URL bar: the screen reads the state, as browsers' toolbars do.
                    BasicText(s.url ?: "-")
                    WebView(s, Modifier.size(100.dp))
                }
            }
        }
        waitFor("live") { state?.phase == WebViewPhase.Live }
        val engine = ui { factory.created.single() }
        runOnIdle { state!!.navigator.load("https://other.test/") }
        waitForIdle()
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        val loads = calls(engine).filter { it.startsWith("load:") }
        assertEquals("https://other.test/", (ui { state!!.content } as WebContent.Url).url, "the app's load was undone; loads=$loads")
        assertEquals(listOf("load:${url.key}", "load:url:https://other.test/"), loads)
        assertNoViolations(factory)
    }

    // B: after a hand-off, the state never points at an engine its owner doesn't hold.
    @Test fun b_aMovedStateNeverReachesAnotherViewsEngine() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        var mode = "pass"
        val gate = CompletableDeferred<Unit>()
        val factory = WebEngineFactory { config ->
            when (mode) {
                "fail" -> WebEngineResult.Unavailable(UnavailableReason.CreationFailed, "probe")
                "gate" -> {
                    gate.await()
                    fake.create(config)
                }
                else -> fake.create(config).also { mode = "fail" } // the first creation only: no prewarmed spare
            }
        }
        val runtime = testRuntime(factory, maxLive = 3, maxIdle = 2)
        val state = WebViewState(notes)
        val other = WebViewState(WebContent.Html("<p>other</p>", WebOrigin("other")))
        var wide by mutableStateOf(false)
        setContent {
            ProvideWebViewRuntime(runtime) {
                Column {
                    if (wide) Row { WebView(state, Modifier.size(100.dp)) } else Column { WebView(state, Modifier.size(100.dp)) }
                    WebView(other, Modifier.size(100.dp).testTag("other"), activation = Activation.OnInteraction)
                }
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val e1 = ui { fake.created.single() }
        val saveGate = CompletableDeferred<Unit>()
        try {
            ui {
                e1.saveStateGate = saveGate
                mode = "gate"
            }
            val oldHost = ui { state.owner }
            wide = true
            // The new view waits up to 1 s for the old one's save, then takes the state and asks for an engine.
            waitFor("taken over") { ui { state.owner != null && state.owner !== oldHost } }
            ui { saveGate.complete(Unit) }
            waitFor("the old engine is a spare") { ui { runtime.pool.idleCount } == 1 }
            onNodeWithTag("other").performClick()
            waitFor("the other view live") { other.phase == WebViewPhase.Live }
            val stale = ui { state.engine }
            if (stale != null) {
                ui { state.navigator.reload() }
                waitForIdle()
            }
            assertNull(stale, "the state points at an engine it doesn't hold (the other view's: ${stale === ui { other.engine }})")
            assertTrue(!ui { state.bridge.isConnected }, "the moved state's bridge still reaches the old view's engine")
            assertTrue(calls(e1).none { it == "reload" }, "the state reloaded another view's page")
        } finally {
            ui {
                saveGate.complete(Unit)
                gate.complete(Unit)
            }
        }
        waitFor("the moved view live") { state.phase == WebViewPhase.Live }
        assertNoViolations(fake)
    }

    // C: a profile is a user's cookies and storage; nothing of one profile's page may reach another's.
    @Test fun c_switchingProfilesCarriesNothingAcross() = runComposeUiTest {
        val factory = FakeWebEngineFactory(behavior = FakeEngineBehavior(pageState = JsonPrimitive("alice's draft")))
        val runtime = testRuntime(factory)
        val state = WebViewState(url)
        var profile by mutableStateOf<WebProfile>(WebProfile.Named("alice"))
        setContent {
            ProvideWebViewRuntime(runtime) {
                WebView(state, Modifier.size(100.dp), settings = WebSettings(profile = profile, bridgeOrigins = setOf("https://example.test")))
            }
        }
        waitFor("live") { state.phase == WebViewPhase.Live && ui { state.bridge.isReady } }
        ui { factory.behavior = FakeEngineBehavior() }
        profile = WebProfile.Named("bob")
        waitFor("live on bob's engine") { ui { factory.created.size } == 2 && state.phase == WebViewPhase.Live }
        val bob = ui { factory.created.last() }
        assertEquals(WebProfile.Named("bob"), bob.config.profile)
        val leaked = calls(bob).filter { it.startsWith("restore:") || ("\$restoreState" in it && "alice" in it) }
        assertEquals(emptyList(), leaked, "alice's engine state or page state reached bob's engine")
        assertNoViolations(factory)
    }

    // D: a navigator call into an engine that throws must not throw into the app.
    @Test fun d_anEngineThatThrowsFromReloadDoesNotThrowIntoTheApp() = runComposeUiTest {
        val fake = FakeWebEngineFactory()
        val runtime = testRuntime(
            WebEngineFactory { config ->
                val engine = (fake.create(config) as WebEngineResult.Created).engine
                WebEngineResult.Created(
                    object : WebEngine by engine {
                        override fun reload() = throw IllegalStateException("probe: reload failed")
                    },
                )
            },
        )
        val errors = mutableListOf<Throwable>()
        ui { runtime.onError = { errors += it } }
        val state = WebViewState(url)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live") { state.phase == WebViewPhase.Live }
        val thrown = runCatching { ui { state.navigator.reload() } }.exceptionOrNull()
        assertNull(thrown, "the engine's exception reached the app")
        // Treated as a crash, as any engine that throws: reported, and destroyed once its surface has gone.
        waitFor("crashed") { state.phase == WebViewPhase.Crashed }
        waitFor("destroyed") { ui { fake.created.single().released } }
        assertEquals(listOf("probe: reload failed"), ui { errors.map { it.message } })
        assertNoViolations(fake)
    }

    // H: what the app sends right after loading new content is for that content, never for the page being replaced.
    @Test fun h_whatTheAppSendsAfterALoadNeverReachesThePreviousPage() = runComposeUiTest {
        val factory = FakeWebEngineFactory()
        val runtime = testRuntime(factory)
        val a = WebContent.Html("<p>plugin A</p>", WebOrigin("plugin-a"))
        val b = WebContent.Html("<p>plugin B</p>", WebOrigin("plugin-b"))
        val state = WebViewState(a)
        setContent { ProvideWebViewRuntime(runtime) { WebView(state, Modifier.size(100.dp)) } }
        waitFor("live and ready") { state.phase == WebViewPhase.Live && ui { state.bridge.isReady } }
        var sent = false
        runOnIdle {
            state.load(b)
            sent = state.bridge.post("config", JsonPrimitive("B's token"))
        }
        waitFor("B live and ready") {
            ui { factory.created.any { engine -> engine.calls.any { it == "load:${b.key}" } } } &&
                state.phase == WebViewPhase.Live && ui { state.bridge.isReady }
        }
        waitForIdle()
        assertTrue(sent, "the post was refused instead of waiting for B's document")
        // Each engine's posts, by the content its document showed when they arrived.
        val received = ui {
            factory.created.flatMap { engine ->
                var showing: String? = null
                engine.calls.mapNotNull { call ->
                    if (call.startsWith("load:")) showing = call.removePrefix("load:")
                    if (call.startsWith("post:") && "B's token" in call) showing else null
                }
            }
        }
        assertEquals(listOf(b.key), received, "B's data went to another document, or never reached B's")
        assertNoViolations(factory)
    }
}
