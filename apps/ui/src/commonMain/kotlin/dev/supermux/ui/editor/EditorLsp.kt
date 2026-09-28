// Code intelligence for the native editor (M5 A4): ONE LspClient per (session, server), shared by
// every document of the store that server handles, over the broker's LSP channel
// (BrokerLspTransport). A document joins its server's client when a pane showing it knows the
// session ([LspHub.attach]); it leaves when it closes (its view's plugins stop: didClose).
package dev.supermux.ui.editor

import dev.supermux.editor.compose.EditorAnnotations
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.plugins.lsp.LspClient
import dev.supermux.editor.plugins.lsp.LspClientConfig
import dev.supermux.editor.plugins.lsp.LspTextEdit
import dev.supermux.editor.plugins.lsp.Positions
import dev.supermux.editor.plugins.lsp.PositionEncoding
import dev.supermux.editor.plugins.lsp.registerWidgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a pane knows about code intelligence for its documents: the session, its workdir, the broker bridge. */
class LspLink(val sessionId: String, val workdir: String, val bridge: LspBridge)

/**
 * The LSP clients of one [DocumentStore] (its native editor's): one per (session, serverId).
 * [onNavigate] opens a workdir-relative path at a 1-based line (a definition or reference in
 * another file); the pane showing the store sets it.
 */
class LspHub internal constructor(private val store: DocumentStore, private val scope: CoroutineScope, private val parseOnWorker: Boolean = true) {
    private class Entry(val transport: BrokerLspTransport, val client: LspClient, val bridge: LspBridge)

    private val entries = LinkedHashMap<Pair<String, String>, Entry>()

    var onNavigate: (path: String, line: Int) -> Unit = { _, _ -> }

    /** The client serving (session, server), if one was made (tests, a status line). */
    fun client(sessionId: String, serverId: String): LspClient? = entries[sessionId to serverId]?.client

    /** The transport of (session, server), if one was made. */
    fun transport(sessionId: String, serverId: String): BrokerLspTransport? = entries[sessionId to serverId]?.transport

    /**
     * Join [native]'s document to its language server's client for [link]'s session: ask the broker
     * which server handles the path (its status query), open it, and put the client's plugin in the
     * document's primary view. A document already served for that session only makes sure its
     * server is up; another session's is detached first. Nothing when no server is ready.
     */
    suspend fun attach(native: NativeDocument, link: LspLink) {
        if (native.disposed || link.workdir.isEmpty()) return
        val current = native.lspKey
        if (current != null) {
            if (current.first == link.sessionId) { entries[current]?.transport?.ensureConnected(); return }
            native.detachLsp()
        }
        val status = link.bridge.queryStatus(native.document.path)
        val serverId = status.serverId
        if (!status.supported || serverId == null || status.state != "ready") {
            println("[lsp] '${native.document.path}' not ready for LSP (state=${status.state}, supported=${status.supported})")
            return
        }
        if (native.disposed || native.lspKey != null) return
        val key = link.sessionId to serverId
        val entry = entries.getOrPut(key) { newEntry(link, serverId) }
        entry.transport.ensureConnected()
        val uri = pathToUri(joinPath(link.workdir, native.document.path))
        native.attachLsp(key, entry.client.plugin(uri, status.languageId ?: native.language ?: "plaintext")) { registry ->
            entry.client.registerWidgets(registry)
        }
    }

    /** The store's owner goes: every client stops (the documents' views close their documents first). */
    fun close() {
        for (e in entries.values) {
            // shutdown + exit while the scope still runs (UNDISPATCHED: the owner's scope is about to be
            // cancelled; the client closes inside NonCancellable), then lsp_close so the broker stops
            // the process even if the pipe never carried them.
            runCatching { scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { e.client.close() } }
            e.transport.close()
            e.bridge.close(e.transport.serverId)
        }
        entries.clear()
    }

    private fun newEntry(link: LspLink, serverId: String): Entry {
        val transport = BrokerLspTransport(link.bridge, serverId, scope)
        lateinit var client: LspClient
        client = LspClient(
            transport,
            scope,
            LspClientConfig(
                rootUri = dirUri(link.workdir),
                onNavigate = { uri, range ->
                    val path = uriToWorkdirPath(uri, link.workdir)
                    if (path == null) println("[lsp] $uri is outside the workdir: not opened")
                    else onNavigate(path, range.start.line + 1)
                },
                onWorkspaceEdit = { uri, edits -> applyElsewhere(link.workdir, uri, edits, client.features.value.positionEncoding) },
                onMessage = { type, message -> println("[lsp] $serverId (${link.sessionId}) ${if (type == 1) "error" else "message"}: $message") },
                parseOnWorker = parseOnWorker,
            ),
        )
        transport.start()
        return Entry(transport, client, link.bridge)
    }

    /**
     * A rename's, a code action's or the server's edit to a document the client does not have open:
     * open in this store but served by no client (a mirror, another session) → through its view;
     * not open → read, edited, written back with its own line endings. True when it will apply (a
     * disk write that then fails is reported, as nothing can undo a half-applied rename anyway).
     */
    internal fun applyElsewhere(workdir: String, uri: String, edits: List<LspTextEdit>, encoding: PositionEncoding): Boolean {
        val path = uriToWorkdirPath(uri, workdir) ?: return false
        val doc = store.get(path)
        val native = doc?.let { store.nativeFor(it) }
        if (native != null) {
            val view = native.primary
            val changes = lspChanges(view.state.doc, edits, encoding) ?: return false
            // A user-level edit (a rename, a code action): recorded, one undo step in that document.
            // The callback does not say which kind it was, so one name for all of them.
            // A host edit: applied even if the last pane to show the view left it read-only.
            val before = view.state.doc
            view.dispatch(TransactionSpec(changeSet = changes, userEvent = "edit.workspace", annotations = listOf(EditorAnnotations.hostEdit.of(true))))
            return changes.isEmpty || view.state.doc !== before
        }
        if (doc != null) {
            val changes = lspChanges(Rope.of(doc.content), edits, encoding) ?: return false
            doc.content = changes.apply(doc.content)
            return true
        }
        scope.launch {
            val raw = store.readFile(path).getOrElse { println("[lsp] $path: not edited (${it.message})"); return@launch }
            val loaded = LineEndings.load(raw)
            val changes = lspChanges(Rope.of(loaded.text), edits, encoding) ?: return@launch
            if (!store.writeFile(path, LineEndings.save(changes.apply(loaded.text), loaded.crlf))) println("[lsp] $path: the edit could not be written")
        }
        return true
    }
}

/** [edits] (LSP ranges in [encoding], all against [doc]) as one change set, or null when two overlap. */
internal fun lspChanges(doc: Rope, edits: List<LspTextEdit>, encoding: PositionEncoding): ChangeSet? {
    val specs = edits.map { e ->
        val from = Positions.fromLsp(doc, e.range.start, encoding)
        val to = Positions.fromLsp(doc, e.range.end, encoding).coerceAtLeast(from)
        ChangeSpec(from, to, e.newText.replace("\r\n", "\n"))
    }.sortedWith(compareBy({ it.from }, { it.to }))
    for (i in 1 until specs.size) if (specs[i].from < specs[i - 1].to) return null
    return ChangeSet.of(doc.length, specs)
}

/** A `file://` URI under [workdir] as a workdir-relative path (percent-decoded), or null outside it. */
fun uriToWorkdirPath(uri: String, workdir: String): String? {
    val abs = decodeFileUri(uri) ?: return null
    val root = workdir.removeSuffix("/") + "/"
    // clangd and friends canonicalise (/private/var for /var): compare loosely on that prefix.
    val candidates = listOf(root, "/private$root")
    for (r in candidates) if (abs.startsWith(r)) return abs.removePrefix(r).ifEmpty { null }
    return null
}

private fun decodeFileUri(uri: String): String? {
    if (!uri.startsWith("file://")) return null
    val rest = uri.removePrefix("file://").let { if (it.startsWith("localhost/")) it.removePrefix("localhost") else it }
    // Percent-escapes are UTF-8 bytes; everything else is text as it is (runs, so a surrogate pair stays whole).
    val out = StringBuilder()
    var i = 0
    while (i < rest.length) {
        if (rest[i] == '%') {
            val bytes = ArrayList<Byte>()
            while (i + 2 < rest.length && rest[i] == '%') {
                val v = rest.substring(i + 1, i + 3).toIntOrNull(16) ?: break
                bytes += v.toByte()
                i += 3
            }
            if (bytes.isEmpty()) { out.append('%'); i++ } else out.append(bytes.toByteArray().decodeToString())
            continue
        }
        val next = rest.indexOf('%', i).let { if (it < 0) rest.length else it }
        out.append(rest, i, next)
        i = next
    }
    return out.toString()
}
