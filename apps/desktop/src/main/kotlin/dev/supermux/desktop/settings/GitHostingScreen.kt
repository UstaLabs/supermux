// Ported from apps/android/.../settings/GitHostingScreen.kt (desktop-parity Task 4).
// Desktop adaptations:
//   - ModalBottomSheet AddForgeSheet → Compose Dialog (desktop convention)
//   - painterResource icons → Icons.Filled (Hub / Add / Expand)
//   - LocalPanes.warning / LocalSemantics → desktop LocalSemantics
//   - Hardcoded sp/dp → MaterialTheme.typography + Space/Radii tokens
//   - testTags for compose UI tests + SM_GIT_HOSTING headless verification
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import dev.supermux.desktop.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import dev.supermux.desktop.ui.Dialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Size
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.theme.Stroke
import dev.supermux.net.ForgeCliStatus
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Settings hub detail: connected GitHub/GitLab accounts.
 *
 * Empty state offers CLI import + manual connect; a connection list shows status badges and
 * Disconnect. "Add account" opens [AddForgeDialog] (PAT or CLI import). Host-scoped — the
 * caller re-keys via activeHostId.
 */
@Composable
fun GitHostingScreen(
    forgesLoad: suspend () -> ForgeConnectionsResponse?,
    forgeAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean,
    forgeImport: suspend (kind: String, transport: String) -> Boolean,
    forgeRemove: suspend (id: String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var connections by remember { mutableStateOf<List<ForgeConnection>>(emptyList()) }
    var cliStatus by remember { mutableStateOf<ForgeCliStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var dialogOpen by remember { mutableStateOf(false) }
    var presetKind by remember { mutableStateOf<String?>(null) }
    var disconnectTarget by remember { mutableStateOf<ForgeConnection?>(null) }
    var disconnecting by remember { mutableStateOf(false) }

    suspend fun reload(clearError: Boolean = true) {
        loading = true
        val r = forgesLoad()
        if (r != null) {
            connections = r.connections
            cliStatus = r.cli
            if (clearError) error = null
        } else {
            error = "Couldn't load connections"
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("git_hosting_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = SettingsDetailMaxWidth)
                .fillMaxWidth()
                .fillMaxSize(),
        ) {
            when {
                loading && connections.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = cs.primary,
                            modifier = Modifier.testTag("git_hosting_loading"),
                        )
                    }
                }
                connections.isEmpty() -> ForgeEmptyState(
                    error = error,
                    cliStatus = cliStatus,
                    connections = connections,
                    onImport = { kind ->
                        scope.launch {
                            val ok = forgeImport(kind, "https")
                            if (ok) {
                                reload()
                            } else {
                                error = "Couldn't import from ${cliName(kind)} — is it logged in?"
                            }
                        }
                    },
                    onManual = { kind ->
                        presetKind = kind
                        dialogOpen = true
                    },
                    onRetry = { scope.launch { reload() } },
                )
                else -> Column(Modifier.fillMaxSize()) {
                    error?.let {
                        Text(
                            it,
                            color = cs.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .padding(Space.lg)
                                .testTag("git_hosting_error"),
                        )
                    }
                    LazyColumn(Modifier.weight(1f).testTag("git_hosting_list")) {
                        items(connections, key = { it.id }) { c ->
                            ForgeConnectionRow(
                                c = c,
                                onReconnect = {
                                    presetKind = c.kind
                                    dialogOpen = true
                                },
                                onDisconnect = { disconnectTarget = c },
                            )
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                    TextButton(
                        onClick = {
                            presetKind = null
                            dialogOpen = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Space.sm)
                            .testTag("git_hosting_add_account"),
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(Space.md + Space.xs),
                            tint = cs.primary,
                        )
                        Spacer(Modifier.width(Space.xs))
                        Text("Add account", color = cs.primary)
                    }
                }
            }
        }
    }

    if (dialogOpen) {
        AddForgeDialog(
            presetKind = presetKind,
            cliStatus = cliStatus,
            onAdd = forgeAdd,
            onImport = forgeImport,
            onDismiss = { dialogOpen = false },
            onDone = {
                dialogOpen = false
                scope.launch { reload() }
            },
        )
    }

    disconnectTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!disconnecting) disconnectTarget = null },
            title = { Text("Disconnect @${target.account.login}?") },
            text = {
                Text("The account will be removed from this broker. You can reconnect at any time.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (disconnecting) return@TextButton
                        disconnecting = true
                        scope.launch {
                            val ok = forgeRemove(target.id)
                            disconnecting = false
                            disconnectTarget = null
                            if (ok) {
                                connections = connections.filterNot { it.id == target.id }
                                error = null
                                delay(250)
                                reload()
                            } else {
                                error = "Couldn't disconnect — try again."
                                reload(clearError = false)
                            }
                        }
                    },
                    enabled = !disconnecting,
                    modifier = Modifier.testTag("git_hosting_disconnect_confirm"),
                ) {
                    Text("Disconnect", color = cs.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { disconnectTarget = null },
                    enabled = !disconnecting,
                    modifier = Modifier.testTag("git_hosting_disconnect_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("git_hosting_disconnect_dialog"),
        )
    }
}

@Composable
private fun ForgeConnectionRow(
    c: ForgeConnection,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantics = LocalSemantics.current
    val needsReconnect = c.status == "needs_reconnect"
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag("forge_row_${c.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Box(
            Modifier
                .size(Space.xxl)
                .clip(RoundedCornerShape(Radii.sm))
                .background(cs.surfaceContainer)
                .border(Stroke.hairline, cs.outline, RoundedCornerShape(Radii.sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Hub,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(Space.lg + Space.xs),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(
                    "@${c.account.login}",
                    color = cs.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("forge_login_${c.id}"),
                )
                if (needsReconnect) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Radii.pill))
                            .border(Stroke.hairline, semantics.warning.copy(alpha = 0.5f), RoundedCornerShape(Radii.pill))
                            .padding(horizontal = Space.sm, vertical = Space.xs),
                    ) {
                        Text(
                            "reconnect",
                            color = semantics.warning,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    Box(
                        Modifier
                            .size(Size.statusDot)
                            .clip(CircleShape)
                            .background(semantics.success)
                            .testTag("forge_ok_${c.id}"),
                    )
                }
            }
            val host = c.host.ifEmpty { if (c.kind == "gitlab") "gitlab.com" else "github.com" }
            val via = if (c.source == "cli") " · via CLI" else ""
            Text(
                "$host · ${c.transport.uppercase()}$via",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (needsReconnect) {
            TextButton(onClick = onReconnect, modifier = Modifier.testTag("forge_reconnect_${c.id}")) {
                Text("Reconnect", color = cs.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        TextButton(
            onClick = onDisconnect,
            modifier = Modifier.testTag("forge_disconnect_${c.id}"),
        ) {
            Text("Disconnect", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ForgeEmptyState(
    error: String?,
    cliStatus: ForgeCliStatus?,
    connections: List<ForgeConnection>,
    onImport: (String) -> Unit,
    onManual: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val importable = importableKinds(cliStatus, connections)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xl)
            .testTag("git_hosting_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        error?.let {
            Text(
                it,
                color = cs.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(top = Space.lg)
                    .testTag("git_hosting_error"),
            )
            TextButton(onClick = onRetry, modifier = Modifier.testTag("git_hosting_retry")) {
                Text("Retry")
            }
        }
        Spacer(Modifier.height(Space.xxl + Space.lg))
        Box(
            Modifier
                .size(Space.xxl + Space.xxl - Space.xs)
                .clip(RoundedCornerShape(Radii.lg))
                .background(cs.surfaceContainer)
                .border(Stroke.hairline, cs.outline, RoundedCornerShape(Radii.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Hub,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(Space.xl + Space.xs),
            )
        }
        Spacer(Modifier.height(Space.lg))
        Text(
            "Connect a Git host",
            color = cs.onSurface,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("git_hosting_empty_title"),
        )
        Spacer(Modifier.height(Space.sm))
        Text(
            "Bring your GitHub & GitLab repos into supermux — clone, create, and launch sessions without leaving the app.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        if (importable.isNotEmpty()) {
            Spacer(Modifier.height(Space.xl))
            importable.forEach { kind ->
                val login = cliLogin(cliStatus, kind)
                Button(
                    onClick = { onImport(kind) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Space.xs)
                        .testTag("git_hosting_import_$kind"),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                ) {
                    Text(
                        "Import from ${cliName(kind)}" + (login?.let { " (@$it)" } ?: ""),
                        color = cs.onPrimary,
                    )
                }
            }
        }

        Spacer(Modifier.height(Space.xl - Space.xs))
        Text(
            if (importable.isEmpty()) "Connect manually" else "or connect manually",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag("git_hosting_manual_label"),
        )
        Spacer(Modifier.height(Space.md + Space.xs))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            listOf("github", "gitlab").forEach { kind ->
                Button(
                    onClick = { onManual(kind) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("git_hosting_manual_$kind"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.surfaceContainer,
                        contentColor = cs.onSurface,
                    ),
                ) {
                    Text(kind.replaceFirstChar { it.uppercase() })
                }
            }
        }
        Spacer(Modifier.height(Space.sm + Space.xs))
        Text(
            "Uses a personal access token or your CLI login.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = Space.xxl + Space.lg),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddForgeDialog(
    presetKind: String?,
    cliStatus: ForgeCliStatus?,
    onAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean,
    onImport: suspend (kind: String, transport: String) -> Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantics = LocalSemantics.current
    val scope = rememberCoroutineScope()

    var kind by remember {
        mutableStateOf(presetKind?.takeIf { it == "github" || it == "gitlab" } ?: "github")
    }
    var token by remember { mutableStateOf("") }
    var hostUrl by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("https") }
    var showAdvanced by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val canImport = cliStatus?.let { (if (kind == "github") it.github else it.gitlab).available } == true
    val cliLoginLabel = cliStatus?.let {
        (if (kind == "github") it.github else it.gitlab).login
    }?.let { " (@$it)" } ?: ""
    val hostUrlError = forgeHostUrlError(hostUrl)
    val canConnect = token.trim().isNotEmpty() && hostUrlError == null

    fun submitConnect() {
        val t = token.trim()
        if (t.isEmpty() || hostUrlError != null || submitting) return
        submitting = true
        error = null
        scope.launch {
            val ok = onAdd(kind, t, hostUrl.trim().ifBlank { null }, transport)
            submitting = false
            if (ok) onDone()
            else error = "Couldn't connect — check your token and try again."
        }
    }

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Column(
            Modifier
                .widthIn(max = SettingsDetailMaxWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.lg))
                .background(cs.surfaceContainerLow)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl - Space.xs)
                .padding(vertical = Space.xl)
                .testTag("git_hosting_add_dialog"),
            verticalArrangement = Arrangement.spacedBy(Space.md + Space.xs),
        ) {
            Text(
                "Add a Git account",
                color = cs.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().testTag("git_hosting_kind_row"),
            ) {
                val kinds = listOf("github", "gitlab")
                kinds.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                        modifier = Modifier.testTag("git_hosting_kind_$k"),
                    ) { Text(k.replaceFirstChar { it.uppercase() }) }
                }
            }

            if (canImport) {
                Button(
                    onClick = {
                        submitting = true
                        error = null
                        scope.launch {
                            val ok = onImport(kind, transport)
                            submitting = false
                            if (ok) onDone()
                            else {
                                error = "Couldn't import from ${cliName(kind)} — is it logged in?"
                            }
                        }
                    },
                    enabled = !submitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("git_hosting_cli_import"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.surfaceContainer,
                        contentColor = cs.onSurface,
                    ),
                ) {
                    Text("Import token from ${cliName(kind)}$cliLoginLabel")
                }
                Text(
                    "— or paste a token —",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "Personal access token",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            SecretField(
                value = token,
                onValueChange = { token = it },
                placeholder = if (kind == "github") "github_pat_…" else "glpat-…",
                modifier = Modifier.fillMaxWidth().testTag("git_hosting_token"),
                onSubmit = { if (canConnect) submitConnect() },
            )
            if (error != null) {
                Text(
                    error!!,
                    color = cs.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag("git_hosting_add_error"),
                )
            } else {
                Text(
                    "Needs scopes: ${scopesHint(kind, hostUrl)}",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown &&
                            (e.key == Key.Enter || e.key == Key.Spacebar)
                        ) {
                            showAdvanced = !showAdvanced
                            true
                        } else {
                            false
                        }
                    }
                    .testTag("git_hosting_advanced_toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Self-hosted & transport",
                    color = cs.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(Space.lg + Space.xs),
                )
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = hostUrl,
                    onValueChange = { hostUrl = it; error = null },
                    placeholder = {
                        Text(
                            "API base URL — e.g. github.acme.com/api/v3",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    isError = hostUrlError != null,
                    supportingText = hostUrlError?.let { msg ->
                        {
                            Text(
                                msg,
                                color = cs.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.testTag("git_hosting_host_url_error"),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("git_hosting_host_url"),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                    colors = settingsFieldColors(),
                )
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth().testTag("git_hosting_transport_row"),
                ) {
                    val transports = listOf("https" to "HTTPS", "ssh" to "SSH")
                    transports.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = transport == value,
                            onClick = { transport = value },
                            shape = SegmentedButtonDefaults.itemShape(i, transports.size),
                            modifier = Modifier.testTag("git_hosting_transport_$value"),
                        ) { Text(label) }
                    }
                }
                if (transport == "ssh" && kind == "gitlab") {
                    Text(
                        "SSH for GitLab is experimental.",
                        color = semantics.warning,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Button(
                onClick = { submitConnect() },
                enabled = canConnect && !submitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("git_hosting_connect"),
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        color = cs.onPrimary,
                        strokeWidth = Stroke.thin,
                        modifier = Modifier.size(Space.lg),
                    )
                } else {
                    Text(
                        "Connect ${kind.replaceFirstChar { it.uppercase() }}",
                        color = cs.onPrimary,
                    )
                }
            }

            TextButton(
                onClick = onDismiss,
                enabled = !submitting,
                modifier = Modifier
                    .align(Alignment.End)
                    .testTag("git_hosting_add_cancel"),
            ) { Text("Cancel") }
        }
    }
}

// ── Forge helpers (same rules as Android GitHostingScreen) ──────────────────────────────────────

internal fun importableKinds(cli: ForgeCliStatus?, connections: List<ForgeConnection>): List<String> {
    if (cli == null) return emptyList()
    val result = mutableListOf<String>()
    for (kind in listOf("github", "gitlab")) {
        val presence = if (kind == "github") cli.github else cli.gitlab
        if (!presence.available) continue
        val login = presence.login
        if (login != null) {
            val already = connections.any {
                it.kind == kind && it.account.login.equals(login, ignoreCase = true)
            }
            if (already) continue
        }
        result.add(kind)
    }
    return result
}

internal fun cliLogin(cli: ForgeCliStatus?, kind: String): String? =
    cli?.let { if (kind == "github") it.github.login else it.gitlab.login }

internal fun cliName(kind: String): String = if (kind == "github") "gh" else "glab"

internal fun scopesHint(kind: String, hostUrl: String): String {
    if (kind == "github") {
        val host = hostUrl.trim()
            .replace(Regex("^https?://"), "")
            .substringBefore("/")
        return if (host.isNotEmpty() && host != "github.com") {
            "repo, read:org"
        } else {
            "Contents + Administration (read & write)"
        }
    }
    return "api"
}

/**
 * Validate an optional self-hosted API base URL.
 * Blank is allowed (public github.com / gitlab.com). Non-blank must look like a host or http(s) URL.
 * Returns an error message, or null when valid.
 */
internal fun forgeHostUrlError(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    if (t.any { it.isWhitespace() }) return "URL can't contain spaces"
    val lower = t.lowercase()
    if ("://" in lower && !lower.startsWith("http://") && !lower.startsWith("https://")) {
        return "URL must start with http:// or https://"
    }
    return if (isValidForgeHostUrl(t)) null
    else "Enter a host like github.acme.com or https://git.example.com/api/v3"
}

/** True when [raw] is blank or a plausible host / http(s) API base URL. */
internal fun isValidForgeHostUrl(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty()) return true
    if (t.any { it.isWhitespace() }) return false
    val lower = t.lowercase()
    if ("://" in lower && !lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val withoutScheme = t.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
    if (withoutScheme.isEmpty() || withoutScheme.startsWith("/")) return false
    val hostPort = withoutScheme.substringBefore("/")
    if (hostPort.isEmpty()) return false
    val host = hostPort.substringBefore(":")
    val port = hostPort.substringAfter(":", missingDelimiterValue = "")
    if (port.isNotEmpty() && (port.toIntOrNull() == null || port.toInt() !in 1..65535)) return false
    if (host.equals("localhost", ignoreCase = true)) return true
    // Require at least one dot (e.g. github.com, git.acme.internal) and DNS-ish labels.
    if (!host.contains('.')) return false
    val labels = host.split('.')
    if (labels.any { it.isEmpty() }) return false
    val labelOk = Regex("^[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?$")
    return labels.all { labelOk.matches(it) }
}
