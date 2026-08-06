// Desktop-parity Task 5: Proxies section — list / create / toggle public / remove.
package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.shell.SettingsSection
import dev.supermux.desktop.shell.AppShell
import dev.supermux.desktop.shell.ShellStateStore
import dev.supermux.desktop.shell.ShellUiState
import dev.supermux.net.BrokerApi
import dev.supermux.net.CreateProxyResponse
import dev.supermux.net.ProxyDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ProxiesSettingsScreenTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { p -> runCatching { Files.deleteIfExists(p) } }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("proxies_settings_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

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
        ProxiesSettingsScreen(
            proxiesLoad = proxiesLoad,
            sessionNames = sessionNames,
            proxyCreate = proxyCreate,
            proxySetPublic = proxySetPublic,
            proxyRemove = proxyRemove,
        )
    }

    @Test fun proxies_render_from_a_fake_list() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
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
        setContent {
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

    // ── DesktopAppState + BrokerApi (ktor mock) ─────────────────────────────────────────────────

    private fun appForProxies(
        listJson: String? = """[{"domain":"app.example.local","sessionName":"web","port":3000,"isPublic":false}]""",
        createJson: String = """{"url":"https://x","domain":"x","port":1}""",
        createStatus: HttpStatusCode = HttpStatusCode.OK,
        patchStatus: HttpStatusCode = HttpStatusCode.OK,
        deleteStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<DesktopAppState, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
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
        val app = DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return app to methods
    }

    @Test fun desktop_app_state_proxies_decodes_mock_broker() = runComposeUiTest {
        val (app, methods) = appForProxies()
        var listed: List<ProxyDto>? = emptyList()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
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
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
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
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
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
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
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
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ProxiesSettingsScreen(
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
        onNodeWithTag("proxy_public_switch_app.example.local").performClick()
        waitForIdle()
        onNodeWithTag("proxies_public_confirm").performClick()
        waitUntil(timeoutMillis = 5_000) {
            methods.any { it.first == HttpMethod.Patch } && !ok
        }
        assertFalse(ok)
        onNodeWithTag("proxies_public_error").assertIsDisplayed()
    }

    @Test fun settings_hub_opens_proxies_section() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Proxies) }
        val (app, _) = appForProxies()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_overlay").assertIsDisplayed()
        onNodeWithTag("settings_section_proxies").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("proxies_settings_screen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
