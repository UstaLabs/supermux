package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.compose.HoverResult
import dev.supermux.editor.compose.ViewPluginHost
import dev.supermux.editor.compose.ViewPluginInstance
import dev.supermux.editor.compose.indentUnitFacet
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.plugins.autocomplete.Completion
import dev.supermux.editor.plugins.autocomplete.CompletionApply
import dev.supermux.editor.plugins.autocomplete.CompletionContext
import dev.supermux.editor.plugins.autocomplete.CompletionResult
import dev.supermux.editor.plugins.autocomplete.Snippet
import dev.supermux.editor.plugins.lint.Diagnostic
import dev.supermux.editor.plugins.lint.DiagnosticAction
import dev.supermux.editor.plugins.lint.Lint
import dev.supermux.editor.plugins.lint.Severity
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One document of an [LspClient], as a view plugin instance: it keeps the server's copy in step
 * (`didOpen`, batched incremental `didChange`, `didClose`) and runs the features for its view.
 *
 * **Sync.** [synced] is the text the server has (version [version]); [unsynced] the edits since. An
 * edit composes into [unsynced] and schedules ONE `didChange` [LspClientConfig.syncDelayMs] later;
 * every request sends the pending edits first. Incremental changes are sent back to front, each range
 * in the synced text's coordinates (CM6's `contentChangesFor`); a server that wants full sync gets
 * the whole text. The last [KEEP] versions' texts and the edits between them are kept, so a response
 * for an older version is read in THAT text and mapped to the current one.
 */
internal class LspDocument(
    val client: LspClient,
    private val host: ViewPluginHost,
    val uri: String,
    private val languageId: String,
) : ViewPluginInstance {
    internal val target: CommandTarget get() = host.target
    private val features: ServerFeatures get() = client.features.value
    private val enc: PositionEncoding get() = features.positionEncoding

    var version = 0; private set
    var synced: Rope? = null; private set
    private var unsynced: ChangeSet? = null
    private val texts = ArrayDeque<Pair<Int, Rope>>()
    private val steps = ArrayDeque<Pair<Int, ChangeSet>>()
    private var syncJob: Job? = null
    private var signatureJob: Job? = null
    private var codeActionJob: Job? = null
    private var hoverIds = 0
    private var closed = false

    val isOpen: Boolean get() = synced != null

    init {
        client.attach(this)
        if (client.ready) open()
    }

    // ------------------------------------------------------------------ sync --

    fun open() {
        if (closed) return
        val doc = target.state.doc
        version++
        synced = doc
        unsynced = ChangeSet.empty(doc.length)
        texts.clear(); steps.clear()
        texts += version to doc
        val v = version
        client.launchOrdered {
            client.notify("textDocument/didOpen", buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", uri); put("languageId", languageId); put("version", v); put("text", doc.toString()) })
            })
        }
        // After the dispatch that may be running now (a view plugin starts inside one).
        val f = features
        host.scope.launch { target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setFeatures.of(f)))) }
    }

    fun connectionLost() {
        synced = null
        unsynced = null
        syncJob?.cancel(); signatureJob?.cancel(); codeActionJob?.cancel()
        target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setSignature.of(null))))
    }

    /** Send the edits since the last sync now (before a request). */
    suspend fun sync() {
        syncJob?.cancel(); syncJob = null
        val prev = synced ?: return
        val cs = unsynced ?: return
        if (cs.isEmpty) return
        val doc = cs.apply(prev)
        val kind = features.sync
        version++
        val v = version
        synced = doc
        unsynced = ChangeSet.empty(doc.length)
        steps += v to cs
        texts += v to doc
        while (texts.size > KEEP) texts.removeFirst()
        while (steps.size > KEEP) steps.removeFirst()
        if (kind == 0) return
        val changes = buildJsonArray {
            if (kind == 1) add(buildJsonObject { put("text", doc.toString()) })
            else for (c in cs.iterChanges().asReversed()) add(buildJsonObject {
                put("range", Positions.rangeToLsp(prev, c.fromA, c.toA, enc).json())
                put("text", c.inserted)
            })
        }
        client.notify("textDocument/didChange", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri); put("version", v) })
            put("contentChanges", changes)
        })
    }

    private fun scheduleSync() {
        if (syncJob?.isActive == true) return
        syncJob = host.scope.launch {
            delay(client.config.syncDelayMs)
            syncJob = null
            sync()
        }
    }

    /** The offset of [pos], a position in version [v]'s text, in the CURRENT document; null when that version is gone. */
    fun mapFrom(v: Int, pos: LspPosition, assoc: Int = -1): Int? {
        val text = texts.lastOrNull { it.first == v }?.second ?: return null
        var at = Positions.fromLsp(text, pos, enc)
        for ((sv, cs) in steps) if (sv > v) at = cs.mapPos(at.coerceIn(0, cs.lengthBefore), assoc)
        val u = unsynced ?: return null
        return u.mapPos(at.coerceIn(0, u.lengthBefore), assoc)
    }

    /** The edits since version [v] (composed), or null when that version is gone. */
    private fun changesSince(v: Int): ChangeSet? {
        val text = texts.lastOrNull { it.first == v }?.second ?: return null
        var cs = ChangeSet.empty(text.length)
        for ((sv, c) in steps) if (sv > v) cs = cs.compose(c)
        return unsynced?.let { cs.compose(it) }
    }

    private fun lspPos(offset: Int) = Positions.toLsp(target.state.doc, offset, enc)

    private fun textDocument() = buildJsonObject { put("uri", uri) }

    override fun update(tr: Transaction) {
        if (tr.docChanged && synced != null) {
            unsynced = unsynced?.compose(tr.changes)
            scheduleSync()
        }
        for (e in tr.effects) if (e.isOf(LspPlugin.requestSignature)) startSignature(1, null)
        followSignature(tr)
    }

    fun close() {
        if (closed) return
        closed = true
        syncJob?.cancel(); signatureJob?.cancel(); codeActionJob?.cancel()
        client.detach(this)
        if (synced != null) {
            synced = null
            client.launchOrdered { client.notify("textDocument/didClose", buildJsonObject { put("textDocument", textDocument()) }) }
        }
    }

    override fun destroy() = close()

    // ------------------------------------------------------------------ edits from the server --

    private fun touches(cs: ChangeSet, from: Int, to: Int): Boolean =
        cs.iterChanges().any { c -> c.fromA < to && c.toA > from || c.fromA == c.toA && c.fromA in from..to && from < to || c.fromA == from && c.toA == to }

    /**
     * [edits], in version [v]'s text, applied to the current document as ONE transaction with
     * [userEvent] (through [via], the target to dispatch with). An edit whose text the user changed
     * since is dropped ([abortIfTouched]: then all of them, CM6's rule for formatting). False when
     * nothing could be applied.
     */
    fun applyEdits(edits: List<LspTextEdit>, v: Int, userEvent: String?, abortIfTouched: Boolean = false, via: CommandTarget = target): Boolean {
        val text = texts.lastOrNull { it.first == v }?.second ?: return false
        val since = changesSince(v) ?: return false
        val specs = ArrayList<ChangeSpec>()
        for (e in edits) {
            val a = Positions.fromLsp(text, e.range.start, enc)
            val b = Positions.fromLsp(text, e.range.end, enc).coerceAtLeast(a)
            if (!since.isEmpty && touches(since, a, b)) { if (abortIfTouched) return false else continue }
            val ma = since.mapPos(a, 1)
            val mb = maxOf(ma, since.mapPos(b, -1))
            specs += ChangeSpec(ma, mb, e.newText)
        }
        if (specs.isEmpty()) return edits.isEmpty()
        val sorted = specs.sortedWith(compareBy({ it.from }, { it.to }))
        val clean = ArrayList<ChangeSpec>()
        for (s in sorted) { val l = clean.lastOrNull(); if (l != null && s.from < l.to) continue; clean += s }
        val st = via.state
        val cs = try { ChangeSet.of(st.doc.length, clean) } catch (e: IllegalArgumentException) { return false }
        via.dispatch(TransactionSpec(changeSet = cs, userEvent = userEvent))
        return true
    }

    /** A server-pushed edit (`workspace/applyEdit`), in the text the server has now. */
    fun applyServerEdits(edits: List<LspTextEdit>, userEvent: String): Boolean = applyEdits(edits, version, userEvent)

    // ------------------------------------------------------------------ diagnostics --

    private var diagnosticsToken = 0

    fun diagnostics(params: JsonElement?) {
        val st = target.state
        if (st.fieldOrNull(Lint.field) == null) return
        val v = params["version"].int
        if (v != null && v != version) return // for another version of the text: the next one comes
        val raw = params["diagnostics"].arr.orEmpty()
        val list = raw.mapNotNull { d ->
            val r = range(d["range"]) ?: return@mapNotNull null
            val a = mapFrom(version, r.start, 1) ?: return@mapNotNull null
            val b = mapFrom(version, r.end, -1)?.coerceAtLeast(a) ?: return@mapNotNull null
            val sev = when (d["severity"].int) { 2 -> Severity.WARNING; 3 -> Severity.INFO; 4 -> Severity.HINT; else -> Severity.ERROR }
            val code = d["code"].let { it.str ?: it.int?.toString() }
            val source = listOfNotNull(d["source"].str, code).joinToString(" ").ifEmpty { null }
            (d as JsonObject) to Diagnostic(a, b, sev, d["message"].str.orEmpty(), source)
        }
        val token = ++diagnosticsToken
        target.dispatch(Lint.setDiagnostics(st, list.map { it.second }))
        codeActionJob?.cancel()
        if (!features.codeAction || list.isEmpty()) return
        codeActionJob = host.scope.launch { codeActions(list, token) }
    }

    /** Each diagnostic's code actions (the [MAX_ACTION_DIAGNOSTICS] nearest the cursor), as its lint actions. */
    private suspend fun codeActions(list: List<Pair<JsonObject, Diagnostic>>, token: Int) {
        val head = target.state.selection.main.head
        val v = version
        val asked = list.withIndex().sortedBy { kotlin.math.abs(it.value.second.from - head) }.take(MAX_ACTION_DIAGNOSTICS)
        val found = coroutineScope {
            asked.map { (i, p) ->
                async {
                    val (raw, d) = p
                    val res = client.request("textDocument/codeAction", buildJsonObject {
                        put("textDocument", textDocument())
                        put("range", raw["range"]!!)
                        put("context", buildJsonObject { put("diagnostics", buildJsonArray { add(raw) }) })
                    }) ?: return@async null
                    val actions = res.arr.orEmpty().mapNotNull { a -> action(a, v) }
                    if (actions.isEmpty()) null else i to actions
                }
            }.awaitAll().filterNotNull().toMap()
        }
        if (token != diagnosticsToken || found.isEmpty()) return
        // The diagnostics as they are now (mapped since), in the same order: add the actions.
        val now = Lint.diagnostics(target.state)
        if (now.size != list.size) return
        val byOrder = list.indices.sortedWith(compareBy({ list[it].second.from }, { list[it].second.to }))
        val updated = now.mapIndexed { k, d -> found[byOrder[k]]?.let { d.copy(actions = it) } ?: d }
        target.dispatch(Lint.setDiagnostics(target.state, updated))
    }

    private fun action(a: JsonElement, v: Int): DiagnosticAction? {
        val title = a["title"].str ?: return null
        if (a["disabled"] != null) return null
        return DiagnosticAction(title) { via, _, _ ->
            client.launch {
                var act = a
                if (act["edit"] == null && act["command"].str == null && act["data"] != null && features.codeActionResolve) {
                    act = client.request("codeAction/resolve", a) ?: a
                }
                val edit = act["edit"]
                if (edit != null) for ((u, edits) in workspaceEdit(edit)) {
                    if (normalizeUri(u) == normalizeUri(uri)) applyEdits(edits, v, userEvent = null, via = via)
                    else client.config.onWorkspaceEdit?.invoke(u, edits)
                }
                // A Command (the action itself, or the action's): the server runs it, and may push edits.
                val cmd = if (act["command"].str != null) act else act["command"]
                val name = cmd["command"].str
                if (name != null) client.request("workspace/executeCommand", buildJsonObject {
                    put("command", name)
                    cmd["arguments"]?.let { put("arguments", it) }
                }, quiet = false)
            }
        }
    }

    // ------------------------------------------------------------------ completion --

    private fun wordRange(doc: Rope, pos: Int): IntRange {
        val line = doc.lineAt(pos)
        var a = pos; var b = pos
        while (a > line.from && isIdent(doc.charAt(a - 1))) a--
        while (b < line.to && isIdent(doc.charAt(b))) b++
        return a until b
    }

    private fun isIdent(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    suspend fun complete(ctx: CompletionContext): CompletionResult? {
        val f = features
        if (!f.completion || !isOpen) return null
        val before = if (ctx.pos > 0) ctx.state.doc.charAt(ctx.pos - 1) else ' '
        val triggers = f.completionTriggers.ifEmpty { client.config.fallbackTriggers }
        val trig = ctx.triggerCharacter?.firstOrNull()
        val context = when {
            ctx.explicit -> buildJsonObject { put("triggerKind", 1) }
            trig != null && trig in triggers -> buildJsonObject { put("triggerKind", 2); put("triggerCharacter", trig.toString()) }
            before.isLetter() || before == '_' -> buildJsonObject { put("triggerKind", 1) }
            else -> return null
        }
        sync()
        val doc = ctx.state.doc
        val res = client.request("textDocument/completion", buildJsonObject {
            put("textDocument", textDocument())
            put("position", Positions.toLsp(doc, ctx.pos, enc).json())
            put("context", context)
        }) ?: return null
        val enc = enc
        return withContext(client.parseContext) { completionResult(res, ctx, doc, f, enc) }
    }

    internal suspend fun completionResult(res: JsonElement, ctx: CompletionContext, doc: Rope, f: ServerFeatures, enc: PositionEncoding): CompletionResult? {
        val items = res.arr ?: res["items"].arr ?: return null
        if (items.isEmpty()) return null
        val incomplete = res["isIncomplete"].bool == true
        val defaults = res["itemDefaults"]
        val defaultFormat = defaults["insertTextFormat"].int
        // CM6's completionResultRange: the default edit range, else the first item's, else the word.
        val range = defaults["editRange"].let { r -> range(r) ?: range(r["insert"]) } ?: items[0]["textEdit"].let { t -> range(t["range"]) ?: range(t["insert"]) }
        val word = wordRange(doc, ctx.pos)
        val from = range?.let { Positions.fromLsp(doc, it.start, enc) } ?: word.first
        val to = range?.let { Positions.fromLsp(doc, it.end, enc) } ?: ctx.pos
        val options = ArrayList<Completion>(items.size)
        for ((k, item) in items.withIndex()) {
            // The browser: give the thread back every 1,000 items (5,000 map in ~3 ms there).
            if (lspSliceBigMessages && k > 0 && k % 1000 == 0) lspGiveBack()
            val label = item["label"].str ?: continue
            val text = item["textEdit"]["newText"].str ?: item["textEditText"].str ?: item["insertText"].str ?: label
            val snippet = (item["insertTextFormat"].int ?: defaultFormat) == 2
            val extra = item["additionalTextEdits"].arr?.mapNotNull { e ->
                val te = textEdit(e) ?: return@mapNotNull null
                val a = Positions.fromLsp(doc, te.range.start, enc)
                ChangeSpec(a, Positions.fromLsp(doc, te.range.end, enc).coerceAtLeast(a), te.newText)
            }.orEmpty()
            val apply: CompletionApply? = when {
                extra.isNotEmpty() -> CompletionApply.WithEdits(text, extra, snippet)
                snippet -> CompletionApply.Template(Snippet.fromLsp(text))
                text != (item["filterText"].str ?: label) -> CompletionApply.Text(text)
                else -> null
            }
            // The documentation is converted only for the option that gets selected (5,000 markdown
            // conversions cost ~30 ms on the JVM, many times that in the browser).
            val rawDoc = item["documentation"]
            val filter = item["filterText"].str ?: label
            options += Completion(
                label = filter,
                displayLabel = label.takeIf { it != filter },
                detail = item["detail"].str,
                type = kindToType(item["kind"].int),
                sortText = item["sortText"].str,
                apply = apply,
                resolveInfo = when {
                    rawDoc != null -> ({ markup(rawDoc)?.let(client.config.markdown) })
                    f.completionResolve -> ({ resolveDocs(item) })
                    else -> null
                },
            )
        }
        return CompletionResult(from.coerceAtMost(ctx.pos), options, to = maxOf(to, ctx.pos), validFor = if (incomplete) null else prefixRegexp(items))
    }

    private suspend fun resolveDocs(item: JsonElement): String? {
        val r = client.request("completionItem/resolve", item) ?: return null
        return markup(r["documentation"])?.let(client.config.markdown) ?: r["detail"].str
    }

    /** CM6's `prefixRegexp`: the non-word prefixes the items start with, then word characters. */
    private fun prefixRegexp(items: List<JsonElement>): Regex {
        val step = maxOf(1, (items.size + 49) / 50)
        val prefixes = LinkedHashSet<String>()
        var i = 0
        while (i < items.size) {
            val it = items[i]
            val text = it["textEdit"]["newText"].str ?: it["textEditText"].str ?: it["insertText"].str ?: it["label"].str.orEmpty()
            if (text.isNotEmpty() && !isIdent(text[0])) prefixes += text.takeWhile { c -> !isIdent(c) }
            i += step
        }
        val word = "[\\p{L}\\p{N}_$]*"
        return if (prefixes.isEmpty()) Regex(word) else Regex("(?:" + prefixes.joinToString("|") { Regex.escape(it) } + ")?" + word)
    }

    // ------------------------------------------------------------------ hover --

    suspend fun hover(st: EditorState, pos: Int): HoverResult? {
        if (!features.hover || !isOpen) return null
        sync()
        val v = version
        val res = client.request("textDocument/hover", buildJsonObject { put("textDocument", textDocument()); put("position", Positions.toLsp(st.doc, pos, enc).json()) }) ?: return null
        if (target.state.doc !== st.doc) return null // stale: the text changed while the server worked
        val text = markup(res["contents"])?.let(client.config.markdown)?.takeIf { it.isNotBlank() } ?: return null
        val r = range(res["range"])
        val w = wordRange(st.doc, pos)
        val from = r?.let { mapFrom(v, it.start, 1) } ?: w.first
        val to = r?.let { mapFrom(v, it.end, -1) } ?: (w.last + 1)
        val id = "h${hoverIds++}"
        client.putHover(id, text)
        return HoverResult(from, maxOf(from, to), WidgetKey(LspPlugin.HOVER_TOOLTIP, id), above = true)
    }

    // ------------------------------------------------------------------ signature help --

    private var signatureDelay: Job? = null

    private fun followSignature(tr: Transaction) {
        if (!features.signatureHelp || !isOpen) return
        val active = LspPlugin.state(tr.state).signature
        var trigger: Char? = null
        if (tr.docChanged && tr.isUserEvent("input") && !tr.isUserEvent("input.complete")) {
            val triggers = features.signatureTriggers + (if (active != null) features.signatureRetriggers else emptySet())
            for (c in tr.changes.iterChanges()) for (ch in c.inserted) if (ch in triggers) trigger = ch
        }
        if (trigger != null) startSignature(2, trigger, retrigger = active != null)
        else if (active != null && tr.selectionSet) {
            signatureDelay?.cancel()
            signatureDelay = host.scope.launch { delay(250); startSignature(3, null, retrigger = true) }
        }
    }

    private fun startSignature(kind: Int, trigger: Char?, retrigger: Boolean = LspPlugin.state(target.state).signature != null) {
        if (!features.signatureHelp || !isOpen) return
        signatureDelay?.cancel()
        signatureJob?.cancel()
        signatureJob = host.scope.launch {
            sync()
            val st = target.state
            val pos = st.selection.main.head
            val res = client.request("textDocument/signatureHelp", buildJsonObject {
                put("textDocument", textDocument())
                put("position", Positions.toLsp(st.doc, pos, enc).json())
                put("context", buildJsonObject {
                    put("triggerKind", kind)
                    trigger?.let { put("triggerCharacter", it.toString()) }
                    put("isRetrigger", retrigger)
                })
            }, quiet = kind != 1)
            if (target.state.selection != st.selection) return@launch // the cursor moved on: a newer one comes
            val data = res?.let { parseSignatures(it, pos) }
            val cur = LspPlugin.state(target.state).signature
            if (data == null) { if (cur != null) target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setSignature.of(null)))); return@launch }
            // The same signatures: keep the user's choice of signature and the anchor.
            val same = cur != null && cur.signatures.map { it.label } == data.signatures.map { it.label }
            val next = if (same && kind == 3) data.copy(active = cur!!.active, pos = cur.pos) else if (same) data.copy(pos = cur!!.pos) else data
            if (next != cur) target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setSignature.of(next))))
        }
    }

    private fun parseSignatures(res: JsonElement, pos: Int): SignatureData? {
        val sigs = res["signatures"].arr.orEmpty().mapNotNull { s ->
            val label = s["label"].str ?: return@mapNotNull null
            val params = s["parameters"].arr.orEmpty().map { p ->
                val l = p["label"]
                val offs = l.arr
                if (offs != null && offs.size == 2) (offs[0].int ?: 0) until (offs[1].int ?: 0)
                else l.str?.let { t -> label.indexOf(t).let { i -> if (i < 0) IntRange.EMPTY else i until i + t.length } } ?: IntRange.EMPTY
            }
            Signature(label, markup(s["documentation"])?.let(client.config.markdown), params, s["activeParameter"].int)
        }
        if (sigs.isEmpty()) return null
        val active = (res["activeSignature"].int ?: 0).coerceIn(0, sigs.size - 1)
        return SignatureData(sigs, active, res["activeParameter"].int ?: 0, pos)
    }

    // ------------------------------------------------------------------ navigation --

    fun definition(): Boolean {
        if (!features.definition || !isOpen) return false
        host.scope.launch {
            sync()
            val v = version
            val res = client.request("textDocument/definition", buildJsonObject { put("textDocument", textDocument()); put("position", lspPos(target.state.selection.main.head).json()) }, quiet = false)
            val loc = locations(res).firstOrNull() ?: return@launch
            if (normalizeUri(loc.uri) == normalizeUri(uri)) {
                val at = mapFrom(v, loc.range.start) ?: return@launch
                target.dispatch(TransactionSpec(selection = EditorSelection.cursor(at), scrollIntoView = true, userEvent = "select.definition"))
            } else {
                client.config.onNavigate?.invoke(loc.uri, loc.range)
            }
        }
        return true
    }

    fun references(): Boolean {
        if (!features.references || !isOpen) return false
        host.scope.launch {
            sync()
            val v = version
            val res = client.request("textDocument/references", buildJsonObject {
                put("textDocument", textDocument())
                put("position", lspPos(target.state.selection.main.head).json())
                put("context", buildJsonObject { put("includeDeclaration", true) })
            }, quiet = false) ?: return@launch
            val doc = target.state.doc
            val refs = locations(res).map { loc ->
                if (normalizeUri(loc.uri) == normalizeUri(uri)) {
                    val a = mapFrom(v, loc.range.start, 1)
                    val b = mapFrom(v, loc.range.end, -1)
                    if (a == null || b == null) Reference(loc, null, null, loc.range.start.line, "")
                    else Reference(loc, a, maxOf(a, b), doc.lineIndexAt(a), doc.lineAt(a).text.trim())
                } else Reference(loc, null, null, loc.range.start.line, "")
            }
            if (refs.isNotEmpty()) target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setReferences.of(refs))))
        }
        return true
    }

    // ------------------------------------------------------------------ rename, format --

    fun renameWord(): IntRange? {
        if (!features.rename || !isOpen) return null
        val st = target.state
        val w = wordRange(st.doc, st.selection.main.head)
        return if (w.isEmpty()) null else w
    }

    fun rename(newName: String, at: Int) {
        host.scope.launch {
            sync()
            val v = version
            val res = client.request("textDocument/rename", buildJsonObject {
                put("textDocument", textDocument()); put("position", lspPos(at).json()); put("newName", newName)
            }, quiet = false) ?: return@launch
            for ((u, edits) in workspaceEdit(res)) {
                if (normalizeUri(u) == normalizeUri(uri)) applyEdits(edits, v, "edit.rename")
                else client.config.onWorkspaceEdit?.invoke(u, edits)
            }
        }
    }

    fun format(): Boolean {
        if (!features.formatting || !isOpen) return false
        host.scope.launch {
            sync()
            val v = version
            val unit = target.state.facet(indentUnitFacet)
            val res = client.request("textDocument/formatting", buildJsonObject {
                put("textDocument", textDocument())
                put("options", buildJsonObject { put("tabSize", if (unit == "\t") 4 else unit.length); put("insertSpaces", !unit.contains('\t')) })
            }, quiet = false) ?: return@launch
            val edits = res.arr.orEmpty().mapNotNull(::textEdit)
            if (edits.isNotEmpty()) applyEdits(edits, v, "edit.format", abortIfTouched = true)
        }
        return true
    }

    companion object {
        const val KEEP = 32
        const val MAX_ACTION_DIAGNOSTICS = 30
    }
}

/** One signature of signature help: its [label], docs, its parameters' ranges in the label. */
data class Signature(val label: String, val documentation: String?, val parameters: List<IntRange>, val activeParameter: Int? = null)

/** Signature help shown at [pos]: the signatures, the [active] one, the active parameter. */
data class SignatureData(val signatures: List<Signature>, val active: Int, val activeParameter: Int, val pos: Int)

/** One result of find references: its location, its range here (null: another document), line and text. */
data class Reference(val location: LspLocation, val from: Int?, val to: Int?, val line: Int, val text: String)

internal val JsonElement?.asPrimitive: JsonPrimitive? get() = this as? JsonPrimitive
