package dev.supermux.webview.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The page's side of the bridge's wire format, which [FakeWebEngine]'s pages speak as the shim does
 * (`dev.supermux.webview.bridge.BridgeShim`'s JavaScript). The library keeps its own codec internal; this is the format
 * the shim and the host share, from the page's end:
 * - an event is `{"t":"event","n":…,"d":…}`, a reply `{"t":"reply","id":…,"r":…}`, and a call from the host
 *   `{"t":"call","id":…,"m":…,"p":…}`;
 * - what a page sends ends with `"from":<its document's nonce>`, and what the host sends a document with
 *   `"to":<its nonce>`, but for the host's `$hello`.
 *
 * The library's own tests check the fake against the library's codec, so the two can't drift apart unseen.
 */
internal object FakePageWire {
    /**
     * An event the page's document [from] posts.
     * @throws IllegalArgumentException if [data] isn't strict JSON, as the host's codec refuses it.
     */
    fun event(name: String, data: JsonElement, from: String?): String {
        require(data.isStrictJson()) { "bridge payload is not strict JSON" }
        return buildJsonObject {
            put("t", "event")
            put("n", name)
            put("d", data)
            if (from != null) put("from", from)
        }.toString()
    }

    /**
     * The page's document [from] answers the host's call [id] with [result].
     * @throws IllegalArgumentException if [result] isn't strict JSON.
     */
    fun reply(id: Long, result: JsonElement, from: String?): String {
        require(result.isStrictJson()) { "bridge payload is not strict JSON" }
        return buildJsonObject {
            put("t", "reply")
            put("id", id)
            put("r", result)
            if (from != null) put("from", from)
        }.toString()
    }

    /** What the page reads of a message from the host: its kind, an event's name or a call's method and id, and [to]. */
    class HostMessage(val event: String?, val call: String?, val callId: Long?, val to: String?)

    /**
     * [json] as the page reads it, or null when it isn't a well-formed call, reply or event within the host codec's
     * limits (256 KiB, 32 levels deep, strict literals).
     */
    fun read(json: String): HostMessage? {
        if (json.length > MAX_CHARS || !scanJson(json)) return null
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return null
        }
        val message = element as? JsonObject ?: return null
        val to = message.string("to")
        return when (message.string("t")) {
            "event" -> HostMessage(event = message.string("n") ?: return null, call = null, callId = null, to = to)
            "call" -> {
                val id = message.long("id") ?: return null
                HostMessage(event = null, call = message.string("m") ?: return null, callId = id, to = to)
            }
            "reply" -> {
                message.long("id") ?: return null
                HostMessage(event = null, call = null, callId = null, to = to)
            }
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

    /** The longest message the host's codec reads: its replies' cap. */
    private const val MAX_CHARS = 256 * 1024

    /** How deep the host's codec lets arrays and objects nest. */
    private const val MAX_DEPTH = 32

    /** Whether every literal is real JSON (no NaN, no Infinity), as the host's codec requires. */
    private fun JsonElement.isStrictJson(): Boolean = when (this) {
        is JsonNull -> true
        is JsonPrimitive -> isString || content == "true" || content == "false" || isJsonNumber(content, 0, content.length)
        is JsonArray -> all { it.isStrictJson() }
        is JsonObject -> values.all { it.isStrictJson() }
    }

    /**
     * One pass over [json], as the host's codec makes before it parses: arrays and objects nest at most [MAX_DEPTH]
     * deep, strings end, and every token outside a string is `true`, `false`, `null` or a JSON number.
     */
    private fun scanJson(json: String): Boolean {
        var depth = 0
        var i = 0
        val n = json.length
        while (i < n) {
            when (json[i]) {
                '"' -> {
                    i++
                    while (i < n && json[i] != '"') i += if (json[i] == '\\') 2 else 1
                    if (i >= n) return false
                    i++
                }
                '[', '{' -> {
                    if (++depth > MAX_DEPTH) return false
                    i++
                }
                ']', '}' -> {
                    if (--depth < 0) return false
                    i++
                }
                ',', ':', ' ', '\t', '\n', '\r' -> i++
                else -> {
                    val start = i
                    while (i < n && json[i] !in DELIMITERS) i++
                    if (!isJsonLiteral(json, start, i)) return false
                }
            }
        }
        return depth == 0
    }

    private const val DELIMITERS = ",:[]{}\" \t\n\r"

    private fun isJsonLiteral(s: String, start: Int, end: Int): Boolean {
        val length = end - start
        return (length == 4 && s.regionMatches(start, "true", 0, 4)) ||
            (length == 5 && s.regionMatches(start, "false", 0, 5)) ||
            (length == 4 && s.regionMatches(start, "null", 0, 4)) ||
            isJsonNumber(s, start, end)
    }

    /** Whether `s[start, end)` is a JSON number: `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`. */
    private fun isJsonNumber(s: String, start: Int, end: Int): Boolean {
        var i = start
        if (i < end && s[i] == '-') i++
        if (i >= end) return false
        when (s[i]) {
            '0' -> i++
            in '1'..'9' -> while (i < end && s[i] in '0'..'9') i++
            else -> return false
        }
        if (i < end && s[i] == '.') {
            val digits = ++i
            while (i < end && s[i] in '0'..'9') i++
            if (i == digits) return false
        }
        if (i < end && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < end && (s[i] == '+' || s[i] == '-')) i++
            val digits = i
            while (i < end && s[i] in '0'..'9') i++
            if (i == digits) return false
        }
        return i == end
    }
}

/**
 * The origin of a network page's [url] as browsers serialize it, `scheme://host[:port]`, lowercased, with the scheme's
 * default port dropped; `"null"` (opaque) for a URL without a host. The library's own rule (its URL parsing is
 * internal), which its bridge checks the fake's pages against.
 */
internal fun fakePageOrigin(url: String): String {
    val scheme = schemeOf(url) ?: return "null"
    if (scheme == "blob") {
        val inner = normalizeUrl(url).substringAfter(':')
        return if (schemeOf(inner).let { it == "http" || it == "https" }) fakePageOrigin(inner) else "null"
    }
    val hostPort = authorityOf(url)?.substringAfterLast('@')?.takeIf { it.isNotEmpty() } ?: return "null"
    val defaultPort = when (scheme) {
        "https", "wss" -> ":443"
        "http", "ws" -> ":80"
        else -> null
    }
    val normalized = if (defaultPort != null && hostPort.endsWith(defaultPort)) hostPort.dropLast(defaultPort.length) else hostPort
    return "$scheme://$normalized"
}

private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

/** Schemes a browser parses leniently: any run of `/` and `\` after the colon, and `\` ends the authority. */
private val SPECIAL_SCHEMES = setOf("http", "https", "ws", "wss", "ftp")

/** What a browser's URL parser does first: drop ASCII tab and newline anywhere, trim C0 controls and spaces. */
private fun normalizeUrl(url: String): String = url.filterNot { it == '\t' || it == '\n' || it == '\r' }.trim { it <= ' ' }

private fun schemeOf(url: String): String? = SCHEME.find(normalizeUrl(url))?.groupValues?.get(1)?.lowercase()

/** The lowercased authority (`userinfo@host:port`) of a hierarchical URL, or null. */
private fun authorityOf(url: String): String? {
    val cleaned = normalizeUrl(url)
    val scheme = schemeOf(cleaned) ?: return null
    val rest = cleaned.substring(scheme.length + 1)
    val special = scheme in SPECIAL_SCHEMES
    val start = when {
        special -> rest.trimStart('/', '\\')
        rest.startsWith("//") -> rest.substring(2)
        else -> return null
    }
    return start.takeWhile { it != '/' && it != '?' && it != '#' && !(special && it == '\\') }.lowercase()
}
