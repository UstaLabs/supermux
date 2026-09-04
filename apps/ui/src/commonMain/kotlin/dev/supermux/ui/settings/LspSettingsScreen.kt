// The one LSP settings screen for both apps (cluster C, task C3). Desktop's standalone
// `LspSettingsScreen` is the base — it is the richer of the two (its own back row + title, a
// spinner-centred loading state, the full test-tag set, `internal` pure helpers) — and Android's
// former `EditorLspSection` is now this same composable with `showTopBar = false`, embedded in the
// Editor settings page exactly where the section used to be.
//
// What each side contributed:
//   - Desktop: the standalone chrome, the centred spinner (Android's inline "Loading…" row is
//     dropped), all tags, and the DROP of Android's `lspError` branch — `lspLoad()` never throws
//     (HostStore degrades to emptyList() internally), so that state was never set and the branch
//     was dead code.
//   - Android: `KeyboardOptions(autoCorrectEnabled = false, capitalization = …)` on the add-custom
//     fields (a server id or a shell command is not a sentence) — harmless on a hardware keyboard,
//     and the `scrollable = false` embedding, since the Android page already scrolls around it.
//   - Both: the header/caption/field composables each app had inlined are the shared
//     `dev.supermux.ui.widgets` ones (they came from the same source in cluster A, task A5).
//
// Icons are Material (`Icons.Filled.Delete/Download/Check/Close`) — Android's `painterResource`
// drawables do not exist off Android, and the two glyph sets match.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.widgets.SettingsCaption
import dev.supermux.ui.widgets.SettingsSectionHeader
import dev.supermux.ui.widgets.settingsFieldColors
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import dev.supermux.net.AddCustomLspArgs

/**
 * The LSP settings overlay: a back row + title, then per-server rows (enable Switch + state badge
 * + install with a streamed log + remove for custom servers) and an add-custom-server form. Owns
 * its OWN server-list state (loads via [lspLoad] on first composition, then mutates it in place on
 * toggle/install-reload/add/remove) — unlike the Usage/Archived overlays, where AppShell owns
 * a single point-in-time snapshot, because every mutation here needs to patch the list in place
 * (mirrors Android's EditorLspSection exactly). [lspInstallLog]/[lspInstallDone] are the LIVE
 * per-server install stream (HostStore, folded from lsp_install_progress/lsp_install_done
 * frames) — not reloaded, just observed.
 */
@Composable
fun LspSettingsScreen(
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
    onBack: () -> Unit,
    /** When false (Settings hub / the Android Editor page), omit the nested Back/title chrome —
     *  the host owns navigation. */
    showTopBar: Boolean = true,
    /** False when the HOST already scrolls (Android's Editor settings page): the screen then wraps
     *  its content height instead of filling and scrolling, since a vertical scroll nested in a
     *  vertical scroll measures to zero height. */
    scrollable: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf<List<LspServer>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toggling by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var showAddForm by remember { mutableStateOf(false) }

    val installLog by lspInstallLog.collectAsState()
    val installDone by lspInstallDone.collectAsState()
    var dismissedResults by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun reload() {
        loading = true
        servers = lspLoad()
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        (if (scrollable) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .background(cs.surfaceContainerHigh)
            .testTag("lsp_settings_screen"),
    ) {
        if (showTopBar) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("lsp_settings_back")) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(Space.sm))
                Text(
                    "Language servers",
                    color = cs.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Box(if (scrollable) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            if (loading) {
                CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier
                        .align(if (scrollable) Alignment.Center else Alignment.TopCenter)
                        .padding(Space.lg)
                        .testTag("lsp_settings_spinner"),
                )
            } else {
                Column(
                    (if (scrollable) {
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    } else {
                        Modifier.fillMaxWidth()
                    })
                        .padding(start = Space.lg, end = Space.lg, top = Space.sm, bottom = Space.xl),
                ) {
                    // Android's section header, kept: without the top bar nothing else in this
                    // composable names what the rows are.
                    if (!showTopBar) SettingsSectionHeader("LANGUAGE SERVERS")
                    SettingsCaption(
                        "Language servers run on the broker host.",
                        modifier = Modifier.padding(bottom = Space.sm),
                    )
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
                        TextButton(onClick = { showAddForm = true }, modifier = Modifier.testTag("lsp_add_toggle")) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
                            Spacer(Modifier.width(Space.xs))
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
    val semantics = LocalSemantics.current
    val ready = server.state == "ready"

    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.sm).testTag("lsp_server_row_${server.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
                Text(stateLabel(server), color = if (ready) semantics.success else semantics.warning, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (server.custom) {
                    IconButton(onClick = onRemove, enabled = !removing, modifier = Modifier.testTag("lsp_remove_${server.id}")) {
                        if (removing) {
                            CircularProgressIndicator(color = cs.error, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = cs.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                    enabled = !toggling,
                    modifier = Modifier.testTag("lsp_toggle_${server.id}"),
                )
            }
        }

        // Install affordance (enabled + installable + not ready).
        if (server.enabled && server.installable && !ready) {
            TextButton(onClick = onInstall, enabled = !installBlocked, modifier = Modifier.testTag("lsp_install_${server.id}")) {
                if (installing) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = cs.primary, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(Space.xs))
                Text(server.installLabel ?: "Install", color = cs.primary, fontSize = 12.sp)
            }
        }

        // Live install log (while installing this server).
        if (installing && installLines.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(cs.surfaceContainerLowest)
                    .padding(Space.sm)
                    .testTag("lsp_install_log_${server.id}"),
            ) {
                installLines.takeLast(6).forEach { line ->
                    Text(line, color = cs.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Terminal install result (until dismissed).
        installResult?.let { result ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("lsp_install_result_${server.id}"),
            ) {
                Icon(
                    if (result.ok) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (result.ok) semantics.success else cs.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (result.ok) (installLines.lastOrNull() ?: "Installed") else (result.error ?: "Install failed"),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissResult,
                    modifier = Modifier.size(24.dp).testTag("lsp_install_dismiss_${server.id}"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = cs.onSurfaceVariant, modifier = Modifier.size(12.dp))
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

    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.sm).testTag("lsp_add_form"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsSectionHeader("Add language server")
        addError?.let { Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.testTag("lsp_add_error")) }

        LspField("Display name", "Zig", label, mono = false, testTag = "lsp_add_label") {
            label = it
            // Auto-slug the id from the label until the user edits it directly.
            if (id.isBlank() && it.isNotBlank()) id = slugId(it)
        }
        LspField("Server id", "zig", id, mono = true, testTag = "lsp_add_id") { id = it }
        LspField("Command on broker", "zls", command, mono = true, testTag = "lsp_add_command") { command = it }
        LspField("Args (optional)", "--stdio", args, mono = true, testTag = "lsp_add_args") { args = it }
        LspField("Extensions", ".zig, .zon", extensions, mono = true, testTag = "lsp_add_extensions") { extensions = it }
        LspField("Language id (optional)", "zig", languageId, mono = true, testTag = "lsp_add_language_id") { languageId = it }
        LspField("Install command (optional)", "apt install -y zls", installCmd, mono = true, testTag = "lsp_add_install_cmd") { installCmd = it }

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
                modifier = Modifier.weight(1f).testTag("lsp_add_save"),
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
            ) {
                if (saving) {
                    CircularProgressIndicator(color = cs.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(Space.sm))
                    Text("Saving…", color = cs.onPrimary)
                } else {
                    Text("Save", color = cs.onPrimary)
                }
            }
            OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.testTag("lsp_add_cancel")) {
                Text("Cancel", color = cs.onSurface)
            }
        }
    }
}

@Composable
private fun LspField(
    label: String,
    placeholder: String,
    value: String,
    mono: Boolean,
    testTag: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        singleLine = true,
        // Android's: a server id, a command or an extension list is not a sentence. Inert where
        // there is no soft keyboard.
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

/** "Ready" / "Ready · <command>" (custom) / "Needs <requires>" / "Not installed" /
 *  "Binary not found on broker" (custom) — port of Android's `stateLabel`. */
internal fun stateLabel(server: LspServer): String = when (server.state) {
    "ready" -> if (server.custom && server.command != null) "Ready · ${server.command}" else "Ready"
    "prereq-missing" -> "Needs ${server.requires ?: "toolchain"}"
    else -> if (server.custom) "Binary not found on broker" else "Not installed"
}

/** Up to 6 unique extensions, comma-joined, with a trailing "…" if more were truncated. */
internal fun extSummary(exts: List<String>): String {
    val unique = exts.distinct()
    val shown = unique.take(6)
    val tail = if (unique.size > shown.size) "…" else ""
    return shown.joinToString(", ") + tail
}

/** Lowercase + hyphenate a display name into a server id; falls back to "server" if nothing
 *  alphanumeric survives. */
internal fun slugId(label: String): String {
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
