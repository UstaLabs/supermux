// The thin status strip UNDER the composer card. Shared by both hosts since D3; on Android it
// renders only under a non-Compact window (the phone composer stays footer-less).
//
// Original desktop note follows.
// The thin status strip UNDER the composer card: transcript detail on the left, the session's
// git context on the right.
//
// Why here and not in the header: both facts describe the conversation you are about to add to,
// not the session's identity. Detail level was buried two levels deep in the ⋮ menu even though
// it changes what every row on screen looks like; branch/worktree was visible only in the
// sidebar. The composer is where the eye already is before sending, so this is where they belong.
//
// The git side is the ONLY place these ops live now: the workspace header's duplicate strip is
// gone (AppShell), so a work tree's Fetch/Pull/Push is offered exactly once — from the chat whose
// next message is going to change that tree.
package dev.supermux.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Tune
import dev.supermux.ui.widgets.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.gitOpResultLabel
import dev.supermux.chat.shouldPublish
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.net.GitOpResult
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.gitBadge
import dev.supermux.ui.ChatDetailLevel
import kotlinx.coroutines.launch
import dev.supermux.ui.prefs.LocalUiPrefs

/**
 * One 24dp line under the composer card. Renders nothing but the detail chip when the session has
 * no git context at all, so a non-repo work tree gets no empty right-hand slot.
 */
@Composable
fun ComposerFooter(
    session: SessionInfo,
    modifier: Modifier = Modifier,
    onFetch: (suspend () -> GitOpResult?)? = null,
    onPull: (suspend () -> GitOpResult?)? = null,
    onPush: (suspend () -> GitOpResult?)? = null,
    onPublish: (suspend () -> GitOpResult?)? = null,
) {
    // A host with no git ops wired (Android, which reaches git through its own badge) still gets
    // the branch label — it just is not a menu, so the strip never offers a dead Fetch/Pull/Push.
    val gitOps = onFetch != null && onPull != null && onPush != null && onPublish != null
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val uiPrefs = LocalUiPrefs.current
    val detail by uiPrefs.chatDetailLevel.collectAsState(ChatDetailLevel.MEDIUM)
    var detailOpen by remember { mutableStateOf(false) }
    var gitOpen by remember { mutableStateOf(false) }
    var gitResult by remember(session.id) { mutableStateOf<String?>(null) }
    // Monotonic op token — same guard GitBadgeMenu uses: a slow op launched first must not
    // clobber the label of a fast op launched after it.
    var seq by remember(session.id) { mutableStateOf(0) }
    // A session switch with the menu open must not leave it bound to the old session's callbacks.
    LaunchedEffect(session.id) { gitOpen = false }

    fun run(op: String, call: suspend () -> GitOpResult?) {
        gitOpen = false
        val token = ++seq
        scope.launch {
            val label = gitOpResultLabel(op, call())
            if (token == seq) gitResult = label
        }
    }

    Row(
        modifier.fillMaxWidth().height(24.dp).testTag("composer_footer"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box {
            FooterChip(
                icon = { tint ->
                    Icon(Icons.Outlined.Tune, null, tint = tint, modifier = Modifier.size(12.dp))
                },
                label = "Detail: ${detail.label}",
                trailingChevron = true,
                tag = "footer_detail",
                onClick = { detailOpen = true },
            )
            DropdownMenu(expanded = detailOpen, onDismissRequest = { detailOpen = false }) {
                // Same three rows (and the same one-line explanations) the ⋮ submenu offers, so
                // the two entry points can never drift apart.
                listOf(
                    ChatDetailLevel.LOW to "Messages only · tools on status line",
                    ChatDetailLevel.MEDIUM to "Messages + tool summaries",
                    ChatDetailLevel.HIGH to "Everything, tool args and all",
                ).forEach { (level, hint) ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    level.label,
                                    color = if (level == detail) cs.primary else cs.onSurface,
                                )
                                Text(hint, fontSize = 11.sp, color = cs.onSurfaceVariant)
                            }
                        },
                        modifier = Modifier.testTag("footer_detail_${level.wire}"),
                        onClick = { scope.launch { uiPrefs.putChatDetailLevel(level) }; detailOpen = false },
                    )
                }
            }
        }

        // Git context: the branch this session actually writes to (a worktree session's pinned
        // branch, else the ref it compares against), plus the badge's ahead/behind/dirty digest.
        val branch = session.session_branch ?: session.git?.compareRef?.takeIf { it.isNotEmpty() }
        if (branch != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                gitResult?.let {
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = 8.dp).testTag("footer_git_result"),
                    )
                }
                Box {
                    val badge = gitBadge(session.git)?.text?.takeIf { it.isNotEmpty() && it != "✓" }
                    FooterChip(
                        icon = { tint ->
                            Icon(
                                Icons.Outlined.CallSplit,
                                null,
                                tint = tint,
                                modifier = Modifier.size(12.dp),
                            )
                        },
                        label = if (badge == null) branch else "$branch  $badge",
                        trailingChevron = gitOps,
                        tag = "footer_branch",
                        onClick = if (!gitOps) null else ({
                            // A fresh open clears the last op's label and bumps the token, so an
                            // op still in flight from a prior open can't write over this clear.
                            gitResult = null
                            seq++
                            gitOpen = true
                        }),
                    )
                    DropdownMenu(expanded = gitOpen, onDismissRequest = { gitOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Fetch") },
                            modifier = Modifier.testTag("footer_git_fetch"),
                            onClick = { onFetch?.let { run("Fetch", it) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Pull") },
                            modifier = Modifier.testTag("footer_git_pull"),
                            onClick = { onPull?.let { run("Pull", it) } },
                        )
                        // Publish (no upstream yet) and Push are the same slot — you can only ever
                        // need one of them, and offering both invites picking the wrong one.
                        if (shouldPublish(session.git)) {
                            DropdownMenuItem(
                                text = { Text("Publish") },
                                modifier = Modifier.testTag("footer_git_publish"),
                                onClick = { onPublish?.let { run("Publish", it) } },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Push") },
                                modifier = Modifier.testTag("footer_git_push"),
                                onClick = { onPush?.let { run("Push", it) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A borderless, muted chip: no fill and no outline, so the strip reads as a caption rather
 *  than a second row of controls competing with the composer's own. */
@Composable
private fun FooterChip(
    icon: @Composable (tint: Color) -> Unit,
    label: String,
    trailingChevron: Boolean,
    tag: String,
    onClick: (() -> Unit)?,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (onClick == null) Modifier else Modifier.clickable(onClick = onClick),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        icon(cs.onSurfaceVariant)
        Text(
            label,
            fontSize = 11.sp,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailingChevron) {
            Icon(
                Icons.Outlined.KeyboardArrowDown,
                null,
                tint = cs.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
