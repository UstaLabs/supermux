package dev.supermux.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.LocalPanes
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ─── Editor / Language-servers section ──────────────────────────────────────────
//
// The LSP half of the Editor screen (iOS EditorSettingsScreen's "Language servers"
// section). NOT a full page — it is rendered inside the existing local-prefs
// EditorSettingsPage (MoreScreens.kt §6), so iOS's single "Editor" screen is mirrored.
// Per-server enable toggle + state badge + install (with live streamed log) + add/remove
// custom server.

@Composable
fun EditorLspSection(
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf<List<LspServer>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var lspError by remember { mutableStateOf<String?>(null) }
    var toggling by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var showAddForm by remember { mutableStateOf(false) }

    // Live install-progress maps (observed) so the installing row streams lines + result.
    val installLog by lspInstallLog.collectAsState()
    val installDone by lspInstallDone.collectAsState()
    var dismissedResults by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun reload() {
        loading = true
        lspError = null
        val loaded = lspLoad()
        servers = loaded
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSectionHeader("LANGUAGE SERVERS")
        SettingsCaption("Language servers run on the broker host.")

        when {
            loading -> Row(
                Modifier.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("Loading…", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
            lspError != null -> Column(Modifier.padding(vertical = 8.dp)) {
                Text(lspError!!, color = cs.error, fontSize = 13.sp)
                TextButton(onClick = { scope.launch { reload() } }) { Text("Retry", color = cs.primary) }
            }
            else -> {
                servers.forEach { server ->
                    HorizontalDivider(color = cs.outlineVariant)
                    LspServerRow(
                        server = server,
                        toggling = toggling == server.id,
                        installing = installing == server.id,
                        removing = removing == server.id,
                        installBlocked = installing != null,
                        installLines = installLog[server.id].orEmpty(),
                        installResult = installDone[server.id]?.takeIf { server.id !in dismissedResults },
                        onToggle = { enabled ->
                            if (toggling == null) {
                                scope.launch {
                                    toggling = server.id
                                    val updated = lspToggle(server.id, enabled)
                                    if (updated != null) servers = updated
                                    toggling = null
                                }
                            }
                        },
                        onInstall = {
                            if (installing == null) {
                                scope.launch {
                                    installing = server.id
                                    dismissedResults = dismissedResults - server.id
                                    lspInstall(server.id)
                                    reload()
                                    installing = null
                                }
                            }
                        },
                        onRemove = {
                            if (removing == null) {
                                scope.launch {
                                    removing = server.id
                                    val r = lspRemoveCustom(server.id)
                                    if (r?.ok == true) {
                                        servers = r.lsp?.servers ?: servers.filterNot { it.id == server.id }
                                    }
                                    removing = null
                                }
                            }
                        },
                        onDismissResult = { dismissedResults = dismissedResults + server.id },
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)

                if (!showAddForm) {
                    TextButton(onClick = { showAddForm = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
                        Spacer(Modifier.width(4.dp))
                        Text("Add language server", color = cs.primary)
                    }
                } else {
                    AddLspForm(
                        onCancel = { showAddForm = false },
                        onSubmit = { args, onResult ->
                            scope.launch {
                                val r = lspAddCustom(args)
                                if (r?.ok == true && r.lsp != null) {
                                    servers = r.lsp!!.servers
                                    showAddForm = false
                                    onResult(null)
                                } else {
                                    onResult(r?.error ?: "Couldn't add language server")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LspServerRow(
    server: LspServer,
    toggling: Boolean,
    installing: Boolean,
    removing: Boolean,
    installBlocked: Boolean,
    installLines: List<String>,
    installResult: ServerFrame.LspInstallDone?,
    onToggle: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val warning = Color(LocalPanes.current.warning)
    val ready = server.state == "ready"

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(server.label, color = cs.onSurface, fontSize = 14.sp)
                    if (server.custom) {
                        Text("CUSTOM", color = cs.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (server.extensions.isNotEmpty()) {
                    Text(extSummary(server.extensions), color = cs.onSurfaceVariant, fontSize = 11.sp)
                }
                Text(stateLabel(server), color = if (ready) Color(0xFF3FB950) else warning, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (server.custom) {
                    IconButton(onClick = onRemove, enabled = !removing) {
                        if (removing) {
                            CircularProgressIndicator(color = cs.error, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(
                                painterResource(R.drawable.ic_trash),
                                contentDescription = "Remove",
                                tint = cs.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                    enabled = !toggling,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = cs.onPrimary,
                        checkedTrackColor = cs.primary,
                    ),
                )
            }
        }

        // Install affordance (enabled + installable + not ready)
        if (server.enabled && server.installable && !ready) {
            TextButton(onClick = onInstall, enabled = !installBlocked) {
                if (installing) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else {
                    Icon(
                        painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(server.installLabel ?: "Install", color = cs.primary, fontSize = 12.sp)
            }
        }

        // Live install log (while installing this server)
        if (installing && installLines.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(cs.surfaceContainerLowest)
                    .padding(8.dp),
            ) {
                installLines.takeLast(6).forEach { line ->
                    Text(line, color = cs.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Terminal install result (until dismissed)
        installResult?.let { result ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    painterResource(if (result.ok) R.drawable.ic_check else R.drawable.ic_x),
                    contentDescription = null,
                    tint = if (result.ok) Color(0xFF3FB950) else cs.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (result.ok) (installLines.lastOrNull() ?: "Installed")
                    else (result.error ?: "Install failed"),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissResult, modifier = Modifier.size(24.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_x),
                        contentDescription = "Dismiss",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddLspForm(
    onCancel: () -> Unit,
    onSubmit: (AddCustomLspArgs, onResult: (String?) -> Unit) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var label by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var extensions by remember { mutableStateOf("") }
    var languageId by remember { mutableStateOf("") }
    var installCmd by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Add language server", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        addError?.let { Text(it, color = cs.error, fontSize = 12.sp) }

        LspField("Display name", "Zig", label, mono = false) {
            label = it
            // Auto-slug the id from the label until the user edits it directly.
            if (id.isBlank() && it.isNotBlank()) id = slugId(it)
        }
        LspField("Server id", "zig", id, mono = true) { id = it }
        LspField("Command on broker", "zls", command, mono = true) { command = it }
        LspField("Args (optional)", "--stdio", args, mono = true) { args = it }
        LspField("Extensions", ".zig, .zon", extensions, mono = true) { extensions = it }
        LspField("Language id (optional)", "zig", languageId, mono = true) { languageId = it }
        LspField("Install command (optional)", "apt install -y zls", installCmd, mono = true) { installCmd = it }

        SettingsCaption("Install command runs as the broker user — do not use sudo.")

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val idStr = id.trim().ifEmpty { slugId(label) }
                    val labelStr = label.trim()
                    val commandStr = command.trim()
                    val extStr = extensions.trim()
                    if (idStr.isEmpty() || labelStr.isEmpty() || commandStr.isEmpty() || extStr.isEmpty()) {
                        addError = "Fill in display name, command, and extensions"
                        return@Button
                    }
                    val argsList = args.trim().split(" ").map { it.trim() }.filter { it.isNotEmpty() }
                    val extList = extStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    saving = true
                    addError = null
                    onSubmit(
                        AddCustomLspArgs(
                            id = idStr,
                            label = labelStr,
                            command = commandStr,
                            extensions = extList,
                            args = argsList,
                            languageId = languageId.trim().ifEmpty { null },
                            installCmd = installCmd.trim().ifEmpty { null },
                        ),
                    ) { err ->
                        saving = false
                        if (err != null) addError = err
                    }
                },
                enabled = !saving,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
            ) {
                if (saving) {
                    CircularProgressIndicator(color = cs.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…", color = cs.onPrimary)
                } else {
                    Text("Save", color = cs.onPrimary)
                }
            }
            OutlinedButton(onClick = onCancel, enabled = !saving) { Text("Cancel", color = cs.onSurface) }
        }
    }
}

@Composable
private fun LspField(
    label: String,
    placeholder: String,
    value: String,
    mono: Boolean,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = if (mono) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
        ),
        textStyle = if (mono) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
        colors = settingsFieldColors(),
    )
}

private fun stateLabel(server: LspServer): String = when (server.state) {
    "ready" -> if (server.custom && server.command != null) "Ready · ${server.command}" else "Ready"
    "prereq-missing" -> "Needs ${server.requires ?: "toolchain"}"
    else -> if (server.custom) "Binary not found on broker" else "Not installed"
}

private fun extSummary(exts: List<String>): String {
    val unique = exts.distinct()
    val shown = unique.take(6)
    val tail = if (unique.size > shown.size) "…" else ""
    return shown.joinToString(", ") + tail
}

private fun slugId(label: String): String {
    val slug = label
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split("-")
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .take(48)
    return slug.ifEmpty { "server" }
}
