package dev.supermux.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ─── Agents settings (CLI login + API-key fallback + opencode providers) ─────────
//
// Parity with iOS AgentSettingsView — the most state-heavy screen. One expandable row
// per detected agent (claude / codex / cursor / opencode). For the CLI-login agents the
// expanded body is EITHER the device-code login state machine (§5b) OR an API-key /
// OAuth-token secret field (§5c). The opencode row expands to the provider sub-list (§5d).
//
// The login poll runs inside `LaunchedEffect(loginActive)` so it is structured-concurrency
// cancelled when the row leaves composition or `loginActive` flips — no manual poll handle.

private const val POLL_INTERVAL_MS = 1500L
private val LOGIN_KINDS = setOf("claude", "codex", "cursor", "grok")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSettingsPage(
    onBack: () -> Unit,
    agentStatuses: suspend () -> List<AgentInstallStatus>,
    agentStartLogin: suspend (kind: String) -> AgentLoginState?,
    agentPollLogin: suspend (kind: String) -> AgentLoginState?,
    agentSendCode: (kind: String, code: String) -> Unit,
    agentCancelLogin: (kind: String) -> Unit,
    agentSaveSecret: (kind: String, value: String) -> Unit,
    openCodeProviders: suspend () -> List<OpenCodeProvider>,
    openCodeSetKey: (providerId: String, key: String) -> Unit,
    openCodeStartOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    openCodeFinishOAuth: (providerId: String, method: Int, code: String) -> Unit,
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
        // agentStatuses() swallows errors (returns []); only surface "couldn't load" when
        // there's nothing to show, so a transient empty refresh doesn't flash over real rows.
        error = if (result.isEmpty()) "Couldn't load agent statuses." else null
        loading = false
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        if (loading && statuses.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(statuses, key = { it.kind }) { status ->
                    AgentRow(
                        status = status,
                        onAuthChanged = { reloadKey++ },
                        agentStartLogin = agentStartLogin,
                        agentPollLogin = agentPollLogin,
                        agentSendCode = agentSendCode,
                        agentCancelLogin = agentCancelLogin,
                        agentSaveSecret = agentSaveSecret,
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
                    error?.let { Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
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
    agentSendCode: (kind: String, code: String) -> Unit,
    agentCancelLogin: (kind: String) -> Unit,
    agentSaveSecret: (kind: String, value: String) -> Unit,
    openCodeProviders: suspend () -> List<OpenCodeProvider>,
    openCodeSetKey: (providerId: String, key: String) -> Unit,
    openCodeStartOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    openCodeFinishOAuth: (providerId: String, method: Int, code: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Un-authed agents start expanded so setup is obvious; authed ones collapse.
    var expanded by remember { mutableStateOf(!status.authed) }

    // Device-code login state machine.
    var loginActive by remember { mutableStateOf(false) }
    var login by remember { mutableStateOf<AgentLoginState?>(null) }
    var codeValue by remember { mutableStateOf("") }

    val isLoginKind = status.kind in LOGIN_KINDS

    // The poll loop. Keyed on `loginActive`: launches on start, cancelled when loginActive
    // flips false (Cancel) or the row leaves composition (structured concurrency).
    LaunchedEffect(loginActive) {
        if (!loginActive) return@LaunchedEffect
        login = null
        agentStartLogin(status.kind)            // first state (may be "starting")
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            val s = agentPollLogin(status.kind) ?: continue
            login = s
            when (s.phase) {
                "success" -> { onAuthChanged(); break }
                "failed", "cancelled" -> break
                else -> {}                       // "starting" | "awaiting_user" → keep polling
            }
        }
    }

    val cancel: () -> Unit = {
        agentCancelLogin(status.kind)
        loginActive = false
        login = null
        codeValue = ""
    }

    Column(Modifier.fillMaxWidth().animateContentSize()) {
        // Header (tappable to toggle)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val iconRes = when {
                status.authed -> R.drawable.ic_check
                status.installed -> R.drawable.ic_settings
                else -> R.drawable.ic_x
            }
            Icon(
                painterResource(iconRes),
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
                Text(statusLabel(status), color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
            if (status.authed) {
                Text("Ready", color = cs.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            } else {
                Icon(
                    painterResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // Expanded body
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when {
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
                            if (c.isNotEmpty()) { agentSendCode(status.kind, c); codeValue = "" }
                        },
                        onCancel = cancel,
                    )
                    else -> {
                        // grok authenticates only via `grok login --device-auth`; there's no
                        // key to paste, so offer just the link flow (like opencode's providers).
                        if (status.kind != "grok") {
                            ApiKeyField(kind = status.kind, onSave = { v -> agentSaveSecret(status.kind, v); onAuthChanged() })
                        }
                        if (status.installed && isLoginKind) {
                            LinkLoginButton(kind = status.kind, onStart = { loginActive = true })
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(status: AgentInstallStatus): String = when {
    status.authed -> "Authenticated"
    !status.installed -> "Not installed"
    status.kind == "opencode" -> "Ready · free tier"
    else -> "Installed, not authenticated"
}

// ─── API key / OAuth token form (§5c) ───────────────────────────────────────────

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
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val v = value.trim()
                    if (v.isNotEmpty()) { saving = true; onSave(v); value = ""; saving = false }
                },
                enabled = value.trim().isNotEmpty() && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
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
        OutlinedButton(onClick = onStart) { Text("Start authorization", color = cs.primary) }
    }
}

// ─── Device-code / browser login flow (§5b) ─────────────────────────────────────

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
    when (login?.phase) {
        null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            GeneratingRow()
            CancelButton(onCancel)
        }
        "success" -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Text("Authorized successfully.", color = cs.primary, fontSize = 14.sp)
        }
        "failed" -> Text("Login failed: ${login.error ?: "unknown error"}", color = cs.error, fontSize = 14.sp)
        "cancelled" -> Text("Login cancelled.", color = cs.onSurfaceVariant, fontSize = 14.sp)
        else -> AwaitingUser(login, codeValue, onCodeChange, onSubmitCode, onCancel)   // "starting" | "awaiting_user"
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
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val url = login.url
        if (url != null) {
            Text("Open this link to authorize.", color = cs.onSurfaceVariant, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { openUrl(context, url) },
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                ) { Text("Open sign-in page", color = cs.onPrimary) }
                IconButton(onClick = { copyToClipboard(context, "auth url", url) }) {
                    Icon(painterResource(R.drawable.ic_file), contentDescription = "Copy", tint = cs.primary, modifier = Modifier.size(18.dp))
                }
            }
            login.code?.let { code ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Enter code:", color = cs.onSurfaceVariant, fontSize = 11.sp)
                    SelectionContainer {
                        Text(code, color = cs.onSurface, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
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
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        colors = settingsFieldColors(),
                    )
                    Button(
                        onClick = onSubmitCode,
                        enabled = codeValue.trim().isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        Text("Generating sign-in link — this can take a few seconds…", color = cs.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    TextButton(onClick = onCancel) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
}

// ─── opencode provider sub-list (§5d) ───────────────────────────────────────────

@Composable
private fun OpenCodeProvidersSection(
    load: suspend () -> List<OpenCodeProvider>,
    setKey: (providerId: String, key: String) -> Unit,
    startOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    finishOAuth: (providerId: String, method: Int, code: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var providers by remember { mutableStateOf<List<OpenCodeProvider>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        // Anthropic + OpenAI are covered by the claude/codex agents — hide them here.
        val hidden = setOf("anthropic", "openai")
        providers = load().filter { it.id !in hidden }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsSectionHeader("CONNECT A PROVIDER") {
            IconButton(onClick = { if (!loading) reloadKey++ }, enabled = !loading) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = "Refresh", tint = cs.primary, modifier = Modifier.size(16.dp))
            }
        }
        SettingsCaption("Free models work out of the box — connect a subscription for more.")

        if (loading && providers.isEmpty()) {
            CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            // Surface the synthetic OpenCode (Zen + Go) row when the providers list doesn't
            // already include it (the backend pairs the key onto opencode + opencode-go).
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
private fun OpenCodeZenKeyRow(setKey: (String, String) -> Unit, onChanged: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyValue by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainer)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("OpenCode", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        TextButton(
            onClick = { openUrl(context, "https://opencode.ai/auth") },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("Get a key at opencode.ai/auth", color = cs.primary, fontSize = 11.sp) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecretField(
                value = keyValue,
                onValueChange = { keyValue = it },
                placeholder = "OpenCode key (Zen + Go)",
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val k = keyValue.trim()
                    if (k.isNotEmpty()) {
                        saving = true
                        setKey("opencode", k)
                        keyValue = ""
                        scope.launch { delay(700); saving = false; onChanged() }
                    }
                },
                enabled = keyValue.trim().isNotEmpty() && !saving,
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
            ) { Text(if (saving) "…" else "Save", color = cs.onPrimary) }
        }
    }
}

@Composable
private fun OpenCodeProviderRow(
    provider: OpenCodeProvider,
    setKey: (providerId: String, key: String) -> Unit,
    startOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    finishOAuth: (providerId: String, method: Int, code: String) -> Unit,
    onChanged: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
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
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(prettyProviderName(provider.id), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (provider.configured) {
                Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = cs.primary, modifier = Modifier.size(14.dp))
            }
        }

        val currentOauthUrl = oauthUrl
        if (currentOauthUrl != null) {
            // OAuth in progress — reopen + paste-code finish.
            Text("A browser tab opened — authorize, then paste the code:", color = cs.onSurfaceVariant, fontSize = 11.sp)
            TextButton(
                onClick = { openUrl(context, currentOauthUrl) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
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
                            finishOAuth(provider.id, mi, c)
                            scope.launch {
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
                            openUrl(context, url)
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
                                setKey(provider.id, k)
                                keyValue = ""
                                scope.launch { delay(700); saving = false; onChanged() }
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

private fun prettyProviderName(id: String): String =
    id.split('-', '_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
