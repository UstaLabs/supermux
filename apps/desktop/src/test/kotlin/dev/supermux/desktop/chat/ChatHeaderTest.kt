package dev.supermux.desktop.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.ProxyDto
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The two per-session header affordances that moved off the deleted session header
 * (SessionDetail) and onto the chat view's own slim header: the session-links (proxies) globe
 * menu, and the Chat⇄Native pill.
 *
 * Both belong to ONE session and a chat view IS one session — unlike the git badge, which belongs
 * to the work tree and moved to the workspace header instead (see WorkspaceHeaderTest).
 *
 * These cases are rewrites of SessionDetailTest's
 * `forceLinksMenuOpensTheGlobeDropdownAndConsumesTheOneShotFlag`, `toggleShownForClaudeHidden…`,
 * `toggleHiddenForNonClaude`, `togglingSwapsContentButKeepsChatInTree`,
 * `onExitFlipsBackToChatAndClearsNativeView` and `clickingNativePillPersistsPreference…`,
 * retargeted at their new owner.
 *
 * DesktopAppState is built with `connectOnInit = false` so no WebSocket/HTTP is opened.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ChatHeaderTest {
    private fun app() = DesktopAppState(
        baseUrl = "ws://test:9898",
        token = "t",
        scope = TestScope(UnconfinedTestDispatcher()),
        connectOnInit = false,
    )

    private val claudeSession =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude")
    private val codexSession =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "codex")

    // The real native panel is a SwingPanel (DesktopTerminalPanel) which cannot be hosted under
    // runComposeUiTest, so inject a pure-Compose stand-in that captures its onExit.
    private var capturedOnExit: (() -> Unit)? = null
    private val fakeNative: @Composable (onExit: () -> Unit) -> Unit = { onExit ->
        capturedOnExit = onExit
        Box(Modifier.fillMaxSize().testTag("native_fake"))
    }

    // ── Links (proxies) menu ──────────────────────────────────────────────────────────

    @Test
    fun linksMenuHiddenWhenTheSessionHasNoProxies() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = claudeSession, draft = "", onDraftChange = {},
                    loadProxies = { emptyList() },
                )
            }
        }
        onNodeWithTag("session_links").assertDoesNotExist()
    }

    @Test
    fun forceLinksMenuOpensTheGlobeDropdownAndConsumesTheOneShotFlag() = runComposeUiTest {
        var consumed = 0
        var force by mutableStateOf(false)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = claudeSession, draft = "", onDraftChange = {},
                    loadProxies = {
                        listOf(ProxyDto(domain = "d.example", sessionName = "demo", port = 3000))
                    },
                    forceLinksMenu = force,
                    onForceLinksMenuConsumed = { consumed++ },
                )
            }
        }
        onNodeWithTag("session_links").assertIsDisplayed()
        runOnIdle { force = true }
        waitForIdle()
        onNodeWithText("d.example").assertIsDisplayed()
        runOnIdle { assertEquals(1, consumed) }
    }

    @Test
    fun aHeaderlessChatNeverLoadsProxies() = runComposeUiTest {
        // Suppressing the header suppresses the load too — nothing would draw the result.
        var loads = 0
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = claudeSession, draft = "", onDraftChange = {},
                    showHeader = false,
                    loadProxies = { loads++; emptyList() },
                )
            }
        }
        runOnIdle { assertEquals(0, loads) }
    }

    // ── Chat ⇄ Native pill ────────────────────────────────────────────────────────────

    @Test
    fun pillShownForClaudeWithANativeSurface() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = claudeSession, draft = "", onDraftChange = {},
                    loadProxies = { emptyList() },
                    nativeContent = fakeNative,
                )
            }
        }
        onNodeWithTag("agent_view_chat").assertIsDisplayed()
        onNodeWithTag("agent_view_native").assertIsDisplayed()
    }

    @Test
    fun pillHiddenForNonClaude() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = codexSession, draft = "", onDraftChange = {},
                    loadProxies = { emptyList() },
                    nativeContent = fakeNative,
                )
            }
        }
        onNodeWithTag("agent_view_chat").assertDoesNotExist()
        onNodeWithTag("agent_view_native").assertDoesNotExist()
        // ...and the native panel is never composed for a session that has no native view.
        onNodeWithTag("pane_native").assertDoesNotExist()
    }

    @Test
    fun pillHiddenWhenTheCallerSuppliesNoNativeSurface() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = claudeSession, draft = "", onDraftChange = {},
                    loadProxies = { emptyList() },
                )
            }
        }
        onNodeWithTag("agent_view_chat").assertDoesNotExist()
    }

    @Test
    fun togglingSwapsTheBodyButKeepsChatInTreeAndTheHeaderOnScreen() = runComposeUiTest {
        capturedOnExit = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = claudeSession, draft = "", onDraftChange = {},
                    loadProxies = { emptyList() },
                    nativeContent = fakeNative,
                )
            }
        }
        // Native is lazy: not composed until first opened.
        onNodeWithTag("chat_body").assertIsDisplayed()
        onNodeWithTag("native_fake").assertDoesNotExist()

        onNodeWithTag("agent_view_native").performClick()
        waitForIdle()
        onNodeWithTag("native_fake").assertIsDisplayed()
        // Chat STAYS in the tree (keep-alive, not remounted) so its draft/scroll survive...
        onNodeWithTag("chat_body").assertExists()
        // ...and the header stays on screen, so the way back is always reachable. This is the one
        // behaviour that IMPROVED in the move: the old shell drew the pill in a header ABOVE the
        // pane, here it is in the pane's own header.
        onNodeWithTag("agent_view_chat").assertIsDisplayed()

        onNodeWithTag("agent_view_chat").performClick()
        waitForIdle()
        onNodeWithTag("chat_body").assertIsDisplayed()
        // The native panel STAYS composed (kept alive at 0×0) rather than being disposed.
        onNodeWithTag("native_fake").assertExists()
    }

    @Test
    fun ptyExitDropsThePanelAndReturnsToTheTranscript() = runComposeUiTest {
        capturedOnExit = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = claudeSession, draft = "", onDraftChange = {},
                    loadProxies = { emptyList() },
                    nativeContent = fakeNative,
                )
            }
        }
        onNodeWithTag("agent_view_native").performClick()
        waitForIdle()
        onNodeWithTag("native_fake").assertIsDisplayed()

        runOnIdle { capturedOnExit?.invoke() }
        waitForIdle()
        onNodeWithTag("chat_body").assertIsDisplayed()
        // A dead PTY is fully disposed (not kept alive) so a later re-open builds a fresh client.
        onNodeWithTag("native_fake").assertDoesNotExist()
    }

    @Test
    fun aSessionSwitchStartsOnChatAndMountsAFreshNativePanel() = runComposeUiTest {
        // ChatPanel stays composed across a session switch, so the native choice and the panel
        // itself must be keyed on the session — otherwise the new session would open showing the
        // OLD session's PTY.
        val mounts = mutableListOf<String>()
        val disposals = mutableListOf<String>()
        var current by mutableStateOf(claudeSession)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                ChatPanel(
                    app = app(), session = current, draft = "", onDraftChange = {},
                    loadProxies = { emptyList() },
                    nativeContent = {
                        val forSession = current.id
                        DisposableEffect(Unit) {
                            mounts.add(forSession)
                            onDispose { disposals.add(forSession) }
                        }
                        Box(Modifier.fillMaxSize().testTag("native_fake_$forSession"))
                    },
                )
            }
        }
        onNodeWithTag("agent_view_native").performClick()
        waitForIdle()
        onNodeWithTag("native_fake_s1").assertIsDisplayed()

        runOnIdle { current = claudeSession.copy(id = "s2", name = "demo2", workdir = "/w/s2") }
        waitForIdle()
        // The new session starts on Chat, and s1's panel is gone.
        onNodeWithTag("chat_body").assertIsDisplayed()
        onNodeWithTag("native_fake_s1").assertDoesNotExist()
        assertEquals(listOf("s1"), mounts)
        assertEquals(listOf("s1"), disposals)
    }
}
