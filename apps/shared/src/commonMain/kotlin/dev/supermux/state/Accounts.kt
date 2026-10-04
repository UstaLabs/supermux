// Store glue for accounts (slice A3b): the typed result of an accounts mutation, and the two
// small conversions every host needs. The wire models and the HTTP calls are A3a's (BrokerApi).
package dev.supermux.state

import dev.supermux.net.AccountLoginStateDto
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an accounts mutation answered. A refusal keeps the broker's own `code` (session_busy,
 * account_exists, session_not_running, …) and message, so a screen can say the right thing instead
 * of "something went wrong".
 */
sealed interface AccountResult<out T> {
    data class Ok<T>(val value: T) : AccountResult<T>
    data class Failed(val status: Int?, val code: String?, val message: String?) : AccountResult<Nothing>
}

private val HTTP_FAILURE = Regex("""^BrokerApi request unavailable:\s*HTTP\s+(\d{3})\s*(.*)$""", RegexOption.DOT_MATCHES_ALL)
private val CODE_FIELD = Regex(""""code"\s*:\s*"([a-z_]+)"""")
private val lenient = Json { ignoreUnknownKeys = true }

/**
 * Reads [BrokerApi]'s SKIE-safe failure (`BrokerApi request unavailable: HTTP <status> <body>`)
 * into a [AccountResult.Failed]. The body is the broker's `{error, code}` JSON (possibly cut at
 * 200 chars, in which case only the status survives).
 */
fun accountFailure(t: Throwable): AccountResult.Failed {
    val msg = t.message.orEmpty()
    val m = HTTP_FAILURE.matchEntire(msg) ?: return AccountResult.Failed(null, null, null)
    val status = m.groupValues[1].toIntOrNull()
    val raw = m.groupValues[2].trim()
    val body = runCatching { lenient.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    return AccountResult.Failed(
        status = status,
        code = body?.get("code")?.jsonPrimitive?.contentOrNull ?: CODE_FIELD.find(raw)?.groupValues?.get(1),
        message = body?.get("error")?.jsonPrimitive?.contentOrNull,
    )
}

/** Runs an accounts call; a real cancellation propagates, a broker refusal becomes [AccountResult.Failed]. */
internal suspend fun <T> accountCall(block: suspend () -> T): AccountResult<T> =
    try {
        AccountResult.Ok(block())
    } catch (c: CancellationException) {
        currentCoroutineContext().ensureActive()
        accountFailure(c)
    } catch (e: Throwable) {
        AccountResult.Failed(null, null, e.message)
    }

/** The `account_login_state` frame as the REST shape, so one type drives the login UI. */
fun ServerFrame.AccountLoginState.toDto(): AccountLoginStateDto = AccountLoginStateDto(
    loginId = loginId,
    agent = agent,
    phase = phase,
    url = url,
    code = code,
    needsCode = needsCode,
    error = error,
    errorCode = errorCode,
    account = account,
)

/**
 * Order of a guided login's phases. A late poll answer must never move the UI backwards past a
 * frame that already arrived (e.g. back to "starting" after "awaiting_user").
 */
fun accountLoginPhaseRank(phase: String): Int = when (phase) {
    "starting" -> 0
    "awaiting_user" -> 1
    "verifying" -> 2
    "done", "failed", "cancelled" -> 3
    else -> -1
}
