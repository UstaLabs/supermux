// The workspace's own header strip — one line above the pane tree.
//
// It exists for exactly one thing today: the git badge + its Fetch/Pull/Publish-or-Push menu.
// That affordance used to hang off the SESSION header in the old single-session shell
// (SessionDetail), which drew it once per session — so two chats sharing one work tree drew the
// same badge twice and either copy could fire an op against the same repo. The work tree belongs
// to the WORKSPACE (`workdir` / `repo_root` / `base_branch` / `branch` are workspace columns), so
// the badge belongs here: drawn once, for the workspace.
//
// The badge's DATA and its ops are still session-keyed on the broker (`GET/POST
// /sessions/<id>/git/*`, `SessionInfo.git`), so the workspace resolves ONE session — its primary
// chat, the same one WorkspaceListPanel already reads `git` off for the sidebar row — and drives
// the menu through it. A workspace with no chat session, or a non-repo work tree, draws no strip
// at all rather than an empty bar.
package dev.supermux.desktop.shell

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.theme.Space
import dev.supermux.net.GitOpResult
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.gitBadge

/**
 * The workspace header strip. Renders NOTHING when [gitSession] is null (no chat session to read
 * git off) or when that session has no git badge (non-repo work tree) — an empty bar would be
 * chrome for its own sake, and the workspace shell had no header before this.
 *
 * [forceGitMenu] is the off-by-default `SM_GIT_MENU` headless hook, forwarded straight to
 * [GitBadgeMenu] — see [GitMenuForceOp] for why Push/Publish can never be auto-fired.
 */
@Composable
fun WorkspaceHeader(
    gitSession: SessionInfo?,
    onFetch: suspend () -> GitOpResult?,
    onPull: suspend () -> GitOpResult?,
    onPush: suspend () -> GitOpResult?,
    onPublish: suspend () -> GitOpResult?,
    modifier: Modifier = Modifier,
    forceGitMenu: GitMenuForceOp? = null,
    onForceGitMenuConsumed: () -> Unit = {},
    onMoveWorkspaceToNewWindow: (() -> Unit)? = null,
) {
    if (gitSession == null || gitBadge(gitSession.git) == null) return
    val cs = MaterialTheme.colorScheme
    ContextMenuArea(
        items = {
            if (onMoveWorkspaceToNewWindow == null) emptyList()
            else listOf(ContextMenuItem("Move workspace to New Window") { onMoveWorkspaceToNewWindow() })
        },
    ) {
    Row(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(cs.surfaceContainerLow)
            .padding(horizontal = Space.sm)
            .testTag("workspace_header"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GitBadgeMenu(
            session = gitSession,
            onFetch = onFetch,
            onPull = onPull,
            onPush = onPush,
            onPublish = onPublish,
            forceOp = forceGitMenu,
            onForceOpConsumed = onForceGitMenuConsumed,
        )
    }
    }
}
