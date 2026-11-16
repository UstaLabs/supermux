package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.SessionPrDto
import dev.supermux.ui.platform.FakePlatform
import kotlin.test.Test

/**
 * #152 C4: a session started from a pull / merge request says so in the chat header, after the crumb: "#123 · pushes
 * update it" when its pushes reach the request, "· review only" when they can't (a fork that allows no edits).
 */
@OptIn(ExperimentalTestApi::class)
class ChatHeaderPrTest {
    private val base = SessionInfo(id = "s1", name = "Fix login redirect loop", workdir = "/home/u/projects/storefront", agent = "claude")

    @Composable
    private fun Panel(session: SessionInfo) {
        val app = remember { testHostStore() }
        ChatPanel(
            session = session,
            state = rememberChatState(app, session.id),
            actions = rememberChatActions(app, session),
            draft = "",
            onDraftChange = {},
            showHeader = true,
        )
    }

    @Test fun a_pull_request_session_says_its_pushes_update_it() = runComposeUiTest {
        val pr = SessionPrDto("github", 123, "https://github.com/acme/storefront/pull/123")
        setPlatformContent(FakePlatform()) { Panel(base.copy(source = "pr", pr = pr, push_remote = "origin")) }
        onNodeWithTag("chat_header_pr").assertTextEquals("#123 · pushes update it")
    }

    @Test fun a_merge_request_its_pushes_cannot_reach_is_review_only() = runComposeUiTest {
        val mr = SessionPrDto("gitlab", 7, "https://gitlab.com/acme/storefront/-/merge_requests/7")
        setPlatformContent(FakePlatform()) { Panel(base.copy(source = "pr", pr = mr)) }
        onNodeWithTag("chat_header_pr").assertTextEquals("!7 · review only")
    }

    @Test fun any_other_session_has_no_chip() = runComposeUiTest {
        setPlatformContent(FakePlatform()) { Panel(base.copy(source = "new")) }
        waitForIdle()
        onNodeWithTag("chat_header_pr").assertDoesNotExist()
    }
}
