package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.semantics.SemanticsActions
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.FileManager
import dev.supermux.ui.platform.LocalSessionOnThisComputer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #149: the chat header's folder crumb opens the session's folder in the system file manager — only when the
 * session's host is this computer, and only on a device that has a file manager. Elsewhere it stays plain text.
 */
@OptIn(ExperimentalTestApi::class)
class ChatHeaderFolderTest {
    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/home/u/projects/supermux", agent = "claude")

    @Composable
    private fun Panel(onThisComputer: Boolean) {
        val app = androidx.compose.runtime.remember { testHostStore() }
        CompositionLocalProvider(LocalSessionOnThisComputer provides { onThisComputer }) {
            ChatPanel(
                session = session,
                state = rememberChatState(app, session.id),
                actions = rememberChatActions(app, session),
                draft = "",
                onDraftChange = {},
                showHeader = true,
            )
        }
    }

    private fun platform(manager: FileManager?) = FakePlatform().apply { fileManager = manager }

    @Test fun onThisComputer_theCrumbOpensTheSessionsFolder() = runComposeUiTest {
        val platform = platform(FileManager.Finder)
        setPlatformContent(platform) { Panel(onThisComputer = true) }
        onNodeWithTag("chat_header_folder")
            .assert(SemanticsMatcher("labelled Show in Finder") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show in Finder" })
            .performClick()
        waitForIdle()
        assertEquals(listOf("/home/u/projects/supermux"), platform.openedFolders)
    }

    @Test fun aRemoteHostsCrumbOpensNothing() = runComposeUiTest {
        val platform = platform(FileManager.Explorer)
        setPlatformContent(platform) { Panel(onThisComputer = false) }
        onNodeWithTag("chat_header_folder").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)).performClick()
        waitForIdle()
        assertEquals(emptyList(), platform.openedFolders)
    }

    @Test fun aDeviceWithNoFileManagerOpensNothing() = runComposeUiTest {
        val platform = platform(null)
        setPlatformContent(platform) { Panel(onThisComputer = true) }
        onNodeWithTag("chat_header_folder").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        assertEquals(emptyList(), platform.openedFolders)
    }
}
