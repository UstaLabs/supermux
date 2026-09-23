package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ProxyDto
import dev.supermux.proto.SessionInfo
import dev.supermux.state.HostStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The chat header of the shared [ChatPanel]: the session-links (proxies) slot and the Chat⇄Native
 * pill. Moved from desktop with its case names when both apps' `ChatPanel` collapsed into `:ui`
 * (cluster D4).
 *
 * The links menu itself stays in desktop's `shell/` (cluster G moves it), so the panel reaches it
 * through the `headerLinks` SLOT and owns only the load-on-open. These cases therefore drive a
 * pure-Compose stand-in slot with the same contract — exactly the way the native panel has always
 * been faked here, because the real one is a heavyweight SwingPanel.
 *
 * The store is built with `connectOnInit = false` so no WebSocket/HTTP is opened.
 */
@OptIn(ExperimentalTestApi::class)
class ChatHeaderTest {
    private fun app(): HostStore = testHostStore()

    private val claudeSession =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude")
    private val codexSession =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "codex")

    // The real native panel opens a live terminal engine and a broker socket, neither of which
    // belongs in a header test, so inject a pure-Compose stand-in that captures its onExit.
    private var capturedOnExit: (() -> Unit)? = null
    private val fakeNative: @Composable (onExit: () -> Unit) -> Unit = { onExit ->
        capturedOnExit = onExit
        Box(Modifier.fillMaxSize().testTag("native_fake"))
    }

    /** Stand-in for desktop's `SessionLinksMenu`: hidden with no proxies, force-open lists them. */
    private val fakeLinks:
        @Composable androidx.compose.foundation.layout.RowScope.(List<ProxyDto>, Boolean, () -> Unit) -> Unit =
        { proxies, force, onConsumed ->
            if (proxies.isNotEmpty()) {
                var open by androidx.compose.runtime.remember { mutableStateOf(false) }
                LaunchedEffect(force) { if (force) { open = true; onConsumed() } }
                Box(Modifier.size(20.dp).testTag("session_links")) {
                    if (open) Text(proxies.joinToString { it.domain })
                }
            }
        }

    @Composable
    private fun panel(
        app: HostStore,
        session: SessionInfo,
        showHeader: Boolean = true,
        loadProxies: suspend () -> List<ProxyDto> = { emptyList() },
        forceLinksMenu: Boolean = false,
        onForceLinksMenuConsumed: () -> Unit = {},
        nativeContent: (@Composable (onExit: () -> Unit) -> Unit)? = null,
    ) {
        ChatPanel(
            session = session,
            state = rememberChatState(app, session.id),
            actions = rememberChatActions(app, session, loadProxies),
            draft = "",
            onDraftChange = {},
            showHeader = showHeader,
            forceLinksMenu = forceLinksMenu,
            onForceLinksMenuConsumed = onForceLinksMenuConsumed,
            headerLinks = fakeLinks,
            nativeContent = nativeContent,
        )
    }

    // ── Links (proxies) menu ──────────────────────────────────────────────────────────

    @Test
    fun linksMenuHiddenWhenTheSessionHasNoProxies() = runComposeUiTest {
        setPlatformContent { panel(app(), claudeSession, loadProxies = { emptyList() }) }
        onNodeWithTag("session_links").assertDoesNotExist()
    }

    @Test
    fun forceLinksMenuOpensTheGlobeDropdownAndConsumesTheOneShotFlag() = runComposeUiTest {
        var consumed = 0
        var force by mutableStateOf(false)
        setPlatformContent {
            panel(
                app(), claudeSession,
                loadProxies = { listOf(ProxyDto(domain = "d.example", sessionName = "demo", port = 3000)) },
                forceLinksMenu = force,
                onForceLinksMenuConsumed = { consumed++ },
            )
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
        setPlatformContent {
            panel(app(), claudeSession, showHeader = false, loadProxies = { loads++; emptyList() })
        }
        runOnIdle { assertEquals(0, loads) }
    }

    // ── Chat ⇄ Native pill ────────────────────────────────────────────────────────────

    @Test
    fun pillShownForClaudeWithANativeSurface() = runComposeUiTest {
        setPlatformContent { panel(app(), claudeSession, nativeContent = fakeNative) }
        onNodeWithTag("agent_view_chat").assertIsDisplayed()
        onNodeWithTag("agent_view_native").assertIsDisplayed()
    }

    @Test
    fun pillHiddenForNonClaude() = runComposeUiTest {
        setPlatformContent { panel(app(), codexSession, nativeContent = fakeNative) }
        onNodeWithTag("agent_view_chat").assertDoesNotExist()
        onNodeWithTag("agent_view_native").assertDoesNotExist()
        // ...and the native panel is never composed for a session that has no native view.
        onNodeWithTag("pane_native").assertDoesNotExist()
    }

    @Test
    fun pillHiddenWhenTheCallerSuppliesNoNativeSurface() = runComposeUiTest {
        setPlatformContent { panel(app(), claudeSession) }
        onNodeWithTag("agent_view_chat").assertDoesNotExist()
    }

    @Test
    fun togglingSwapsTheBodyButKeepsChatInTreeAndTheHeaderOnScreen() = runComposeUiTest {
        capturedOnExit = null
        setPlatformContent { panel(app(), claudeSession, nativeContent = fakeNative) }
        // Native is lazy: not composed until first opened.
        onNodeWithTag("chat_body").assertIsDisplayed()
        onNodeWithTag("native_fake").assertDoesNotExist()

        onNodeWithTag("agent_view_native").performClick()
        waitForIdle()
        onNodeWithTag("native_fake").assertIsDisplayed()
        // Chat STAYS in the tree (keep-alive, not remounted) so its draft/scroll survive...
        onNodeWithTag("chat_body").assertExists()
        // ...and the header stays on screen, so the way back is always reachable.
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
        setPlatformContent { panel(app(), claudeSession, nativeContent = fakeNative) }
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
        val store = app()
        setPlatformContent {
            panel(
                store, current,
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
