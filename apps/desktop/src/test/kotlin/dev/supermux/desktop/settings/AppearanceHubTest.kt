// Desktop's `AppShell` wiring for the two Settings-hub EXTRA rows (cluster E7).
package dev.supermux.desktop.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.shell.TestAppShell
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.desktop.testDeps
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.net.BrokerApi
import dev.supermux.state.HostStore
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.prefs.seedAppearance
import dev.supermux.ui.theme.AppearanceMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
                TestAppShell(app(), ui)
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
                TestAppShell(app(), ui)
            }
        }
        waitForIdle()
        onNodeWithTag("settings_section_appupdate").assertIsDisplayed()
    }

    // ── the synchronous cold-start seed ───────────────────────────────────────────────────────
    //
    // `ShellUiState.appearance` defaults to DARK, so `Main.kt` must know the stored theme BEFORE it
    // builds the state — resolving it in an effect shows a LIGHT user one dark composition on every
    // launch. It can, because `DesktopSettingsStore` holds its map in an eager `StateFlow`: these
    // pin that a blocking read returns the real value, and that the one-time `ui-state.json`
    // migration lands in the store rather than being re-done every launch.

    @Test fun a_stored_theme_is_readable_synchronously_before_the_first_frame() {
        val file = tempPath("settings")
        Files.writeString(file, """{"appearance:mode":"LIGHT"}""")
        val seeded = runBlocking {
            UiPrefs(DesktopSettingsStore(file)).seedAppearance(
                default = AppearanceMode.DARK,
                legacy = null,
            )
        }
        assertEquals(AppearanceMode.LIGHT, seeded)
        // ...and that value — not the DARK default — is what the shell carries into composition.
        assertEquals(AppearanceMode.LIGHT, ShellUiState().apply { appearance = seeded }.appearance)
    }

    @Test fun a_ui_state_json_theme_is_migrated_into_the_store_once() {
        val file = tempPath("settings-legacy")
        val seeded = runBlocking {
            UiPrefs(DesktopSettingsStore(file)).seedAppearance(
                default = AppearanceMode.DARK,
                legacy = AppearanceMode.LIGHT,
            )
        }
        assertEquals(AppearanceMode.LIGHT, seeded)
        // A fresh store over the same file — i.e. the next launch — reads it without the legacy arg.
        assertEquals(
            AppearanceMode.LIGHT,
            runBlocking { UiPrefs(DesktopSettingsStore(file)).appearanceMode.first() },
        )
    }
}
