package dev.supermux.desktop.workspace

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test

/**
 * Compose UI test proving the SessionDetail split tree reacts to the pane toggles the model owns:
 * flipping editor/terminal/display on the [WorkspaceLayout] mounts the matching ComingSoonPane
 * (tagged `pane_editor` / `pane_terminal` / `pane_display`). This is the headless counterpart to
 * the manual "toggle panes via the menu" check — the model is covered by WorkspaceLayoutTest, and
 * this asserts the rendering wired off it. The chat pane (`pane_chat`) is present by default.
 *
 * DesktopAppState is built with `connectOnInit = false` so no WebSocket/HTTP is opened.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SessionDetailTest {
    private fun app() = DesktopAppState(
        baseUrl = "ws://test:9898",
        token = "t",
        scope = TestScope(UnconfinedTestDispatcher()),
        connectOnInit = false,
    )

    private val session =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude")

    @Test
    fun togglingWorkPanesMountsComingSoonPanes() = runComposeUiTest {
        val layout = WorkspaceLayout()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(
                    app = app(),
                    session = session,
                    agent = null,
                    layout = layout,
                    draft = "",
                    onDraftChange = {},
                )
            }
        }

        // Default panes = chat only: chat present, work panes absent.
        onNodeWithTag("pane_chat").assertIsDisplayed()
        onNodeWithTag("pane_editor").assertDoesNotExist()
        onNodeWithTag("pane_terminal").assertDoesNotExist()
        onNodeWithTag("pane_display").assertDoesNotExist()

        // Toggle editor on → its ComingSoonPane mounts; chat stays.
        runOnIdle { layout.toggleEditor("s1") }
        onNodeWithTag("pane_editor").assertIsDisplayed()
        onNodeWithTag("pane_chat").assertIsDisplayed()

        // Toggle terminal on → both editor and terminal panes present (vertical split).
        runOnIdle { layout.toggleTerminal("s1") }
        onNodeWithTag("pane_editor").assertIsDisplayed()
        onNodeWithTag("pane_terminal").assertIsDisplayed()

        // Toggle display on → the display pane joins the right area.
        runOnIdle { layout.toggleDisplay("s1") }
        onNodeWithTag("pane_display").assertIsDisplayed()

        // Hide chat (work present, so the invariant allows it) → chat pane leaves the tree.
        runOnIdle { layout.toggleChat("s1") }
        onNodeWithTag("pane_chat").assertDoesNotExist()
        onNodeWithTag("pane_editor").assertIsDisplayed()
    }
}
