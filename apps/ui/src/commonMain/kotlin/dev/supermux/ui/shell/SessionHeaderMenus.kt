// Cluster G7: the ONE set of session-header affordances, for both hosts.
//
// Desktop's `shell/SessionHeaderMenus.kt` is the base — the git-badge count menu
// (Fetch/Pull/Publish-or-Push), the session-links (proxies) menu and the ⋮ overflow
// (Detail/Continue/Rename/Mute/Kill), each a focused, individually runComposeUiTest-able
// composable rather than one header with a dozen parameters. Android's `workspace/ChatViewChrome.kt`
// is folded in as the Compact/Touch branch: [ChatViewHeader] (the tablet chat pane's own bar),
// [PhoneTabChatOverflow] (the phone tab strip's trailing slot), the git rows inside [OverflowMenu],
// and [gitOpResultText] — whose result now reaches the user through `Platform.notices` (a toast on
// Android, a snackbar on desktop) instead of naming `Toast` here.
//
// Where they differed, and what won:
//   • Git ops. Desktop awaits the op and shows a small transient inline label next to the badge
//     ([GitBadgeMenu], tag `git_op_result`); Android fires it and toasts. BOTH survive: the badge
//     menu keeps its inline label, and the overflow's git rows (Android's shape) report through
//     `notices`. The ops themselves come from ONE holder ([ShellActions.gitFetch] …), so neither
//     host reaches for a store here.
//   • Link opening. Desktop opened through AWT's desktop browse, Android via `LocalUriHandler`.
//     Both are `Platform.openUrl` now; [SessionLinksMenu] keeps the injectable `onOpenUrl` so a
//     test can capture the URL without spawning a browser, and gains Android's external-link
//     leading icon.
//   • Icons. Android's `R.drawable.ic_*` become Material icons (the G5 precedent).
//   • Tags. Every tag from both files is kept. The overflow's two are CALLER-CHOSEN
//     ([OverflowMenu]'s `buttonTag`/`detailTag`) because desktop's chat header and Android's
//     workspace chrome address the very same menu by two different names, and both suites (plus
//     device automation) must keep working.
//
// Right-click (the F3 `RowContextMenu` precedent): the overflow is wrapped in a [RowContextMenu]
// carrying Rename/Mute/Kill, so a pointer host can reach them without opening the menu. On a host
// whose context menu is INERT (`LocalContextMenuAvailable == false` — Android) that wrapper is a
// passthrough, which is exactly why the ⋮ button itself is never gated on it: an inert-menu host
// must still have a visible affordance for every action.
//
// Headless verification (M4c Task 3): there is no input injection under Xvfb, so the git and links
// menus take an optional one-shot force-open param (`ShellUiState.forceGitMenuFor` /
// `forceLinksMenuFor`, set by the off-by-default SM_GIT_MENU/SM_LINKS_MENU env hooks in Main.kt)
// that expands its DropdownMenu exactly the way a real click would. [GitMenuForceOp] additionally
// accepts FETCH/PULL to fire that op through the SAME `run(...)` path a click uses — see its KDoc
// for why Push/Publish are structurally excluded. [SessionLinksMenu] is open-ONLY.
package dev.supermux.ui.shell

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.gitOpResultLabel
import dev.supermux.chat.shouldPublish
import dev.supermux.net.GitOpResult
import dev.supermux.net.ModelInfo
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.gitBadge
import dev.supermux.state.ContinueHandoff
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.ui.chat.ContinueConversationFlow
import dev.supermux.ui.chat.ContinueMenuItem
import dev.supermux.ui.chat.FinishBindings
import dev.supermux.ui.chat.FinishHeaderButton
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.session.RowContextMenu
import dev.supermux.ui.session.RowContextMenuEntry
import dev.supermux.ui.session.SessionStatusRail
import dev.supermux.ui.session.headerGitBadgeLabel
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlinx.coroutines.launch

// ── Pure, testable bits (no Compose) ──────────────────────────────────────────────────

/** The exposed proxies belonging to [session] — the broker returns ALL proxies, so the links menu
 *  filters by session name client-side (Android threaded a pre-filtered list; this filters here). */
fun sessionProxies(proxies: List<ProxyDto>, session: SessionInfo): List<ProxyDto> =
    proxies.filter { it.sessionName == session.name }

/**
 * Android's user-facing wording for a completed git op — the text that goes to `Platform.notices`.
 *
 * Distinct from `:shared`'s [gitOpResultLabel], which is the COMPACT inline form desktop shows next
 * to the badge; this one spells the failure modes out ("Push rejected — pull first") because a
 * toast is all the user gets.
 */
fun gitOpResultText(r: GitOpResult?): String = when (r?.status) {
    null -> "Failed"
    "pushed" -> "Pushed"
    "up_to_date" -> "Up to date"
    "clean" -> "Pulled"
    "rejected_non_ff" -> "Push rejected — pull first"
    "conflict" -> "Conflict in ${r.files.size} file(s)"
    "dirty" -> "Uncommitted changes block the pull"
    "auth_failed" -> "Auth failed"
    "error" -> r.message ?: "Error"
    else -> r.status
}

/**
 * The restricted force-op set the headless `SM_GIT_MENU` hook (Main.kt) may drive against
 * [GitBadgeMenu]: [OPEN] only expands the dropdown (no click), [FETCH]/[PULL] additionally fire
 * that op through the SAME `run(...)` path a real click uses. There is deliberately NO Push/Publish
 * member — those mutate a real remote, so no env hook may ever auto-fire them; menu-RENDER is the
 * only headless surface for those two (screenshot the item, never click it).
 */
enum class GitMenuForceOp { OPEN, FETCH, PULL }

/**
 * Fire one of a session's git ops and report the outcome through `Platform.notices` — Android's
 * `toastGitOp`, minus the `Toast`. Returns nothing: the notice IS the feedback.
 */
@Composable
fun rememberGitOpRunner(sessionId: String, actions: ShellActions): (String) -> Unit {
    val notices = LocalPlatform.current.notices
    val scope = rememberCoroutineScope()
    return remember(sessionId, actions, notices, scope) {
        { op: String ->
            scope.launch {
                val result = when (op) {
                    "fetch" -> actions.gitFetch(sessionId)
                    "pull" -> actions.gitPull(sessionId)
                    "push" -> actions.gitPush(sessionId)
                    "publish" -> actions.gitPublish(sessionId)
                    else -> null
                }
                notices.show(gitOpResultText(result))
            }
            Unit
        }
    }
}

// ── GitBadgeMenu ───────────────────────────────────────────────────────────────────────

/**
 * The header git badge (ahead/behind/dirty counts from shared [gitBadge]) rendered as a clickable
 * pill that drops a menu of Fetch / Pull / Publish-or-Push. Renders NOTHING when `session.git` is
 * null (non-repo session) — the whole affordance is gated on a badge existing.
 *
 * Each op callback is a `suspend () -> GitOpResult?`; the menu awaits it and shows a small
 * transient result label (tag `git_op_result`) next to the badge, cleared the next time the menu
 * opens. That inline label is desktop's shape and stays desktop's: the touch branch reports through
 * `Platform.notices` from [OverflowMenu]'s git rows instead.
 */
@Composable
fun GitBadgeMenu(
    session: SessionInfo,
    onFetch: suspend () -> GitOpResult?,
    onPull: suspend () -> GitOpResult?,
    onPush: suspend () -> GitOpResult?,
    onPublish: suspend () -> GitOpResult?,
    modifier: Modifier = Modifier,
    // Off-by-default headless hook (SM_GIT_MENU, Main.kt) delivery: a one-shot [GitMenuForceOp].
    // OPEN just expands the dropdown; FETCH/PULL also fire that op via the real `run(...)` path
    // below (same as a live click) so the inline `git_op_result` label can be screenshot under
    // Xvfb. Applied once, then [onForceOpConsumed] clears the source.
    forceOp: GitMenuForceOp? = null,
    onForceOpConsumed: () -> Unit = {},
) {
    val badge = gitBadge(session.git) ?: return
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var result by remember(session.id) { mutableStateOf<String?>(null) }
    // Monotonic op token: each launch (and each fresh menu-open) bumps it, and a completing op only
    // writes [result] when its captured token is still current. Without this, out-of-order
    // completions race — a slow op launched first would clobber a fast op launched later
    // (Fetch·slow → reopen → Pull·fast shows Pull, then late Fetch overwrites it). Keyed on session.
    var seq by remember(session.id) { mutableStateOf(0) }
    // A session switch with the menu open must not leave it bound to the new session's callbacks.
    LaunchedEffect(session.id) { expanded = false }

    // Fire an op on a coroutine, record its outcome into the inline result label (only if this op is
    // still the latest one), and close the menu.
    fun run(op: String, call: suspend () -> GitOpResult?) {
        expanded = false
        val token = ++seq
        scope.launch {
            val label = gitOpResultLabel(op, call())
            if (token == seq) result = label
        }
    }

    // SM_GIT_MENU headless hook delivery — see [GitMenuForceOp] KDoc for the safety rationale.
    LaunchedEffect(forceOp) {
        when (forceOp) {
            null -> {}
            GitMenuForceOp.OPEN -> {
                result = null
                seq++
                expanded = true
            }
            GitMenuForceOp.FETCH -> run("Fetch", onFetch)
            GitMenuForceOp.PULL -> run("Pull", onPull)
        }
        if (forceOp != null) onForceOpConsumed()
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box {
            Text(
                text = headerGitBadgeLabel(badge),
                color = cs.onSurfaceVariant,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag("git_badge")
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(6.dp))
                    .clickable {
                        // A fresh open clears the last op's label and bumps the token, so a still
                        // in-flight op from a prior open can't write its result after this clear.
                        result = null
                        seq++
                        expanded = true
                    }
                    .padding(horizontal = Space.sm, vertical = 3.dp),
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("Fetch") },
                    modifier = Modifier.testTag("git_fetch"),
                    onClick = { run("Fetch", onFetch) },
                )
                DropdownMenuItem(
                    text = { Text("Pull") },
                    modifier = Modifier.testTag("git_pull"),
                    onClick = { run("Pull", onPull) },
                )
                if (shouldPublish(session.git)) {
                    DropdownMenuItem(
                        text = { Text("Publish") },
                        modifier = Modifier.testTag("git_publish"),
                        onClick = { run("Publish", onPublish) },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Push") },
                        modifier = Modifier.testTag("git_push"),
                        onClick = { run("Push", onPush) },
                    )
                }
            }
        }
        result?.let {
            Spacer(Modifier.width(Space.xs))
            Text(
                text = it,
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("git_op_result"),
            )
        }
    }
}

// ── SessionLinksMenu ─────────────────────────────────────────────────────────────────────

/**
 * A globe [IconButton] dropping a menu of this session's exposed proxy URLs. Renders NOTHING when
 * the session has no proxies. Each row shows [dev.supermux.util.proxyDisplayUrl] with Android's
 * external-link leading icon and opens [dev.supermux.util.proxyUrl] via [onOpenUrl] — the
 * platform's own browser handoff by default.
 */
@Composable
fun SessionLinksMenu(
    session: SessionInfo,
    proxies: List<ProxyDto>,
    onOpenUrl: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    // Off-by-default headless hook (SM_LINKS_MENU, Main.kt) delivery: force-expands the dropdown
    // (no click on a row — opening a URL is left to a real user). No-op when the session has no
    // proxies (the early return above means the menu never renders to force-open in the first
    // place). Applied once, then [onForceOpenConsumed] clears the source.
    forceOpen: Boolean = false,
    onForceOpenConsumed: () -> Unit = {},
) {
    val links = sessionProxies(proxies, session)
    if (links.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    val open = onOpenUrl ?: { url -> platform.openUrl(url) }
    var expanded by remember { mutableStateOf(false) }
    // Close on a session switch so the menu never stays open bound to the new session.
    LaunchedEffect(session.id) { expanded = false }
    LaunchedEffect(forceOpen) { if (forceOpen) { expanded = true; onForceOpenConsumed() } }
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("session_links")) {
            Icon(
                Icons.Outlined.Public,
                contentDescription = "Links",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            links.forEach { p ->
                DropdownMenuItem(
                    text = { Text(dev.supermux.util.proxyDisplayUrl(p)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = { expanded = false; open(dev.supermux.util.proxyUrl(p)) },
                )
            }
        }
    }
}

// ── OverflowMenu ───────────────────────────────────────────────────────────────────────

/**
 * The ⋮ overflow on the chat/session header:
 *  - Detail (tool-call level: Low / Medium / High)
 *  - Continue in new conversation (the shared [ContinueConversationFlow] — compact gets the bottom
 *    sheet, a pointer gets the dialog)
 *  - Git: Fetch / Pull / Publish-or-Push (Android's rows; shown only when [onGitOp] is non-null AND
 *    the session is a repo). This is the touch branch's git affordance — a pointer host normally
 *    has [GitBadgeMenu] in its workspace header instead and passes null here.
 *  - Rename / Mute / Kill
 *  - Usage / Editor-LSP management rows (optional)
 *
 * [onToggleMute] receives the DESIRED next mute state.
 * [onContinue] when non-null shows the continue item; receives [ContinueHandoff] (message +
 * agent/model/thinking, web/iOS parity) and returns the new session id (or null).
 * [onContinued] is called with that id so the shell can select it.
 *
 * [buttonTag]/[detailTag] exist because the same menu is addressed by two names: desktop's chat
 * header calls it `shell_overflow`/`overflow_detail`, Android's workspace chrome
 * `workspace_overflow`/`workspace_overflow_detail`. Both suites keep their nodes.
 */
@Composable
fun OverflowMenu(
    session: SessionInfo,
    onRename: (String) -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onKill: () -> Unit,
    onUsage: () -> Unit = {},
    onLspSettings: () -> Unit = {},
    /** Fires "fetch"/"pull"/"push"/"publish"; null hides the git rows entirely. */
    onGitOp: ((String) -> Unit)? = null,
    /** When non-null, show "Continue in new conversation" and run this to spawn + send handoff. */
    onContinue: (suspend (ContinueHandoff) -> String?)? = null,
    loadContinueAgents: suspend () -> List<String> = { emptyList() },
    loadContinueModels: suspend (String) -> List<ModelInfo> = { emptyList() },
    loadContinueReasoning: suspend (String, String?) -> ReasoningResponse? = { _, _ -> null },
    onContinued: (String) -> Unit = {},
    /** Hide shell-management rows (Usage / LSP) when this is a slim chat-header menu. */
    showManagementRows: Boolean = true,
    /** Hide the session-management rows (Rename / Mute / Kill) — Android's workspace chrome, where
     *  they live on the session list instead and the overflow is a git + detail menu. */
    showSessionRows: Boolean = true,
    modifier: Modifier = Modifier,
    buttonTag: String = "shell_overflow",
    detailTag: String = "overflow_detail",
    forceOpen: Boolean = false,
    onForceOpenConsumed: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showRename by remember(session.id) { mutableStateOf(false) }
    var renameText by remember(session.id) { mutableStateOf(session.name) }
    var showKill by remember(session.id) { mutableStateOf(false) }
    var showContinue by remember(session.id) { mutableStateOf(false) }
    val muted = session.mute ?: false
    // Close on a session switch so the ⋮ menu never stays bound to the new session's callbacks
    // (a stale open Kill would otherwise target the wrong session).
    LaunchedEffect(session.id) {
        expanded = false
        showContinue = false
    }
    LaunchedEffect(forceOpen) { if (forceOpen) { expanded = true; onForceOpenConsumed() } }

    // Right-click parity (F3's RowContextMenu): a pointer host reaches the three session actions
    // without opening the menu. Inert on Android — which is why the ⋮ button below is NOT gated on
    // it: an inert-menu host must still carry every action visibly.
    val contextEntries: () -> List<RowContextMenuEntry> = {
        if (!showSessionRows) emptyList() else listOf(
            RowContextMenuEntry("Rename") { renameText = session.name; showRename = true },
            RowContextMenuEntry(if (muted) "Unmute" else "Mute") { onToggleMute(!muted) },
            RowContextMenuEntry("Kill") { showKill = true },
        )
    }

    RowContextMenu(items = contextEntries) {
        Box(modifier) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(buttonTag)) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = "More",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            val uiPrefs = LocalUiPrefs.current
            val chatDetail by uiPrefs.chatDetailLevel.collectAsState(ChatDetailLevel.MEDIUM)
            var detailSubmenu by remember { mutableStateOf(false) }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false; detailSubmenu = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Detail")
                            Text(chatDetail.label, color = cs.onSurfaceVariant)
                        }
                    },
                    modifier = Modifier.testTag(detailTag),
                    onClick = { detailSubmenu = true },
                )
                if (onContinue != null) {
                    ContinueMenuItem {
                        expanded = false
                        showContinue = true
                    }
                }
                if (onGitOp != null && session.git != null) {
                    GitRow("Fetch", Icons.Outlined.Download, "overflow_git_fetch") {
                        expanded = false; onGitOp("fetch")
                    }
                    GitRow("Pull", Icons.Outlined.CallMerge, "overflow_git_pull") {
                        expanded = false; onGitOp("pull")
                    }
                    if (shouldPublish(session.git)) {
                        GitRow("Publish", Icons.Outlined.CloudOff, "overflow_git_publish") {
                            expanded = false; onGitOp("publish")
                        }
                    } else {
                        GitRow("Push", Icons.Outlined.CloudUpload, "overflow_git_push") {
                            expanded = false; onGitOp("push")
                        }
                    }
                }
                if (showManagementRows) {
                    DropdownMenuItem(
                        text = { Text("Usage") },
                        modifier = Modifier.testTag("overflow_usage"),
                        onClick = { expanded = false; onUsage() },
                    )
                    DropdownMenuItem(
                        text = { Text("Editor / LSP…") },
                        modifier = Modifier.testTag("overflow_lsp_settings"),
                        onClick = { expanded = false; onLspSettings() },
                    )
                }
                if (showSessionRows) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        modifier = Modifier.testTag("overflow_rename"),
                        onClick = { expanded = false; renameText = session.name; showRename = true },
                    )
                    DropdownMenuItem(
                        text = { Text(if (muted) "Unmute" else "Mute") },
                        modifier = Modifier.testTag("overflow_mute"),
                        onClick = { expanded = false; onToggleMute(!muted) },
                    )
                    DropdownMenuItem(
                        text = { Text("Kill", color = cs.error) },
                        modifier = Modifier.testTag("overflow_kill"),
                        onClick = { expanded = false; showKill = true },
                    )
                }
            }
            DropdownMenu(
                expanded = detailSubmenu,
                onDismissRequest = { detailSubmenu = false },
            ) {
                listOf(
                    ChatDetailLevel.LOW to "Messages only · tools on status line",
                    ChatDetailLevel.MEDIUM to "Quiet tool lines between messages",
                    ChatDetailLevel.HIGH to "Terminal windows & file diffs",
                ).forEach { (level, desc) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    level.label,
                                    fontWeight = if (chatDetail == level) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp)
                            }
                        },
                        modifier = Modifier.testTag("overflow_detail_${level.wire}"),
                        enabled = true,
                        onClick = {
                            scope.launch { uiPrefs.putChatDetailLevel(level) }
                            detailSubmenu = false
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("overflow_rename_field"),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRename(renameText.trim()); showRename = false },
                    enabled = renameText.isNotBlank(),
                    modifier = Modifier.testTag("overflow_rename_confirm"),
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
        )
    }
    if (showKill) {
        AlertDialog(
            onDismissRequest = { showKill = false },
            title = { Text("Kill session?") },
            text = { Text("This ends \"${session.name}\" and its agent. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { onKill(); showKill = false },
                    modifier = Modifier.testTag("overflow_kill_confirm"),
                ) { Text("Kill", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { showKill = false }) { Text("Cancel") } },
        )
    }
    if (showContinue && onContinue != null) {
        ContinueConversationFlow(
            session = session,
            onContinue = onContinue,
            onContinued = onContinued,
            loadAgents = loadContinueAgents,
            loadModels = loadContinueModels,
            loadReasoning = loadContinueReasoning,
            onDismiss = { showContinue = false },
        )
    }
}

/** One git row of [OverflowMenu] — Android's shape (label + leading glyph), Material icons. */
@Composable
private fun GitRow(label: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = { Text(label) },
        modifier = Modifier.testTag(tag),
        leadingIcon = {
            Icon(icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
        },
        onClick = onClick,
    )
}

// ── ChatViewHeader (the Compact/Touch branch) ─────────────────────────────────────────────

/**
 * The chat pane's OWN bar, drawn above a [dev.supermux.ui.chat.ChatPanel] whose `showHeader` is
 * false: git rail, name + badge, links, Chat⇄Native, Finish, overflow. Android's tablet workspace
 * shape, verbatim — a pointer host draws the panel's own one-line header with its slots instead
 * (see `ViewHost`'s [ChatHeaderMode]).
 *
 * Settings/Usage/Devices stay on the session-list overflow (they have no chat home), hence
 * `showManagementRows = false` below.
 */
@Composable
fun ChatViewHeader(
    session: SessionInfo,
    working: Boolean,
    nativeView: Boolean,
    onSetNative: (Boolean) -> Unit,
    sessionLinks: List<ProxyDto>,
    finish: FinishBindings,
    onGitOp: (String) -> Unit,
    /** Rename / Mute / Kill — Android's tablet overflow had none of the three; desktop's did, and
     *  its dialogs come with them. [onToggleMute] receives the DESIRED next state. */
    onRename: (String) -> Unit = {},
    onToggleMute: (Boolean) -> Unit = {},
    onKill: () -> Unit = {},
    onContinue: (suspend (ContinueHandoff) -> String?)? = null,
    loadContinueAgents: suspend () -> List<String> = { emptyList() },
    loadContinueModels: suspend (String) -> List<ModelInfo> = { emptyList() },
    loadContinueReasoning: suspend (String, String?) -> ReasoningResponse? = { _, _ -> null },
    onContinued: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SessionStatusRail(git = session.git, working = working)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.name,
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            gitBadge(session.git)?.let { badge ->
                val label = if (badge.kind == GitBadgeKind.BASE && badge.compareRef.isNotEmpty())
                    "${badge.compareRef} ${badge.text}" else badge.text
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SessionLinksMenu(session = session, proxies = sessionLinks)
        if (session.agent == "claude") {
            AgentViewToggle(
                nativeView = nativeView,
                onSetNative = onSetNative,
                modifier = Modifier.testTag("toggle_native"),
            )
        }
        OverflowMenu(
            session = session,
            onRename = onRename,
            onToggleMute = onToggleMute,
            onKill = onKill,
            onGitOp = onGitOp,
            onContinue = onContinue,
            loadContinueAgents = loadContinueAgents,
            loadContinueModels = loadContinueModels,
            loadContinueReasoning = loadContinueReasoning,
            onContinued = onContinued,
            // Settings/Usage/Devices stay on the session-list overflow (they have no chat home);
            // Rename/Mute/Kill belong to THIS session and are here on both hosts now.
            showManagementRows = false,
            buttonTag = "workspace_overflow",
            detailTag = "workspace_overflow_detail",
        )
    }
}

/**
 * The phone workspace's tab-strip trailing slot: those chat panes skip [ChatViewHeader] (no
 * duplicate chrome under the tab row), so Continue + the git ops live here instead.
 */
@Composable
fun PhoneTabChatOverflow(
    sessionId: String,
    actions: ShellActions,
    onSelectSession: (String) -> Unit,
) {
    val sessions by actions.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId } ?: return
    val gitOp = rememberGitOpRunner(sessionId, actions)
    OverflowMenu(
        session = session,
        onRename = {},
        onToggleMute = {},
        onKill = {},
        onGitOp = gitOp,
        onContinue = { handoff ->
            actions.continueConversation(
                session,
                handoff.message,
                handoff.agent,
                handoff.model,
                handoff.reasoningLevel,
            )
        },
        loadContinueAgents = { actions.launcherAgents() },
        loadContinueModels = { actions.launcherModels(it) },
        loadContinueReasoning = { agent, model -> actions.launcherReasoning(agent, model) },
        onContinued = onSelectSession,
        showManagementRows = false,
        showSessionRows = false,
        buttonTag = "workspace_overflow",
        detailTag = "workspace_overflow_detail",
    )
}
