package dev.supermux.android.session

import kotlinx.coroutines.CancellationException

private val HTTP_CODE = Regex("""HTTP\s+(\d{3})""")
private const val GENERIC_UNAVAILABLE = "BrokerApi request unavailable"

/**
 * User-facing text when POST /sessions (continue / new chat) fails.
 *
 * [BrokerApi.decode] surfaces non-2xx as [CancellationException] with [GENERIC_UNAVAILABLE]
 * and only prints the JSON body — we cannot read that body from Android without changing
 * shared. Prefer an HTTP status in the exception chain when present; otherwise a slightly
 * more specific refusal string.
 */
fun spawnFailureMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.take(6)
    for (err in chain) {
        val msg = err.message.orEmpty()
        HTTP_CODE.find(msg)?.let { m ->
            return "Broker refused to start the session (HTTP ${m.groupValues[1]})"
        }
    }
    val top = t.message?.trim().orEmpty()
    if (top.isEmpty() || top == GENERIC_UNAVAILABLE) {
        return "Broker refused to start the session"
    }
    return top
}

/** Remap the SKIE-safe spawn cancel into an [IllegalStateException] callers can show. */
fun remapSpawnFailure(t: Throwable): Nothing {
    if (t is CancellationException && t.message != GENERIC_UNAVAILABLE) throw t
    throw IllegalStateException(spawnFailureMessage(t), t)
}
