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

import androidx.compose.material3.PlainTooltip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.automirrored.filled.CallMerge
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.localizedPlural
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.automirrored.filled.CallSplit
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
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.StringKey
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedLabels

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
    /** #72: the ⟳ / ☁ item: sync (pull, then push) or publish this chat's branch; null = only a caption. */
    onGitSync: (suspend (op: String) -> dev.supermux.net.GitOpResult?)? = null,
    /** #72: the branch's PR / MR (#12 ✓ / ✗ next to the branch); null = none shown. */
    onGitPr: (suspend () -> dev.supermux.net.PrRead?)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val uiPrefs = LocalUiPrefs.current
    val detail by uiPrefs.chatDetailLevel.collectAsState(ChatDetailLevel.MEDIUM)
    var detailOpen by remember { mutableStateOf(false) }
    // Touch AND narrow (Compact) opens the detail menu as a bottom sheet; anything else keeps the popover.
    val sheets = !dev.supermux.ui.adaptive.LocalPointerAvailable.current &&
        dev.supermux.ui.adaptive.LocalWindowWidthClass.current == dev.supermux.ui.adaptive.WindowWidthClass.Compact

    val levelNames = localizedLabels(ChatDetailLevel.entries, ::detailLevelRes)
    val detailRows = listOf(
        ChatDetailLevel.LOW to localized(Str.composer_detail_low_hint),
        ChatDetailLevel.MEDIUM to localized(Str.composer_detail_medium_hint),
        ChatDetailLevel.HIGH to localized(Str.composer_detail_high_hint),
    )
    val detailLabel = localized(Str.composer_detail_chip, levelNames.getValue(detail))
    val detailTitle = localized(Str.composer_detail_title)

    Row(
        modifier.fillMaxWidth().height(24.dp).testTag("composer_footer"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FooterChip(
                icon = { tint ->
                    Icon(Icons.Outlined.Tune, null, tint = tint, modifier = Modifier.size(12.dp))
                },
                label = detailLabel,
                trailingChevron = true,
                tag = "footer_detail",
                onClick = { detailOpen = true },
            )
            if (sheets && detailOpen) {
                PickerSheet(
                    title = detailTitle,
                    options = detailRows.map { (level, hint) -> level.wire to "${levelNames.getValue(level)} · $hint" },
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
                detailRows.forEach { (level, hint) ->
                    DropdownMenuItem(
                        text = {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    levelNames.getValue(level),
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

        // Git, as VS Code's status bar has it (#72 v4): the branch you are on (always, even with no changes), its
        // ⟳ ↓↑ against origin (a click syncs) or ☁ Publish, and "N changed" (a click opens Source Control).
        val git = session.git
        if (git != null) {
            var syncing by remember { mutableStateOf(false) }
            // A failed sync is never silent: the chip says so (hover: why) until the next try.
            var failed by remember(session.id) { mutableStateOf<String?>(null) }
            // An older broker sends no branch: a plain checkout's compareRef is its upstream ("origin/main") once published.
            val branch = git.branch ?: session.session_branch
                ?: git.compareRef.takeIf { git.mode == "remote" }?.let { if (git.unpublished == true) it else it.substringAfter('/') } // platform-ok: a git ref name
            Row(
                Modifier.weight(1f).padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (branch != null) FooterChip(
                    icon = { tint -> Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = tint, modifier = Modifier.size(12.dp)) },
                    label = branch,
                    trailingChevron = false,
                    tag = "footer_git_branch",
                    onClick = onOpenChanges,
                )
                val sync = git.sync
                val published = sync != null || (git.mode == "remote" && git.unpublished != true)
                if (onGitSync != null && branch != null) {
                    val ahead = sync?.ahead ?: git.ahead.takeIf { git.mode == "remote" } ?: 0
                    val behind = sync?.behind ?: git.behind.takeIf { git.mode == "remote" } ?: 0
                    FooterChip(
                        icon = { tint -> Icon(if (published) Icons.Filled.Sync else Icons.Filled.CloudUpload, null, tint = tint, modifier = Modifier.size(12.dp)) },
                        label = when {
                            syncing -> "…"
                            failed != null -> localized(Str.scm_sync_failed_short)
                            !published -> localized(Str.scm_publish_short)
                            else -> listOfNotNull(behind.takeIf { it > 0 }?.let { "$it↓" }, ahead.takeIf { it > 0 }?.let { "$it↑" }).joinToString(" ")
                        },
                        trailingChevron = false,
                        tag = "footer_git_sync",
                        hint = failed,
                        error = failed != null,
                        onClick = {
                            when {
                                syncing -> Unit
                                // No remote: Source Control asks where to publish (GitHub, GitLab, a URL), as VS Code does.
                                failed != null || git.hasRemote == false -> { failed = null; onOpenChanges?.invoke() }
                                else -> scope.launch {
                                    syncing = true
                                    val ok = { r: dev.supermux.net.GitOpResult? -> r != null && r.status in setOf("pushed", "up_to_date", "clean", "ok", "") }
                                    val r = if (!published) onGitSync("publish") else onGitSync("pull").takeIf { !ok(it) } ?: onGitSync("push")
                                    failed = if (ok(r)) null else localized(Str.scm_failed, r?.message ?: r?.status ?: localized(Str.scm_offline))
                                    syncing = false
                                }
                            }
                        },
                    )
                }
                // The branch's pull / merge request, as the GitHub Pull Requests extension puts it in the status bar.
                var pr by remember(session.id) { mutableStateOf<dev.supermux.net.PrInfo?>(null) }
                if (onGitPr != null && branch != null) LaunchedEffect(session.id, branch) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        while (true) { pr = onGitPr()?.pr?.takeIf { it.state == "open" }; kotlinx.coroutines.delay(30_000) }
                    }
                }
                pr?.let { p ->
                    val bad = p.checks.count { it.state == "bad" }
                    val run = p.checks.count { it.state == "run" }
                    FooterChip(
                        icon = { tint -> Icon(Icons.AutoMirrored.Filled.CallMerge, null, tint = tint, modifier = Modifier.size(12.dp)) },
                        label = (if (p.forge == "gitlab") "!" else "#") + p.number + when { bad > 0 -> " ✗$bad"; run > 0 -> " ⟳"; p.checks.isNotEmpty() -> " ✓"; else -> "" }, // i18n-ok: GitHub's / GitLab's number signs and status marks
                        trailingChevron = false,
                        tag = "footer_git_pr",
                        onClick = onOpenChanges,
                    )
                }
                if (git.dirty > 0) FooterChip(
                    icon = { tint -> Icon(NewTabGlyphs.Changes, null, tint = tint, modifier = Modifier.size(12.dp)) },
                    label = localizedPlural(Plurals.git_sign_changed, git.dirty, git.dirty),
                    trailingChevron = false,
                    tag = "footer_git_sign",
                    onClick = onOpenChanges,
                )
            }
        }
    }
}

/** The detail level's name, shared with Settings › General. */
private fun detailLevelRes(level: ChatDetailLevel): StringKey = when (level) {
    ChatDetailLevel.LOW -> Str.general_chat_detail_low
    ChatDetailLevel.MEDIUM -> Str.general_chat_detail_medium
    ChatDetailLevel.HIGH -> Str.general_chat_detail_high
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
    /** #72: shown on hover (why a sync failed). */
    hint: String? = null,
    /** #72: the chip says something went wrong. */
    error: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val tone = if (error) cs.error else cs.onSurfaceVariant
    val chip: @Composable () -> Unit = { Row(
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
        icon(tone)
        Text(
            label,
            fontSize = 11.sp,
            color = tone,
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
    } }
    if (hint == null) chip() else FooterHint(hint, chip)
}

/** #72: why a footer chip says something went wrong, on hover. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FooterHint(text: String, content: @Composable () -> Unit) {
    androidx.compose.material3.TooltipBox(
        positionProvider = androidx.compose.material3.TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip(modifier = Modifier.widthIn(max = 320.dp)) { Text(text, fontSize = 12.sp) } },
        state = androidx.compose.material3.rememberTooltipState(),
        content = content,
    )
}
