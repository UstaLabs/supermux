// Desktop's `AppShell` wiring for the Assistant section of the Settings hub (cluster E3 split).
package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.desktop.shell.TestAppShell
import dev.supermux.ui.shell.ShellUiState
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Desktop's shell around the Assistant settings section.
 *
 * The screen moved to `:ui` in cluster E3
 * (`dev.supermux.ui.settings.AssistantSettingsScreenTest`); what stays here is the hub opening the
 * section against a real `HostStore`.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AssistantSettingsHubTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { p -> runCatching { Files.deleteIfExists(p) } }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("assistant_settings_hub_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun appForAssistant(): Pair<HostStore, Unit> {
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val textHeaders = headersOf(HttpHeaders.ContentType, "text/plain")
            when {
                req.url.encodedPath == "/settings/config" && req.method == HttpMethod.Get ->
                    respond("""{"paName":"Mux"}""", HttpStatusCode.OK, jsonHeaders)
                req.url.encodedPath == "/settings/soul" && req.method == HttpMethod.Get ->
                    respond("Be helpful.", HttpStatusCode.OK, textHeaders)
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", HttpClient(engine)),
        )
        return app to Unit
    }

    @Test fun settings_hub_opens_assistant_section() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Assistant) }
        val (app, _) = appForAssistant()
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                TestAppShell(app, ui)
            }
        }
        waitForIdle()
        onNodeWithTag("settings_overlay").assertIsDisplayed()
        onNodeWithTag("settings_section_assistant").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("assistant_settings_screen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
