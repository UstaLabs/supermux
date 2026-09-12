package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.net.BrokerApi
import dev.supermux.net.CreateProxyResponse
import dev.supermux.net.ProxyDto
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.FakeSettingsStore
import dev.supermux.ui.chat.FixedClock
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * The shared [ProxiesSettingsScreen] (cluster E4) — desktop's suite, moved by name.
 *
 * Covers load Error vs Empty, the create form, the make-public confirm, the remove confirm and the
 * failure paths of all three against a real `HostStore` over a mocked `BrokerApi` — plus the
 * Compact branch Android contributed. The `AppShell` hub wiring stays in `:desktop`
 * (`ProxiesSettingsHubTest`).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ProxiesSettingsScreenTest {

    private fun ComposeUiTest.proxiesContent(
        pointer: Boolean = true,
        widthClass: WindowWidthClass = WindowWidthClass.Expanded,
        content: @Composable () -> Unit,
    ) = setPlatformContent(platform = FakePlatform(), pointer = pointer, widthClass = widthClass) {
        content()
    }

    /** The holder is an E4 detail; every test keeps naming the five calls it always named. */
    @Composable
    private fun ProxiesScreenUnderTest(
        proxiesLoad: suspend () -> List<ProxyDto>?,
        sessionNames: () -> List<String>,
        proxyCreate: suspend (String, Int, String?) -> CreateProxyResponse?,
        proxySetPublic: suspend (String, Boolean) -> Boolean,
        proxyRemove: suspend (String) -> Boolean,
        topBarShown: Boolean = true,
    ) = ProxiesSettingsScreen(
        actions = ProxiesSettingsActions(
            proxiesLoad, flowOf(sessionNames()), proxyCreate, proxySetPublic, proxyRemove,
        ),
        topBarShown = topBarShown,
    )

    private fun uiTestDeps(client: HttpClient) = HostStoreDeps(
        httpFactory = { client },
        settings = FakeSettingsStore(),
        clock = FixedClock(),
    )

    private fun sampleProxies() = listOf(
        ProxyDto(
            domain = "app.example.local",
            sessionName = "web",
            port = 3000,
            isPublic = false,
            url = "https://app.example.local",
        ),
        ProxyDto(
            domain = "api.example.local",
            sessionName = "api",
            port = 8080,
            isPublic = true,
            url = "https://api.example.local",
        ),
    )

    private fun screen(
        proxiesLoad: suspend () -> List<ProxyDto>? = { sampleProxies() },
        sessionNames: () -> List<String> = { listOf("web", "api") },
        proxyCreate: suspend (String, Int, String?) -> CreateProxyResponse? = { _, _, _ -> null },
        proxySetPublic: suspend (String, Boolean) -> Boolean = { _, _ -> true },
        proxyRemove: suspend (String) -> Boolean = { true },
    ) = @Composable {
        ProxiesScreenUnderTest(
            proxiesLoad = proxiesLoad,
            sessionNames = sessionNames,
            proxyCreate = proxyCreate,
            proxySetPublic = proxySetPublic,
            proxyRemove = proxyRemove,
        )
    }

    @Test fun proxies_render_from_a_fake_list() = runComposeUiTest {
        proxiesContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_row_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxies_settings_screen").assertIsDisplayed()
        onNodeWithTag("proxy_row_api.example.local").assertIsDisplayed()
        onNodeWithTag("proxy_target_app.example.local").assertIsDisplayed()
        onNodeWithText("public").assertIsDisplayed()
        onNodeWithText("private").assertIsDisplayed()
        onNodeWithTag("proxies_expose_button").assertIsDisplayed()
    }

    @Test fun load_failure_shows_error_with_retry_not_empty() = runComposeUiTest {
        val loads = AtomicInteger(0)
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(proxiesLoad = {
                    loads.incrementAndGet()
                    null
                })()
            }
        }
        waitForIdle()
        onNodeWithTag("proxies_settings_error").assertIsDisplayed()
        onNodeWithTag("proxies_settings_retry").assertIsDisplayed()
        onNodeWithText("Couldn't load proxies.").assertIsDisplayed()
        onNodeWithTag("proxies_settings_empty").assertDoesNotExist()
        assertTrue(loads.get() >= 1)
    }

    @Test fun empty_list_shows_empty_state_not_error() = runComposeUiTest {
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(proxiesLoad = { emptyList() })()
            }
        }
        waitForIdle()
        onNodeWithTag("proxies_settings_empty").assertIsDisplayed()
        onNodeWithTag("proxies_settings_error").assertDoesNotExist()
        onNodeWithText("No proxies configured.").assertIsDisplayed()
    }

    @Test fun create_proxy_dialog_posts_and_reloads() = runComposeUiTest {
        val created = AtomicReference<Triple<String, Int, String?>?>(null)
        val loads = AtomicInteger(0)
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    proxiesLoad = {
                        loads.incrementAndGet()
                        if (created.get() != null) {
                            sampleProxies() + ProxyDto(
                                domain = "new.example.local",
                                sessionName = "web",
                                port = 4000,
                            )
                        } else {
                            sampleProxies()
                        }
                    },
                    proxyCreate = { session, port, domain ->
                        created.set(Triple(session, port, domain))
                        CreateProxyResponse(url = "https://new.example.local", domain = "new.example.local", port = port)
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("proxies_expose_button").performClick()
        waitForIdle()
        onNodeWithTag("proxies_create_dialog").assertIsDisplayed()
        onNodeWithTag("proxies_create_session").assertIsDisplayed()
        onNodeWithTag("proxies_create_port").performTextInput("4000")
        onNodeWithTag("proxies_create_domain").performTextInput("new.example.local")
        waitForIdle()
        onNodeWithTag("proxies_create_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            created.get() != null || runCatching {
                onNodeWithTag("proxy_row_new.example.local").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        assertEquals(Triple("web", 4000, "new.example.local"), created.get())
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_row_new.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertTrue(loads.get() >= 2)
    }

    @Test fun create_proxy_failure_keeps_dialog_and_shows_error() = runComposeUiTest {
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(proxyCreate = { _, _, _ -> null })()
            }
        }
        waitForIdle()
        onNodeWithTag("proxies_expose_button").performClick()
        waitForIdle()
        onNodeWithTag("proxies_create_port").performTextInput("4000")
        onNodeWithTag("proxies_create_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxies_create_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxies_create_dialog").assertIsDisplayed()
        onNodeWithTag("proxies_create_port").assertIsDisplayed()
    }

    @Test fun remove_requires_confirm_then_reloads() = runComposeUiTest {
        val removed = AtomicReference<String?>(null)
        val loads = AtomicInteger(0)
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    proxiesLoad = {
                        loads.incrementAndGet()
                        if (removed.get() == "app.example.local") {
                            listOf(sampleProxies()[1])
                        } else {
                            sampleProxies()
                        }
                    },
                    proxyRemove = { domain ->
                        removed.set(domain)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("proxy_remove_app.example.local").performClick()
        waitForIdle()
        onNodeWithTag("proxies_remove_dialog").assertIsDisplayed()
        onNodeWithText("Remove proxy?").assertIsDisplayed()
        onNodeWithTag("proxies_remove_cancel").performClick()
        waitForIdle()
        assertEquals(null, removed.get())
        onNodeWithTag("proxy_row_app.example.local").assertIsDisplayed()
        onNodeWithTag("proxy_remove_app.example.local").performClick()
        waitForIdle()
        onNodeWithTag("proxies_remove_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_row_app.example.local").assertDoesNotExist()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals("app.example.local", removed.get())
        assertTrue(loads.get() >= 2)
    }

    @Test fun remove_failure_shows_error_in_dialog() = runComposeUiTest {
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(proxyRemove = { false })()
            }
        }
        waitForIdle()
        onNodeWithTag("proxy_remove_app.example.local").performClick()
        waitForIdle()
        onNodeWithTag("proxies_remove_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxies_remove_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxies_remove_dialog").assertIsDisplayed()
        onNodeWithTag("proxy_row_app.example.local").assertIsDisplayed()
    }

    @Test fun toggle_public_requires_confirm_then_calls_set() = runComposeUiTest {
        val toggled = AtomicReference<Pair<String, Boolean>?>(null)
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    proxySetPublic = { domain, isPublic ->
                        toggled.set(domain to isPublic)
                        true
                    },
                )()
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_public_switch_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxy_public_switch_app.example.local").performClick()
        waitForIdle()
        // Making public requires confirm — mutation not yet fired.
        assertEquals(null, toggled.get())
        onNodeWithTag("proxies_public_dialog").assertIsDisplayed()
        onNodeWithText("Make proxy public?").assertIsDisplayed()
        onNodeWithTag("proxies_public_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) { toggled.get() != null }
        assertEquals("app.example.local" to true, toggled.get())
    }

    @Test fun toggle_public_failure_shows_error_in_dialog() = runComposeUiTest {
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(proxySetPublic = { _, _ -> false })()
            }
        }
        waitForIdle()
        onNodeWithTag("proxy_public_switch_app.example.local").performClick()
        waitForIdle()
        onNodeWithTag("proxies_public_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxies_public_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxies_public_dialog").assertIsDisplayed()
    }

    private fun appForProxies(
        listJson: String? = """[{"domain":"app.example.local","sessionName":"web","port":3000,"isPublic":false}]""",
        createJson: String = """{"url":"https://x","domain":"x","port":1}""",
        createStatus: HttpStatusCode = HttpStatusCode.OK,
        patchStatus: HttpStatusCode = HttpStatusCode.OK,
        deleteStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<HostStore, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            methods.add(req.method to path)
            when {
                path == "/proxies" && req.method == HttpMethod.Get -> {
                    if (listJson == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(listJson, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/proxies" && req.method == HttpMethod.Post ->
                    respond(createJson, createStatus, jsonHeaders)
                path.startsWith("/proxies/") && req.method == HttpMethod.Patch ->
                    respond("{}", patchStatus, jsonHeaders)
                path.startsWith("/proxies/") && req.method == HttpMethod.Delete ->
                    respond("{}", deleteStatus, jsonHeaders)
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = uiTestDeps(client),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return app to methods
    }

    @Test fun desktop_app_state_proxies_decodes_mock_broker() = runComposeUiTest {
        val (app, methods) = appForProxies()
        var listed: List<ProxyDto>? = emptyList()
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesScreenUnderTest(
                    proxiesLoad = {
                        listed = app.proxiesForSettings()
                        listed
                    },
                    sessionNames = { listOf("web") },
                    proxyCreate = { s, p, d -> app.createProxy(s, p, d) },
                    proxySetPublic = { d, p -> app.setProxyPublic(d, p) },
                    proxyRemove = { d -> app.removeProxy(d) },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_row_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(1, listed?.size)
        assertTrue(methods.any { it.first == HttpMethod.Get && it.second == "/proxies" })
    }

    @Test fun desktop_app_state_proxies_null_on_broker_error() = runComposeUiTest {
        val (app, _) = appForProxies(listJson = null)
        var result: List<ProxyDto>? = emptyList()
        var called = false
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesScreenUnderTest(
                    proxiesLoad = {
                        result = app.proxiesForSettings()
                        called = true
                        result
                    },
                    sessionNames = { emptyList() },
                    proxyCreate = { _, _, _ -> null },
                    proxySetPublic = { _, _ -> false },
                    proxyRemove = { false },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { called }
        assertEquals(null, result)
        onNodeWithTag("proxies_settings_error").assertIsDisplayed()
    }

    @Test fun desktop_app_state_remove_proxy_false_on_http_500() = runComposeUiTest {
        val (app, methods) = appForProxies(deleteStatus = HttpStatusCode.InternalServerError)
        var removed = true
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesScreenUnderTest(
                    proxiesLoad = { app.proxiesForSettings() },
                    sessionNames = { listOf("web") },
                    proxyCreate = { _, _, _ -> null },
                    proxySetPublic = { _, _ -> true },
                    proxyRemove = {
                        removed = app.removeProxy(it)
                        removed
                    },
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_remove_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxy_remove_app.example.local").performClick()
        waitForIdle()
        onNodeWithTag("proxies_remove_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            methods.any { it.first == HttpMethod.Delete } && !removed
        }
        assertFalse(removed)
        onNodeWithTag("proxies_remove_error").assertIsDisplayed()
    }

    @Test fun desktop_app_state_create_proxy_null_on_http_500() = runComposeUiTest {
        val (app, _) = appForProxies(createStatus = HttpStatusCode.InternalServerError)
        var created: CreateProxyResponse? = CreateProxyResponse("u", "d", 1)
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesScreenUnderTest(
                    proxiesLoad = { app.proxiesForSettings() },
                    sessionNames = { listOf("web") },
                    proxyCreate = { s, p, d ->
                        created = app.createProxy(s, p, d)
                        created
                    },
                    proxySetPublic = { _, _ -> true },
                    proxyRemove = { true },
                )
            }
        }
        waitForIdle()
        onNodeWithTag("proxies_expose_button").performClick()
        waitForIdle()
        onNodeWithTag("proxies_create_port").performTextInput("4000")
        onNodeWithTag("proxies_create_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxies_create_error").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        assertEquals(null, created)
    }

    @Test fun desktop_app_state_toggle_public_false_on_http_500() = runComposeUiTest {
        val (app, methods) = appForProxies(patchStatus = HttpStatusCode.InternalServerError)
        var ok = true
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesScreenUnderTest(
                    proxiesLoad = { app.proxiesForSettings() },
                    sessionNames = { listOf("web") },
                    proxyCreate = { _, _, _ -> null },
                    proxySetPublic = { d, p ->
                        ok = app.setProxyPublic(d, p)
                        ok
                    },
                    proxyRemove = { true },
                )
            }
        }
        waitForIdle()
        // The list here comes from a REAL HostStore over a mock engine, so the rows land a few
        // frames after the first idle — and since Compose Multiplatform 1.12 changed what
        // `waitForIdle()` waits for, that first idle can arrive before them. Wait for the row
        // itself, exactly as the two sibling toggle tests above do.
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_public_switch_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxy_public_switch_app.example.local").performClick()
        waitForIdle()
        onNodeWithTag("proxies_public_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            methods.any { it.first == HttpMethod.Patch } && !ok
        }
        assertFalse(ok)
        onNodeWithTag("proxies_public_error").assertIsDisplayed()
    }

    // ── Compact / touch (Android's branch) ──────────────────────────────────────────────────────

    /** The phone page paints Android's chrome: its own top bar with the "+" expose action. */
    @Test fun compact_page_paints_its_own_top_bar_with_the_expose_action() = runComposeUiTest {
        var backs = 0
        proxiesContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
                    actions = ProxiesSettingsActions(
                        proxiesLoad = { sampleProxies() },
                        sessionNames = flowOf(listOf("web")),
                    ),
                    onBack = { backs++ },
                    topBarShown = false,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_row_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxies_expose_button").assertDoesNotExist()
        onNodeWithTag("proxies_expose_action").performClick()
        waitForIdle()
        onNodeWithTag("proxies_create_dialog").assertIsDisplayed()
        onNodeWithTag("proxies_create_cancel").performClick()
        waitForIdle()
        onNodeWithTag("proxies_settings_back").performClick()
        assertEquals(1, backs)
    }

    /** When the hub already painted a top bar, the page keeps the header button and adds no bar. */
    @Test fun compact_page_defers_to_the_hub_chrome() = runComposeUiTest {
        proxiesContent(pointer = false, widthClass = WindowWidthClass.Compact) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_row_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxies_settings_back").assertDoesNotExist()
        onNodeWithTag("proxies_expose_button").assertIsDisplayed()
    }

    /** Hit targets key on `LocalPointerAvailable`: a finger gets more room between rows. */
    @Test fun touch_proxy_rows_sit_further_apart_than_pointer_rows() {
        fun rowPitch(pointer: Boolean): Float {
            var pitch = 0f
            runComposeUiTest {
                proxiesContent(pointer = pointer, widthClass = WindowWidthClass.Compact) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() }
                }
                waitForIdle()
                waitUntil(timeoutMillis = 5_000) {
                    try {
                        onNodeWithTag("proxy_row_api.example.local").assertIsDisplayed()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                val first =
                    onNodeWithTag("proxy_row_app.example.local").fetchSemanticsNode().positionInRoot.y
                val second =
                    onNodeWithTag("proxy_row_api.example.local").fetchSemanticsNode().positionInRoot.y
                pitch = second - first
            }
            return pitch
        }
        val touch = rowPitch(pointer = false)
        val mouse = rowPitch(pointer = true)
        assertTrue(touch > mouse, "touch pitch $touch should exceed pointer pitch $mouse")
    }

    /** The per-row URL row Android never had: copy writes the URL, open hands it to the platform. */
    @Test fun row_url_copies_and_opens() = runComposeUiTest {
        val platform = FakePlatform()
        setPlatformContent(platform = platform) {
            SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_url_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxy_url_open_app.example.local").performClick()
        waitForIdle()
        assertEquals(listOf("https://app.example.local"), platform.openedUrls)
    }

    // ── E4 review fixes ────────────────────────────────────────────────────────────────────────

    /**
     * `Route.Proxies` is its own destination: a phone in LANDSCAPE is Medium, not Compact, and
     * there is no hub above it — so the title and Back have to be there anyway.
     */
    @Test fun a_standalone_route_keeps_its_chrome_above_compact() = runComposeUiTest {
        var backs = 0
        proxiesContent(pointer = false, widthClass = WindowWidthClass.Medium) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
                    actions = ProxiesSettingsActions(proxiesLoad = { sampleProxies() }),
                    onBack = { backs++ },
                    topBarShown = false,
                    standalone = true,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_row_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxies_settings_back").performClick()
        assertEquals(1, backs)
        onNodeWithTag("proxies_expose_action").assertIsDisplayed()
    }

    /** Inside the hub on a wide window there is exactly one chrome, and it is not the screen's. */
    @Test fun a_hub_section_on_a_wide_window_paints_no_bar_of_its_own() = runComposeUiTest {
        proxiesContent(widthClass = WindowWidthClass.Expanded) {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
                    actions = ProxiesSettingsActions(proxiesLoad = { sampleProxies() }),
                    topBarShown = false,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxy_row_app.example.local").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithTag("proxies_settings_back").assertDoesNotExist()
        onNodeWithTag("proxies_expose_button").assertIsDisplayed()
    }

    /** The per-row copy/open buttons reach Material's 48dp minimum without a pointer. */
    @Test fun touch_url_buttons_reach_the_minimum_target() {
        /** The button's height in px, and the 48dp minimum in the SAME px, at this density. */
        fun buttonHeight(pointer: Boolean): Pair<Int, Int> {
            var measured = 0 to 0
            runComposeUiTest {
                proxiesContent(pointer = pointer, widthClass = WindowWidthClass.Compact) {
                    SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() }
                }
                waitForIdle()
                waitUntil(timeoutMillis = 5_000) {
                    try {
                        onNodeWithTag("proxy_url_copy_app.example.local").assertIsDisplayed()
                        true
                    } catch (_: Throwable) {
                        false
                    }
                }
                val h = onNodeWithTag("proxy_url_copy_app.example.local")
                    .fetchSemanticsNode().size.height
                measured = h to with(density) { 48.dp.roundToPx() }
            }
            return measured
        }
        val (touch, minTarget) = buttonHeight(pointer = false)
        val (mouse, _) = buttonHeight(pointer = true)
        // Absolute, not merely "bigger than the mouse one": Material's minimum is a floor.
        assertTrue(touch >= minTarget, "touch button $touch should reach the ${minTarget}px minimum")
        assertTrue(touch > mouse, "touch button $touch should exceed pointer button $mouse")
    }

    /** A session spawned while the screen is open reaches the expose-port form. */
    @Test fun the_expose_form_follows_the_live_session_list() = runComposeUiTest {
        val names = MutableStateFlow(listOf("web"))
        proxiesContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
                    actions = ProxiesSettingsActions(
                        proxiesLoad = { sampleProxies() },
                        sessionNames = names,
                    ),
                    topBarShown = true,
                )
            }
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxies_expose_button").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        // Open the form, then open its session picker, and only THEN spawn the session: the list
        // has to reach a form that is already on screen, not just one composed after the change.
        onNodeWithTag("proxies_expose_button").performClick()
        waitForIdle()
        onNodeWithTag("proxies_create_session").performClick()
        waitForIdle()
        onNodeWithTag("proxies_create_session_item_spawned-later").assertDoesNotExist()
        names.value = listOf("web", "spawned-later")
        waitForIdle()
        onNodeWithTag("proxies_create_session_item_spawned-later").assertIsDisplayed()
    }
}
