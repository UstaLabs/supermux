package dev.supermux.webview.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** One message on the page↔host bridge (spec §5). Both sides can call, reply and post events. */
sealed interface BridgeMessage {
    data class Call(val id: Long, val method: String, val params: JsonElement = JsonNull) : BridgeMessage
    data class Reply(val id: Long, val result: JsonElement? = null, val error: String? = null) : BridgeMessage
    data class Event(val name: String, val data: JsonElement = JsonNull) : BridgeMessage
}

/**
 * The wire format shared with the page-side shim ([BridgeShim]):
 * - a call is `{"t":"call","id":…,"m":…,"p":…}`;
 * - a reply is `{"t":"reply","id":…,"r":…}` or `{"t":"reply","id":…,"e":…}`;
 * - an event is `{"t":"event","n":…,"d":…}`.
 */
object BridgeCodec {
    /** Longer messages are dropped unread. */
    const val MAX_LENGTH: Int = 1 shl 20

    fun encode(message: BridgeMessage): String = when (message) {
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

    /** The message in [json], or null when it is malformed, oversized or of an unknown kind. */
    fun decode(json: String): BridgeMessage? {
        if (json.length > MAX_LENGTH) return null
        val element = try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            return null
        }
        val obj = element as? JsonObject ?: return null
        return when (obj.string("t")) {
            "call" -> BridgeMessage.Call(obj.long("id") ?: return null, obj.string("m") ?: return null, obj["p"] ?: JsonNull)
            "reply" -> BridgeMessage.Reply(obj.long("id") ?: return null, obj["r"], obj.string("e"))
            "event" -> BridgeMessage.Event(obj.string("n") ?: return null, obj["d"] ?: JsonNull)
            else -> null
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
