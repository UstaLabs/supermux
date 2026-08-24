// Ported from apps/android/.../shell/SessionShellDetail.kt — three header affordances: the
// git-badge count menu (Fetch/Pull/Publish-or-Push), the session-links (proxies) menu, and the ⋮
// overflow (Rename/Mute/Kill). Android threads these through SessionShellDetail's params + an
// onGitOp(op) string; desktop splits them into three focused, individually runComposeUiTest-able
// composables.
//
// They no longer share one header. The old single-session shell drew all three in its own bar; that
// shell is gone, and each affordance followed what it actually belongs to:
//   • GitBadgeMenu   → [WorkspaceHeader], because the work tree is the WORKSPACE's.
//   • SessionLinksMenu, OverflowMenu → the chat view's own header (ChatPanel), because a proxy and
//     a rename/mute/kill belong to ONE session, which is what a chat view is.
//
// Differences from Android worth noting:
//   • Git ops: Android fires onGitOp("fetch") and the AppViewModel shows a snackbar/toast with the
//     result. Desktop has NO snackbar host yet (same gap the launcher/finish flows noted), so the
//     GitBadgeMenu awaits the op's GitOpResult directly and shows a small transient inline label next
//     to the badge (cleared on the next menu-open). A proper snackbar host is a documented M4-polish
//     follow-up.
//   • Management nav: overflow "Usage" → openUsage(); File ▸ Archived… → openArchived();
//     Settings hub sections via File menu / sidebar footer. Overflow keeps session-scoped
//     Rename/Mute/Kill (parity with the session list right-click menu).
//   • Link opening: Android uses LocalUriHandler.openUri; desktop opens via the shared
//     ui.openInBrowser (java.awt.Desktop.browse on a daemon thread) — injected as onOpenUrl so tests
//     can capture the URL without spawning a browser.
//
// Headless verification (M4c Task 3): there is no xdotool/input-injection under Xvfb, so the git
// and links menus take an optional one-shot force-open param (ShellUiState.forceGitMenuFor /
// forceLinksMenuFor, set by the off-by-default SM_GIT_MENU/SM_LINKS_MENU env hooks in Main.kt) that
// expands its DropdownMenu exactly the way a real click would. GitBadgeMenu's hook additionally
// accepts [GitMenuForceOp.FETCH]/[PULL] to fire that op live through the SAME `run(...)` path a
// click uses — see that enum's KDoc for why Push/Publish are structurally excluded from ever being
// auto-fired. SessionLinksMenu is open-ONLY: opening a URL from a hook is left to a real user.
// OverflowMenu keeps its `forceOpen` param but has no env hook driving it any more (SM_OVERFLOW_MENU
// went with the shell whose header it opened).
package dev.supermux.desktop.shell

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.ui.draw.clip
import dev.supermux.desktop.ui.AlertDialog
import dev.supermux.desktop.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.chat.ChatDetailPrefs
import dev.supermux.desktop.session.AgentLogo
import dev.supermux.desktop.session.DEFAULT_MODEL_ID
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.openInBrowser
import dev.supermux.net.GitOpResult
import dev.supermux.net.ModelInfo
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.resolveReasoningLevel
import dev.supermux.net.showReasoningPicker
import dev.supermux.proto.GitBadge
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.gitBadge
import dev.supermux.ui.ChatDetailLevel
import dev.supermux.util.proxyDisplayUrl
import dev.supermux.util.proxyUrl
import kotlinx.coroutines.launch

// ── Pure, testable bits (no Compose) ──────────────────────────────────────────────────

/** The header label for a rendered [GitBadge]: BASE-kind badges prefix the compare ref (e.g.
 *  `main +2 ·1`), every other kind is just the glyph text (Android SessionShellDetail:400). */
fun headerGitBadgeLabel(badge: GitBadge): String =
    if (badge.kind == GitBadgeKind.BASE && badge.compareRef.isNotEmpty())
        "${badge.compareRef} ${badge.text}"
    else badge.text

/** Whether the git menu's third row is Publish (no upstream yet) rather than Push. Mirrors Android's
 *  `session.git?.unpublished == true` gate. Pure so the decision is unit-testable off the DTO. */
fun shouldPublish(git: GitLiteStatusDto?): Boolean = git?.unpublished == true

/** The exposed proxies belonging to [session] — the broker returns ALL proxies, so the links menu
 *  filters by session name client-side (Android threads a pre-filtered list; desktop filters here). */
fun sessionProxies(proxies: List<ProxyDto>, session: SessionInfo): List<ProxyDto> =
    proxies.filter { it.sessionName == session.name }

/** Compact result label for a completed git op — the message when the broker gave one, else its
 *  status, else a generic done; `null` result (any failure, getOrNull-degraded upstream) → "<Op>
 *  failed". Shown inline next to the badge since desktop has no snackbar host yet. */
fun gitOpResultLabel(op: String, result: GitOpResult?): String {
    if (result == null) return "$op failed"
    result.message?.takeIf { it.isNotBlank() }?.let { return it }
    return result.status.ifBlank { "$op done" }
}

/**
 * The restricted force-op set the headless `SM_GIT_MENU` hook (Main.kt) may drive against
 * [GitBadgeMenu]: [OPEN] only expands the dropdown (no click), [FETCH]/[PULL] additionally fire
 * that op through the SAME `run(...)` path a real click uses. There is deliberately NO Push/Publish
 * member — those mutate a real remote, so no env hook may ever auto-fire them; menu-RENDER is the
 * only headless surface for those two (screenshot the item, never click it).
 */
enum class GitMenuForceOp { OPEN, FETCH, PULL }

// ── GitBadgeMenu ───────────────────────────────────────────────────────────────────────

/**
 * The header git badge (ahead/behind/dirty counts from shared [gitBadge]) rendered as a clickable
 * pill that drops a menu of Fetch / Pull / Publish-or-Push. Renders NOTHING when `session.git` is
 * null (non-repo session) — the whole affordance is gated on a badge existing.
 *
 * Each op callback is a `suspend () -> GitOpResult?` (the DesktopAppState git wrappers); the menu
 * awaits it and shows a small transient result label (tag `git_op_result`) next to the badge,
 * cleared the next time the menu opens. No snackbar host yet — see the file header.
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
 * A globe [IconButton] dropping a menu of this session's exposed proxy URLs (Android's
 * sessionLinksMenu parity). Renders NOTHING when the session has no proxies. Each row shows
 * [proxyDisplayUrl] and opens [proxyUrl] via [onOpenUrl] (defaults to the OS browser).
 */
@Composable
fun SessionLinksMenu(
    session: SessionInfo,
    proxies: List<ProxyDto>,
    onOpenUrl: (String) -> Unit = ::openInBrowser,
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
    var expanded by remember { mutableStateOf(false) }
    // Close on a session switch so the menu never stays open bound to the new session.
    LaunchedEffect(session.id) { expanded = false }
    LaunchedEffect(forceOpen) { if (forceOpen) { expanded = true; onForceOpenConsumed() } }
    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("session_links")) {
            Icon(
                Icons.Filled.Public,
                contentDescription = "Links",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            links.forEach { p ->
                DropdownMenuItem(
                    text = { Text(proxyDisplayUrl(p)) },
                    onClick = { expanded = false; onOpenUrl(proxyUrl(p)) },
                )
            }
        }
    }
}

// ── OverflowMenu ───────────────────────────────────────────────────────────────────────

/** Payload from the continue dialog — agent/model/thinking plus the editable handoff text. */
data class ContinueHandoff(
    val message: String,
    val agent: String,
    val model: String?,
    val reasoningLevel: String?,
)

private val CONTINUE_AGENT_FALLBACK = listOf("claude", "codex", "cursor", "opencode", "grok")

/**
 * The ⋮ overflow on the chat/session header:
 *  - Detail (tool-call level: Low / Medium / High)
 *  - Continue in new conversation
 *  - Rename / Mute / Kill
 *  - Usage / Editor-LSP management rows (optional; no current caller enables them — Usage and
 *    Editor/LSP are reached from the sidebar footer, the File menu and the Settings hub)
 *
 * [onToggleMute] receives the DESIRED next mute state.
 * [onContinue] when non-null shows the continue item; receives [ContinueHandoff] (message +
 * agent/model/thinking, web/iOS parity) and returns the new session id (or null).
 * [onContinued] is called with that id so the shell can select it.
 */
@Composable
fun OverflowMenu(
    session: SessionInfo,
    onRename: (String) -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onKill: () -> Unit,
    onUsage: () -> Unit = {},
    onLspSettings: () -> Unit = {},
    /** When non-null, show "Continue in new conversation" and run this to spawn + send handoff. */
    onContinue: (suspend (ContinueHandoff) -> String?)? = null,
    loadContinueAgents: suspend () -> List<String> = { emptyList() },
    loadContinueModels: suspend (String) -> List<ModelInfo> = { emptyList() },
    loadContinueReasoning: suspend (String, String?) -> ReasoningResponse? = { _, _ -> null },
    onContinued: (String) -> Unit = {},
    /** Hide shell-management rows (Usage / LSP) when this is a slim chat-header menu. */
    showManagementRows: Boolean = true,
    modifier: Modifier = Modifier,
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
    var continueText by remember(session.id) {
        mutableStateOf(dev.supermux.session.HandoffPrefill.build(session.name, session.id))
    }
    var continueBusy by remember { mutableStateOf(false) }
    var continueFailed by remember { mutableStateOf(false) }
    var continueAgent by remember(session.id) {
        mutableStateOf(dev.supermux.session.HandoffPrefill.defaultAgent(session.agent))
    }
    var continueModel by remember(session.id) { mutableStateOf<String?>(null) }
    var continueReasoning by remember(session.id) { mutableStateOf<String?>(null) }
    var continueAgents by remember { mutableStateOf(CONTINUE_AGENT_FALLBACK) }
    var continueModels by remember { mutableStateOf(emptyList<ModelInfo>()) }
    var continueReasoningLevels by remember { mutableStateOf(emptyList<ReasoningLevel>()) }
    var continueReasoningVisible by remember { mutableStateOf(false) }
    var continueAgentMenu by remember { mutableStateOf(false) }
    var continueModelMenu by remember { mutableStateOf(false) }
    var continueReasoningMenu by remember { mutableStateOf(false) }
    var continueSeeding by remember { mutableStateOf(false) }
    val muted = session.mute ?: false
    // Close on a session switch so the ⋮ menu never stays bound to the new session's callbacks
    // (a stale open Kill would otherwise target the wrong session).
    LaunchedEffect(session.id) {
        expanded = false
        showContinue = false
        continueText = dev.supermux.session.HandoffPrefill.build(session.name, session.id)
        continueFailed = false
    }
    LaunchedEffect(forceOpen) { if (forceOpen) { expanded = true; onForceOpenConsumed() } }
    LaunchedEffect(showContinue, session.id) {
        if (!showContinue) return@LaunchedEffect
        continueSeeding = true
        val installed = loadContinueAgents().map { it.lowercase() }.filter { it.isNotBlank() }
        continueAgents = installed.ifEmpty { CONTINUE_AGENT_FALLBACK }
        val next = dev.supermux.session.HandoffPrefill.defaultAgent(session.agent)
        continueAgent = if (next in continueAgents) next else continueAgents.first()
        val sameAgent = session.agent.equals(continueAgent, ignoreCase = true)
        continueModel = if (sameAgent) session.model?.takeIf { it.isNotBlank() } else null
        continueReasoning = if (sameAgent) session.reasoningLevel?.takeIf { it.isNotBlank() } else null
        continueSeeding = false
    }
    LaunchedEffect(showContinue, continueAgent, continueSeeding) {
        if (!showContinue || continueSeeding) return@LaunchedEffect
        continueModels = loadContinueModels(continueAgent)
        if (continueModel != null && continueModels.none { it.id == continueModel }) {
            continueModel = null
        }
    }
    LaunchedEffect(showContinue, continueAgent, continueModel, continueSeeding) {
        if (!showContinue || continueSeeding) return@LaunchedEffect
        val resp = loadContinueReasoning(continueAgent, continueModel)
        val levels = resp?.levels.orEmpty()
        continueReasoningLevels = levels
        continueReasoningVisible = resp != null && resp.visible && showReasoningPicker(levels)
        continueReasoning = if (continueReasoningVisible) {
            resolveReasoningLevel(levels, continueReasoning)
        } else {
            null
        }
    }

    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("shell_overflow")) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "More",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        val chatDetail by ChatDetailPrefs.level.collectAsState()
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
                modifier = Modifier.testTag("overflow_detail"),
                onClick = { detailSubmenu = true },
            )
            if (onContinue != null) {
                DropdownMenuItem(
                    text = { Text("Continue in new conversation") },
                    modifier = Modifier.testTag("overflow_continue"),
                    onClick = {
                        expanded = false
                        continueText = dev.supermux.session.HandoffPrefill.build(session.name, session.id)
                        continueFailed = false
                        showContinue = true
                    },
                )
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
                        ChatDetailPrefs.set(level)
                        detailSubmenu = false
                        expanded = false
                    },
                )
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
        AlertDialog(
            onDismissRequest = { if (!continueBusy) showContinue = false },
            title = { Text("Continue in new conversation") },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Same working directory as ${session.name}. The new agent is told to read this session first. Edit freely before start.",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = Space.sm),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = Space.sm)
                            .testTag("overflow_continue_pickers"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box {
                            ContinuePickerPill(
                                label = continueAgent.replaceFirstChar { it.uppercase() },
                                onClick = { continueAgentMenu = true },
                                testTag = "overflow_continue_agent",
                                leading = { AgentLogo(continueAgent, size = 12.dp) },
                            )
                            DropdownMenu(
                                expanded = continueAgentMenu,
                                onDismissRequest = { continueAgentMenu = false },
                            ) {
                                continueAgents.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text(a.replaceFirstChar { it.uppercase() }) },
                                        leadingIcon = { AgentLogo(a, size = 14.dp) },
                                        modifier = Modifier.testTag("overflow_continue_agent_$a"),
                                        onClick = {
                                            if (a != continueAgent) {
                                                continueAgent = a
                                                continueModel = null
                                                continueReasoning = null
                                            }
                                            continueAgentMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            val modelLabel = continueModel?.let { id ->
                                continueModels.firstOrNull { it.id == id }?.displayName ?: id
                            } ?: "Default"
                            ContinuePickerPill(
                                label = modelLabel,
                                onClick = { continueModelMenu = true },
                                testTag = "overflow_continue_model",
                            )
                            DropdownMenu(
                                expanded = continueModelMenu,
                                onDismissRequest = { continueModelMenu = false },
                            ) {
                                val opts = listOf(DEFAULT_MODEL_ID to "Default") +
                                    continueModels.map { it.id to it.displayName }
                                opts.forEach { (id, label) ->
                                    val selected = (continueModel ?: DEFAULT_MODEL_ID) == id
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = {
                                            if (selected) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    null,
                                                    Modifier.size(16.dp),
                                                    tint = cs.primary,
                                                )
                                            }
                                        },
                                        modifier = Modifier.testTag("overflow_continue_model_$id"),
                                        onClick = {
                                            continueModel = if (id == DEFAULT_MODEL_ID) null else id
                                            continueModelMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        if (continueReasoningVisible) {
                            Box {
                                ContinuePickerPill(
                                    label = continueReasoning?.replaceFirstChar { it.uppercase() } ?: "Default",
                                    onClick = { continueReasoningMenu = true },
                                    testTag = "overflow_continue_reasoning",
                                )
                                DropdownMenu(
                                    expanded = continueReasoningMenu,
                                    onDismissRequest = { continueReasoningMenu = false },
                                ) {
                                    continueReasoningLevels.forEach { level ->
                                        DropdownMenuItem(
                                            text = { Text(level.id.replaceFirstChar { it.uppercase() }) },
                                            modifier = Modifier.testTag("overflow_continue_reasoning_${level.id}"),
                                            onClick = {
                                                continueReasoning = level.id
                                                continueReasoningMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = continueText,
                        onValueChange = { continueText = it; continueFailed = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("overflow_continue_field"),
                        minLines = 8,
                        maxLines = 14,
                    )
                    if (continueFailed) {
                        Text(
                            "Couldn't start — check the agent is installed and signed in.",
                            color = cs.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = Space.sm).testTag("overflow_continue_error"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            continueBusy = true
                            continueFailed = false
                            val id = onContinue(
                                ContinueHandoff(
                                    message = continueText,
                                    agent = continueAgent,
                                    model = continueModel,
                                    reasoningLevel = continueReasoning,
                                ),
                            )
                            continueBusy = false
                            if (id != null) {
                                showContinue = false
                                onContinued(id)
                            } else {
                                continueFailed = true
                            }
                        }
                    },
                    enabled = !continueBusy && continueText.isNotBlank() && session.workdir.isNotBlank(),
                    modifier = Modifier.testTag("overflow_continue_confirm"),
                ) { Text(if (continueBusy) "Starting…" else "Start") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showContinue = false },
                    enabled = !continueBusy,
                    modifier = Modifier.testTag("overflow_continue_cancel"),
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ContinuePickerPill(
    label: String,
    onClick: () -> Unit,
    testTag: String,
    leading: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke()
        Text(
            label.take(22),
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
        )
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp),
        )
    }
}
