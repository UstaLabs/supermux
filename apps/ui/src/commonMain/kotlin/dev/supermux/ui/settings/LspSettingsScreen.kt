// Code intelligence (#97 spec §2.2, §10.8): the machine's language servers, Coding's last section. Today's LSP screen
// restyled in place with the Settings kit: one item per server with its toggle, Install (the live log while it runs,
// "Installing…", a failure with Show log) and Remove for your own; then the "Add your own language server" fold with
// today's seven fields, validation and save.
//
// The section owns its server list (loaded on first composition, then patched in place by toggle / install / add /
// remove); the install log and result are the store's live per-server streams, only observed. The add form is kept for
// the page on display (#89), and its save runs on the fleet's scope.
package dev.supermux.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AddCustomLspArgs
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.settings.kit.KitButtons
import dev.supermux.ui.settings.kit.KitFold
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitTextField
import dev.supermux.ui.settings.kit.KitToggle
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.theme.MonoFontFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Every call the Code intelligence section makes, in one holder. [lspInstallLog]/[lspInstallDone] are the store's
 * live per-server install streams (folded from its install frames), observed, never reloaded.
 */
@Immutable
class CodeIntelligenceActions(
    val lspLoad: suspend () -> List<LspServer> = { emptyList() },
    val lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>? = { _, _ -> null },
    val lspInstall: suspend (id: String) -> LspInstallResult? = { null },
    val lspInstallLog: StateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap()),
    val lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>> = MutableStateFlow(emptyMap()),
    val lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult? = { null },
    val lspRemoveCustom: suspend (id: String) -> LspMutationResult? = { null },
)

/** [CodeIntelligenceActions] against one paired host — desktop's wiring. */
@Composable
fun rememberCodeIntelligenceActions(app: HostStore): CodeIntelligenceActions = remember(app) {
    CodeIntelligenceActions(
        lspLoad = { app.lspLoad() },
        lspToggle = { id, enabled -> app.lspToggle(id, enabled) },
        lspInstall = { id -> app.lspInstall(id) },
        lspInstallLog = app.lspInstallLog,
        lspInstallDone = app.lspInstallDone,
        lspAddCustom = { a -> app.lspAddCustom(a.id, a.label, a.command, a.extensions, a.args, a.languageId, a.installCmd) },
        lspRemoveCustom = { id -> app.lspRemoveCustom(id) },
    )
}

/** [CodeIntelligenceActions] against the fleet's ACTIVE host — Android's and iOS's wiring. */
@Composable
fun rememberCodeIntelligenceActions(fleet: FleetStore): CodeIntelligenceActions = remember(fleet) {
    CodeIntelligenceActions(
        lspLoad = { fleet.lspLoad() },
        lspToggle = { id, enabled -> fleet.lspToggle(id, enabled) },
        lspInstall = { id -> fleet.lspInstall(id) },
        lspInstallLog = fleet.lspInstallLog,
        lspInstallDone = fleet.lspInstallDone,
        lspAddCustom = { a -> fleet.lspAddCustom(a.id, a.label, a.command, a.extensions, a.args, a.languageId, a.installCmd) },
        lspRemoveCustom = { id -> fleet.lspRemoveCustom(id) },
    )
}

/** The machine as a sentence names it mid-way: its name, or "this machine" when Settings knows none. */
@Composable
private fun machineInSentence(): String = LocalSettingsMachines.current?.current?.name ?: "this machine"

/**
 * One item per language server, then "Add your own language server". Loads on first composition; the caller re-keys
 * it on the machine.
 */
@Composable
fun CodeIntelligenceSection(actions: CodeIntelligenceActions, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val machine = machineInSentence()

    var servers by remember { mutableStateOf<List<LspServer>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var toggling by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var logFor by remember { mutableStateOf<String?>(null) }
    // The add form and its save, kept for the page on display (#89): a rotation lost seven fields of typing. The save
    // runs on the fleet's scope and leaves the new list in [LspAddForm.added] for the section on display.
    val add = rememberRetainedOnPage("lsp:add") { LspAddForm() }
    val saves = rememberRetainedScope()
    LaunchedEffect(add, add.added) {
        val list = add.added ?: return@LaunchedEffect
        add.added = null
        servers = list
    }

    val installLog by actions.lspInstallLog.collectAsState()
    val installDone by actions.lspInstallDone.collectAsState()
    var dismissedResults by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun reload() {
        servers = actions.lspLoad()
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier.fillMaxWidth().testTag("lsp_settings_screen")) {
        if (loaded && servers.isNotEmpty()) {
            KitItemList {
                servers.forEach { server ->
                    key(server.id) {
                        LspServerItem(
                            server = server,
                            machine = machine,
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
                                        val updated = actions.lspToggle(server.id, enabled)
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
                                        actions.lspInstall(server.id)
                                        reload()
                                        installing = null
                                    }
                                }
                            },
                            onRemove = {
                                if (removing == null) {
                                    scope.launch {
                                        removing = server.id
                                        val r = actions.lspRemoveCustom(server.id)
                                        if (r?.ok == true) {
                                            servers = r.lsp?.servers ?: servers.filterNot { it.id == server.id }
                                        }
                                        removing = null
                                    }
                                }
                            },
                            onShowLog = { logFor = server.id },
                            onDismissResult = { dismissedResults = dismissedResults + server.id },
                        )
                    }
                }
            }
        }

        // A new fold after each save, so a saved server leaves it closed; open from the start while it holds typing.
        if (loaded) key(add.saved) {
            KitFold(
                "Add your own language server",
                modifier = Modifier.kitAnchor("add-language-server"),
                anchors = setOf("add-language-server"),
                tag = "lsp_add_toggle",
                initiallyOpen = add.hasTyping(),
            ) {
                AddLspForm(
                    form = add,
                    machine = machine,
                    onCancel = { add.clear() },
                    onSubmit = { args ->
                        saves.launch {
                            val r = actions.lspAddCustom(args)
                            add.saving = false
                            if (r?.ok == true && r.lsp != null) {
                                add.clear()
                                add.saved++
                                add.added = r.lsp!!.servers
                            } else {
                                add.addError = r?.error ?: "Couldn't add the language server."
                            }
                        }
                    },
                )
            }
        }
    }

    logFor?.let { id ->
        val server = servers.firstOrNull { it.id == id }
        val result = installDone[id]
        KitSheet(
            title = "Installing ${server?.label ?: id}",
            onDismiss = { logFor = null },
            description = result?.error,
            tag = "lsp_install_log_sheet",
            buttons = { KitGhostButton("Close", onClick = { logFor = null }) },
        ) {
            val lines = installLog[id].orEmpty()
            Column(Modifier.padding(top = 10.dp)) {
                if (lines.isEmpty()) KitNote("Nothing was logged.")
                lines.forEach { line -> Text(line, fontSize = 11.sp, fontFamily = MonoFontFamily, color = kitColors().dim) }
            }
        }
    }
}

@Composable
private fun LspServerItem(
    server: LspServer,
    machine: String,
    toggling: Boolean,
    installing: Boolean,
    removing: Boolean,
    installBlocked: Boolean,
    installLines: List<String>,
    installResult: ServerFrame.LspInstallDone?,
    onToggle: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onShowLog: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val c = kitColors()
    val ready = server.state == "ready"
    Column(Modifier.fillMaxWidth().testTag("lsp_server_row_${server.id}")) {
        KitItem(
            server.label,
            subtitle = if (ready && server.extensions.isNotEmpty()) extSummary(server.extensions) else stateLabel(server, machine),
            mark = KitMark.Letters(lspMark(server.label)),
        ) {
            if (server.enabled && server.installable && !ready) {
                KitGhostButton(
                    if (installing) "Installing…" else (server.installLabel ?: "Install"),
                    onClick = onInstall,
                    modifier = Modifier.testTag("lsp_install_${server.id}"),
                    enabled = !installBlocked,
                )
            }
            if (server.custom) {
                KitGhostButton(
                    if (removing) "Removing…" else "Remove",
                    onClick = onRemove,
                    modifier = Modifier.testTag("lsp_remove_${server.id}"),
                    enabled = !removing,
                    destructive = true,
                )
            }
            KitToggle(
                checked = server.enabled,
                onCheckedChange = onToggle,
                label = "Use ${server.label}",
                modifier = Modifier.testTag("lsp_toggle_${server.id}"),
                enabled = !toggling,
            )
        }

        // The live install log, while this server installs.
        if (installing && installLines.isNotEmpty()) {
            Column(Modifier.padding(start = 38.dp, bottom = 8.dp).testTag("lsp_install_log_${server.id}")) {
                installLines.takeLast(6).forEach { line ->
                    Text(line, fontSize = 11.sp, fontFamily = MonoFontFamily, color = c.dim, maxLines = 1)
                }
            }
        }

        // How the last install ended, until dismissed.
        installResult?.let { result ->
            Row(
                Modifier.fillMaxWidth().padding(start = 38.dp, bottom = 8.dp).testTag("lsp_install_result_${server.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    if (result.ok) "Installed." else "Couldn't install ${server.label}.",
                    fontSize = 12.sp,
                    color = if (result.ok) c.dim else c.danger,
                    modifier = Modifier.weight(1f),
                )
                if (!result.ok) {
                    KitTextAction("Show log", onClick = onShowLog, modifier = Modifier.testTag("lsp_install_show_log_${server.id}"))
                }
                KitTextAction("Dismiss", onClick = onDismissResult, modifier = Modifier.testTag("lsp_install_dismiss_${server.id}"))
            }
        }
    }
}

@Composable
private fun AddLspForm(
    form: LspAddForm,
    machine: String,
    onCancel: () -> Unit,
    onSubmit: (AddCustomLspArgs) -> Unit,
) {
    var label by form::label
    var id by form::id
    var command by form::command
    var args by form::args
    var extensions by form::extensions
    var languageId by form::languageId
    var installCmd by form::installCmd
    val saving = form.saving

    fun save() {
        val idStr = id.trim().ifEmpty { slugId(label) }
        val labelStr = label.trim()
        val commandStr = command.trim()
        val extStr = extensions.trim()
        if (labelStr.isEmpty() || commandStr.isEmpty() || extStr.isEmpty()) {
            form.addError = "Fill in the name, command and file extensions."
            return
        }
        form.saving = true
        form.addError = null
        onSubmit(
            AddCustomLspArgs(
                id = idStr,
                label = labelStr,
                command = commandStr,
                extensions = extStr.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                args = args.trim().split(" ").map { it.trim() }.filter { it.isNotEmpty() },
                languageId = languageId.trim().ifEmpty { null },
                installCmd = installCmd.trim().ifEmpty { null },
            ),
        )
    }

    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp).testTag("lsp_add_form"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        KitNote("Name, command, file extensions — for languages we don't list.")
        LspField("Short name", "zig", id, "lsp_add_id", enabled = !saving) { id = it }
        LspField("Name", "Zig", label, "lsp_add_label", mono = false, enabled = !saving) {
            label = it
            // The short name follows the name until it is typed in itself.
            if (id.isBlank() && it.isNotBlank()) id = slugId(it)
        }
        LspField("Command", "zls", command, "lsp_add_command", enabled = !saving) { command = it }
        LspField("Arguments", "--stdio", args, "lsp_add_args", enabled = !saving) { args = it }
        LspField("File extensions", ".zig, .zon", extensions, "lsp_add_extensions", enabled = !saving) { extensions = it }
        LspField("Language name (optional)", "zig", languageId, "lsp_add_language_id", enabled = !saving) { languageId = it }
        LspField("Install command (optional)", null, installCmd, "lsp_add_install_cmd", enabled = !saving) { installCmd = it }
        KitNote("The install command runs as your user on $machine, without sudo.")
        form.addError?.let {
            Text(it, fontSize = 12.sp, color = kitColors().danger, modifier = Modifier.testTag("lsp_add_error"))
        }
        KitButtons {
            KitGhostButton(
                if (saving) "Adding…" else "Add",
                onClick = { save() },
                modifier = Modifier.testTag("lsp_add_save"),
                enabled = !saving,
            )
            KitGhostButton("Cancel", onClick = onCancel, modifier = Modifier.testTag("lsp_add_cancel"), enabled = !saving)
        }
    }
}

@Composable
private fun LspField(
    label: String,
    placeholder: String?,
    value: String,
    tag: String,
    mono: Boolean = true,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    KitTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        enabled = enabled,
        mono = mono,
        // A short name, a command or an extension list is not a sentence. Inert where there is no soft keyboard.
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = if (mono) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
        ),
        fieldTag = tag,
    )
}

/** "Ready" / "Ready · ‹command›" (yours) / "Needs ‹what› on ‹machine›" / "Not installed" / "Not found on ‹machine›" (yours). */
internal fun stateLabel(server: LspServer, machine: String): String = when (server.state) {
    "ready" -> if (server.custom && server.command != null) "Ready · ${server.command}" else "Ready"
    "prereq-missing" -> "Needs ${server.requires ?: "its toolchain"} on $machine"
    else -> if (server.custom) "Not found on $machine" else "Not installed"
}

/** Up to 6 unique extensions, comma-joined, with a trailing "…" if more were truncated. */
internal fun extSummary(exts: List<String>): String {
    val unique = exts.distinct()
    val shown = unique.take(6)
    val tail = if (unique.size > shown.size) "…" else ""
    return shown.joinToString(", ") + tail
}

/** The item's two letters: a name's capitals ("TypeScript" → "TS"), else its first two letters ("Pyright" → "Py"). */
internal fun lspMark(label: String): String {
    val caps = label.filter { it.isUpperCase() }
    if (caps.length >= 2) return caps.take(2)
    val letters = label.filter { it.isLetterOrDigit() }
    return letters.take(1).uppercase() + letters.drop(1).take(1).lowercase()
}

/** Lowercase + hyphenate a name into a short name; falls back to "server" if nothing alphanumeric survives. */
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

/**
 * The "Add your own language server" form, kept for the page on display (`rememberRetainedOnPage`, #89): the fields
 * and a save in flight, which leaves the new list in [added]. [saved] counts saves, so the fold closes after one.
 */
private class LspAddForm {
    var label by mutableStateOf("")
    var id by mutableStateOf("")
    var command by mutableStateOf("")
    var args by mutableStateOf("")
    var extensions by mutableStateOf("")
    var languageId by mutableStateOf("")
    var installCmd by mutableStateOf("")
    var saving by mutableStateOf(false)
    var addError by mutableStateOf<String?>(null)
    var saved by mutableIntStateOf(0)
    /** The servers after a save, for the section on display. */
    var added by mutableStateOf<List<LspServer>?>(null)

    fun hasTyping(): Boolean =
        listOf(label, id, command, args, extensions, languageId, installCmd).any { it.isNotEmpty() }

    fun clear() {
        label = ""
        id = ""
        command = ""
        args = ""
        extensions = ""
        languageId = ""
        installCmd = ""
        saving = false
        addError = null
    }
}
