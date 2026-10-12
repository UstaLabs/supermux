package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.net.ProxyDto
import dev.supermux.proto.SessionInfo
import dev.supermux.state.HostStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The chat header of the shared [ChatPanel]: the session-links (proxies) slot. Moved from desktop with its case names when both apps' `ChatPanel` collapsed into `:ui`
 * (cluster D4).
 *
 * The links menu itself stays in desktop's `shell/` (cluster G moves it), so the panel reaches it
 * through the `headerLinks` SLOT and owns only the load-on-open. These cases therefore drive a
 * pure-Compose stand-in slot with the same contract.
 *
 * The store is built with `connectOnInit = false` so no WebSocket/HTTP is opened.
 */
@OptIn(ExperimentalTestApi::class)
class ChatHeaderTest {
    private fun app(): HostStore = testHostStore()

    private val claudeSession =
        SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude")

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
}
