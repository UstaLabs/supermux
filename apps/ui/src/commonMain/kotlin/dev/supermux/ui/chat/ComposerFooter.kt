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
// The git side is a sign, not a menu (#72): "3 changed", "2 commits not in dev"… and nothing when
// the tree holds nothing. It opens Changes; Fetch/Pull/Push are in the chat's ⋮ menu.
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Tune
import dev.supermux.ui.widgets.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.shell.NewTabGlyphs
import dev.supermux.ui.ChatDetailLevel
import kotlinx.coroutines.launch
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.theme.Radii

/**
 * One 24dp line under the composer card. Renders nothing but the detail chip when the session has
 * no git context at all, so a non-repo work tree gets no empty right-hand slot.
 */
@Composable
fun ComposerFooter(
    session: SessionInfo,
    modifier: Modifier = Modifier,
    /** Opens the Changes view; null on a host that has none, where the sign is only a caption. */
    onOpenChanges: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val uiPrefs = LocalUiPrefs.current
    val detail by uiPrefs.chatDetailLevel.collectAsState(ChatDetailLevel.MEDIUM)
    var detailOpen by remember { mutableStateOf(false) }
    // Touch AND narrow (Compact) opens the detail menu as a bottom sheet; anything else keeps the popover.
    val sheets = !dev.supermux.ui.adaptive.LocalPointerAvailable.current &&
        dev.supermux.ui.adaptive.LocalWindowWidthClass.current == dev.supermux.ui.adaptive.WindowWidthClass.Compact

    Row(
        modifier.fillMaxWidth().height(24.dp).testTag("composer_footer"),
        verticalAlignment = Alignment.CenterVertically,
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
            val detailRows = listOf(
                ChatDetailLevel.LOW to "Messages only · tools on status line",
                ChatDetailLevel.MEDIUM to "Messages + tool summaries",
                ChatDetailLevel.HIGH to "Everything, tool args and all",
            )
            if (sheets && detailOpen) {
                PickerSheet(
                    title = "Detail",
                    options = detailRows.map { (level, hint) -> level.wire to "${level.label} · $hint" },
                    current = detail.wire,
                    onPick = { wire ->
                        detailRows.firstOrNull { it.first.wire == wire }?.let { (level, _) ->
                            scope.launch { uiPrefs.putChatDetailLevel(level) }
                        }
                    },
                    onDismiss = { detailOpen = false },
                )
            }
            DropdownMenu(expanded = detailOpen && !sheets, onDismissRequest = { detailOpen = false }) {
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

        // Git: a quiet sign of what this session's work tree holds, only when it holds something
        // (#72). Clicking it opens Changes; the git commands themselves live in the chat's ⋮ menu.
        val sign = composerGitSign(session.git)
        if (sign != null) {
            Row(
                Modifier.weight(1f).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                FooterChip(
                    icon = { tint -> Icon(NewTabGlyphs.Changes, null, tint = tint, modifier = Modifier.size(12.dp)) },
                    label = sign.label(),
                    trailingChevron = false,
                    tag = "footer_git_sign",
                    onClick = onOpenChanges,
                )
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
            .clip(RoundedCornerShape(Radii.pill))
            .then(
                if (onClick == null) Modifier
                else Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
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
            modifier = Modifier.weight(1f, fill = false),
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
