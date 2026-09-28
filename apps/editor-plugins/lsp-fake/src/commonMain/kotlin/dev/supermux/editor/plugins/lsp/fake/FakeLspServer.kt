package dev.supermux.editor.plugins.lsp.fake

import dev.supermux.editor.plugins.lsp.LspConnState
import dev.supermux.editor.plugins.lsp.LspTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * An in-process language server for a toy language, behind [LspTransport]: what the LSP plugin's
 * tests and the editor sample talk to.
 *
 * The language: `fun name(a, b) { … }`, `val name = …`, `var name = …`; identifiers are letters,
 * digits and `_`. The server keeps its own copy of every open document (it applies `didChange`'s
 * incremental edits itself, in its own position code, so a sync bug shows up as a different text) and
 * answers:
 * - **diagnostics** after every change: [marker] (`TODO`) is a warning with a code action "Replace
 *   TODO with DONE"; the word `error` is an error;
 * - **completion**: after `.` the members `length`, `size`, `first()`, `toString()`; otherwise the
 *   keywords, every declared name (docs by `completionItem/resolve`) and a `for` snippet (plus
 *   [extraCompletions] generated items, for performance runs);
 * - **hover** on a declared name (markdown), **signature help** inside `name(…)`, **definition**
 *   (its declaration), **references** (every occurrence), **rename** (every occurrence),
 *   **formatting** (trailing spaces removed, tabs to 4 spaces), **code actions** for the marker.
 *
 * Test hooks: [delayMs] per method, [failing] (answers an error), [silent] (never answers: timeouts),
 * [disconnect] / [reconnect], [pushEdit] (a `workspace/applyEdit`), [documents], [log].
 */
class FakeLspServer(
    private val scope: CoroutineScope,
    /** The position encoding the server picks (null: the client's first offer). */
    var encoding: String? = null,
    /** 2: incremental sync, 1: full text every time. */
    val syncKind: Int = 2,
    val marker: String = "TODO",
    var extraCompletions: Int = 0,
    /** Code actions come without their edit, resolved by `codeAction/resolve` against the text THEN. */
    val resolveCodeActions: Boolean = false,
) {
    /** Each request method's last params, and the document text the server had when it arrived. */
    val lastParams = HashMap<String, JsonElement>()
    val textAtRequest = HashMap<String, String>()

    private val out = Channel<String>(Channel.UNLIMITED)
    private val statusFlow = MutableStateFlow(LspConnState.CONNECTED)
    private var enc = "utf-16"

    /** Every open document's text as the server has it, and its version. */
    val documents = LinkedHashMap<String, String>()
    val versions = LinkedHashMap<String, Int>()

    /** Every method received, in order. */
    val log = ArrayList<String>()
    val cancelled = ArrayList<Int>()
    val delayMs = HashMap<String, Long>()
    val failing = HashSet<String>()
    val silent = HashSet<String>()
    var initialized = false; private set

    /** What the client initialized with (its `capabilities`). */
    var clientCapabilities: JsonElement? = null; private set

    private val connectionFlow = MutableStateFlow(1)

    /** Test hook: every `send` takes this long (a slow pipe: the client must never reorder around it). */
    var sendDelayMs: Long = 0

    /** Test hook: the next this-many sends throw (a broken pipe), after [failAfterSends] good ones. */
    var failSends: Int = 0
    var failAfterSends: Int = 0
    private var sends = 0

    /** Every message the client sent, in the order the transport accepted them (method or "response"). */
    val wire = ArrayList<String>()

    val transport: LspTransport = object : LspTransport {
        override suspend fun send(message: String) {
            if (sendDelayMs > 0) delay(sendDelayMs)
            sends++
            if (failSends > 0 && sends > failAfterSends) { failSends--; throw IllegalStateException("the fake pipe broke") }
            if (statusFlow.value != LspConnState.CONNECTED) return
            val msg = Json.parseToJsonElement(message).jsonObject
            wire += (msg["method"] as? JsonPrimitive)?.contentOrNull ?: "response"
            scope.launch { handle(msg) }
        }
        override val incoming: Flow<String> = out.receiveAsFlow()
        override val status: StateFlow<LspConnState> = statusFlow
        override val connection: StateFlow<Int> = connectionFlow
    }

    /** The connection drops: the server forgets its documents (a restarted server). */
    fun disconnect() {
        statusFlow.value = LspConnState.DISCONNECTED
        forget()
    }

    fun reconnect() {
        connectionFlow.value++
        statusFlow.value = LspConnState.CONNECTED
    }

    /** A drop and a reconnect inside one tick: a StateFlow of the status alone never shows it. */
    fun blip() {
        statusFlow.value = LspConnState.DISCONNECTED
        forget()
        connectionFlow.value++
        statusFlow.value = LspConnState.CONNECTED
    }

    private fun forget() { documents.clear(); versions.clear(); initialized = false }

    private fun send(o: JsonElement) { if (statusFlow.value == LspConnState.CONNECTED) out.trySend(o.toString()) }

    private var nextServerId = 1000

    /** The client's answers to [pushEdit], by request id. */
    val applyResults = LinkedHashMap<Int, JsonElement>()

    /**
     * Push a `workspace/applyEdit` to the client: [edits] as (range as 4 numbers, text); with a
     * [version], as `documentChanges` for that version of the document. Returns the request id
     * ([applyResults] gets the client's answer).
     */
    fun pushEdit(uri: String, edits: List<Pair<IntArray, String>>, version: Int? = null): Int {
        val id = nextServerId++
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", id); put("method", "workspace/applyEdit")
            put("params", buildJsonObject {
                put("edit", buildJsonObject {
                    val list = JsonArray(edits.map { (r, t) -> edit(r[0], r[1], r[2], r[3], t) })
                    if (version == null) put("changes", buildJsonObject { put(uri, list) })
                    else put("documentChanges", buildJsonArray { add(buildJsonObject { put("textDocument", buildJsonObject { put("uri", uri); put("version", version) }); put("edits", list) }) })
                })
            })
        })
        return id
    }

    /** What rename answers for another document too (a URI and its edits), as the fake cannot see it. */
    val extraRenameEdits = LinkedHashMap<String, List<Pair<IntArray, String>>>()

    private suspend fun handle(msg: JsonObject) {
        val method = msg["method"]?.let { (it as? JsonPrimitive)?.contentOrNull }
        val id = msg["id"]
        if (method == null) {
            // A response to our applyEdit.
            (id as? JsonPrimitive)?.intOrNull?.let { applyResults[it] = msg["result"] ?: msg["error"] ?: JsonNull }
            return
        }
        log += method
        val params = msg["params"]
        if (id == null) { notification(method, params); return }
        val idNum = (id as? JsonPrimitive)?.intOrNull ?: 0
        // Answered against the documents as they are when the request ARRIVES (a real server handles
        // messages in order: a didChange sent after the request is not seen by it), sent after the delay.
        val result = if (method in failing || method in silent) null else try { request(method, params) } catch (e: Throwable) { respondError(id, -32603, e.toString()); return }
        delayMs[method]?.let { delay(it) }
        if (method in silent) return
        if (idNum in cancelled) { respondError(id, -32800, "cancelled"); return }
        if (method in failing) { respondError(id, -32603, "the fake server fails $method"); return }
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result ?: JsonNull) })
    }

    private fun respondError(id: JsonElement, code: Int, message: String) =
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("error", buildJsonObject { put("code", code); put("message", message) }) })

    private fun notification(method: String, params: JsonElement?) {
        when (method) {
            "initialized" -> initialized = true
            "textDocument/didOpen" -> {
                val td = params!!.jsonObject["textDocument"]!!.jsonObject
                val uri = td.s("uri")
                documents[uri] = td.s("text")
                versions[uri] = td.i("version")
                publish(uri)
            }
            "textDocument/didChange" -> {
                val td = params!!.jsonObject["textDocument"]!!.jsonObject
                val uri = td.s("uri")
                var text = documents[uri] ?: return
                for (c in params.jsonObject["contentChanges"]!!.jsonArray) {
                    val o = c.jsonObject
                    val r = o["range"]
                    text = if (r == null) o.s("text") else {
                        val a = offset(text, r.jsonObject["start"]!!)
                        val b = offset(text, r.jsonObject["end"]!!)
                        text.substring(0, a) + o.s("text") + text.substring(b)
                    }
                }
                documents[uri] = text
                versions[uri] = td.i("version")
                publish(uri)
            }
            "textDocument/didClose" -> { val uri = params!!.jsonObject["textDocument"]!!.jsonObject.s("uri"); documents.remove(uri); versions.remove(uri) }
            "\$/cancelRequest" -> cancelled += params!!.jsonObject.i("id")
        }
    }

    // ------------------------------------------------------------------ positions (the server's own) --

    private fun lines(text: String) = text.split('\n')

    private fun units(s: String): Int = when (enc) {
        "utf-8" -> s.encodeToByteArray().size
        "utf-32" -> s.codePointCount()
        else -> s.length
    }

    private fun String.codePointCount(): Int { var n = 0; var i = 0; while (i < length) { i += if (this[i].isHighSurrogate() && i + 1 < length) 2 else 1; n++ }; return n }

    /** Offset in [text] of an LSP position (in the negotiated encoding). */
    private fun offset(text: String, p: JsonElement): Int {
        val ls = lines(text)
        val line = p.jsonObject.i("line")
        var off = 0
        for (k in 0 until minOf(line, ls.size)) off += ls[k].length + 1
        if (line >= ls.size) return text.length
        val l = ls[line]
        val ch = p.jsonObject.i("character")
        var i = 0
        while (i < l.length && units(l.substring(0, i + (if (l[i].isHighSurrogate() && i + 1 < l.length) 2 else 1))) <= ch) i += if (l[i].isHighSurrogate() && i + 1 < l.length) 2 else 1
        return off + i
    }

    private fun pos(text: String, offset: Int): JsonObject {
        val before = text.substring(0, offset)
        val line = before.count { it == '\n' }
        val start = before.lastIndexOf('\n') + 1
        return buildJsonObject { put("line", line); put("character", units(text.substring(start, offset))) }
    }

    private fun range(text: String, a: Int, b: Int) = buildJsonObject { put("start", pos(text, a)); put("end", pos(text, b)) }

    private fun edit(l1: Int, c1: Int, l2: Int, c2: Int, t: String) = buildJsonObject {
        put("range", buildJsonObject {
            put("start", buildJsonObject { put("line", l1); put("character", c1) })
            put("end", buildJsonObject { put("line", l2); put("character", c2) })
        })
        put("newText", t)
    }

    // ------------------------------------------------------------------ the toy language --

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun wordAt(text: String, at: Int): IntRange? {
        var a = at; var b = at
        while (a > 0 && isIdent(text[a - 1])) a--
        while (b < text.length && isIdent(text[b])) b++
        return if (a == b) null else a until b
    }

    private val declaration = Regex("\\b(fun|val|var)\\s+([\\p{L}_][\\p{L}\\p{N}_]*)(\\(([^)]*)\\))?")

    private data class Decl(val kind: String, val name: String, val nameAt: Int, val params: List<String>?)

    private fun declarations(text: String): List<Decl> = declaration.findAll(text).map { m ->
        Decl(m.groupValues[1], m.groupValues[2], m.groups[2]!!.range.first, m.groups[4]?.value?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() })
    }.toList()

    /**
     * Whole-word occurrences of [name], scanned by hand: a look-behind regex is quadratic in
     * Kotlin/Wasm's engine (a 100 KB file hung the browser page).
     */
    private fun occurrences(text: String, name: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = text.indexOf(name)
        while (i >= 0) {
            val end = i + name.length
            if ((i == 0 || !isIdent(text[i - 1])) && (end == text.length || !isIdent(text[end]))) out += i until end
            i = text.indexOf(name, i + 1)
        }
        return out
    }

    private fun lineOf(text: String, at: Int) = text.substring(0, at).count { it == '\n' }

    private fun publish(uri: String) {
        val text = documents[uri] ?: return
        val diags = buildJsonArray {
            for (r in occurrences(text, marker)) add(buildJsonObject {
                put("range", range(text, r.first, r.last + 1)); put("severity", 2); put("source", "fake"); put("code", "todo")
                put("message", "$marker left in the code")
            })
            for (r in occurrences(text, "error")) add(buildJsonObject {
                put("range", range(text, r.first, r.last + 1)); put("severity", 1); put("source", "fake")
                put("message", "'error' is not allowed here")
            })
        }
        send(buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "textDocument/publishDiagnostics")
            put("params", buildJsonObject { put("uri", uri); put("version", versions[uri] ?: 0); put("diagnostics", diags) })
        })
    }

    private fun request(method: String, params: JsonElement?): JsonElement? {
        val p = params?.jsonObject
        if (method == "initialize") {
            clientCapabilities = p?.get("capabilities")
            val offered = p?.get("capabilities")?.jsonObject?.get("general")?.jsonObject?.get("positionEncodings")?.jsonArray?.map { it.jsonPrimitive() } ?: emptyList()
            enc = encoding ?: offered.firstOrNull() ?: "utf-16"
            return buildJsonObject {
                put("serverInfo", buildJsonObject { put("name", "fake-toy-lsp") })
                put("capabilities", buildJsonObject {
                    put("positionEncoding", enc)
                    put("textDocumentSync", syncKind)
                    put("completionProvider", buildJsonObject { put("triggerCharacters", buildJsonArray { add(JsonPrimitive(".")) }); put("resolveProvider", true) })
                    put("hoverProvider", true)
                    put("signatureHelpProvider", buildJsonObject { put("triggerCharacters", buildJsonArray { add(JsonPrimitive("(")); add(JsonPrimitive(",")) }); put("retriggerCharacters", buildJsonArray { add(JsonPrimitive(")")) }) })
                    put("definitionProvider", true)
                    put("referencesProvider", true)
                    put("renameProvider", true)
                    put("documentFormattingProvider", true)
                    if (resolveCodeActions) put("codeActionProvider", buildJsonObject { put("resolveProvider", true) }) else put("codeActionProvider", true)
                })
            }
        }
        if (method == "shutdown") return JsonNull
        if (method == "completionItem/resolve") {
            val item = p!!
            val label = item.s("label")
            val text = documents.values.firstOrNull().orEmpty()
            val d = declarations(text).firstOrNull { it.name == label }
            return buildJsonObject {
                for ((k, v) in item) put(k, v)
                put("documentation", buildJsonObject { put("kind", "markdown"); put("value", if (d != null) "**$label**: a `${d.kind}` declared on line ${lineOf(text, d.nameAt) + 1}" else "`$label`") })
            }
        }
        if (method == "codeAction/resolve") {
            val data = p!!["data"]!!.jsonObject
            val u = data.s("uri")
            val t = documents[u] ?: return p
            val r = occurrences(t, marker).firstOrNull() ?: return p
            return buildJsonObject {
                for ((k, v) in p) put(k, v)
                put("edit", buildJsonObject { put("changes", buildJsonObject { put(u, buildJsonArray { add(buildJsonObject { put("range", range(t, r.first, r.last + 1)); put("newText", "DONE") }) }) }) })
            }
        }
        val uri = p?.get("textDocument")?.jsonObject?.s("uri") ?: return JsonNull
        val text = documents[uri] ?: return JsonNull
        lastParams[method] = p
        textAtRequest[method] = text
        val at = p["position"]?.let { offset(text, it) } ?: 0
        return when (method) {
            "textDocument/completion" -> completion(text, at)
            "textDocument/hover" -> {
                val w = wordAt(text, at) ?: return JsonNull
                val name = text.substring(w.first, w.last + 1)
                val d = declarations(text).firstOrNull { it.name == name } ?: return JsonNull
                buildJsonObject {
                    put("contents", buildJsonObject { put("kind", "markdown"); put("value", "**$name** — `${d.kind}` declared on line ${lineOf(text, d.nameAt) + 1}" + (d.params?.let { "\n\nParameters: `${it.joinToString(", ")}`" } ?: "")) })
                    put("range", range(text, w.first, w.last + 1))
                }
            }
            "textDocument/signatureHelp" -> signature(text, at)
            "textDocument/definition" -> {
                val w = wordAt(text, at) ?: return JsonNull
                val name = text.substring(w.first, w.last + 1)
                val d = declarations(text).firstOrNull { it.name == name } ?: return JsonNull
                buildJsonObject { put("uri", uri); put("range", range(text, d.nameAt, d.nameAt + name.length)) }
            }
            "textDocument/references" -> {
                val w = wordAt(text, at) ?: return JsonNull
                val name = text.substring(w.first, w.last + 1)
                JsonArray(occurrences(text, name).map { r -> buildJsonObject { put("uri", uri); put("range", range(text, r.first, r.last + 1)) } })
            }
            "textDocument/rename" -> {
                val w = wordAt(text, at) ?: return JsonNull
                val name = text.substring(w.first, w.last + 1)
                val newName = p.s("newName")
                buildJsonObject { put("changes", buildJsonObject {
                    put(uri, JsonArray(occurrences(text, name).map { r -> buildJsonObject { put("range", range(text, r.first, r.last + 1)); put("newText", newName) } }))
                    // Other open documents: every occurrence there too (the toy language has one namespace).
                    for ((u, t) in documents) if (u != uri) put(u, JsonArray(occurrences(t, name).map { r -> buildJsonObject { put("range", range(t, r.first, r.last + 1)); put("newText", newName) } }))
                    for ((u, es) in extraRenameEdits) put(u, JsonArray(es.map { (r, t) -> edit(r[0], r[1], r[2], r[3], t) }))
                }) }
            }
            "textDocument/formatting" -> {
                val edits = ArrayList<JsonElement>()
                var off = 0
                for (l in lines(text)) {
                    val lead = l.takeWhile { it == ' ' || it == '\t' }
                    if (lead.contains('\t')) edits += buildJsonObject { put("range", range(text, off, off + lead.length)); put("newText", lead.replace("\t", "    ")) }
                    val trimmed = l.trimEnd(' ', '\t')
                    if (trimmed.length < l.length && trimmed.length >= lead.length) edits += buildJsonObject { put("range", range(text, off + trimmed.length, off + l.length)); put("newText", "") }
                    off += l.length + 1
                }
                JsonArray(edits)
            }
            "textDocument/codeAction" -> {
                val diags = p["context"]?.jsonObject?.get("diagnostics")?.jsonArray.orEmpty()
                JsonArray(diags.filter { (it.jsonObject["code"] as? JsonPrimitive)?.contentOrNull == "todo" }.map { d ->
                    buildJsonObject {
                        put("title", "Replace $marker with DONE"); put("kind", "quickfix")
                        if (resolveCodeActions) put("data", buildJsonObject { put("uri", uri) })
                        else put("edit", buildJsonObject { put("changes", buildJsonObject { put(uri, buildJsonArray { add(buildJsonObject { put("range", d.jsonObject["range"]!!); put("newText", "DONE") }) }) }) })
                    }
                })
            }
            else -> JsonNull
        }
    }

    private val keywords = listOf("fun", "val", "var", "if", "else", "return", "when", "while")
    private val members = listOf("length" to 10, "size" to 10, "first()" to 2, "toString()" to 2)

    private fun completion(text: String, at: Int): JsonElement {
        var a = at
        while (a > 0 && isIdent(text[a - 1])) a--
        val afterDot = a > 0 && text[a - 1] == '.'
        val items = buildJsonArray {
            if (afterDot) for ((m, kind) in members) add(buildJsonObject { put("label", m.removeSuffix("()")); put("kind", kind); put("insertText", m); put("detail", "member") })
            else {
                for (k in keywords) add(buildJsonObject { put("label", k); put("kind", 14) })
                val seen = HashSet<String>()
                for (d in declarations(text)) if (seen.add(d.name)) add(buildJsonObject {
                    put("label", d.name); put("kind", if (d.kind == "fun") 3 else 6); put("detail", d.kind)
                    if (d.params != null) put("insertText", d.name)
                })
                add(buildJsonObject {
                    put("label", "for"); put("kind", 15); put("detail", "for loop"); put("insertTextFormat", 2)
                    put("insertText", "for (\${1:item} in \${2:items}) {\n\t$0\n}")
                    put("documentation", "A `for` loop over a collection.")
                })
                for (i in 0 until extraCompletions) add(buildJsonObject { put("label", "generated$i"); put("kind", 6); put("detail", "generated item $i"); put("sortText", "z$i") })
            }
        }
        return buildJsonObject { put("isIncomplete", false); put("items", items) }
    }

    private fun signature(text: String, at: Int): JsonElement {
        var depth = 0
        var i = at - 1
        var commas = 0
        while (i >= 0 && text[i] != '\n') {
            when (text[i]) {
                ')' -> depth++
                '(' -> if (depth == 0) break else depth--
                ',' -> if (depth == 0) commas++
            }
            i--
        }
        if (i < 0 || text[i] != '(') return JsonNull
        var a = i
        while (a > 0 && isIdent(text[a - 1])) a--
        val name = text.substring(a, i)
        val d = declarations(text).firstOrNull { it.name == name && it.params != null } ?: return JsonNull
        val label = "$name(${d.params!!.joinToString(", ")})"
        return buildJsonObject {
            put("signatures", buildJsonArray {
                add(buildJsonObject {
                    put("label", label)
                    put("documentation", "Declared on line ${lineOf(text, d.nameAt) + 1}.")
                    put("parameters", JsonArray(d.params.map { buildJsonObject { put("label", it) } }))
                })
            })
            put("activeSignature", 0)
            put("activeParameter", commas.coerceAtMost(maxOf(0, d.params.size - 1)))
        }
    }

    private fun JsonObject.s(k: String): String = (this[k] as JsonPrimitive).content
    private fun JsonObject.i(k: String): Int = (this[k] as JsonPrimitive).intOrNull ?: 0
    private fun JsonElement.jsonPrimitive(): String = (this as JsonPrimitive).content

}
