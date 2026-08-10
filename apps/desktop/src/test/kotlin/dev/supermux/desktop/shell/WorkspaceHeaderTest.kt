package dev.supermux.desktop.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.GitOpResult
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The git badge moved off the session header (SessionDetail, deleted) and onto the WORKSPACE — one
 * strip above the pane tree, drawn once for the work tree rather than once per chat.
 *
 * These cases are the surviving half of SessionDetailTest's
 * `forceGitMenuOpensTheBadgeDropdownAndConsumesTheOneShotFlag`, rewritten against the new owner,
 * plus the two gates the strip adds (no chat session / non-repo work tree → no strip at all).
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceHeaderTest {
    private val repoSession = SessionInfo(
        id = "s1", name = "demo", workdir = "/w/s1", agent = "claude",
        git = GitLiteStatusDto(mode = "base", compareRef = "main", ahead = 2, behind = 0, dirty = 1),
    )
    private val plainSession = SessionInfo(id = "s1", name = "demo", workdir = "/w/s1", agent = "claude")

    private fun noOps(): suspend () -> GitOpResult? = { null }

    @Test
    fun repoWorkspaceDrawsTheBadgeStrip() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceHeader(
                    gitSession = repoSession,
                    onFetch = noOps(), onPull = noOps(), onPush = noOps(), onPublish = noOps(),
                )
            }
        }
        onNodeWithTag("workspace_header").assertIsDisplayed()
        onNodeWithTag("git_badge").assertIsDisplayed()
    }

    @Test
    fun nonRepoWorkspaceDrawsNoStripAtAll() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceHeader(
                    gitSession = plainSession,
                    onFetch = noOps(), onPull = noOps(), onPush = noOps(), onPublish = noOps(),
                )
            }
        }
        // No empty bar: the workspace shell had no header before this, and a badge-less strip
        // would be chrome for its own sake.
        onNodeWithTag("workspace_header").assertDoesNotExist()
    }

    @Test
    fun workspaceWithNoChatSessionDrawsNoStrip() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceHeader(
                    gitSession = null,
                    onFetch = noOps(), onPull = noOps(), onPush = noOps(), onPublish = noOps(),
                )
            }
        }
        onNodeWithTag("workspace_header").assertDoesNotExist()
    }

    @Test
    fun forceGitMenuOpensTheBadgeDropdownAndConsumesTheOneShotFlag() = runComposeUiTest {
        // Rewritten from SessionDetailTest: the SM_GIT_MENU hook now lands on the workspace header.
        var consumed = 0
        var force by mutableStateOf<GitMenuForceOp?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceHeader(
                    gitSession = repoSession,
                    onFetch = noOps(), onPull = noOps(), onPush = noOps(), onPublish = noOps(),
                    forceGitMenu = force,
                    onForceGitMenuConsumed = { consumed++ },
                )
            }
        }
        onNodeWithTag("git_fetch").assertDoesNotExist()
        runOnIdle { force = GitMenuForceOp.OPEN }
        waitForIdle()
        onNodeWithTag("git_fetch").assertIsDisplayed()
        runOnIdle { assertEquals(1, consumed) }
    }

    @Test
    fun fetchRunsAgainstTheWorkspacesOwnSession() = runComposeUiTest {
        // The op target is the workspace's primary chat, resolved once — so a workspace with two
        // chats fires exactly one Fetch, not one per chat header.
        var fetches = 0
        var force by mutableStateOf<GitMenuForceOp?>(null)
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                WorkspaceHeader(
                    gitSession = repoSession,
                    onFetch = { fetches++; GitOpResult(status = "ok") },
                    onPull = noOps(), onPush = noOps(), onPublish = noOps(),
                    forceGitMenu = force,
                    onForceGitMenuConsumed = {},
                )
            }
        }
        runOnIdle { force = GitMenuForceOp.FETCH }
        waitForIdle()
        runOnIdle { assertEquals(1, fetches) }
    }
}
