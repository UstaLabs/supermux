package dev.supermux.webview.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One message on the page↔host bridge (spec §5). Both sides can call, reply and post events. */
internal sealed interface BridgeMessage {
    data class Call(val id: Long, val method: String, val params: JsonElement = JsonNull) : BridgeMessage
    data class Reply(val id: Long, val result: JsonElement? = null, val error: String? = null) : BridgeMessage
    data class Event(val name: String, val data: JsonElement = JsonNull) : BridgeMessage
}

/**
 * A decoded [message] and the documents it names, each by its nonce: [to], the document a host message is for, and
 * [from], the document a page message comes from. Null when it names none.
 */
internal data class Envelope(val message: BridgeMessage, val to: String? = null, val from: String? = null)

/**
 * The wire format shared with the page-side shim ([BridgeShim]):
 * - a call is `{"t":"call","id":…,"m":…,"p":…}`;
 * - a reply is `{"t":"reply","id":…,"r":…}` or `{"t":"reply","id":…,"e":…}`;
 * - an event is `{"t":"event","n":…,"d":…}`.
 *
 * Documents are named by the nonce they announced, last in the message:
 * - Host messages for a document end with `"to":<its nonce>`; the shim drops the ones for another document. Only the
 *   host's `$hello` carries none.
 * - Page messages end with `"from":<the sender's nonce>`; the host drops the ones from a document that isn't current.
 *   A `$ready` also carries its nonce in `d.nonce`.
 *
 * Decoding never throws. Page input is untrusted, so one pass checks length, nesting and every literal BEFORE
 * parsing. Deep nesting overflows the parser's stack, an Error that no catch stops (fatal on Kotlin/Native), and the
 * parser accepts unquoted tokens such as `alert(1)`.
 */
internal object BridgeCodec {
    /** Calls and events. */
    const val DEFAULT_MAX_MESSAGE_CHARS: Int = 64 * 1024

    /** Replies, which carry saved page state. */
    const val DEFAULT_MAX_REPLY_CHARS: Int = 256 * 1024

    const val DEFAULT_MAX_DEPTH: Int = 32

    /**
     * Encodes [message], addressed [to] a document's nonce, or sent [from] one, when given (written last). Throws
     * [IllegalArgumentException] if its payload isn't strict JSON (NaN, Infinity…).
     */
    fun encode(message: BridgeMessage, to: String? = null, from: String? = null): String {
        val payload = when (message) {
            is BridgeMessage.Call -> message.params
            is BridgeMessage.Reply -> message.result ?: JsonNull
            is BridgeMessage.Event -> message.data
        }
        require(payload.isStrictJson()) { "bridge payload is not strict JSON" }
        return buildJsonObject {
            when (message) {
                is BridgeMessage.Call -> {
                    put("t", "call")
                    put("id", message.id)
                    put("m", message.method)
                    put("p", message.params)
                }
                is BridgeMessage.Reply -> {
                    put("t", "reply")
                    put("id", message.id)
                    val error = message.error
                    if (error != null) put("e", error) else put("r", message.result ?: JsonNull)
                }
                is BridgeMessage.Event -> {
                    put("t", "event")
                    put("n", message.name)
                    put("d", message.data)
                }
            }
            if (to != null) put("to", to)
            if (from != null) put("from", from)
        }.toString()
    }

    /** The message in [json], or null when it is oversized, too deep, not strict JSON, or of an unknown kind. */
    fun decode(json: String, maxChars: Int = DEFAULT_MAX_REPLY_CHARS, maxDepth: Int = DEFAULT_MAX_DEPTH): BridgeMessage? =
        decodeEnvelope(json, maxChars, maxDepth)?.message

    /** [decode], with the documents the message names. Only a string names one. */
    fun decodeEnvelope(json: String, maxChars: Int = DEFAULT_MAX_REPLY_CHARS, maxDepth: Int = DEFAULT_MAX_DEPTH): Envelope? {
        if (json.length > maxChars || !scanJson(json, maxDepth)) return null
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return null
        }
        val obj = element as? JsonObject ?: return null
        val message = when (obj.string("t")) {
            "call" -> BridgeMessage.Call(obj.long("id") ?: return null, obj.string("m") ?: return null, obj["p"] ?: JsonNull)
            "reply" -> {
                val id = obj.long("id") ?: return null
                val error = obj["e"]
                when {
                    error == null || error is JsonNull -> BridgeMessage.Reply(id, obj["r"] ?: JsonNull, null)
                    error is JsonPrimitive && error.isString -> BridgeMessage.Reply(id, null, error.content)
                    else -> BridgeMessage.Reply(id, null, "invalid error")
                }
            }
            "event" -> BridgeMessage.Event(obj.string("n") ?: return null, obj["d"] ?: JsonNull)
            else -> return null
        }
        return Envelope(message, to = obj.string("to"), from = obj.string("from"))
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

/**
 * One pass over [json], without recursion. Arrays and objects must nest at most [maxDepth] deep. Strings must end.
 * Every token outside a string must be `true`, `false`, `null` or a JSON number. The parser checks the rest of the
 * grammar.
 */
internal fun scanJson(json: String, maxDepth: Int): Boolean {
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
                if (++depth > maxDepth) return false
                i++
            }
            ']', '}' -> {
                if (--depth < 0) return false
                i++
            }
            ',', ':', ' ', '\t', '\n', '\r' -> i++
            else -> {
                val start = i
                while (i < n && !isDelimiter(json[i])) i++
                if (!isJsonLiteral(json, start, i)) return false
            }
        }
    }
    return depth == 0
}

private fun isDelimiter(c: Char): Boolean =
    c == ',' || c == ':' || c == '[' || c == ']' || c == '{' || c == '}' || c == '"' || c == ' ' || c == '\t' || c == '\n' || c == '\r'

private fun isJsonLiteral(s: String, start: Int, end: Int): Boolean {
    val length = end - start
    return (length == 4 && s.regionMatches(start, "true", 0, 4)) ||
        (length == 5 && s.regionMatches(start, "false", 0, 5)) ||
        (length == 4 && s.regionMatches(start, "null", 0, 4)) ||
        isJsonNumber(s, start, end)
}

/** Whether `s[start, end)` is a JSON number: `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`. */
internal fun isJsonNumber(s: String, start: Int, end: Int): Boolean {
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

/** Whether every literal is real JSON. Used on host-built payloads before encoding them. */
internal fun JsonElement.isStrictJson(): Boolean = when (this) {
    is JsonNull -> true
    is JsonPrimitive -> isString || content == "true" || content == "false" || isJsonNumber(content, 0, content.length)
    is JsonArray -> all { it.isStrictJson() }
    is JsonObject -> values.all { it.isStrictJson() }
}
