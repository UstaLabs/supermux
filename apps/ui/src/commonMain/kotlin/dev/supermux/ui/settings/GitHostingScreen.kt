// GitHub & GitLab (#97 spec §2.2, §10.8): the machine's connected forge accounts, as Coding's first section and as the
// setup wizard's git-hosting step. Today's Git hosting screen restyled in place with the Settings kit: the same load,
// the same add form (now a sheet: the gh / glab login on the machine, or a pasted token with "Your own server" for
// either forge), the disconnect confirm that keeps the row when the machine refuses, and every surviving test tag.
//
// The add form is still kept for the page on display (#89), and its connect runs on the fleet's scope.
package dev.supermux.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.ForgeCliStatus
import dev.supermux.net.ForgeConnection
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.settings.kit.KitDot
import dev.supermux.ui.settings.kit.KitFold
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitItemStatus
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitOptions
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitTextField
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.kitColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Every call the GitHub & GitLab section makes, in one holder.
 *
 * Mutations are suspend→Boolean: a refused delete keeps the row and says so instead of optimistically dropping it.
 */
@Immutable
class GitHostingActions(
    /** Connections + which of gh / glab the machine has; `null` = transport/decode failure. */
    val forgesLoad: suspend () -> ForgeConnectionsResponse? = { null },
    val forgeAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean =
        { _, _, _, _ -> false },
    val forgeImport: suspend (kind: String, transport: String) -> Boolean = { _, _ -> false },
    val forgeRemove: suspend (id: String) -> Boolean = { false },
)

/** [GitHostingActions] against one paired host — desktop's wiring. */
@Composable
fun rememberGitHostingActions(app: HostStore): GitHostingActions = remember(app) {
    GitHostingActions(
        forgesLoad = { app.forgesLoad() },
        forgeAdd = { kind, token, host, transport -> app.forgeAdd(kind, token, host, transport) },
        forgeImport = { kind, transport -> app.forgeImport(kind, transport) },
        forgeRemove = { id -> app.forgeRemove(id) },
    )
}

/** [GitHostingActions] against the fleet's ACTIVE host — Android's and iOS's wiring. */
@Composable
fun rememberGitHostingActions(fleet: FleetStore): GitHostingActions = remember(fleet) {
    GitHostingActions(
        forgesLoad = { fleet.forgesLoad() },
        forgeAdd = { kind, token, host, transport -> fleet.forgeAdd(kind, token, host, transport) },
        forgeImport = { kind, transport -> fleet.forgeImport(kind, transport) },
        forgeRemove = { id -> fleet.forgeRemove(id) },
    )
}

/**
 * The GitHub & GitLab section on a page of its own — what the setup wizard's git-hosting step embeds. [onBack] and
 * [topBarShown] are kept for its callers; the page paints no chrome of its own.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun GitHostingScreen(
    actions: GitHostingActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
) {
    KitPage("GitHub & GitLab", modifier) { GitHubSection(actions) }
}

/** The machine as a sentence names it mid-way: its name, or "this machine" when Settings knows none. */
@Composable
private fun machineInSentence(): String = LocalSettingsMachines.current?.current?.name ?: "this machine"

internal fun forgeLabel(kind: String): String = if (kind == "gitlab") "GitLab" else "GitHub"

/**
 * The connected accounts as items (handle; "‹host› · HTTPS|SSH"; Connected or Reconnect; Disconnect) and "Add a GitHub
 * or GitLab account". Loads on first composition; the caller re-keys it on the machine.
 */
@Composable
fun GitHubSection(actions: GitHostingActions, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val machine = machineInSentence()

    var connections by remember { mutableStateOf<List<ForgeConnection>>(emptyList()) }
    var cliStatus by remember { mutableStateOf<ForgeCliStatus?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    /** What a disconnect that failed says, under the list. */
    var error by remember { mutableStateOf<String?>(null) }
    // The add form, open or not and what is typed in it, kept for the page on display (#89): a rotation closed it
    // with a pasted token gone. Its connect runs on the fleet's scope and lands in it (see [ForgeForm]).
    val add = rememberRetainedOnPage("git:add") { ForgeForm() }
    var disconnectTarget by remember { mutableStateOf<ForgeConnection?>(null) }
    var disconnecting by remember { mutableStateOf(false) }

    suspend fun reload(clearError: Boolean = true) {
        val r = actions.forgesLoad()
        if (r != null) {
            connections = r.connections
            cliStatus = r.cli
            loadFailed = false
            if (clearError) error = null
        } else {
            loadFailed = true
        }
        loaded = true
    }

    LaunchedEffect(attempt) { reload() }

    Column(modifier.fillMaxWidth().testTag("git_hosting_screen")) {
        when {
            loadFailed -> {
                KitNote("Couldn't reach $machine.", Modifier.testTag("git_hosting_error"))
                KitTextAction(
                    "Try again",
                    onClick = { attempt++ },
                    modifier = Modifier.padding(top = 4.dp).testTag("git_hosting_retry"),
                )
            }
            !loaded -> Unit
            else -> {
                error?.let { ErrorLine(it, "git_hosting_error") }
                KitItemList(Modifier.testTag("git_hosting_list")) {
                    connections.forEach { c ->
                        ForgeItem(c, onReconnect = { add.open(c.kind) }, onDisconnect = { disconnectTarget = c })
                    }
                    val logins = loginKinds(cliStatus)
                    KitItem(
                        "Add a GitHub or GitLab account",
                        modifier = Modifier.testTag("git_hosting_add_account"),
                        subtitle = if (logins.isEmpty()) {
                            "Paste a personal access token"
                        } else {
                            "Use the ${logins.joinToString(" / ") { cliName(it) }} login on $machine, or paste a token"
                        },
                        mark = KitMark.Add,
                        onClick = { add.open(null) },
                    )
                }
            }
        }
    }

    if (add.isOpen) {
        AddForgeSheet(
            form = add,
            cliStatus = cliStatus,
            machine = machine,
            onAdd = actions.forgeAdd,
            onImport = actions.forgeImport,
            onDone = {
                add.close()
                scope.launch { reload() }
            },
        )
    }

    disconnectTarget?.let { target ->
        KitSheet(
            title = "Disconnect @${target.account.login}?",
            onDismiss = { if (!disconnecting) disconnectTarget = null },
            description = "supermux on $machine stops using this account. You can connect it again at any time.",
            tag = "git_hosting_disconnect_dialog",
            buttons = {
                KitGhostButton(
                    "Cancel",
                    onClick = { disconnectTarget = null },
                    modifier = Modifier.testTag("git_hosting_disconnect_cancel"),
                    enabled = !disconnecting,
                )
                KitGhostButton(
                    if (disconnecting) "Disconnecting…" else "Disconnect",
                    onClick = {
                        if (disconnecting) return@KitGhostButton
                        disconnecting = true
                        scope.launch {
                            val ok = actions.forgeRemove(target.id)
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
                    modifier = Modifier.testTag("git_hosting_disconnect_confirm"),
                    enabled = !disconnecting,
                    destructive = true,
                )
            },
        )
    }
}

@Composable
private fun ForgeItem(c: ForgeConnection, onReconnect: () -> Unit, onDisconnect: () -> Unit) {
    val host = c.host.ifEmpty { if (c.kind == "gitlab") "gitlab.com" else "github.com" }
    KitItem(
        "@${c.account.login}",
        modifier = Modifier.testTag("forge_row_${c.id}"),
        subtitle = "$host · ${c.transport.uppercase()}",
        mark = KitMark.Letters(if (c.kind == "gitlab") "gl" else "gh"),
    ) {
        if (c.status == "needs_reconnect") {
            KitGhostButton("Reconnect", onClick = onReconnect, modifier = Modifier.testTag("forge_reconnect_${c.id}"))
        } else {
            KitItemStatus("Connected", dot = KitDot.Ok, modifier = Modifier.testTag("forge_ok_${c.id}"))
        }
        KitGhostButton("Disconnect", onClick = onDisconnect, modifier = Modifier.testTag("forge_disconnect_${c.id}"))
    }
}

/**
 * "Add a GitHub or GitLab account": the gh / glab login on the machine (each only where the machine has it), or a
 * pasted token for either forge, with "Your own server" (the host, HTTPS · SSH) for both. A sheet that a tap outside
 * does not close (a token may be half pasted), and that stays while a connect is in flight.
 */
@Composable
private fun AddForgeSheet(
    form: ForgeForm,
    cliStatus: ForgeCliStatus?,
    machine: String,
    onAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean,
    onImport: suspend (kind: String, transport: String) -> Boolean,
    onDone: () -> Unit,
) {
    val scope = rememberRetainedScope()
    var kind by form::kind
    var token by form::token
    var hostUrl by form::hostUrl
    var transport by form::transport
    var error by form::error
    val submitting = form.submitting

    // A connect that landed, finished by the form on screen (it may be another one than started it).
    LaunchedEffect(form, form.done) {
        if (!form.done) return@LaunchedEffect
        form.done = false
        onDone()
    }

    val hostUrlError = forgeHostUrlError(hostUrl)
    val canConnect = token.trim().isNotEmpty() && hostUrlError == null

    fun submitConnect() {
        val t = token.trim()
        if (t.isEmpty() || hostUrlError != null || form.submitting) return
        form.submitting = true
        error = null
        val k = kind
        val host = hostUrl.trim().ifBlank { null }
        val tr = transport
        scope.launch {
            val ok = onAdd(k, t, host, tr)
            form.submitting = false
            if (ok) form.done = true else form.error = "Couldn't connect — check your token and try again."
        }
    }

    fun useLogin(k: String) {
        if (form.submitting) return
        form.submitting = true
        error = null
        val tr = transport
        scope.launch {
            val ok = onImport(k, tr)
            form.submitting = false
            if (ok) form.done = true else form.error = "Couldn't use the ${cliName(k)} login on $machine — is it signed in?"
        }
    }

    KitSheet(
        title = "Add a GitHub or GitLab account",
        // Read when the dismiss arrives, not captured at composition.
        onDismiss = { if (!form.submitting) form.close() },
        tag = "git_hosting_add_dialog",
        dismissOnOutside = false,
        buttons = {
            KitGhostButton(
                "Cancel",
                onClick = { form.close() },
                modifier = Modifier.testTag("git_hosting_add_cancel"),
                enabled = !submitting,
            )
            KitGhostButton(
                if (submitting) "Connecting…" else "Connect ${forgeLabel(kind)}",
                onClick = { submitConnect() },
                modifier = Modifier.testTag("git_hosting_connect"),
                enabled = canConnect && !submitting,
            )
        },
    ) {
        val logins = loginKinds(cliStatus)
        if (logins.isNotEmpty()) {
            KitItemList(Modifier.padding(top = 12.dp)) {
                logins.forEach { k ->
                    KitItem(
                        "Use the ${cliName(k)} login on $machine",
                        modifier = Modifier.testTag("git_hosting_import_$k"),
                        subtitle = cliLogin(cliStatus, k)?.let { "@$it, found on that machine" },
                        onClick = { useLogin(k) },
                    )
                }
            }
        }
        KitSectionHead("Paste a token")
        KitOptions(
            options = listOf("github", "gitlab"),
            selected = kind,
            onSelect = { kind = it },
            label = ::forgeLabel,
            modifier = Modifier.padding(top = 6.dp).testTag("git_hosting_kind_row"),
            enabled = !submitting,
            tag = { "git_hosting_kind_$it" },
        )
        KitTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.padding(top = 12.dp),
            label = "Personal access token",
            placeholder = if (kind == "github") "github_pat_…" else "glpat-…",
            secret = true,
            enabled = !submitting,
            onSubmit = { if (canConnect) submitConnect() },
            fieldTag = "git_hosting_token",
        )
        if (error != null) {
            ErrorLine(error!!, "git_hosting_add_error")
        } else {
            KitNote("Needs scopes: ${scopesHint(kind, hostUrl)}")
        }
        // Open from the start when it holds something: an unfolded phone keeps the typing, not the fold.
        KitFold(
            "Your own server",
            tag = "git_hosting_advanced_toggle",
            initiallyOpen = hostUrl.isNotBlank() || transport != "https",
        ) {
            KitTextField(
                value = hostUrl,
                onValueChange = {
                    hostUrl = it
                    error = null
                },
                modifier = Modifier.padding(top = 8.dp),
                label = "Server",
                placeholder = if (kind == "gitlab") "gitlab.example.com" else "github.example.com",
                enabled = !submitting,
                error = hostUrlError,
                mono = true,
                fieldTag = "git_hosting_host_url",
            )
            KitOptions(
                options = listOf("https", "ssh"),
                selected = transport,
                onSelect = { transport = it },
                label = { it.uppercase() },
                modifier = Modifier.padding(top = 10.dp).testTag("git_hosting_transport_row"),
                enabled = !submitting,
                tag = { "git_hosting_transport_$it" },
            )
            if (transport == "ssh" && kind == "gitlab") KitNote("SSH for GitLab is experimental.")
        }
    }
}

@Composable
private fun ErrorLine(text: String, tag: String) {
    Text(text, fontSize = 12.sp, color = kitColors().danger, modifier = Modifier.padding(top = 8.dp).testTag(tag))
}

// ── Forge helpers ───────────────────────────────────────────────────────────────────────────────

/** The forges whose command-line login the machine has, so the add sheet can offer "Use the gh login on …". */
internal fun loginKinds(cli: ForgeCliStatus?): List<String> =
    if (cli == null) emptyList() else listOf("github", "gitlab").filter { (if (it == "github") cli.github else cli.gitlab).available }

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
 * Validate an optional self-hosted server.
 * Blank is allowed (public github.com / gitlab.com). Non-blank must look like a host or http(s) URL; supermux adds
 * the API path itself, so the hint never asks for one (inventory bug 8).
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
    return if (isValidForgeHostUrl(t)) null else "Enter a host like github.example.com"
}

/** True when [raw] is blank or a plausible host / http(s) URL. */
internal fun isValidForgeHostUrl(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty()) return true
    if (t.any { it.isWhitespace() }) return false
    val lower = t.lowercase()
    if ("://" in lower && !lower.startsWith("http://") && !lower.startsWith("https://")) return false
    val withoutScheme = t.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
    // platform-ok: a URL
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

/**
 * The add sheet, kept for the page on display (`rememberRetainedOnPage`, #89): open or not, what is typed, and a
 * connect in flight, which runs on the fleet's scope and sets [done] for the form on screen. Closing it starts the
 * next opening afresh.
 */
private class ForgeForm {
    var isOpen by mutableStateOf(false)
    var kind by mutableStateOf("github")
    var token by mutableStateOf("")
    var hostUrl by mutableStateOf("")
    var transport by mutableStateOf("https")
    var error by mutableStateOf<String?>(null)
    var submitting by mutableStateOf(false)
    var done by mutableStateOf(false)

    fun open(presetKind: String?) {
        close()
        kind = presetKind?.takeIf { it == "github" || it == "gitlab" } ?: "github"
        isOpen = true
    }

    fun close() {
        isOpen = false
        kind = "github"
        token = ""
        hostUrl = ""
        transport = "https"
        error = null
        submitting = false
        done = false
    }
}
