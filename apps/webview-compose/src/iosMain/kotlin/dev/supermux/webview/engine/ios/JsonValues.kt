package dev.supermux.webview.engine.ios

import kotlinx.cinterop.toKString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import platform.Foundation.NSNull
import platform.Foundation.NSNumber

/**
 * A script's result, as WebKit hands it over (strings, numbers, booleans, arrays, dictionaries, null), as JSON; null for
 * anything else. Built here rather than with `NSJSONSerialization`, which escapes slashes, and to which a boolean can
 * arrive as a plain number.
 */
internal fun jsonElementOf(value: Any?): JsonElement? = when (value) {
    null, is NSNull -> JsonNull
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    // A boolean is a CFBoolean, whose Objective-C type is a char.
    is NSNumber -> if (value.objCType?.toKString() == "c") JsonPrimitive(value.boolValue) else numberOf(value.doubleValue)
    is Number -> numberOf(value.toDouble())
    is List<*> -> JsonArray(value.map { jsonElementOf(it) ?: return null })
    is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> (key as? String ?: return null) to (jsonElementOf(item) ?: return null) })
    else -> null
}

/** A JSON number, integral when the value is one: 3, not 3.0. Not finite: null, as JSON has none. */
private fun numberOf(value: Double): JsonElement? = when {
    !value.isFinite() -> null
    value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9.007199254740992E15 -> JsonPrimitive(value.toLong())
    else -> JsonPrimitive(value)
}
