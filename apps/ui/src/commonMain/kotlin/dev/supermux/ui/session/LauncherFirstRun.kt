// The launcher's first-run states, and the broker refusals that lead to them.
//
// A fresh install has no projects and, often, no agent CLI. Rather than a search box over nothing
// and a send that crashes, the launcher shows:
//   - [FirstProjectScreen] while the host has NO projects: "Open a folder" (a path the broker
//     validates) or "Create a new project" (a name; the broker makes it under its projects root via
//     `createLocalRepo`, with `git init` only when git is there). Git is optional throughout.
//   - [InstallAgentPanel] once a project is chosen but no agent is installed (or the spawn came back
//     409 `code:"agent_not_installed"`): the real Settings ▸ Agents screen, embedded, so install and
//     login are the exact same flows.
//
// [BrokerRefusal] carries the broker's OWN reason out of a call that used to collapse every failure
// to null, so the picker can say "A folder named x already exists" instead of "check the connection".
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AgentInstallStatus
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.AgentSettingsScreen
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.submitOnEnter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The spawn's 409 `code` when the chosen agent's CLI is not on the host. */
const val AGENT_NOT_INSTALLED = "agent_not_installed"

/**
 * The broker ANSWERED, and said no. [message] is its JSON `error` (blank when the body had none);
 * [status] the HTTP status; [code] / [agent] its machine-readable `code` and `agent` fields.
 * A network failure is never one of these — the launcher seams return null for that.
 */
class BrokerRefusal(
    message: String,
    val status: Int? = null,
    val code: String? = null,
    val agent: String? = null,
) : Exception(message)

private val HTTP_REFUSAL = Regex("""BrokerApi request unavailable:\s*HTTP\s+(\d{3})\s*(.*)""", RegexOption.DOT_MATCHES_ALL)
private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** A string field of a (possibly truncated — BrokerApi keeps 200 chars) JSON object body. */
private fun jsonField(body: String, name: String): String? {
    runCatching { lenientJson.parseToJsonElement(body) as? JsonObject }.getOrNull()?.let { obj ->
        return (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
    val m = Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(body) ?: return null
    return m.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
}

/**
 * The broker's refusal inside [t] (anywhere in its cause chain), or null when [t] is not one — a
 * dropped connection, a timeout, a decode failure. BrokerApi raises a non-2xx answer as
 * `BrokerApi request unavailable: HTTP <status> <body>`; a [BrokerRefusal] passes through.
 */
fun brokerRefusal(t: Throwable): BrokerRefusal? {
    for (err in generateSequence(t) { it.cause }.take(6)) {
        if (err is BrokerRefusal) return err
        val m = HTTP_REFUSAL.find(err.message.orEmpty()) ?: continue
        val body = m.groupValues[2].trim()
        return BrokerRefusal(
            message = jsonField(body, "error").orEmpty().trim(),
            status = m.groupValues[1].toIntOrNull(),
            code = jsonField(body, "code"),
            agent = jsonField(body, "agent"),
        )
    }
    return null
}

/**
 * Run a launcher broker call keeping the broker's own refusal: an HTTP refusal is THROWN as a
 * [BrokerRefusal], a real network failure returns null. A genuine cancellation still propagates.
 */
suspend fun <T> refusalOrNull(block: suspend () -> T): T? = try {
    block()
} catch (c: CancellationException) {
    // BrokerApi raises HTTP failures as CancellationException (SKIE-safe); a cancelled caller is not one.
    currentCoroutineContext().ensureActive()
    throw brokerRefusal(c) ?: return null
} catch (e: Throwable) {
    throw brokerRefusal(e) ?: return null
}

/**
 * What a failed host operation ([label]: "create my-app", "clone o/r") says: the broker's reason
 * when it gave one, its HTTP status when it gave only that, else — a network failure, or a null
 * result — the connection wording.
 */
fun hostOpFailureMessage(label: String, failure: Throwable?): String {
    val refusal = failure?.let(::brokerRefusal)
    return when {
        refusal == null -> "Couldn't $label — check the connection and try again."
        !refusal.message.isNullOrBlank() -> refusal.message!!
        refusal.status != null -> "Couldn't $label (HTTP ${refusal.status})."
        else -> "Couldn't $label."
    }
}

/**
 * The agent a spawn failure says is not installed (409 `code:"agent_not_installed"`): its `agent`
 * field, "" when the body named none, null when [t] is some other failure.
 */
fun agentNotInstalled(t: Throwable): String? {
    val refusal = brokerRefusal(t) ?: return null
    return if (refusal.code == AGENT_NOT_INSTALLED) refusal.agent.orEmpty() else null
}

/** Test tags for the first-run states. */
object LauncherFirstRunTags {
    const val FIRST_PROJECT = "launcher_first_project"
    const val OPEN_FOLDER = "launcher_first_open_folder"
    const val CREATE_PROJECT = "launcher_first_create_project"
    const val PATH_FIELD = "launcher_first_path"
    const val NAME_FIELD = "launcher_first_name"
    const val CONFIRM = "launcher_first_confirm"
    const val BUSY = "launcher_first_busy"
    const val ERROR = "launcher_first_error"
    const val INSTALL_AGENT = "launcher_install_agent"
    const val INSTALL_AGENT_MESSAGE = "launcher_install_agent_message"
}

private enum class FirstProjectChoice { Open, Create }

/**
 * A fresh host's new-session screen: two big actions instead of a search over nothing.
 *
 * @param validatePath the launcher's `validatePath` (null = transport failure).
 * @param createLocalRepo the launcher's `createLocalRepo`: the new folder's path, null on a network
 *   failure, or a thrown [BrokerRefusal] carrying the broker's reason.
 * @param onProject a folder is ready: the launcher takes it as its project.
 */
@Composable
fun FirstProjectScreen(
    validatePath: suspend (String) -> dev.supermux.net.PathValidation?,
    createLocalRepo: suspend (String) -> String?,
    onProject: (String) -> Unit,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf<FirstProjectChoice?>(null) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun choose(c: FirstProjectChoice) {
        if (busy) return
        if (choice != c) input = ""
        choice = c
        error = null
    }

    fun confirm() {
        val value = input.trim()
        val c = choice ?: return
        if (value.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                when (c) {
                    FirstProjectChoice.Open -> {
                        val res = validatePath(value)
                        val path = res?.path
                        if (res != null && res.ok && !path.isNullOrBlank()) onProject(path)
                        else error = res?.error ?: "Couldn't check that folder — check the connection and try again."
                    }
                    FirstProjectChoice.Create -> {
                        val path = createLocalRepo(value)
                        if (!path.isNullOrBlank()) onProject(path) else error = hostOpFailureMessage("create $value", null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = hostOpFailureMessage(if (c == FirstProjectChoice.Create) "create $value" else "open $value", e)
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier.fillMaxWidth().testTag(LauncherFirstRunTags.FIRST_PROJECT),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text("Start with a project", color = cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Agents work inside a folder. Open one you have, or start a new one.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        val open: @Composable (Modifier) -> Unit = { m ->
            FirstProjectCard(
                icon = Icons.Filled.FolderOpen,
                title = "Open a folder",
                caption = "A folder you already have",
                selected = choice == FirstProjectChoice.Open,
                onClick = { choose(FirstProjectChoice.Open) },
                modifier = m.testTag(LauncherFirstRunTags.OPEN_FOLDER),
            )
        }
        val create: @Composable (Modifier) -> Unit = { m ->
            FirstProjectCard(
                icon = Icons.Filled.Add,
                title = "Create a new project",
                caption = "A new, empty folder",
                selected = choice == FirstProjectChoice.Create,
                onClick = { choose(FirstProjectChoice.Create) },
                modifier = m.testTag(LauncherFirstRunTags.CREATE_PROJECT),
            )
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                open(Modifier.fillMaxWidth())
                create(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                open(Modifier.weight(1f))
                create(Modifier.weight(1f))
            }
        }
        choice?.let { c ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    singleLine = true,
                    enabled = !busy,
                    label = { Text(if (c == FirstProjectChoice.Open) "Folder path" else "Project name") },
                    placeholder = { Text(if (c == FirstProjectChoice.Open) "~/code/my-app" else "my-app") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(if (c == FirstProjectChoice.Open) LauncherFirstRunTags.PATH_FIELD else LauncherFirstRunTags.NAME_FIELD)
                        .submitOnEnter(!busy) { confirm() },
                )
                Button(
                    onClick = { confirm() },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.testTag(LauncherFirstRunTags.CONFIRM),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            Modifier.size(Space.lg).testTag(LauncherFirstRunTags.BUSY),
                            strokeWidth = Stroke.thin,
                            color = cs.onPrimary,
                        )
                    } else {
                        Text(if (c == FirstProjectChoice.Open) "Open" else "Create")
                    }
                }
            }
        }
        error?.let {
            Text(
                it,
                color = cs.error,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().testTag(LauncherFirstRunTags.ERROR),
            )
        }
    }
}

@Composable
private fun FirstProjectCard(
    icon: ImageVector,
    title: String,
    caption: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.lg)
    Column(
        modifier
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .border(if (selected) Stroke.thin else Stroke.hairline, if (selected) cs.primary else cs.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.lg, vertical = Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(32.dp))
        Text(title, color = cs.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text(caption, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

/**
 * "Install an agent": the real Agents settings screen, embedded under a one-line explanation. It
 * reports every list it loads through [onStatusesChanged], so the launcher sees an install (or a
 * login) land and swaps back to the composer by itself.
 *
 * @param message the broker's own refusal when a spawn just came back `agent_not_installed`.
 */
@Composable
fun InstallAgentPanel(
    actions: AgentSettingsActions,
    onStatusesChanged: (List<AgentInstallStatus>) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().testTag(LauncherFirstRunTags.INSTALL_AGENT),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text("Install an agent", color = cs.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            message ?: "Chats run on a coding agent, and none is installed on this host yet. Install one below.",
            color = cs.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(LauncherFirstRunTags.INSTALL_AGENT_MESSAGE),
        )
        // A fixed height: the Agents screen is a LazyColumn, and the launcher scrolls.
        Box(
            Modifier
                .fillMaxWidth()
                .height(440.dp)
                .clip(RoundedCornerShape(Radii.lg))
                .border(Stroke.hairline, cs.outlineVariant, RoundedCornerShape(Radii.lg)),
        ) {
            AgentSettingsScreen(actions = actions, topBarShown = true, onStatusesChanged = onStatusesChanged)
        }
    }
}
