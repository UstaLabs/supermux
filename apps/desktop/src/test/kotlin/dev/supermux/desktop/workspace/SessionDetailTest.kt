package dev.supermux.desktop.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.TerminalClient
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // ── Chat|Native toggle (Task 7) ──────────────────────────────────────────────────
    //
    // The Native pane embeds a SwingPanel (DesktopTerminalPanel) which cannot be hosted under
    // runComposeUiTest (no real AWT window), so these tests inject a pure-Compose fake via
    // SessionDetail's `nativePanelContent` seam. The fake tags itself `native_fake` and captures the
    // onExit callback so the exit-fallback path is drivable headlessly.

    private val codexSession =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "codex")

    @Test
    fun toggleShownForClaudeHiddenForOthers() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {})
            }
        }
        // claude → the labelled pill is present.
        onNodeWithTag("agent_view_chat").assertIsDisplayed()
        onNodeWithTag("agent_view_native").assertIsDisplayed()
    }

    @Test
    fun toggleHiddenForNonClaude() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = codexSession, agent = null,
                    layout = WorkspaceLayout(), draft = "", onDraftChange = {})
            }
        }
        onNodeWithTag("agent_view_chat").assertDoesNotExist()
        onNodeWithTag("agent_view_native").assertDoesNotExist()
        // And the Native pane is never composed for a non-claude session.
        onNodeWithTag("pane_native").assertDoesNotExist()
    }

    @Test
    fun togglingSwapsContentButKeepsChatInTree() = runComposeUiTest {
        val layout = WorkspaceLayout()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = layout, draft = "", onDraftChange = {},
                    nativePanelContent = fakeNative)
            }
        }
        // Native is lazy: not composed until first opened.
        onNodeWithTag("pane_chat").assertIsDisplayed()
        onNodeWithTag("native_fake").assertDoesNotExist()

        // Flip to Native → the fake renders AND chat STAYS in the tree (keep-alive, not remounted).
        runOnIdle { layout.setNativeView("s1", true) }
        onNodeWithTag("native_fake").assertIsDisplayed()
        onNodeWithTag("pane_chat").assertExists()

        // Flip back to Chat → chat is displayed again; the Native panel STAYS composed (kept alive,
        // laid out at 0×0) rather than being disposed — the SwingPanel keep-alive contract.
        runOnIdle { layout.setNativeView("s1", false) }
        onNodeWithTag("pane_chat").assertIsDisplayed()
        onNodeWithTag("native_fake").assertExists()
    }

    @Test
    fun onExitFlipsBackToChatAndClearsNativeView() = runComposeUiTest {
        val layout = WorkspaceLayout()
        layout.setNativeView("s1", true) // start in Native
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = layout, draft = "", onDraftChange = {},
                    nativePanelContent = fakeNative)
            }
        }
        onNodeWithTag("native_fake").assertIsDisplayed()

        // Agent PTY exit → onExit clears the persisted preference and drops the panel back to Chat.
        runOnIdle { capturedOnExit?.invoke() }
        assertEquals(false, layout.nativeView("s1"))
        onNodeWithTag("pane_chat").assertIsDisplayed()
        // A dead PTY is fully disposed (not kept alive) so a later re-open builds a fresh client.
        onNodeWithTag("native_fake").assertDoesNotExist()
    }

    @Test
    fun clickingNativePillPersistsPreferenceViaLayout() = runComposeUiTest {
        val layout = WorkspaceLayout()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                SessionDetail(app = app(), session = session, agent = null,
                    layout = layout, draft = "", onDraftChange = {},
                    nativePanelContent = fakeNative)
            }
        }
        onNodeWithTag("agent_view_native").performClick()
        // The choice is persisted on the layout (which the workspace snapshot serializes).
        assertEquals(true, layout.nativeView("s1"))
        assertTrue(layout.snapshot().native["s1"] == true)

        onNodeWithTag("agent_view_chat").performClick()
        assertEquals(false, layout.nativeView("s1"))
    }
}

// Captured onExit from the most recent [fakeNative] composition, so a test can drive the agent-exit
// fallback path without a live PTY.
private var capturedOnExit: (() -> Unit)? = null

/** Pure-Compose stand-in for DesktopTerminalPanel's SwingPanel — tags itself `native_fake` and
 *  records the onExit callback. Ignores the connect factory (never opens a socket under test). */
private val fakeNative: @Composable (() -> TerminalClient, () -> Unit) -> Unit = { _, onExit ->
    capturedOnExit = onExit
    Box(Modifier.fillMaxSize().testTag("native_fake"))
}
