// The launcher's first-run states, and the broker refusals that lead to them.
//
// A fresh install has no projects and, often, no agent CLI. Rather than a search box over nothing
// and a send that crashes, the launcher shows:
//   - [FirstProjectScreen] (FirstProjectScreen.kt) while the host has NO projects: "Open a folder"
//     (the native folder dialog when the host is this computer, else a path the broker validates) or
//     "Create a new project" (a name; the broker makes it under its projects root via
//     `createLocalRepo`, with `git init` only when git is there). Git is optional throughout.
//   - [InstallAgentPanel] (InstallAgentPanel.kt) once a project is chosen but no agent is installed
//     (or the spawn came back 409 `code:"agent_not_installed"`): one card per agent, installed in
//     place through the same broker install jobs as Settings ▸ Agents.
//
// [BrokerRefusal] carries the broker's OWN reason out of a call that used to collapse every failure
// to null, so the picker can say "A folder named x already exists" instead of "check the connection".
package dev.supermux.ui.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    const val TYPE_PATH = "launcher_first_type_path"
    const val BACK = "launcher_first_back"
    fun agentCard(kind: String) = "launcher_agent_card_$kind"
    fun agentInstall(kind: String) = "launcher_agent_install_$kind"
    fun agentProgress(kind: String) = "launcher_agent_progress_$kind"
    fun agentError(kind: String) = "launcher_agent_error_$kind"
}

