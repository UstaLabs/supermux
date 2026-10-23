package dev.supermux.net

/**
 * What this app tells its own broker when it connects (TELEMETRY.md, `client.connected`).
 * Only these four values; the broker drops anything it doesn't recognise.
 */
data class ClientInfo(
    val surface: String,          // android | ios | desktop | web
    val version: String? = null,
    val os: String? = null,       // android | ios | macos | windows | linux
    val osMajor: Int? = null,
)

fun ClientInfo?.toQuery(): String {
    if (this == null) return ""
    val parts = buildList {
        add("clientSurface=$surface")
        version?.let { add("clientVersion=${it.encodeURLParameterSafe()}") }
        os?.let { add("clientOs=$it") }
        osMajor?.let { add("clientOsMajor=$it") }
    }
    return "?" + parts.joinToString("&")
}

// Versions are [0-9A-Za-z.+-]; '+' is the only character that needs escaping in a query.
private fun String.encodeURLParameterSafe(): String = replace("+", "%2B")
