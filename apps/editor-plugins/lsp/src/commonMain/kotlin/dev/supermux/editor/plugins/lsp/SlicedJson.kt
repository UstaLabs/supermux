package dev.supermux.editor.plugins.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A JSON parser that gives the thread back: iterative (an explicit stack, no recursion), checking the
 * clock every few thousand tokens and calling [giveBack] once [sliceMs] of work has passed. For the
 * browser, where a 1.6 MB completion list held the page 15 ms in one piece with kotlinx's parser: in
 * slices, no single task is longer than about [sliceMs]. Produces the same [JsonElement] tree.
 */
internal object SlicedJson {
    private class Arr { val items = ArrayList<JsonElement>() }
    private class Obj { val map = LinkedHashMap<String, JsonElement>(); var key: String? = null }

    suspend fun parse(text: String, sliceMs: Long, giveBack: suspend () -> Unit): JsonElement {
        val stack = ArrayList<Any>()
        var result: JsonElement? = null
        var i = 0
        val n = text.length
        var mark = TimeSource.Monotonic.markNow()
        var tokens = 0
        val slice = sliceMs.milliseconds

        fun emit(v: JsonElement) {
            when (val top = stack.lastOrNull()) {
                null -> result = v
                is Arr -> top.items += v
                is Obj -> { top.map[top.key ?: error("a value without a key at $i")] = v; top.key = null }
            }
        }

        while (i < n) {
            if (++tokens and 4095 == 0 && mark.elapsedNow() > slice) { giveBack(); mark = TimeSource.Monotonic.markNow() }
            val c = text[i]
            when {
                c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == ',' || c == ':' -> i++
                c == '{' -> { stack += Obj(); i++ }
                c == '[' -> { stack += Arr(); i++ }
                c == '}' -> { val o = stack.removeAt(stack.size - 1) as Obj; emit(JsonObject(o.map)); i++ }
                c == ']' -> { val a = stack.removeAt(stack.size - 1) as Arr; emit(JsonArray(a.items)); i++ }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    var start = i
                    while (true) {
                        require(i < n) { "an unterminated string" }
                        val ch = text[i]
                        if (ch == '"') { sb.append(text, start, i); i++; break }
                        if (ch == '\\') {
                            sb.append(text, start, i)
                            val e = text[i + 1]
                            when (e) {
                                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C'); 'u' -> { sb.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                                else -> sb.append(e)
                            }
                            i += 2
                            start = i
                            continue
                        }
                        i++
                    }
                    val s = sb.toString()
                    val top = stack.lastOrNull()
                    if (top is Obj && top.key == null) top.key = s else emit(JsonPrimitive(s))
                }
                c == 't' -> { emit(JsonPrimitive(true)); i += 4 }
                c == 'f' -> { emit(JsonPrimitive(false)); i += 5 }
                c == 'n' -> { emit(JsonNull); i += 4 }
                else -> {
                    val s = i
                    while (i < n && text[i].let { it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' || it in '0'..'9' }) i++
                    require(i > s) { "unexpected '$c' at $s" }
                    val num = text.substring(s, i)
                    num.toDouble() // a malformed number throws, as kotlinx's parser does
                    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                    emit(kotlinx.serialization.json.JsonUnquotedLiteral(num))
                }
            }
        }
        require(stack.isEmpty()) { "unterminated JSON" }
        return result ?: JsonNull
    }
}

/** Messages at least this long are parsed in slices where [lspSliceBigMessages] (the browser). */
internal const val SLICED_PARSE_MIN = 128 * 1024

/** The browser: big messages are parsed in slices on its one thread ([SlicedJson]); elsewhere they go to a worker. */
internal expect val lspSliceBigMessages: Boolean

/** Give the thread back for a moment (the browser: a real macrotask; elsewhere a `yield`). */
internal expect suspend fun lspGiveBack()
