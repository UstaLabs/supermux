package dev.supermux.android.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.LocalPanes
import dev.supermux.net.ForgeCliStatus
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import kotlinx.coroutines.launch

// ─── Git hosting settings (forge connections) ───────────────────────────────────
//
// Parity with iOS GitHostingSettingsView: an empty-state (CLI-import shortcuts + manual
// connect) or a connection list with status badges + transport/source subtitle; a "+"
// toolbar action opens AddForgeSheet (a ModalBottomSheet — the Material-native auth form);
// disconnect goes through a confirm dialog.

private val GitLabOrange = Color(0xFFFC6D26)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHostingPage(
    onBack: () -> Unit,
    forgesLoad: suspend () -> ForgeConnectionsResponse?,
    forgeAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean,
    forgeImport: suspend (kind: String, transport: String) -> Boolean,
    forgeRemove: (id: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var connections by remember { mutableStateOf<List<ForgeConnection>>(emptyList()) }
    var cliStatus by remember { mutableStateOf<ForgeCliStatus?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    var presetKind by remember { mutableStateOf<String?>(null) }
    var disconnectTarget by remember { mutableStateOf<ForgeConnection?>(null) }

    suspend fun reload() {
        loading = true
        val r = forgesLoad()
        if (r != null) {
            connections = r.connections
            cliStatus = r.cli
            error = null
        } else {
            error = "Couldn't load connections"
        }
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Git hosting", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_left), contentDescription = "Back", tint = cs.onSurface, modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { presetKind = null; sheetOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add account", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading && connections.isEmpty() -> CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center),
                )
                connections.isEmpty() -> ForgeEmptyState(
                    error = error,
                    cliStatus = cliStatus,
                    connections = connections,
                    onImport = { kind ->
                        scope.launch { forgeImport(kind, "https"); reload() }
                    },
                    onManual = { kind -> presetKind = kind; sheetOpen = true },
                )
                else -> Column(Modifier.fillMaxSize()) {
                    error?.let { Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.padding(16.dp)) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(connections, key = { it.id }) { c ->
                            ForgeConnectionRow(
                                c = c,
                                onReconnect = { presetKind = c.kind; sheetOpen = true },
                                onDisconnect = { disconnectTarget = c },
                            )
                            HorizontalDivider(color = cs.outlineVariant)
                        }
                    }
                    TextButton(
                        onClick = { presetKind = null; sheetOpen = true },
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Add account", color = cs.primary)
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        AddForgeSheet(
            presetKind = presetKind,
            cliStatus = cliStatus,
            onAdd = forgeAdd,
            onImport = forgeImport,
            onDismiss = { sheetOpen = false },
            onDone = { sheetOpen = false; scope.launch { reload() } },
        )
    }

    disconnectTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { disconnectTarget = null },
            title = { Text("Disconnect @${target.account.login}?") },
            text = { Text("The account will be removed from this broker. You can reconnect at any time.") },
            confirmButton = {
                TextButton(onClick = {
                    forgeRemove(target.id)
                    connections = connections.filterNot { it.id == target.id }
                    disconnectTarget = null
                    scope.launch { kotlinx.coroutines.delay(250); reload() }
                }) { Text("Disconnect", color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { disconnectTarget = null }) { Text("Cancel") }
            },
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
    val warning = Color(LocalPanes.current.warning)
    val needsReconnect = c.status == "needs_reconnect"
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ForgeIconBox(R.drawable.ic_network, if (c.kind == "gitlab") GitLabOrange else cs.primary)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("@${c.account.login}", color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (needsReconnect) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .border(1.dp, warning.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                    ) {
                        Text("reconnect", color = warning, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    }
                } else {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF3FB950)))
                }
            }
            val host = c.host.ifEmpty { if (c.kind == "gitlab") "gitlab.com" else "github.com" }
            val via = if (c.source == "cli") " · via CLI" else ""
            Text("$host · ${c.transport.uppercase()}$via", color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
        if (needsReconnect) {
            TextButton(onClick = onReconnect) { Text("Reconnect", color = cs.primary, fontSize = 13.sp) }
        }
        TextButton(onClick = onDisconnect) { Text("Disconnect", color = cs.onSurfaceVariant, fontSize = 12.sp) }
    }
}

@Composable
private fun ForgeEmptyState(
    error: String?,
    cliStatus: ForgeCliStatus?,
    connections: List<ForgeConnection>,
    onImport: (String) -> Unit,
    onManual: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val importable = importableKinds(cliStatus, connections)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        error?.let { Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)) }
        Spacer(Modifier.size(48.dp))
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_network), contentDescription = null, tint = cs.primary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.size(16.dp))
        Text("Connect a Git host", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(6.dp))
        Text(
            "Bring your GitHub & GitLab repos into supermux — clone, create, and launch sessions without leaving the app.",
            color = cs.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(),
        )

        if (importable.isNotEmpty()) {
            Spacer(Modifier.size(24.dp))
            importable.forEach { kind ->
                val login = cliLogin(cliStatus, kind)
                Button(
                    onClick = { onImport(kind) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                ) {
                    Text("Import from ${cliName(kind)}" + (login?.let { " (@$it)" } ?: ""), color = cs.onPrimary)
                }
            }
        }

        Spacer(Modifier.size(20.dp))
        Text(
            if (importable.isEmpty()) "Connect manually" else "or connect manually",
            color = cs.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.size(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("github", "gitlab").forEach { kind ->
                Button(
                    onClick = { onManual(kind) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceContainer, contentColor = cs.onSurface),
                ) {
                    Text(kind.replaceFirstChar { it.uppercase() })
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        Text(
            "Uses a personal access token or your CLI login.",
            color = cs.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.padding(bottom = 40.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddForgeSheet(
    presetKind: String?,
    cliStatus: ForgeCliStatus?,
    onAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean,
    onImport: suspend (kind: String, transport: String) -> Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var kind by remember { mutableStateOf(presetKind?.takeIf { it == "github" || it == "gitlab" } ?: "github") }
    var token by remember { mutableStateOf("") }
    var hostUrl by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("https") }
    var showAdvanced by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val warning = Color(LocalPanes.current.warning)
    val canImport = cliStatus?.let { (if (kind == "github") it.github else it.gitlab).available } == true
    val cliLoginLabel = cliStatus?.let { (if (kind == "github") it.github else it.gitlab).login }?.let { " (@$it)" } ?: ""
    val canConnect = token.trim().isNotEmpty()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = cs.surfaceContainerLow) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add a Git account", color = cs.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

            // Kind picker
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val kinds = listOf("github", "gitlab")
                kinds.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(i, kinds.size),
                    ) { Text(k.replaceFirstChar { it.uppercase() }) }
                }
            }

            // CLI import (when available)
            if (canImport) {
                Button(
                    onClick = {
                        submitting = true
                        scope.launch { onImport(kind, transport); onDone() }
                    },
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.surfaceContainer, contentColor = cs.onSurface),
                ) {
                    Text("Import token from ${cliName(kind)}$cliLoginLabel")
                }
                Text("— or paste a token —", color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
            }

            // PAT field
            Text("Personal access token", color = cs.onSurfaceVariant, fontSize = 12.sp)
            SecretField(
                value = token,
                onValueChange = { token = it },
                placeholder = if (kind == "github") "github_pat_…" else "glpat-…",
                modifier = Modifier.fillMaxWidth(),
            )
            if (error != null) {
                Text(error!!, color = cs.error, fontSize = 11.sp)
            } else {
                Text("Needs scopes: ${scopesHint(kind, hostUrl)}", color = cs.onSurfaceVariant, fontSize = 11.sp)
            }

            // Self-hosted & transport
            Row(
                Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Self-hosted & transport", color = cs.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Icon(
                    painterResource(if (showAdvanced) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = hostUrl,
                    onValueChange = { hostUrl = it },
                    placeholder = { Text("API base URL — e.g. github.acme.com/api/v3", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = settingsFieldColors(),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val transports = listOf("https" to "HTTPS", "ssh" to "SSH")
                    transports.forEachIndexed { i, (value, label) ->
                        SegmentedButton(
                            selected = transport == value,
                            onClick = { transport = value },
                            shape = SegmentedButtonDefaults.itemShape(i, transports.size),
                        ) { Text(label) }
                    }
                }
                if (transport == "ssh" && kind == "gitlab") {
                    Text("SSH for GitLab is experimental.", color = warning, fontSize = 11.sp)
                }
            }

            // Connect
            Button(
                onClick = {
                    val t = token.trim()
                    if (t.isEmpty()) return@Button
                    submitting = true
                    error = null
                    scope.launch {
                        val ok = onAdd(kind, t, hostUrl.trim().ifBlank { null }, transport)
                        submitting = false
                        if (ok) onDone() else error = "Couldn't connect — check your token and try again."
                    }
                },
                enabled = canConnect && !submitting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
            ) {
                if (submitting) {
                    CircularProgressIndicator(color = cs.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                } else {
                    Text("Connect ${kind.replaceFirstChar { it.uppercase() }}", color = cs.onPrimary)
                }
            }
        }
    }
}

// ─── Forge helpers ──────────────────────────────────────────────────────────────

private fun importableKinds(cli: ForgeCliStatus?, connections: List<ForgeConnection>): List<String> {
    if (cli == null) return emptyList()
    val result = mutableListOf<String>()
    for (kind in listOf("github", "gitlab")) {
        val presence = if (kind == "github") cli.github else cli.gitlab
        if (!presence.available) continue
        val login = presence.login
        if (login != null) {
            val already = connections.any { it.kind == kind && it.account.login.equals(login, ignoreCase = true) }
            if (already) continue
        }
        result.add(kind)
    }
    return result
}

private fun cliLogin(cli: ForgeCliStatus?, kind: String): String? =
    cli?.let { if (kind == "github") it.github.login else it.gitlab.login }

private fun cliName(kind: String): String = if (kind == "github") "gh" else "glab"

private fun scopesHint(kind: String, hostUrl: String): String {
    if (kind == "github") {
        val host = hostUrl.trim()
            .replace(Regex("^https?://"), "")
            .substringBefore("/")
        return if (host.isNotEmpty() && host != "github.com") "repo, read:org" else "Contents + Administration (read & write)"
    }
    return "api"
}
