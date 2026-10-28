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
 * The wire format shared with the page-side shim ([BridgeShim]):
 * - a call is `{"t":"call","id":…,"m":…,"p":…}`;
 * - a reply is `{"t":"reply","id":…,"r":…}` or `{"t":"reply","id":…,"e":…}`;
 * - an event is `{"t":"event","n":…,"d":…}`.
 *
 * Decoding never throws. Page input is untrusted, so length and nesting are checked BEFORE parsing (deep nesting
 * overflows the parser's stack: an Error no catch stops, fatal on Kotlin/Native), and only strict JSON is accepted.
 */
internal object BridgeCodec {
    const val DEFAULT_MAX_CHARS: Int = 256 * 1024
    const val DEFAULT_MAX_DEPTH: Int = 32

    /** Encodes [message]. Throws [IllegalArgumentException] if its payload isn't strict JSON (NaN, Infinity…). */
    fun encode(message: BridgeMessage): String {
        val payload = when (message) {
            is BridgeMessage.Call -> message.params
            is BridgeMessage.Reply -> message.result ?: JsonNull
            is BridgeMessage.Event -> message.data
        }
        require(payload.isStrictJson()) { "bridge payload is not strict JSON" }
        return when (message) {
            is BridgeMessage.Call -> buildJsonObject {
                put("t", "call")
                put("id", message.id)
                put("m", message.method)
                put("p", message.params)
            }
            is BridgeMessage.Reply -> buildJsonObject {
                put("t", "reply")
                put("id", message.id)
                val error = message.error
                if (error != null) put("e", error) else put("r", message.result ?: JsonNull)
            }
            is BridgeMessage.Event -> buildJsonObject {
                put("t", "event")
                put("n", message.name)
                put("d", message.data)
            }
        }.toString()
    }

    /** The message in [json], or null when it is oversized, too deep, not strict JSON, or of an unknown kind. */
    fun decode(json: String, maxChars: Int = DEFAULT_MAX_CHARS, maxDepth: Int = DEFAULT_MAX_DEPTH): BridgeMessage? {
        if (json.length > maxChars || !hasSafeNesting(json, maxDepth)) return null
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return null
        }
        val obj = element as? JsonObject ?: return null
        if (!obj.isStrictJson()) return null
        return when (obj.string("t")) {
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
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}

/** Whether [json]'s arrays and objects nest at most [maxDepth] deep. One pass, no recursion; strings are skipped. */
internal fun hasSafeNesting(json: String, maxDepth: Int): Boolean {
    var depth = 0
    var i = 0
    while (i < json.length) {
        when (json[i]) {
            '"' -> {
                i++
                while (i < json.length && json[i] != '"') i += if (json[i] == '\\') 2 else 1
                if (i >= json.length) return false
            }
            '[', '{' -> if (++depth > maxDepth) return false
            ']', '}' -> if (--depth < 0) return false
        }
        i++
    }
    return depth == 0
}

private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/** Whether every literal is real JSON: kotlinx accepts unquoted tokens such as `alert(1)` and `NaN`. */
internal fun JsonElement.isStrictJson(): Boolean = when (this) {
    is JsonNull -> true
    is JsonPrimitive -> isString || content == "true" || content == "false" || JSON_NUMBER.matches(content)
    is JsonArray -> all { it.isStrictJson() }
    is JsonObject -> values.all { it.isStrictJson() }
}
