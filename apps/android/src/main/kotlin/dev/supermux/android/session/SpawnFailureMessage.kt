package dev.supermux.android.session

import kotlinx.coroutines.CancellationException

private val HTTP_UNAVAILABLE = Regex(
    """^BrokerApi request unavailable:\s*HTTP\s+(\d{3})\s*(.*)$""",
    RegexOption.DOT_MATCHES_ALL,
)
private val HTTP_CODE = Regex("""HTTP\s+(\d{3})""")
private const val GENERIC_UNAVAILABLE = "BrokerApi request unavailable"

/**
 * User-facing text when POST /sessions (continue / new chat) fails.
 *
 * [dev.supermux.net.BrokerApi] surfaces non-2xx as [CancellationException] whose message is
 * `BrokerApi request unavailable: HTTP <code> <body>`. Prefer the JSON `error` field from that
 * body when present; otherwise an HTTP status or a generic refusal.
 */
fun spawnFailureMessage(t: Throwable): String {
    val chain = generateSequence(t) { it.cause }.take(6)
    for (err in chain) {
        val msg = err.message.orEmpty()
        HTTP_UNAVAILABLE.matchEntire(msg)?.let { m ->
            val status = m.groupValues[1]
            val body = m.groupValues[2].trim()
            jsonErrorField(body)?.let { return it }
            return "Broker refused to start the session (HTTP $status)"
        }
        HTTP_CODE.find(msg)?.let { m ->
            if (!msg.startsWith(GENERIC_UNAVAILABLE)) {
                return "Broker refused to start the session (HTTP ${m.groupValues[1]})"
            }
        }
    }
    val top = t.message?.trim().orEmpty()
    if (top.isEmpty() || isBrokerUnavailableMessage(top)) {
        return "Broker refused to start the session"
    }
    return top
}

/** Remap the SKIE-safe spawn cancel into an [IllegalStateException] callers can show. */
fun remapSpawnFailure(t: Throwable): Nothing {
    if (t is CancellationException && !isBrokerUnavailableMessage(t.message)) throw t
    throw IllegalStateException(spawnFailureMessage(t), t)
}

internal fun isBrokerUnavailableMessage(msg: String?): Boolean {
    val m = msg?.trim().orEmpty()
    return m.isEmpty() || m == GENERIC_UNAVAILABLE || m.startsWith("$GENERIC_UNAVAILABLE:")
}

/** Best-effort `"error":"..."` from a JSON object body. */
internal fun jsonErrorField(body: String): String? {
    val key = "\"error\""
    val start = body.indexOf(key)
    if (start < 0) return null
    var i = start + key.length
    while (i < body.length && body[i].isWhitespace()) i++
    if (i >= body.length || body[i] != ':') return null
    i++
    while (i < body.length && body[i].isWhitespace()) i++
    if (i >= body.length || body[i] != '"') return null
    i++
    val out = StringBuilder()
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && i + 1 < body.length -> {
                val n = body[i + 1]
                out.append(
                    when (n) {
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        '"' -> '"'
                        '\\' -> '\\'
                        else -> n
                    },
                )
                i += 2
            }
            c == '"' -> return out.toString().takeIf { it.isNotBlank() }
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return null
}
