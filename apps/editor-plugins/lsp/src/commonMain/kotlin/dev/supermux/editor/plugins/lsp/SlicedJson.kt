package dev.supermux.editor.plugins.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A strict JSON parser (RFC 8259) that gives the thread back: iterative (an explicit stack, no
 * recursion), checking the clock every few thousand tokens and calling [giveBack] once [sliceMs] of
 * work has passed. For the browser, where a 1.6 MB completion list held the page 15 ms in one piece
 * with kotlinx's parser: in slices, no single task is longer than about [sliceMs]. Produces the same
 * [JsonElement] tree as kotlinx; malformed input (a missing comma or colon, a trailing comma, a bad
 * literal, number or escape, a control character in a string, anything after the value) throws
 * [IllegalArgumentException].
 */
internal object SlicedJson {
    private enum class Want { VALUE, VALUE_OR_END, COMMA_OR_END, KEY, KEY_OR_END, COLON, DONE }

    private class Arr { val items = ArrayList<JsonElement>() }
    private class Obj { val map = LinkedHashMap<String, JsonElement>(); var key: String? = null }

    suspend fun parse(text: String, sliceMs: Long, giveBack: suspend () -> Unit): JsonElement {
        val stack = ArrayList<Any>()
        var result: JsonElement? = null
        var want = Want.VALUE
        var i = 0
        val n = text.length
        var mark = TimeSource.Monotonic.markNow()
        var tokens = 0
        val slice = sliceMs.milliseconds

        fun fail(what: String): Nothing = throw IllegalArgumentException("malformed JSON at $i: $what")

        fun afterValue() { want = if (stack.isEmpty()) Want.DONE else Want.COMMA_OR_END }

        fun emit(v: JsonElement) {
            when (val top = stack.lastOrNull()) {
                null -> result = v
                is Arr -> top.items += v
                is Obj -> { top.map[top.key!!] = v; top.key = null }
            }
            afterValue()
        }

        fun readString(): String {
            val sb = StringBuilder()
            i++
            var start = i
            while (true) {
                if (i >= n) fail("an unterminated string")
                val ch = text[i]
                if (ch == '"') { sb.append(text, start, i); i++; return sb.toString() }
                if (ch < ' ') fail("a control character in a string")
                if (ch == '\\') {
                    sb.append(text, start, i)
                    if (i + 1 >= n) fail("an unterminated escape")
                    when (val e = text[i + 1]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C'); '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                        'u' -> {
                            if (i + 6 > n) fail("a short \\u escape")
                            val hex = text.substring(i + 2, i + 6)
                            sb.append((hex.toIntOrNull(16) ?: fail("a bad \\u escape")).toChar())
                            i += 4
                        }
                        else -> fail("a bad escape \\$e")
                    }
                    i += 2
                    start = i
                    continue
                }
                i++
            }
        }

        fun literal(word: String, v: JsonElement) {
            if (!text.startsWith(word, i)) fail("a bad literal")
            i += word.length
            emit(v)
        }

        while (i < n) {
            if (++tokens and 4095 == 0 && mark.elapsedNow() > slice) { giveBack(); mark = TimeSource.Monotonic.markNow() }
            val c = text[i]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') { i++; continue }
            when (want) {
                Want.DONE -> fail("text after the value")
                Want.COLON -> { if (c != ':') fail("a missing ':'"); i++; want = Want.VALUE }
                Want.COMMA_OR_END -> when (c) {
                    ',' -> { i++; want = if (stack.last() is Obj) Want.KEY else Want.VALUE }
                    ']' -> { val a = stack.removeAt(stack.size - 1) as? Arr ?: fail("a ']' closing an object"); i++; emit(JsonArray(a.items)) }
                    '}' -> { val o = stack.removeAt(stack.size - 1) as? Obj ?: fail("a '}' closing an array"); i++; emit(JsonObject(o.map)) }
                    else -> fail("a missing ','")
                }
                Want.KEY, Want.KEY_OR_END -> when {
                    c == '"' -> { (stack.last() as Obj).key = readString(); want = Want.COLON }
                    c == '}' && want == Want.KEY_OR_END -> { val o = stack.removeAt(stack.size - 1) as Obj; i++; emit(JsonObject(o.map)) }
                    else -> fail("a missing key")
                }
                Want.VALUE, Want.VALUE_OR_END -> when {
                    c == ']' && want == Want.VALUE_OR_END -> { val a = stack.removeAt(stack.size - 1) as Arr; i++; emit(JsonArray(a.items)) }
                    c == '{' -> { stack += Obj(); i++; want = Want.KEY_OR_END }
                    c == '[' -> { stack += Arr(); i++; want = Want.VALUE_OR_END }
                    c == '"' -> emit(JsonPrimitive(readString()))
                    c == 't' -> literal("true", JsonPrimitive(true))
                    c == 'f' -> literal("false", JsonPrimitive(false))
                    c == 'n' -> literal("null", JsonNull)
                    c == '-' || c in '0'..'9' -> {
                        val s = i
                        if (text[i] == '-') i++
                        if (i < n && text[i] == '0') i++ else { if (i >= n || text[i] !in '1'..'9') fail("a bad number"); while (i < n && text[i] in '0'..'9') i++ }
                        if (i < n && text[i] == '.') { i++; if (i >= n || text[i] !in '0'..'9') fail("a bad fraction"); while (i < n && text[i] in '0'..'9') i++ }
                        if (i < n && (text[i] == 'e' || text[i] == 'E')) {
                            i++
                            if (i < n && (text[i] == '+' || text[i] == '-')) i++
                            if (i >= n || text[i] !in '0'..'9') fail("a bad exponent")
                            while (i < n && text[i] in '0'..'9') i++
                        }
                        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
                        emit(kotlinx.serialization.json.JsonUnquotedLiteral(text.substring(s, i)))
                    }
                    else -> fail("unexpected '$c'")
                }
            }
        }
        if (want != Want.DONE) fail("unexpected end")
        return result ?: JsonNull
    }
}

/** Messages at least this long are parsed in slices where [lspSliceBigMessages] (the browser). */
internal const val SLICED_PARSE_MIN = 128 * 1024

/** The browser: big messages are parsed in slices on its one thread ([SlicedJson]); elsewhere they go to a worker. */
internal expect val lspSliceBigMessages: Boolean

/** Give the thread back for a moment (the browser: a real macrotask; elsewhere a `yield`). */
internal expect suspend fun lspGiveBack()
