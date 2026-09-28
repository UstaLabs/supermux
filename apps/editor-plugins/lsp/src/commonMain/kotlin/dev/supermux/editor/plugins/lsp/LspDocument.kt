package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.compose.EditorAnnotations
import dev.supermux.editor.compose.HoverResult
import dev.supermux.editor.compose.ViewPluginHost
import dev.supermux.editor.compose.ViewPluginInstance
import dev.supermux.editor.compose.indentUnitFacet
import dev.supermux.editor.compose.tabSizeFacet
import dev.supermux.editor.core.AnnotationType
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

/** What applying a server's edits did. */
enum class ApplyResult {
    /** Every edit applied. */
    APPLIED,
    /** Some applied; others touched text the user changed since, and were dropped. */
    PARTIAL,
    /** Nothing applied: the edits' version is no longer kept, or (all-or-nothing) one was out of date. */
    FAILED,
}

/**
 * The client's open documents (CM6's `Workspace`): ONE server document per URI, however many views
 * show it. A view's plugin attaches to its URI's document ([LspDocument]); the first attach sends
 * `didOpen`, the last detach `didClose`. Every view of one document shows the same text: an edit in
 * one is applied to the others (as a remote edit) and sent to the server once.
 */
class LspWorkspace internal constructor(private val client: LspClient) {
    internal val docs = LinkedHashMap<String, LspDocument>()

    /** The URIs open in this client (a view shows each). */
    val openDocuments: List<String> get() = docs.keys.toList()

    /** How many views show [uri] (0: not open). */
    fun viewCount(uri: String): Int = document(uri)?.views?.size ?: 0

    /** The server's version of [uri]'s text (what the last `didOpen` / `didChange` said), or null. */
    fun version(uri: String): Int? = document(uri)?.takeIf { it.isOpen }?.version

    /** The text the server has for [uri], or null when it is not open there. */
    fun serverText(uri: String): String? = document(uri)?.synced?.toString()

    internal fun document(uri: String?): LspDocument? {
        if (uri == null) return null
        docs[uri]?.let { return it }
        val k = normalizeUri(uri)
        return docs.values.firstOrNull { normalizeUri(it.uri) == k }
    }

    internal fun attach(view: LspView, uri: String, languageId: String): LspDocument {
        val d = document(uri) ?: LspDocument(client, uri, languageId).also { docs[uri] = it }
        d.attach(view)
        return d
    }

    internal fun detach(view: LspView, d: LspDocument) {
        if (d.detach(view)) docs.remove(d.uri)
    }

    /** The view whose editor is [target] (a command's), or whose state is [state] (a source's). */
    internal fun viewFor(target: CommandTarget): LspView? = docs.values.firstNotNullOfOrNull { d -> d.views.firstOrNull { it.target === target } }
        ?: viewFor(target.state)

    internal fun viewFor(state: EditorState): LspView? =
        docs.values.firstNotNullOfOrNull { d -> d.views.firstOrNull { it.target.state === state } }
            ?: docs.values.firstNotNullOfOrNull { d -> d.views.firstOrNull { it.target.state.doc === state.doc } }
}

/**
 * One document of an [LspClient], shared by its views: the server's copy kept in step (`didOpen`,
 * batched incremental `didChange`, `didClose`) and the versions to read old responses in.
 *
 * **Sync.** [synced] is the text the server has (version [version]); [unsynced] the edits since. An
 * edit composes into [unsynced] and schedules ONE `didChange` [LspClientConfig.syncDelayMs] later;
 * every request calls [sync] first, and sync is SYNCHRONOUS: the `didChange` is queued, in order,
 * before the request's own message (the client's queue), so the server always has the text a request's
 * position is in. Incremental changes are sent back to front, each range in the synced text's
 * coordinates (CM6's `contentChangesFor`); full text to a full-sync server. The last [KEEP] versions'
 * texts and the edits between them are kept, so a response for an older version is read in THAT text
 * and mapped to the current one.
 */
internal class LspDocument(val client: LspClient, val uri: String, private val languageId: String) {
    val views = ArrayList<LspView>()
    private val features: ServerFeatures get() = client.features.value
    val enc: PositionEncoding get() = features.positionEncoding

    var version = 0; private set
    var synced: Rope? = null; private set
    private var unsynced: ChangeSet? = null
    private val texts = ArrayDeque<Pair<Int, Rope>>()
    private val steps = ArrayDeque<Pair<Int, ChangeSet>>()
    private var syncJob: Job? = null
    private var codeActionJob: Job? = null

    val isOpen: Boolean get() = synced != null

    /** The primary view (the first attached): its state's text is the document's. */
    val primary: LspView? get() = views.firstOrNull()

    fun hasView(t: CommandTarget) = views.any { it.target === t }

    fun viewFor(state: EditorState): LspView? = views.firstOrNull { it.target.state === state } ?: views.firstOrNull { it.target.state.doc === state.doc }

    fun attach(view: LspView) {
        val first = views.isEmpty()
        views += view
        if (first) { if (client.ready) open() }
        else {
            // Every view shows the document's text: a second view that differs is brought in line.
            val text = primary!!.target.state.doc
            val mine = view.target.state.doc
            if (mine != text) view.target.dispatch(TransactionSpec(
                changes = listOf(ChangeSpec(0, mine.length, text.toString())),
                annotations = listOf(forwarded.of(true), EditorAnnotations.remote.of(true)),
                userEvent = "remote",
            ))
            view.featuresChanged(features)
        }
    }

    /** True when that was the last view (the document closed). */
    fun detach(view: LspView): Boolean {
        views.remove(view)
        if (views.isNotEmpty()) return false
        close()
        return true
    }

    fun open() {
        val p = primary ?: return
        val doc = p.target.state.doc
        version++
        synced = doc
        unsynced = ChangeSet.empty(doc.length)
        texts.clear(); steps.clear()
        texts += version to doc
        client.notify("textDocument/didOpen", buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri); put("languageId", languageId); put("version", version); put("text", doc.toString()) })
        })
        for (v in views) v.featuresChanged(features)
    }

    fun connectionLost() {
        synced = null
        unsynced = null
        syncJob?.cancel(); codeActionJob?.cancel()
        for (v in views) v.connectionLost()
    }

    /** A view's own edit: to the server (batched) and to the other views. */
    fun localChange(from: LspView, changes: ChangeSet) {
        for (v in views) if (v !== from) {
            try {
                v.target.dispatch(TransactionSpec(changeSet = changes, annotations = listOf(forwarded.of(true), EditorAnnotations.remote.of(true)), userEvent = "remote"))
            } catch (e: IllegalArgumentException) {
                client.report(1, "a view of $uri was out of step and was not updated: ${e.message}")
            }
        }
        if (synced == null) return
        unsynced = try { unsynced?.compose(changes) } catch (e: IllegalArgumentException) {
            // Out of step (never expected): start over with the text as it is.
            client.report(1, "the LSP copy of $uri was out of step; reopening it")
            reopen(); return
        }
        scheduleSync()
    }

    private fun reopen() {
        client.notify("textDocument/didClose", buildJsonObject { put("textDocument", textDocument()) })
        open()
    }

    /** Queue the edits since the last sync now, in order (before a request). Never suspends. */
    fun sync() {
        syncJob?.cancel(); syncJob = null
        val prev = synced ?: return
        val cs = unsynced ?: return
        if (cs.isEmpty) return
        // The primary view's text IS unsynced applied to synced (every view edit composes in at once):
        // keep its instance, so a request's context (the view's state) is recognisably this text.
        val doc = primary?.target?.state?.doc?.takeIf { it.length == cs.lengthAfter } ?: cs.apply(prev)
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

    /**
     * The batch timer runs on the CLIENT's scope, not a view's: the view that typed may go (another
     * document shown) before it fires, and its edit must still reach the server within the delay.
     */
    private fun scheduleSync() {
        if (syncJob?.isActive == true) return
        syncJob = client.launch("an LSP sync") {
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
    fun changesSince(v: Int): ChangeSet? {
        val text = texts.lastOrNull { it.first == v }?.second ?: return null
        var cs = ChangeSet.empty(text.length)
        for ((sv, c) in steps) if (sv > v) cs = cs.compose(c)
        return unsynced?.let { cs.compose(it) }
    }

    fun textDocument() = buildJsonObject { put("uri", uri) }

    fun close() {
        syncJob?.cancel(); codeActionJob?.cancel()
        if (synced != null) {
            synced = null
            client.notify("textDocument/didClose", buildJsonObject { put("textDocument", textDocument()) })
        }
    }

    // ------------------------------------------------------------------ edits from the server --

    private fun touches(cs: ChangeSet, from: Int, to: Int): Boolean =
        cs.iterChanges().any { c -> c.fromA < to && c.toA > from || c.fromA == c.toA && c.fromA in from..to && from < to || c.fromA == from && c.toA == to }

    /**
     * [edits], in version [v]'s text, applied to the current document as ONE transaction with
     * [userEvent], through [via] (a view of this document; the primary one by default). An edit
     * whose text the user changed since is dropped ([PARTIAL]); with [allOrNothing] nothing is
     * applied then ([FAILED], CM6's rule for formatting; rename too). [FAILED] also when version [v]
     * is no longer kept.
     */
    fun applyEdits(edits: List<LspTextEdit>, v: Int, userEvent: String?, allOrNothing: Boolean = false, via: LspView? = null): ApplyResult {
        if (edits.isEmpty()) return ApplyResult.APPLIED
        val text = texts.lastOrNull { it.first == v }?.second ?: return ApplyResult.FAILED
        val since = changesSince(v) ?: return ApplyResult.FAILED
        val target = (via ?: primary)?.target ?: return ApplyResult.FAILED
        val specs = ArrayList<ChangeSpec>()
        var skipped = 0
        for (e in edits) {
            val a = Positions.fromLsp(text, e.range.start, enc)
            val b = Positions.fromLsp(text, e.range.end, enc).coerceAtLeast(a)
            if (!since.isEmpty && touches(since, a, b)) { if (allOrNothing) return ApplyResult.FAILED; skipped++; continue }
            val ma = since.mapPos(a, 1)
            val mb = maxOf(ma, since.mapPos(b, -1))
            specs += ChangeSpec(ma, mb, e.newText)
        }
        val sorted = specs.sortedWith(compareBy({ it.from }, { it.to }))
        val clean = ArrayList<ChangeSpec>()
        for (s in sorted) {
            val l = clean.lastOrNull()
            if (l != null && s.from < l.to) { if (allOrNothing) return ApplyResult.FAILED; skipped++; continue }
            clean += s
        }
        if (clean.isEmpty()) return ApplyResult.FAILED
        val st = target.state
        val cs = try { ChangeSet.of(st.doc.length, clean) } catch (e: IllegalArgumentException) { return ApplyResult.FAILED }
        target.dispatch(TransactionSpec(changeSet = cs, userEvent = userEvent))
        return if (skipped == 0) ApplyResult.APPLIED else ApplyResult.PARTIAL
    }

    // ------------------------------------------------------------------ diagnostics --

    private var diagnosticsToken = 0
    private var diagnosticIds = 0

    fun diagnostics(params: JsonElement?) {
        val v = params["version"].int
        if (v != null && v != version) return // for another version of the text: the next one comes
        if (!isOpen) return
        val raw = params["diagnostics"].arr.orEmpty()
        val list = raw.mapNotNull { d ->
            val r = range(d["range"]) ?: return@mapNotNull null
            val a = mapFrom(version, r.start, 1) ?: return@mapNotNull null
            val b = mapFrom(version, r.end, -1)?.coerceAtLeast(a) ?: return@mapNotNull null
            val sev = when (d["severity"].int) { 2 -> Severity.WARNING; 3 -> Severity.INFO; 4 -> Severity.HINT; else -> Severity.ERROR }
            val code = d["code"].let { it.str ?: it.int?.toString() }
            val source = listOfNotNull(d["source"].str, code).joinToString(" ").ifEmpty { null }
            (d as JsonObject) to Diagnostic(a, b, sev, d["message"].str.orEmpty(), source, id = "lsp${diagnosticIds++}")
        }
        val token = ++diagnosticsToken
        for (view in views) if (view.target.state.fieldOrNull(Lint.field) != null) view.target.dispatch(Lint.setDiagnostics(view.target.state, list.map { it.second }))
        codeActionJob?.cancel()
        if (!features.codeAction || list.isEmpty()) return
        codeActionJob = client.launch("code actions") { codeActions(list, token) }
    }

    /** Each diagnostic's code actions (the [MAX_ACTION_DIAGNOSTICS] nearest the cursor), attached to it BY ITS ID. */
    private suspend fun codeActions(list: List<Pair<JsonObject, Diagnostic>>, token: Int) {
        val head = primary?.target?.state?.selection?.main?.head ?: 0
        val v = version
        val asked = list.sortedBy { kotlin.math.abs(it.second.from - head) }.take(MAX_ACTION_DIAGNOSTICS)
        val found = coroutineScope {
            asked.map { (raw, d) ->
                async {
                    val res = client.request("textDocument/codeAction", buildJsonObject {
                        put("textDocument", textDocument())
                        put("range", raw["range"]!!)
                        put("context", buildJsonObject { put("diagnostics", buildJsonArray { add(raw) }) })
                    }) ?: return@async null
                    val actions = res.arr.orEmpty().mapNotNull { a -> action(a, v) }
                    if (actions.isEmpty()) null else d.id!! to actions
                }
            }.awaitAll().filterNotNull().toMap()
        }
        if (token != diagnosticsToken || found.isEmpty()) return
        for (view in views) {
            val st = view.target.state
            if (st.fieldOrNull(Lint.field) == null) continue
            val now = Lint.diagnostics(st)
            val updated = now.map { d -> found[d.id]?.let { d.copy(actions = it) } ?: d }
            view.target.dispatch(Lint.setDiagnostics(st, updated))
        }
    }

    private fun action(a: JsonElement, v: Int): DiagnosticAction? {
        val title = a["title"].str ?: return null
        if (a["disabled"] != null) return null
        return DiagnosticAction(title) { via, _, _ ->
            client.launch("the code action \"$title\"") {
                var act = a
                var at = v
                if (act["edit"] == null && act["command"].str == null && act["data"] != null && features.codeActionResolve) {
                    // Resolved against the text as it is NOW: sync first, and apply at that version.
                    sync()
                    at = version
                    act = client.request("codeAction/resolve", a) ?: a
                }
                val edit = act["edit"]
                val view = client.workspace.viewFor(via)
                if (edit != null) {
                    val ok = client.applyWorkspaceEdit(workspaceEdit(edit), "edit.codeAction", fallbackVersion = { d -> if (d === this@LspDocument) at else d.version }, via = view?.target)
                    if (!ok) client.report(2, "\"$title\" was not applied whole: the text changed since")
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

    companion object {
        const val KEEP = 32
        const val MAX_ACTION_DIAGNOSTICS = 30

        /** On an edit a document forwards to its other views: not sent to the server again. */
        val forwarded = AnnotationType<Boolean>("lsp.forwarded")
    }
}

/**
 * One view of an [LspDocument] (a view plugin instance): it hands its edits to the document and
 * runs the features for ITS editor: completion, hover, signature help, definition, references,
 * rename, format.
 */
internal class LspView(
    val client: LspClient,
    private val host: ViewPluginHost,
    uri: String,
    languageId: String,
) : ViewPluginInstance {
    val target: CommandTarget get() = host.target
    val scope get() = host.scope
    private val features: ServerFeatures get() = client.features.value
    val doc: LspDocument = client.workspace.attach(this, uri, languageId)
    private val enc: PositionEncoding get() = doc.enc
    private var signatureJob: Job? = null
    private var signatureDelay: Job? = null
    private var hoverIds = 0
    private var destroyed = false

    val isOpen: Boolean get() = doc.isOpen

    fun featuresChanged(f: ServerFeatures) {
        // After the dispatch that may be running now (a view plugin starts inside one).
        host.scope.launch { if (!destroyed) target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setFeatures.of(f)))) }
    }

    fun connectionLost() {
        signatureJob?.cancel(); signatureDelay?.cancel()
        if (LspPlugin.state(target.state).signature != null) target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setSignature.of(null))))
    }

    override fun update(tr: Transaction) {
        if (tr.docChanged && tr.annotation(LspDocument.forwarded) != true) doc.localChange(this, tr.changes)
        for (e in tr.effects) if (e.isOf(LspPlugin.requestSignature)) startSignature(1, null)
        followSignature(tr)
    }

    override fun destroy() {
        destroyed = true
        signatureJob?.cancel(); signatureDelay?.cancel()
        client.workspace.detach(this, doc)
    }

    private fun lspPos(offset: Int) = Positions.toLsp(target.state.doc, offset, enc)

    /** Launch a feature's work on this view's scope; a failure is reported, never thrown. */
    private fun run(what: String, block: suspend () -> Unit): Job = host.scope.launch {
        try { block() } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Throwable) { client.report(1, "$what failed: ${e.message ?: e}") }
    }

    // ------------------------------------------------------------------ completion --

    private fun wordRange(text: Rope, pos: Int): IntRange {
        val line = text.lineAt(pos)
        var a = pos; var b = pos
        while (a > line.from && isIdent(text.charAt(a - 1))) a--
        while (b < line.to && isIdent(text.charAt(b))) b++
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
        // Synchronously: the pending didChange, then this request with a position in that text.
        doc.sync()
        val text = ctx.state.doc
        val server = doc.synced
        if (server !== text && (server == null || server.length != text.length || server != text)) return null // the context is for an older text
        val res = client.request("textDocument/completion", buildJsonObject {
            put("textDocument", doc.textDocument())
            put("position", Positions.toLsp(text, ctx.pos, enc).json())
            put("context", context)
        }) ?: return null
        val enc = enc
        return withContext(client.parseContext) { completionResult(res, ctx, text, f, enc) }
    }

    internal suspend fun completionResult(res: JsonElement, ctx: CompletionContext, text: Rope, f: ServerFeatures, enc: PositionEncoding): CompletionResult? {
        val items = res.arr ?: res["items"].arr ?: return null
        if (items.isEmpty()) return null
        val incomplete = res["isIncomplete"].bool == true
        val defaults = res["itemDefaults"]
        val defaultFormat = defaults["insertTextFormat"].int
        // CM6's completionResultRange: the default edit range, else the first item's, else the word.
        val range = defaults["editRange"].let { r -> range(r) ?: range(r["insert"]) } ?: items[0]["textEdit"].let { t -> range(t["range"]) ?: range(t["insert"]) }
        val word = wordRange(text, ctx.pos)
        val from = range?.let { Positions.fromLsp(text, it.start, enc) } ?: word.first
        val to = range?.let { Positions.fromLsp(text, it.end, enc) } ?: ctx.pos
        val options = ArrayList<Completion>(items.size)
        for ((k, item) in items.withIndex()) {
            // The browser: give the thread back every 1,000 items (5,000 map in ~3 ms there).
            if (lspSliceBigMessages && k > 0 && k % 1000 == 0) lspGiveBack()
            val label = item["label"].str ?: continue
            val insert = item["textEdit"]["newText"].str ?: item["textEditText"].str ?: item["insertText"].str ?: label
            val snippet = (item["insertTextFormat"].int ?: defaultFormat) == 2
            val extra = item["additionalTextEdits"].arr?.mapNotNull { e ->
                val te = textEdit(e) ?: return@mapNotNull null
                val a = Positions.fromLsp(text, te.range.start, enc)
                ChangeSpec(a, Positions.fromLsp(text, te.range.end, enc).coerceAtLeast(a), te.newText)
            }.orEmpty()
            val apply: CompletionApply? = when {
                extra.isNotEmpty() -> CompletionApply.WithEdits(insert, extra, snippet)
                snippet -> CompletionApply.Template(Snippet.fromLsp(insert))
                insert != (item["filterText"].str ?: label) -> CompletionApply.Text(insert)
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
        doc.sync()
        val v = doc.version
        val res = client.request("textDocument/hover", buildJsonObject { put("textDocument", doc.textDocument()); put("position", Positions.toLsp(st.doc, pos, enc).json()) }) ?: return null
        if (target.state.doc !== st.doc) return null // stale: the text changed while the server worked
        val text = markup(res["contents"])?.let(client.config.markdown)?.takeIf { it.isNotBlank() } ?: return null
        val r = range(res["range"])
        val w = wordRange(st.doc, pos)
        val from = r?.let { doc.mapFrom(v, it.start, 1) } ?: w.first
        val to = r?.let { doc.mapFrom(v, it.end, -1) } ?: (w.last + 1)
        val id = "h${hoverIds++}"
        client.putHover(id, text)
        return HoverResult(from, maxOf(from, to), WidgetKey(LspPlugin.HOVER_TOOLTIP, id), above = true)
    }

    // ------------------------------------------------------------------ signature help --

    private fun followSignature(tr: Transaction) {
        if (!features.signatureHelp || !isOpen || tr.annotation(LspDocument.forwarded) == true) return
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
        signatureJob = run("signature help") {
            doc.sync()
            val st = target.state
            val pos = st.selection.main.head
            val res = client.request("textDocument/signatureHelp", buildJsonObject {
                put("textDocument", doc.textDocument())
                put("position", Positions.toLsp(st.doc, pos, enc).json())
                put("context", buildJsonObject {
                    put("triggerKind", kind)
                    trigger?.let { put("triggerCharacter", it.toString()) }
                    put("isRetrigger", retrigger)
                })
            }, quiet = kind != 1)
            if (target.state.selection != st.selection) return@run // the cursor moved on: a newer one comes
            val data = res?.let { parseSignatures(it, pos) }
            val cur = LspPlugin.state(target.state).signature
            if (data == null) { if (cur != null) target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setSignature.of(null)))); return@run }
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
        run("go to definition") {
            doc.sync()
            val v = doc.version
            val res = client.request("textDocument/definition", buildJsonObject { put("textDocument", doc.textDocument()); put("position", lspPos(target.state.selection.main.head).json()) }, quiet = false)
            val loc = locations(res).firstOrNull() ?: return@run
            if (normalizeUri(loc.uri) == normalizeUri(doc.uri)) {
                val at = doc.mapFrom(v, loc.range.start) ?: return@run
                target.dispatch(TransactionSpec(selection = EditorSelection.cursor(at), scrollIntoView = true, userEvent = "select.definition"))
            } else {
                client.config.onNavigate?.invoke(loc.uri, loc.range)
            }
        }
        return true
    }

    fun references(): Boolean {
        if (!features.references || !isOpen) return false
        run("find references") {
            doc.sync()
            val v = doc.version
            val res = client.request("textDocument/references", buildJsonObject {
                put("textDocument", doc.textDocument())
                put("position", lspPos(target.state.selection.main.head).json())
                put("context", buildJsonObject { put("includeDeclaration", true) })
            }, quiet = false) ?: return@run
            val text = target.state.doc
            val refs = locations(res).map { loc ->
                if (normalizeUri(loc.uri) == normalizeUri(doc.uri)) {
                    val a = doc.mapFrom(v, loc.range.start, 1)
                    val b = doc.mapFrom(v, loc.range.end, -1)
                    if (a == null || b == null) Reference(loc, null, null, loc.range.start.line, "")
                    else Reference(loc, a, maxOf(a, b), text.lineIndexAt(a), text.lineAt(a).text.trim())
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

    /**
     * Rename the symbol at [at] to [newName], ALL OR NOTHING: when any occurrence here was edited
     * since the request (or its version is gone), nothing is applied and the prompt says so; other
     * documents open in this client apply through their own views, the rest go to `onWorkspaceEdit`.
     */
    fun rename(newName: String, at: Int) {
        run("rename") {
            doc.sync()
            val v = doc.version
            val res = client.request("textDocument/rename", buildJsonObject {
                put("textDocument", doc.textDocument()); put("position", lspPos(at).json()); put("newName", newName)
            }, quiet = false)
            if (res == null) { renameFailed("The server did not rename it"); return@run }
            val edits = workspaceEdit(res)
            // Check this document first: a rename that is out of date here applies nowhere.
            val here = edits.entries.firstOrNull { normalizeUri(it.key) == normalizeUri(doc.uri) }
            if (here != null) {
                val r = doc.applyEdits(here.value.edits, here.value.version ?: v, "edit.rename", allOrNothing = true, via = this)
                if (r != ApplyResult.APPLIED) { renameFailed("Rename out of date — try again"); return@run }
            }
            val others = edits.filterKeys { normalizeUri(it) != normalizeUri(doc.uri) }
            val ok = client.applyWorkspaceEdit(others, "edit.rename")
            if (!ok) renameFailed("Renamed here; some other files were not changed")
            else if (LspPlugin.state(target.state).rename != null) target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setRename.of(null))))
        }
    }

    private fun renameFailed(message: String) {
        val p = LspPlugin.state(target.state).rename
        if (p != null) target.dispatch(TransactionSpec(effects = listOf(LspPlugin.setRename.of(p.copy(error = message, pending = false)))))
        client.report(2, message)
    }

    fun format(): Boolean {
        if (!features.formatting || !isOpen) return false
        run("format") {
            doc.sync()
            val v = doc.version
            val st = target.state
            val unit = st.facet(indentUnitFacet)
            val res = client.request("textDocument/formatting", buildJsonObject {
                put("textDocument", doc.textDocument())
                put("options", buildJsonObject { put("tabSize", st.facet(tabSizeFacet)); put("insertSpaces", !unit.contains('\t')) })
            }, quiet = false) ?: return@run
            val edits = res.arr.orEmpty().mapNotNull(::textEdit)
            if (edits.isNotEmpty() && doc.applyEdits(edits, v, "edit.format", allOrNothing = true, via = this) != ApplyResult.APPLIED) {
                client.report(2, "Format out of date — try again")
            }
        }
        return true
    }
}

/** One signature of signature help: its [label], docs, its parameters' ranges in the label. */
data class Signature(val label: String, val documentation: String?, val parameters: List<IntRange>, val activeParameter: Int? = null)

/** Signature help shown at [pos]: the signatures, the [active] one, the active parameter. */
data class SignatureData(val signatures: List<Signature>, val active: Int, val activeParameter: Int, val pos: Int)

/** One result of find references: its location, its range here (null: another document), line and text. */
data class Reference(val location: LspLocation, val from: Int?, val to: Int?, val line: Int, val text: String)

internal val JsonElement?.asPrimitive: JsonPrimitive? get() = this as? JsonPrimitive
