// Ported from apps/android/.../settings/AgentSettingsScreen.kt (+ iOS install section).
// Desktop adaptations:
//   - painterResource icons → Icons.Filled (Check / Settings / Close / Expand)
//   - LocalContext openUrl/copy → openInBrowser + LocalClipboardManager
//   - Install flow from iOS AgentSettingsView (Android screen had status only, no install button)
//   - testTags for compose UI tests + headless verification
//   - Login/install poll resumes from broker state when the overlay is reopened
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun AgentSettingsScreen(
    agentStatuses: suspend () -> List<AgentInstallStatus>,
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
    var statuses by remember { mutableStateOf<List<AgentInstallStatus>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        val result = agentStatuses()
        statuses = result
        // Swallow empty only as load error when nothing to show (Android parity).
        error = if (result.isEmpty()) "Couldn't load agent statuses." else null
        loading = false
    }

    Column(modifier.fillMaxSize().background(cs.background).testTag("agent_settings_screen")) {
        if (loading && statuses.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.testTag("agent_settings_loading"),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(statuses, key = { it.kind }) { status ->
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
                        fontSize = 11.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                    error?.let {
                        Text(
                            it,
                            color = cs.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp).testTag("agent_settings_error"),
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
    // Un-authed agents start expanded so setup is obvious; authed ones collapse.
    var expanded by remember(status.kind) { mutableStateOf(!status.authed) }

    var loginActive by remember(status.kind) { mutableStateOf(false) }
    var login by remember(status.kind) { mutableStateOf<AgentLoginState?>(null) }
    var codeValue by remember(status.kind) { mutableStateOf("") }
    var install by remember(status.kind) { mutableStateOf<AgentInstallJob?>(null) }
    var installActive by remember(status.kind) { mutableStateOf(false) }
    var installRequestFailed by remember(status.kind) { mutableStateOf(false) }
    // When true, the login poll should NOT call startAgentLogin (resumed from broker).
    var loginResumed by remember(status.kind) { mutableStateOf(false) }
    var installResumed by remember(status.kind) { mutableStateOf(false) }

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
    LaunchedEffect(loginActive, status.kind) {
        if (!loginActive) return@LaunchedEffect
        // If we already have a resumed state, skip re-start (would mint a new session).
        if (!loginResumed) {
            login = null
            login = agentStartLogin(status.kind)
        }
        loginResumed = false
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            val s = agentPollLogin(status.kind) ?: continue
            login = s
            when (s.phase) {
                "success" -> {
                    onAuthChanged()
                    break
                }
                "failed", "cancelled" -> break
                else -> {}
            }
        }
    }

    // Install poll loop.
    LaunchedEffect(installActive, status.kind) {
        if (!installActive) return@LaunchedEffect
        if (!installResumed) {
            val initial = agentStartInstall(status.kind)
            if (initial == null) {
                installRequestFailed = true
                installActive = false
                return@LaunchedEffect
            }
            install = initial
        }
        installResumed = false
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
            val next = agentPollInstall(status.kind) ?: continue
            install = next
        }
    }

    val cancelLogin: () -> Unit = {
        // Fire-and-forget cancel; UI clears immediately (Android parity).
        scope.launch { agentCancelLogin(status.kind) }
        loginActive = false
        login = null
        codeValue = ""
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
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("agent_row_header_${status.kind}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
            Column(Modifier.weight(1f)) {
                Text(
                    status.kind.replaceFirstChar { it.uppercase() },
                    color = cs.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    statusLabel(status),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.testTag("agent_status_${status.kind}"),
                )
            }
            if (status.authed) {
                Text("Ready", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            } else {
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
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
                    !status.installed -> InstallSection(
                        kind = status.kind,
                        install = install,
                        installActive = installActive,
                        installRequestFailed = installRequestFailed,
                        onStart = {
                            installRequestFailed = false
                            install = null
                            installResumed = false
                            installActive = true
                        },
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
                        if (status.kind != "grok") {
                            ApiKeyField(
                                kind = status.kind,
                                onSave = { v ->
                                    scope.launch {
                                        if (agentSaveSecret(status.kind, v)) onAuthChanged()
                                    }
                                },
                            )
                        }
                        if (status.installed && isLoginKind) {
                            LinkLoginButton(
                                kind = status.kind,
                                onStart = {
                                    loginResumed = false
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
    onStart: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val state = normalizeInstallState(install?.state)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.testTag("agent_install_${kind}"),
    ) {
        Text(
            "Install the ${kind.replaceFirstChar { it.uppercase() }} CLI on this host to use it with Supermux.",
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
        )
        when {
            installActive || state == "running" -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.testTag("agent_install_running_${kind}"),
                ) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text("Installing $kind…", color = cs.onSurface, fontSize = 13.sp)
                }
            }
            state == "done" -> {
                Text(
                    "Installed.",
                    color = cs.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("agent_install_done_${kind}"),
                )
            }
            else -> {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    modifier = Modifier.testTag("agent_install_start_${kind}"),
                ) {
                    Text(
                        if (state == "failed") "Retry installation" else "Install",
                        color = cs.onPrimary,
                    )
                }
            }
        }
        if (state == "failed" || installRequestFailed) {
            Text(
                if (installRequestFailed) "Couldn't start installation." else "Installation failed.",
                color = cs.error,
                fontSize = 12.sp,
                modifier = Modifier.testTag("agent_install_error_${kind}"),
            )
        }
        val log = install?.log.orEmpty()
        if (log.isNotEmpty()) {
            SelectionContainer {
                Text(
                    log.takeLast(2_000),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.surfaceContainer)
                        .padding(8.dp)
                        .testTag("agent_install_log_${kind}"),
                )
            }
        }
    }
}

@Composable
private fun ApiKeyField(kind: String, onSave: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var value by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (kind == "claude") "Paste OAuth token" else "Paste API key",
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
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
            fontSize = 11.sp,
        )
        if (kind == "claude") CopyableCommand("claude setup-token")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretField(
                value = value,
                onValueChange = { value = it },
                placeholder = if (kind == "claude") "oauth_token_…" else "sk-…",
                modifier = Modifier.weight(1f).testTag("agent_secret_${kind}"),
            )
            Button(
                onClick = {
                    val v = value.trim()
                    if (v.isNotEmpty()) {
                        saving = true
                        onSave(v)
                        value = ""
                        saving = false
                    }
                },
                enabled = value.trim().isNotEmpty() && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                modifier = Modifier.testTag("agent_secret_save_${kind}"),
            ) {
                Text(if (saving) "Saving…" else "Save", color = cs.onPrimary)
            }
        }
    }
}

@Composable
private fun LinkLoginButton(kind: String, onStart: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = cs.outlineVariant)
        Text("Authorize via link", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        if (kind == "codex") {
            Text(
                "Requires \"Allow device code login\" enabled in ChatGPT → Settings → Security.",
                color = cs.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        OutlinedButton(
            onClick = onStart,
            modifier = Modifier.testTag("agent_login_start_${kind}"),
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
    Column(Modifier.testTag("agent_login_flow_${kind}")) {
        when (login?.phase) {
            null -> {
                GeneratingRow()
                CancelButton(onCancel)
            }
            "success" -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("agent_login_success_${kind}"),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                Text("Authorized successfully.", color = cs.primary, fontSize = 14.sp)
            }
            "failed" -> Text(
                "Login failed: ${login.error ?: "unknown error"}",
                color = cs.error,
                fontSize = 14.sp,
                modifier = Modifier.testTag("agent_login_failed_${kind}"),
            )
            "cancelled" -> Text("Login cancelled.", color = cs.onSurfaceVariant, fontSize = 14.sp)
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val url = login.url
        if (url != null) {
            Text("Open this link to authorize.", color = cs.onSurfaceVariant, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openInBrowser(url) },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    modifier = Modifier.testTag("agent_login_open_url"),
                ) { Text("Open sign-in page", color = cs.onPrimary) }
                IconButton(onClick = { clipboard.setText(AnnotatedString(url)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = cs.primary, modifier = Modifier.size(18.dp))
                }
            }
            login.code?.let { code ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Enter code:", color = cs.onSurfaceVariant, fontSize = 11.sp)
                    SelectionContainer {
                        Text(
                            code,
                            color = cs.onSurface,
                            fontSize = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("agent_login_device_code"),
                        )
                    }
                }
            }
            Text("Waiting for authorization…", color = cs.onSurfaceVariant, fontSize = 11.sp)
            if (login.needsCode) {
                Text("After authorizing, paste the code from the browser here:", color = cs.onSurfaceVariant, fontSize = 11.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = codeValue,
                        onValueChange = onCodeChange,
                        placeholder = { Text("paste code") },
                        modifier = Modifier.weight(1f).testTag("agent_login_code_field"),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("agent_login_generating"),
    ) {
        CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text("Generating sign-in link — this can take a few seconds…", color = cs.onSurfaceVariant, fontSize = 11.sp)
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
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        val hidden = setOf("anthropic", "openai")
        providers = load().filter { it.id !in hidden }
        loading = false
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.testTag("opencode_providers_section"),
    ) {
        SettingsSectionHeader("CONNECT A PROVIDER") {
            IconButton(onClick = { if (!loading) reloadKey++ }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = cs.primary, modifier = Modifier.size(16.dp))
            }
        }
        SettingsCaption("Free models work out of the box — connect a subscription for more.")

        if (loading && providers.isEmpty()) {
            CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
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
            if (providers.isEmpty()) {
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

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainer)
            .padding(10.dp)
            .testTag("opencode_zen_key_row"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("OpenCode", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        TextButton(
            onClick = { openInBrowser("https://opencode.ai/auth") },
            contentPadding = PaddingValues(0.dp),
        ) { Text("Get a key at opencode.ai/auth", color = cs.primary, fontSize = 11.sp) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretField(
                value = keyValue,
                onValueChange = { keyValue = it },
                placeholder = "OpenCode key (Zen + Go)",
                modifier = Modifier.weight(1f).testTag("opencode_zen_key_field"),
            )
            Button(
                onClick = {
                    val k = keyValue.trim()
                    if (k.isNotEmpty()) {
                        saving = true
                        scope.launch {
                            setKey("opencode", k)
                            keyValue = ""
                            delay(700)
                            saving = false
                            onChanged()
                        }
                    }
                },
                enabled = keyValue.trim().isNotEmpty() && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                modifier = Modifier.testTag("opencode_zen_key_save"),
            ) { Text(if (saving) "…" else "Save", color = cs.onPrimary) }
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
    var oauthUrl by remember { mutableStateOf<String?>(null) }
    var oauthMethodIndex by remember { mutableStateOf<Int?>(null) }
    var oauthCode by remember { mutableStateOf("") }
    var finishing by remember { mutableStateOf(false) }

    val oauthMethod = provider.methods.firstOrNull { it.type == "oauth" }
    val apiMethod = provider.methods.firstOrNull { it.type == "api" }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainer)
            .padding(10.dp)
            .testTag("opencode_provider_${provider.id}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(prettyProviderName(provider.id), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (provider.configured) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(14.dp))
            }
        }

        val currentOauthUrl = oauthUrl
        if (currentOauthUrl != null) {
            Text("A browser tab opened — authorize, then paste the code:", color = cs.onSurfaceVariant, fontSize = 11.sp)
            TextButton(
                onClick = { openInBrowser(currentOauthUrl) },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Reopen sign-in", color = cs.primary, fontSize = 11.sp) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = oauthCode,
                    onValueChange = { oauthCode = it },
                    placeholder = { Text("paste code") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = settingsFieldColors(),
                )
                Button(
                    onClick = {
                        val c = oauthCode.trim()
                        val mi = oauthMethodIndex
                        if (c.isNotEmpty() && mi != null) {
                            finishing = true
                            scope.launch {
                                finishOAuth(provider.id, mi, c)
                                delay(700)
                                finishing = false
                                oauthUrl = null
                                oauthMethodIndex = null
                                oauthCode = ""
                                onChanged()
                            }
                        }
                    },
                    enabled = oauthCode.trim().isNotEmpty() && !finishing,
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                ) { Text(if (finishing) "…" else "Finish", color = cs.onPrimary) }
            }
        } else {
            if (oauthMethod != null) {
                OutlinedButton(onClick = {
                    oauthCode = ""
                    scope.launch {
                        val start = startOAuth(provider.id, oauthMethod.index)
                        val url = start?.url
                        if (!url.isNullOrEmpty()) {
                            oauthMethodIndex = oauthMethod.index
                            oauthUrl = url
                            openInBrowser(url)
                        }
                    }
                }) { Text("Login via browser", color = cs.primary) }
            }
            if (apiMethod != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecretField(
                        value = keyValue,
                        onValueChange = { keyValue = it },
                        placeholder = apiMethod.label.ifEmpty { "API key" },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val k = keyValue.trim()
                            if (k.isNotEmpty()) {
                                saving = true
                                scope.launch {
                                    setKey(provider.id, k)
                                    keyValue = ""
                                    delay(700)
                                    saving = false
                                    onChanged()
                                }
                            }
                        },
                        enabled = keyValue.trim().isNotEmpty() && !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    ) { Text(if (saving) "…" else "Save", color = cs.onPrimary) }
                }
            }
        }
    }
}
