// Desktop's `AppShell` wiring for the two Settings-hub EXTRA rows (cluster E7).
package dev.supermux.desktop.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.session.LauncherStore
import dev.supermux.desktop.shell.AppShell
import dev.supermux.desktop.shell.ShellStateStore
import dev.supermux.desktop.shell.ShellUiState
import dev.supermux.desktop.testDeps
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.net.BrokerApi
import dev.supermux.state.HostStore
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.theme.AppearanceMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Before E7 `Caps.appearanceControls` and `Caps.appUpdate` were false on desktop, so the hub's
 * rail ended at the last [SettingsSection]. Both are real now: Appearance is the SHARED screen
 * (the same one Android's hub and its `Route.Appearance` render) and Check-for-updates routes to
 * desktop's own updater. What this pins is the rail wiring — `DesktopSettingsExtra` reached from
 * the hub's `extraContent` slot; the Appearance screen's own behaviour is `:ui`'s suite.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class AppearanceHubTest {

    private val tempFiles = mutableListOf<Path>()

    @AfterTest fun cleanup() {
        tempFiles.forEach { Files.deleteIfExists(it) }
        tempFiles.clear()
    }

    private fun tempPath(name: String): Path {
        val f = Files.createTempFile("appearance_hub_test_$name", ".json")
        Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    private fun app(): HostStore {
        val engine = MockEngine {
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
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

    @Test fun the_rail_offers_appearance_and_opens_the_shared_screen() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Agents) }
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app(), ui,
                    ShellStateStore(tempPath("state")),
                    LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_section_appearance").assertIsDisplayed()
        onNodeWithTag("settings_section_appearance").performClick()
        waitForIdle()
        onNodeWithTag("appearance_settings_screen").assertIsDisplayed()
        // Material You is Android-only — the row must not appear on a machine with no such OS.
        onNodeWithTag("appearance_dynamic_row").assertDoesNotExist()
        // The wide rail owns navigation, so the page paints no back arrow of its own.
        onNodeWithTag("appearance_settings_back").assertDoesNotExist()
    }

    @Test fun the_rail_offers_check_for_updates() = runComposeUiTest {
        val ui = ShellUiState().apply { openSettings(SettingsSection.Agents) }
        setContent {
            DesktopTheme(appearance = AppearanceMode.DARK) {
                AppShell(
                    app(), ui,
                    ShellStateStore(tempPath("state-upd")),
                    LauncherStore(tempPath("launcher-upd")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("settings_section_appupdate").assertIsDisplayed()
    }
}
