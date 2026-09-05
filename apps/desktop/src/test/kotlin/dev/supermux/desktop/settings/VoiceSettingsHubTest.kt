// Cluster E5 moved [dev.supermux.ui.settings.VoiceSettingsScreen] and its screen-level suite into
// `:ui` (`VoiceSettingsScreenTest`). What stays here is the `AppShell` settings overlay opening on
// the Voice section — desktop-only chrome.
package dev.supermux.desktop.settings

import dev.supermux.desktop.testDeps

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class VoiceSettingsHubTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { p -> runCatching { Files.deleteIfExists(p) } }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("voice_settings_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun appForVoice(
        configJson: String? = """{"voiceSttEngine":"whisper","voiceTtsEngine":"platform","voiceCleanupEngine":"codex"}""",
        glossaryJson: String? = """{"glossary":["Supermux"]}""",
        configPutOk: Boolean = true,
        glossaryGetStatus: HttpStatusCode = HttpStatusCode.OK,
        glossaryPutStatus: HttpStatusCode = HttpStatusCode.OK,
    ): Pair<HostStore, CopyOnWriteArrayList<Pair<HttpMethod, String>>> {
        val methods = CopyOnWriteArrayList<Pair<HttpMethod, String>>()
        val engine = MockEngine { req ->
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            methods.add(req.method to path)
            when {
                path == "/settings/config" && req.method == HttpMethod.Get -> {
                    if (configJson == null) {
                        respond("{}", HttpStatusCode.InternalServerError, jsonHeaders)
                    } else {
                        respond(configJson, HttpStatusCode.OK, jsonHeaders)
                    }
                }
                path == "/settings/config" && req.method == HttpMethod.Put ->
                    respond(
                        "{}",
                        if (configPutOk) HttpStatusCode.OK else HttpStatusCode.InternalServerError,
                        jsonHeaders,
                    )
                path == "/config/voice-glossary" && req.method == HttpMethod.Get -> {
                    if (glossaryJson == null) {
                        respond("{}", glossaryGetStatus, jsonHeaders)
                    } else {
                        respond(glossaryJson, glossaryGetStatus, jsonHeaders)
                    }
                }
                path == "/config/voice-glossary" && req.method == HttpMethod.Put ->
                    respond(glossaryJson ?: """{"glossary":[]}""", glossaryPutStatus, jsonHeaders)
                path.startsWith("/models") ->
                    respond("""{"models":[{"id":"m1","displayName":"Model 1"}]}""", HttpStatusCode.OK, jsonHeaders)
                else ->
                    respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine)
        val app = HostStore(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            deps = testDeps(),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = BrokerApi("ws://test:9898", "t", client),
        )
        return app to methods
    }

    @Test fun settings_hub_opens_voice_section() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Voice) }
        val (app, _) = appForVoice()
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
        onNodeWithTag("settings_overlay").assertIsDisplayed()
        onNodeWithTag("settings_section_voice").assertIsDisplayed()
        waitUntil(timeoutMillis = 5_000) {
            try {
                onNodeWithTag("voice_settings_screen").assertIsDisplayed()
                true
            } catch (_: Throwable) {
                false
            }
        }
    }
}
