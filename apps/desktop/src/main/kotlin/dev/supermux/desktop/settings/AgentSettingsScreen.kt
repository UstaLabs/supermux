// Ported from apps/android/.../settings/AgentSettingsScreen.kt (+ iOS install section).
// Desktop adaptations:
//   - painterResource icons → Icons.Filled (Check / Settings / Close / Expand)
//   - LocalContext openUrl/copy → openInBrowser + LocalClipboardManager
//   - Install flow from iOS AgentSettingsView (Android screen had status only, no install button)
//   - testTags for compose UI tests + headless verification
//   - Login/install poll resumes from broker state when the overlay is reopened
//   - Bounded poll loops, mutation-result handling, Loading/Empty/Error load model
package dev.supermux.desktop.settings

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.ui.openInBrowser
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 1500L
/** ~2 minutes of polling before surfacing a timeout. */
private const val POLL_MAX_TICKS = 80
/** Consecutive null/failed poll responses before treating the broker as gone. */
private const val POLL_NULL_STREAK_LIMIT = 8
private const val ERROR_AUTO_RETRY_MS = 3_000L
private val LOGIN_KINDS = setOf("claude", "codex", "cursor", "grok")

/** Phases considered "in progress" — used to resume login UI after overlay close+reopen. */
internal fun isActiveLoginPhase(phase: String?): Boolean =
    phase == "starting" || phase == "awaiting_user"

/** Install job states: idle (null) → running/pending → done | failed (error). */
internal fun normalizeInstallState(state: String?): String = when (state?.lowercase()) {
    "running", "pending" -> "running"
    "done", "success" -> "done"
    "failed", "error" -> "failed"
    else -> state.orEmpty()
}

internal fun statusLabel(status: AgentInstallStatus): String = when {
    status.authed -> "Authenticated"
    !status.installed -> "Not installed"
    status.kind == "opencode" -> "Ready · free tier"
    else -> "Installed, not authenticated"
}

internal fun prettyProviderName(id: String): String =
    id.split('-', '_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** Load model for the agents list — failure is distinct from a legitimate empty response. */
internal sealed class AgentsLoadState {
    data object Loading : AgentsLoadState()
    data object Empty : AgentsLoadState()
    data class Ready(val statuses: List<AgentInstallStatus>) : AgentsLoadState()
    data class Error(val message: String) : AgentsLoadState()
}

@Composable
fun AgentSettingsScreen(
    /**
     * Load agent install/auth statuses.
     * `null` = transport/decode failure; empty list = legitimate empty; non-empty = data.
     */
    agentStatuses: suspend () -> List<AgentInstallStatus>?,
    agentStartLogin: suspend (kind: String) -> AgentLoginState?,
    agentPollLogin: suspend (kind: String) -> AgentLoginState?,
    agentSendCode: suspend (kind: String, code: String) -> Unit,
    agentCancelLogin: suspend (kind: String) -> Unit,
    agentSaveSecret: suspend (kind: String, value: String) -> Boolean,
    agentStartInstall: suspend (kind: String) -> AgentInstallJob?,
    agentPollInstall: suspend (kind: String) -> AgentInstallJob?,
    openCodeProviders: suspend () -> List<OpenCodeProvider>,
    openCodeSetKey: suspend (providerId: String, key: String) -> Boolean,
    openCodeStartOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    openCodeFinishOAuth: suspend (providerId: String, method: Int, code: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var loadState by remember { mutableStateOf<AgentsLoadState>(AgentsLoadState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }

    suspend fun loadOnce() {
        val previous = loadState
        // Keep prior rows visible while refreshing (avoid flash-to-spinner on Retry).
        if (previous !is AgentsLoadState.Ready) {
            loadState = AgentsLoadState.Loading
        }
        val result = agentStatuses()
        loadState = when {
            result == null -> AgentsLoadState.Error("Couldn't load agent statuses.")
            result.isEmpty() -> AgentsLoadState.Empty
            else -> AgentsLoadState.Ready(result)
        }
    }

    LaunchedEffect(reloadKey) { loadOnce() }

    // Auto-retry while in Error so a broker reconnect recovers without close/reopen.
    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is AgentsLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val result = agentStatuses()
            if (result != null) {
                loadState = if (result.isEmpty()) AgentsLoadState.Empty else AgentsLoadState.Ready(result)
                break
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("agent_settings_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (val state = loadState) {
            is AgentsLoadState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = cs.primary,
                        modifier = Modifier.testTag("agent_settings_loading"),
                    )
                }
            }
            is AgentsLoadState.Empty -> {
                Column(
                    Modifier
                        .widthIn(max = SettingsDetailMaxWidth)
                        .fillMaxWidth()
                        .padding(Space.xl)
                        .testTag("agent_settings_empty"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Text(
                        "No agents reported",
                        color = cs.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "The broker returned an empty agent list.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { reloadKey++ },
                        modifier = Modifier.testTag("agent_settings_retry"),
                    ) { Text("Retry") }
                }
            }
            is AgentsLoadState.Error -> {
                Column(
                    Modifier
                        .widthIn(max = SettingsDetailMaxWidth)
                        .fillMaxWidth()
                        .padding(Space.xl)
                        .testTag("agent_settings_error"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Text(
                        state.message,
                        color = cs.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { reloadKey++ },
                        modifier = Modifier.testTag("agent_settings_retry"),
                    ) { Text("Retry") }
                }
            }
            is AgentsLoadState.Ready -> {
                LazyColumn(
                    Modifier
                        .widthIn(max = SettingsDetailMaxWidth)
                        .fillMaxWidth()
                        .fillMaxSize(),
                    contentPadding = PaddingValues(bottom = Space.xl),
                ) {
                    items(state.statuses, key = { it.kind }) { status ->
                        AgentRow(
                            status = status,
                            onAuthChanged = { reloadKey++ },
                            agentStartLogin = agentStartLogin,
                            agentPollLogin = agentPollLogin,
                            agentSendCode = agentSendCode,
                            agentCancelLogin = agentCancelLogin,
                            agentSaveSecret = agentSaveSecret,
                            agentStartInstall = agentStartInstall,
                            agentPollInstall = agentPollInstall,
                            openCodeProviders = openCodeProviders,
                            openCodeSetKey = openCodeSetKey,
                            openCodeStartOAuth = openCodeStartOAuth,
                            openCodeFinishOAuth = openCodeFinishOAuth,
                        )
                        HorizontalDivider(color = cs.outlineVariant)
                    }
                    item {
                        Text(
                            "Manage CLI authorization and API-key fallback for each agent.",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(Space.lg),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentRow(
    status: AgentInstallStatus,
    onAuthChanged: () -> Unit,
    agentStartLogin: suspend (kind: String) -> AgentLoginState?,
    agentPollLogin: suspend (kind: String) -> AgentLoginState?,
    agentSendCode: suspend (kind: String, code: String) -> Unit,
    agentCancelLogin: suspend (kind: String) -> Unit,
    agentSaveSecret: suspend (kind: String, value: String) -> Boolean,
    agentStartInstall: suspend (kind: String) -> AgentInstallJob?,
    agentPollInstall: suspend (kind: String) -> AgentInstallJob?,
    openCodeProviders: suspend () -> List<OpenCodeProvider>,
    openCodeSetKey: suspend (providerId: String, key: String) -> Boolean,
    openCodeStartOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    openCodeFinishOAuth: suspend (providerId: String, method: Int, code: String) -> Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var expanded by remember(status.kind) { mutableStateOf(!status.authed) }

    var loginActive by remember(status.kind) { mutableStateOf(false) }
    var login by remember(status.kind) { mutableStateOf<AgentLoginState?>(null) }
    var loginStartFailed by remember(status.kind) { mutableStateOf(false) }
    var loginTimedOut by remember(status.kind) { mutableStateOf(false) }
    var codeValue by remember(status.kind) { mutableStateOf("") }
    var install by remember(status.kind) { mutableStateOf<AgentInstallJob?>(null) }
    var installActive by remember(status.kind) { mutableStateOf(false) }
    var installRequestFailed by remember(status.kind) { mutableStateOf(false) }
    var installTimedOut by remember(status.kind) { mutableStateOf(false) }
    // When true, the login poll should NOT call startAgentLogin (resumed from broker).
    var loginResumed by remember(status.kind) { mutableStateOf(false) }
    var installResumed by remember(status.kind) { mutableStateOf(false) }
    // Local-only: user stopped watching an install that may still run on the broker.
    var installStoppedWatching by remember(status.kind) { mutableStateOf(false) }

    val isLoginKind = status.kind in LOGIN_KINDS

    // Resume in-progress broker-owned login/install when the row (re)enters composition so
    // closing+reopening the Settings hub does not drop progress (spec Task 1).
    LaunchedEffect(status.kind, status.installed, status.authed) {
        if (!status.authed && isLoginKind && status.installed) {
            val s = agentPollLogin(status.kind)
            if (s != null && isActiveLoginPhase(s.phase)) {
                login = s
                loginResumed = true
                loginActive = true
                expanded = true
            }
        }
        if (!status.installed) {
            val job = agentPollInstall(status.kind)
            if (job != null && normalizeInstallState(job.state) == "running") {
                install = job
                installResumed = true
                installActive = true
                expanded = true
            }
        }
    }

    // Login poll loop. Keyed on loginActive: launches on start, cancelled when flipped false.
    // Resume path: if we already hold an active phase (from the broker poll above), do NOT
    // re-issue start — that would mint a new session.
    // Timeout/failure MUST clear [login] so a later "Start authorization" does not treat the
    // stale awaiting_user/starting phase as alreadyInProgress and skip the new POST.
    LaunchedEffect(loginActive, status.kind) {
        if (!loginActive) return@LaunchedEffect
        loginStartFailed = false
        loginTimedOut = false
        val alreadyInProgress = loginResumed || isActiveLoginPhase(login?.phase)
        loginResumed = false
        if (!alreadyInProgress) {
            login = null
            val started = agentStartLogin(status.kind)
            if (started == null) {
                loginStartFailed = true
                login = null
                loginActive = false
                return@LaunchedEffect
            }
            login = started
        }
        var ticks = 0
        var nullStreak = 0
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            ticks++
            val s = agentPollLogin(status.kind)
            if (s == null) {
                nullStreak++
                if (nullStreak >= POLL_NULL_STREAK_LIMIT || ticks >= POLL_MAX_TICKS) {
                    loginTimedOut = true
                    login = null
                    loginActive = false
                    break
                }
                continue
            }
            nullStreak = 0
            login = s
            when (s.phase) {
                "success" -> {
                    onAuthChanged()
                    break
                }
                "failed", "cancelled" -> break
                else -> {
                    if (ticks >= POLL_MAX_TICKS) {
                        loginTimedOut = true
                        login = null
                        loginActive = false
                        break
                    }
                }
            }
        }
    }

    // Install poll loop.
    // Resume path mirrors login: if we already hold a running snapshot (or [installResumed]),
    // do NOT re-POST start — that would be redundant and races with the composition resume effect.
    LaunchedEffect(installActive, status.kind) {
        if (!installActive) return@LaunchedEffect
        installTimedOut = false
        val alreadyInProgress =
            installResumed || normalizeInstallState(install?.state) == "running"
        installResumed = false
        if (!alreadyInProgress) {
            val initial = agentStartInstall(status.kind)
            if (initial == null) {
                installRequestFailed = true
                installActive = false
                return@LaunchedEffect
            }
            install = initial
        }
        var ticks = 0
        var nullStreak = 0
        while (isActive) {
            val state = normalizeInstallState(install?.state)
            if (state == "done") {
                onAuthChanged()
                installActive = false
                break
            }
            if (state == "failed") {
                installActive = false
                break
            }
            delay(1_000L)
            ticks++
            val next = agentPollInstall(status.kind)
            if (next == null) {
                nullStreak++
                if (nullStreak >= POLL_NULL_STREAK_LIMIT || ticks >= POLL_MAX_TICKS) {
                    installTimedOut = true
                    installActive = false
                    break
                }
                continue
            }
            nullStreak = 0
            install = next
            if (ticks >= POLL_MAX_TICKS) {
                installTimedOut = true
                installActive = false
                break
            }
        }
    }

    val cancelLogin: () -> Unit = {
        scope.launch { agentCancelLogin(status.kind) }
        loginActive = false
        login = null
        codeValue = ""
        loginStartFailed = false
        loginTimedOut = false
    }

    val cancelInstall: () -> Unit = {
        // Local-only: broker has no install-cancel API. Stop UI polling; the remote job
        // keeps running and can be re-watched via resume / "Watch progress".
        installActive = false
        installTimedOut = false
        installStoppedWatching = true
    }

    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("agent_row_${status.kind}"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = Space.lg, vertical = Space.md)
                .testTag("agent_row_header_${status.kind}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            val icon = when {
                status.authed -> Icons.Filled.Check
                status.installed -> Icons.Filled.Settings
                else -> Icons.Filled.Close
            }
            Icon(
                icon,
                contentDescription = null,
                tint = if (status.authed) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .testTag("agent_status_${status.kind}"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Text(
                        status.kind.replaceFirstChar { it.uppercase() },
                        color = cs.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    if (status.authed) {
                        Text(
                            "Ready",
                            color = cs.primary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Text(
                    statusLabel(status),
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (!status.authed) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = Space.lg, end = Space.lg, bottom = Space.md),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                when {
                    !status.installed -> InstallSection(
                        kind = status.kind,
                        install = install,
                        installActive = installActive,
                        installRequestFailed = installRequestFailed,
                        installTimedOut = installTimedOut,
                        installStoppedWatching = installStoppedWatching,
                        onStart = {
                            installRequestFailed = false
                            installTimedOut = false
                            // Re-watch a still-running broker job without minting a new POST when
                            // we already hold a running snapshot from before "Stop watching".
                            if (installStoppedWatching &&
                                normalizeInstallState(install?.state) == "running"
                            ) {
                                installResumed = true
                            } else {
                                install = null
                                installResumed = false
                            }
                            installStoppedWatching = false
                            installActive = true
                        },
                        onCancel = cancelInstall,
                    )
                    status.kind == "opencode" -> OpenCodeProvidersSection(
                        load = openCodeProviders,
                        setKey = openCodeSetKey,
                        startOAuth = openCodeStartOAuth,
                        finishOAuth = openCodeFinishOAuth,
                    )
                    loginActive -> LoginFlow(
                        kind = status.kind,
                        login = login,
                        codeValue = codeValue,
                        onCodeChange = { codeValue = it },
                        onSubmitCode = {
                            val c = codeValue.trim()
                            if (c.isNotEmpty()) {
                                scope.launch { agentSendCode(status.kind, c) }
                                codeValue = ""
                            }
                        },
                        onCancel = cancelLogin,
                    )
                    else -> {
                        if (loginStartFailed) {
                            Text(
                                "Couldn't start authorization.",
                                color = cs.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("agent_login_start_failed_${status.kind}"),
                            )
                        }
                        if (loginTimedOut) {
                            Text(
                                "Authorization timed out — the broker may be unreachable.",
                                color = cs.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("agent_login_timeout_${status.kind}"),
                            )
                        }
                        if (status.kind != "grok") {
                            ApiKeyField(
                                kind = status.kind,
                                onSave = { v -> agentSaveSecret(status.kind, v) },
                                onSaved = onAuthChanged,
                            )
                        }
                        if (status.installed && isLoginKind) {
                            LinkLoginButton(
                                kind = status.kind,
                                onStart = {
                                    // Clear any stale phase so alreadyInProgress cannot skip POST
                                    // after a prior timeout/failure left login non-null.
                                    login = null
                                    loginResumed = false
                                    loginStartFailed = false
                                    loginTimedOut = false
                                    loginActive = true
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstallSection(
    kind: String,
    install: AgentInstallJob?,
    installActive: Boolean,
    installRequestFailed: Boolean,
    installTimedOut: Boolean,
    installStoppedWatching: Boolean,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val state = normalizeInstallState(install?.state)
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.md),
        modifier = Modifier.testTag("agent_install_$kind"),
    ) {
        Text(
            "Install the ${kind.replaceFirstChar { it.uppercase() }} CLI on this host to use it with Supermux.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        when {
            // Only installActive drives the running UI — after Stop watching we drop to the
            // start/watch button even if the last polled job state is still "running".
            installActive -> {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        modifier = Modifier.testTag("agent_install_running_$kind"),
                    ) {
                        CircularProgressIndicator(
                            color = cs.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Installing $kind…",
                            color = cs.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = onCancel,
                            modifier = Modifier.testTag("agent_install_cancel_$kind"),
                        ) {
                            // Honest label: local poll only — no broker cancel API.
                            Text("Stop watching", color = cs.error)
                        }
                    }
                    Text(
                        "Stops updating this screen only. The install job keeps running on the host.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.testTag("agent_install_stop_hint_$kind"),
                    )
                }
            }
            state == "done" && !installTimedOut && !installStoppedWatching -> {
                Text(
                    "Installed.",
                    color = cs.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("agent_install_done_$kind"),
                )
            }
            else -> {
                if (installStoppedWatching) {
                    Text(
                        "Stopped watching. The install may still be running on the host — " +
                            "use Watch progress to check, or reopen Agents later.",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("agent_install_stopped_watching_$kind"),
                    )
                }
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    modifier = Modifier.testTag("agent_install_start_$kind"),
                ) {
                    Text(
                        when {
                            installStoppedWatching -> "Watch progress"
                            state == "failed" || installTimedOut -> "Retry installation"
                            else -> "Install"
                        },
                        color = cs.onPrimary,
                    )
                }
            }
        }
        if (state == "failed" || installRequestFailed || installTimedOut) {
            Text(
                when {
                    installRequestFailed -> "Couldn't start installation."
                    installTimedOut -> "Installation timed out — the broker may be unreachable."
                    else -> "Installation failed."
                },
                color = cs.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("agent_install_error_$kind"),
            )
        }
        val log = install?.log.orEmpty()
        if (log.isNotEmpty()) {
            SelectionContainer {
                Text(
                    log.takeLast(2_000),
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = MonoFontFamily,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radii.sm))
                        .background(cs.surfaceContainer)
                        .padding(Space.sm)
                        .testTag("agent_install_log_$kind"),
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(
    kind: String,
    onSave: suspend (String) -> Boolean,
    onSaved: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val v = value.trim()
        if (v.isEmpty() || saving) return
        saving = true
        error = null
        scope.launch {
            val ok = onSave(v)
            saving = false
            if (ok) {
                value = ""
                onSaved()
            } else {
                error = "Couldn't save — check the connection and try again."
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(
            if (kind == "claude") "Paste OAuth token" else "Paste API key",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            when (kind) {
                "claude" -> "On a machine with a browser, run this and paste the token it prints:"
                "codex" -> "Paste your OpenAI API key."
                "cursor" -> "Paste your Cursor API key."
                else -> "Paste an API key."
            },
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        if (kind == "claude") CopyableCommand("claude setup-token")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            SecretField(
                value = value,
                onValueChange = { value = it; error = null },
                placeholder = if (kind == "claude") "oauth_token_…" else "sk-…",
                modifier = Modifier.weight(1f).testTag("agent_secret_$kind"),
                onSubmit = ::submit,
                submitEnabled = value.trim().isNotEmpty() && !saving,
            )
            Button(
                onClick = ::submit,
                enabled = value.trim().isNotEmpty() && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                modifier = Modifier.testTag("agent_secret_save_$kind"),
            ) {
                Text(if (saving) "Saving…" else "Save", color = cs.onPrimary)
            }
        }
        error?.let {
            Text(
                it,
                color = cs.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("agent_secret_error_$kind"),
            )
        }
    }
}

@Composable
private fun LinkLoginButton(kind: String, onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        HorizontalDivider(color = cs.outlineVariant)
        Text(
            "Authorize via link",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
        )
        if (kind == "codex") {
            Text(
                "Requires \"Allow device code login\" enabled in ChatGPT → Settings → Security.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        OutlinedButton(
            onClick = onStart,
            modifier = Modifier.testTag("agent_login_start_$kind"),
        ) { Text("Start authorization", color = cs.primary) }
    }
}

@Composable
private fun LoginFlow(
    kind: String,
    login: AgentLoginState?,
    codeValue: String,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.testTag("agent_login_flow_$kind")) {
        when (login?.phase) {
            null -> {
                GeneratingRow()
                CancelButton(onCancel)
            }
            "success" -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("agent_login_success_$kind"),
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Authorized successfully.",
                    color = cs.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            "failed" -> Text(
                "Login failed: ${login.error ?: "unknown error"}",
                color = cs.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("agent_login_failed_$kind"),
            )
            "cancelled" -> Text(
                "Login cancelled.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> AwaitingUser(login, codeValue, onCodeChange, onSubmitCode, onCancel)
        }
    }
}

@Composable
private fun AwaitingUser(
    login: AgentLoginState,
    codeValue: String,
    onCodeChange: (String) -> Unit,
    onSubmitCode: () -> Unit,
    onCancel: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
        val url = login.url
        if (url != null) {
            Text(
                "Open this link to authorize.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Button(
                    onClick = { openInBrowser(url) },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    modifier = Modifier.testTag("agent_login_open_url"),
                ) { Text("Open sign-in page", color = cs.onPrimary) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = cs.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            login.code?.let { code ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Enter code:",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    SelectionContainer {
                        Text(
                            code,
                            color = cs.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("agent_login_device_code"),
                        )
                    }
                }
            }
            Text(
                "Waiting for authorization…",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            if (login.needsCode) {
                Text(
                    "After authorizing, paste the code from the browser here:",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    OutlinedTextField(
                        value = codeValue,
                        onValueChange = onCodeChange,
                        placeholder = { Text("paste code") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("agent_login_code_field")
                            .submitOnEnter(codeValue.trim().isNotEmpty(), onSubmitCode),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                        colors = settingsFieldColors(),
                    )
                    Button(
                        onClick = onSubmitCode,
                        enabled = codeValue.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                        modifier = Modifier.testTag("agent_login_code_submit"),
                    ) { Text("Submit", color = cs.onPrimary) }
                }
            }
        } else {
            GeneratingRow()
        }
        CancelButton(onCancel)
    }
}

@Composable
private fun GeneratingRow() {
    val cs = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.testTag("agent_login_generating"),
    ) {
        CircularProgressIndicator(
            color = cs.primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(16.dp),
        )
        Text(
            "Generating sign-in link — this can take a few seconds…",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    TextButton(onClick = onCancel, modifier = Modifier.testTag("agent_login_cancel")) {
        Text("Cancel", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun OpenCodeProvidersSection(
    load: suspend () -> List<OpenCodeProvider>,
    setKey: suspend (providerId: String, key: String) -> Boolean,
    startOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    finishOAuth: suspend (providerId: String, method: Int, code: String) -> Boolean,
) {
    val cs = MaterialTheme.colorScheme
    var providers by remember { mutableStateOf<List<OpenCodeProvider>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        loadFailed = false
        val hidden = setOf("anthropic", "openai")
        // Treat empty as success (no extra providers); only mark failed if load throws —
        // callers degrade to empty, so we can't distinguish here without a nullable return.
        // Surfacing empty is fine for OpenCode; the zen key row still shows.
        providers = runCatching { load().filter { it.id !in hidden } }
            .onFailure { loadFailed = true }
            .getOrDefault(emptyList())
        loading = false
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(Space.md),
        modifier = Modifier.testTag("opencode_providers_section"),
    ) {
        SettingsSectionHeader("CONNECT A PROVIDER") {
            IconButton(onClick = { if (!loading) reloadKey++ }, enabled = !loading) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
                    tint = cs.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        SettingsCaption("Free models work out of the box — connect a subscription for more.")

        if (loading && providers.isEmpty()) {
            CircularProgressIndicator(
                color = cs.primary,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
        } else {
            if (providers.none { it.id == "opencode" }) {
                OpenCodeZenKeyRow(setKey = setKey, onChanged = { reloadKey++ })
            }
            providers.forEach { provider ->
                OpenCodeProviderRow(
                    provider = provider,
                    setKey = setKey,
                    startOAuth = startOAuth,
                    finishOAuth = finishOAuth,
                    onChanged = { reloadKey++ },
                )
            }
            if (loadFailed) {
                Text(
                    "Couldn't load providers.",
                    color = cs.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("opencode_providers_error"),
                )
            } else if (providers.isEmpty()) {
                SettingsCaption("No additional providers available.")
            }
        }
    }
}

@Composable
private fun OpenCodeZenKeyRow(
    setKey: suspend (String, String) -> Boolean,
    onChanged: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var keyValue by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val k = keyValue.trim()
        if (k.isEmpty() || saving) return
        saving = true
        error = null
        scope.launch {
            val ok = setKey("opencode", k)
            saving = false
            if (ok) {
                keyValue = ""
                onChanged()
            } else {
                error = "Couldn't save key — check the connection and try again."
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(cs.surfaceContainer)
            .padding(Space.md)
            .testTag("opencode_zen_key_row"),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            "OpenCode",
            color = cs.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        TextButton(
            onClick = { openInBrowser("https://opencode.ai/auth") },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                "Get a key at opencode.ai/auth",
                color = cs.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            SecretField(
                value = keyValue,
                onValueChange = { keyValue = it; error = null },
                placeholder = "OpenCode key (Zen + Go)",
                modifier = Modifier.weight(1f).testTag("opencode_zen_key_field"),
                onSubmit = ::submit,
                submitEnabled = keyValue.trim().isNotEmpty() && !saving,
            )
            Button(
                onClick = ::submit,
                enabled = keyValue.trim().isNotEmpty() && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                modifier = Modifier.testTag("opencode_zen_key_save"),
            ) { Text(if (saving) "…" else "Save", color = cs.onPrimary) }
        }
        error?.let {
            Text(
                it,
                color = cs.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("opencode_zen_key_error"),
            )
        }
    }
}

@Composable
private fun OpenCodeProviderRow(
    provider: OpenCodeProvider,
    setKey: suspend (providerId: String, key: String) -> Boolean,
    startOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    finishOAuth: suspend (providerId: String, method: Int, code: String) -> Boolean,
    onChanged: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var keyValue by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var oauthUrl by remember { mutableStateOf<String?>(null) }
    var oauthMethodIndex by remember { mutableStateOf<Int?>(null) }
    var oauthCode by remember { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }
    var oauthError by remember { mutableStateOf<String?>(null) }

    val oauthMethod = provider.methods.firstOrNull { it.type == "oauth" }
    val apiMethod = provider.methods.firstOrNull { it.type == "api" }

    fun submitKey() {
        val k = keyValue.trim()
        if (k.isEmpty() || saving) return
        saving = true
        saveError = null
        scope.launch {
            val ok = setKey(provider.id, k)
            saving = false
            if (ok) {
                keyValue = ""
                onChanged()
            } else {
                saveError = "Couldn't save key — check the connection and try again."
            }
        }
    }

    fun finishOauth() {
        val c = oauthCode.trim()
        val mi = oauthMethodIndex
        if (c.isEmpty() || mi == null || finishing) return
        finishing = true
        oauthError = null
        scope.launch {
            val ok = finishOAuth(provider.id, mi, c)
            finishing = false
            if (ok) {
                oauthUrl = null
                oauthMethodIndex = null
                oauthCode = ""
                onChanged()
            } else {
                oauthError = "Couldn't finish authorization — check the code and try again."
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.md))
            .background(cs.surfaceContainer)
            .padding(Space.md)
            .testTag("opencode_provider_${provider.id}"),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                prettyProviderName(provider.id),
                color = cs.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            if (provider.configured) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        val currentOauthUrl = oauthUrl
        if (currentOauthUrl != null) {
            Text(
                "A browser tab opened — authorize, then paste the code:",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(
                onClick = { openInBrowser(currentOauthUrl) },
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    "Reopen sign-in",
                    color = cs.primary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                OutlinedTextField(
                    value = oauthCode,
                    onValueChange = { oauthCode = it; oauthError = null },
                    placeholder = { Text("paste code") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("opencode_oauth_code_${provider.id}")
                        .submitOnEnter(oauthCode.trim().isNotEmpty() && !finishing, ::finishOauth),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                    colors = settingsFieldColors(),
                )
                Button(
                    onClick = ::finishOauth,
                    enabled = oauthCode.trim().isNotEmpty() && !finishing,
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    modifier = Modifier.testTag("opencode_oauth_finish_${provider.id}"),
                ) { Text(if (finishing) "…" else "Finish", color = cs.onPrimary) }
            }
            oauthError?.let {
                Text(
                    it,
                    color = cs.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("opencode_oauth_error_${provider.id}"),
                )
            }
        } else {
            if (oauthMethod != null) {
                OutlinedButton(onClick = {
                    oauthCode = ""
                    oauthError = null
                    scope.launch {
                        val start = startOAuth(provider.id, oauthMethod.index)
                        val url = start?.url
                        if (!url.isNullOrEmpty()) {
                            oauthMethodIndex = oauthMethod.index
                            oauthUrl = url
                            openInBrowser(url)
                        } else {
                            oauthError = "Couldn't start browser login."
                        }
                    }
                }) { Text("Login via browser", color = cs.primary) }
            }
            if (apiMethod != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    SecretField(
                        value = keyValue,
                        onValueChange = { keyValue = it; saveError = null },
                        placeholder = apiMethod.label.ifEmpty { "API key" },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("opencode_provider_key_${provider.id}"),
                        onSubmit = ::submitKey,
                        submitEnabled = keyValue.trim().isNotEmpty() && !saving,
                    )
                    Button(
                        onClick = ::submitKey,
                        enabled = keyValue.trim().isNotEmpty() && !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                        modifier = Modifier.testTag("opencode_provider_save_${provider.id}"),
                    ) { Text(if (saving) "…" else "Save", color = cs.onPrimary) }
                }
                saveError?.let {
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("opencode_provider_error_${provider.id}"),
                    )
                }
            }
            oauthError?.let {
                Text(
                    it,
                    color = cs.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
