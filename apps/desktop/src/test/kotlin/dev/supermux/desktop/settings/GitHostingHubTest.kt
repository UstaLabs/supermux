// Desktop's `AppShell` wiring for the Git hosting section of the Settings hub (cluster E3 split).
package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.desktop.shell.AppShell
import dev.supermux.desktop.shell.ShellStateStore
import dev.supermux.desktop.shell.ShellUiState
import dev.supermux.net.BrokerApi
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop's shell around the Git hosting settings section.
 *
 * The screen moved to `:ui` in cluster E3 (`dev.supermux.ui.settings.GitHostingScreenTest` holds
 * its 17 load / add-form / disconnect / helper tests); what stays here is the hub opening the
 * section against a real `HostStore` payload and the rail switching into it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class GitHostingHubTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest fun cleanup() {
        tempFiles.forEach { Files.deleteIfExists(it) }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("git_hosting_hub_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun appWithForges(body: String): HostStore {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when {
                req.url.encodedPath == "/forge/connections" && req.method == HttpMethod.Get ->
                    respond(ByteReadChannel(body), HttpStatusCode.OK, jsonHeaders)
                req.url.encodedPath == "/agents/status" ->
                    respond("[]", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }
        return HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
    }

    @Test fun settings_hub_git_hosting_section_loads_real_broker_payload() = runComposeUiTest {
        val body = """
            {"connections":[{"id":"live1","kind":"github","host":"github.com","account":{"login":"liveuser"},"source":"pat","transport":"https","status":"ok"}],"cli":null}
        """.trimIndent()
        val ui = ShellUiState().apply { openSettings(SettingsSection.GitHosting) }
        val app = appWithForges(body)
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_hub").assertIsDisplayed()
        onNodeWithTag("settings_section_githosting").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_screen").assertIsDisplayed()
                onNodeWithTag("forge_row_live1").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("@liveuser").assertIsDisplayed()
        assertEquals(SettingsSection.GitHosting, ui.settingsSection)
    }

    @Test fun rail_can_switch_to_git_hosting_from_agents() = runComposeUiTest {
        val body = """{"connections":[],"cli":null}"""
        val ui = ShellUiState().apply { openSettings(SettingsSection.Agents) }
        val app = appWithForges(body)
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app, ui,
                    ShellStateStore(tempPath("state-rail")),
                    LauncherStore(tempPath("launcher-rail")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_section_githosting").performClick()
        waitForIdle()
        assertEquals(SettingsSection.GitHosting, ui.settingsSection)
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("git_hosting_screen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
        onNodeWithText("Connect a Git host").assertIsDisplayed()
    }
}
