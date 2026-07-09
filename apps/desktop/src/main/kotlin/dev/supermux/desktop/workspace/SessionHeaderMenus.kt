// Ported from apps/android/.../workspace/SessionWorkspaceDetail.kt — the three header affordances the
// desktop TODO(M4c) called out: the git-badge count menu (Fetch/Pull/Publish-or-Push), the
// session-links (proxies) menu, and the ⋮ overflow (Rename/Mute/Kill). Android threads these through
// SessionWorkspaceDetail's params + an onGitOp(op) string; desktop splits them into three focused,
// individually runComposeUiTest-able composables that SessionDetail composes into its header.
//
// Differences from Android worth noting:
//   • Git ops: Android fires onGitOp("fetch") and the AppViewModel shows a snackbar/toast with the
//     result. Desktop has NO snackbar host yet (same gap the launcher/finish flows noted), so the
//     GitBadgeMenu awaits the op's GitOpResult directly and shows a small transient inline label next
//     to the badge (cleared on the next menu-open). A proper snackbar host is a documented M4-polish
//     follow-up.
//   • Management-nav rows (Settings/Usage/Devices/Proxies/Archived): those screens don't exist on
//     desktop yet (Usage=M4f, Archived=M4e, the rest later) — OMITTED here rather than adding dead
//     nav. The overflow keeps only the session-scoped Rename/Mute/Kill (parity with the session
//     list's right-click menu).
//   • Link opening: Android uses LocalUriHandler.openUri; desktop opens via the shared
//     ui.openInBrowser (java.awt.Desktop.browse on a daemon thread) — injected as onOpenUrl so tests
//     can capture the URL without spawning a browser.
//
// Headless verification (M4c Task 3): there is no xdotool/input-injection under Xvfb, so each menu
// takes an optional one-shot force-open param (WorkspaceUiState.forceGitMenuFor/forceLinksMenuFor/
// forceOverflowFor, set by the off-by-default SM_GIT_MENU/SM_LINKS_MENU/SM_OVERFLOW_MENU env hooks
// in Main.kt) that expands its DropdownMenu exactly the way a real click would. GitBadgeMenu's hook
// additionally accepts [GitMenuForceOp.FETCH]/[PULL] to fire that op live through the SAME `run(...)`
// path a click uses — see that enum's KDoc for why Push/Publish are structurally excluded from ever
// being auto-fired. SessionLinksMenu/OverflowMenu are open-ONLY: opening a URL or renaming/muting/
// killing a session from a hook is left to a real user (or a direct DesktopAppState call in a test
// harness) rather than simulating a click through a dialog/text field neither hook can drive.
package dev.supermux.desktop.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.openInBrowser
import dev.supermux.net.GitOpResult
import dev.supermux.net.ProxyDto
import dev.supermux.proto.GitBadge
import dev.supermux.proto.GitBadgeKind
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.gitBadge
import dev.supermux.util.proxyDisplayUrl
import dev.supermux.util.proxyUrl
import kotlinx.coroutines.launch

// ── Pure, testable bits (no Compose) ──────────────────────────────────────────────────

/** The header label for a rendered [GitBadge]: BASE-kind badges prefix the compare ref (e.g.
 *  `main +2 ·1`), every other kind is just the glyph text (Android SessionWorkspaceDetail:400). */
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

/**
 * The ⋮ overflow: session-scoped Rename / Mute-Unmute / Kill (header parity with the session list's
 * right-click). Rename opens an [AlertDialog]+[OutlinedTextField] and Kill a confirm dialog — both
 * copy the session-list pattern. Management-nav rows are intentionally omitted (see file header).
 *
 * [onToggleMute] receives the DESIRED next mute state (Android passes `!(session.mute ?: false)`).
 */
@Composable
fun OverflowMenu(
    session: SessionInfo,
    onRename: (String) -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onKill: () -> Unit,
    modifier: Modifier = Modifier,
    // Off-by-default headless hook (SM_OVERFLOW_MENU, Main.kt) delivery: force-expands the
    // dropdown only — NEVER auto-clicks Rename/Mute/Kill (those are destructive-ish/user-facing,
    // so a live verification drives onRename/onToggleMute directly instead). Applied once, then
    // [onForceOpenConsumed] clears the source.
    forceOpen: Boolean = false,
    onForceOpenConsumed: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var showRename by remember(session.id) { mutableStateOf(false) }
    var renameText by remember(session.id) { mutableStateOf(session.name) }
    var showKill by remember(session.id) { mutableStateOf(false) }
    val muted = session.mute ?: false
    // Close on a session switch so the ⋮ menu never stays bound to the new session's callbacks
    // (a stale open Kill would otherwise target the wrong session).
    LaunchedEffect(session.id) { expanded = false }
    LaunchedEffect(forceOpen) { if (forceOpen) { expanded = true; onForceOpenConsumed() } }

    Box(modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("workspace_overflow")) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "More",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
}
