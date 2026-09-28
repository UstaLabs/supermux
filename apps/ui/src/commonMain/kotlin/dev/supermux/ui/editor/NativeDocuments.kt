// The native editor's side of the document layer (M5 A2): ONE EditorView per open Document, kept
// next to the DocumentStore and outliving every pane. A pane only BORROWS the view ([acquire] /
// [release]); PaneHost composes only the active tab, so a view that lived in the pane would be
// rebuilt on every tab switch and its plugins stopped: the LSP plugin would send didClose/didOpen
// each time and the syntax worker would reparse (editor-plugins/lsp/README.md "M5 lifetime").
//
// What the document keeps is the rope itself: Document.content is DERIVED from the view (no String
// copied per keystroke), and dirty compares the rope with the one last loaded or saved.
package dev.supermux.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.editor.compose.EditorAnnotations
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.AnnotationType
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.Compartment
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapOf
import dev.supermux.editor.plugins.autocomplete.autocompletion
import dev.supermux.editor.plugins.basics.basics
import dev.supermux.editor.plugins.fold.fold
import dev.supermux.editor.plugins.highlight.SyntaxHost
import dev.supermux.editor.plugins.highlight.highlight
import dev.supermux.editor.plugins.history.history
import dev.supermux.editor.plugins.lint.lint
import dev.supermux.editor.plugins.search.search
import dev.supermux.editor.plugins.view.EditorSettings
import dev.supermux.editor.plugins.view.ViewSettings
import dev.supermux.editor.plugins.view.viewSettings
import dev.supermux.editor.syntax.SyntaxBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What a [DocumentStore] needs to give its documents native views: the UI scope their plugins and
 * syntax hosts run on (the store owner's; cancelled when it leaves), the syntax backend, and the
 * editor settings every view starts with ([DocumentStore.applySettings] changes them live).
 * [extraExtensions] is per document (tests add probes).
 */
class NativeEditorEnv(
    val scope: CoroutineScope,
    val syntax: EditorSyntax = EditorSyntax.None,
    settings: EditorSettings = EditorSettings(),
    val extraExtensions: (Document) -> Extension = { extensionOf() },
) {
    var settings: EditorSettings = settings
        internal set
}

/**
 * The native editor of one [Document]: its [primary] view (the one every pane borrows first, the one
 * the LSP client talks to), mirrors for a second pane on the same path, and their syntax hosts.
 * Made by [DocumentStore.nativeFor]; lives until the document closes ([dispose]).
 */
class NativeDocument internal constructor(
    val document: Document,
    private val env: NativeEditorEnv,
    private val onSave: () -> Unit,
) {
    /** The file's language (null: plain text), from its name. */
    val language: String? = env.syntax.languageFor(document.path)

    val primary: EditorView = EditorView(EditorState.create(document.content, extensions = extensions(withLsp = true)))

    private var savedDoc: Rope by mutableStateOf(primary.state.doc)

    // Dirty is read by every tab chip on every recomposition: remember the answer per rope, so a
    // cursor move (same rope) costs nothing and only a same-length edit compares the texts.
    private var checkedDoc: Rope? = null
    private var checkedSaved: Rope? = null
    private var checkedDirty = false

    /** The text differs from the one last loaded or saved (an undo back to it is clean again). */
    val isDirty: Boolean
        get() {
            val doc = primary.state.doc
            val saved = savedDoc
            if (doc === checkedDoc && saved === checkedSaved) return checkedDirty
            val dirty = doc !== saved && (doc.length != saved.length || doc != saved)
            checkedDoc = doc; checkedSaved = saved; checkedDirty = dirty
            return dirty
        }

    /** True once [dispose]d (the document closed). */
    var disposed: Boolean = false
        private set

    private class Mirror(val view: EditorView, val removeListener: () -> Unit, var stopPlugins: (() -> Unit)?, var syntax: SyntaxHost?)

    private val mirrors = ArrayList<Mirror>()
    private var primaryBorrowed = false
    private var stopPrimaryPlugins: (() -> Unit)? = null
    private var primarySyntax: SyntaxHost? = null
    private val removePrimaryListener = primary.addListener { tr -> if (tr.docChanged) mirror(primary, tr) }

    /** Start the primary view's plugins and syntax host (idempotent; from an effect, never mid-composition). */
    fun start() {
        if (disposed || stopPrimaryPlugins != null) return
        stopPrimaryPlugins = primary.startPlugins(env.scope)
        withBackend { b -> if (primarySyntax == null) primarySyntax = syntaxHost(primary, b) }
    }

    /**
     * A view for one pane: the primary while no other pane shows it, else a new mirror view kept in
     * step with the others (edits reach them as `remote` transactions, so each view's history
     * undoes only its own edits; the LSP client sees the primary's). Give it back with [release].
     */
    fun acquire(): EditorView {
        check(!disposed) { "${document.path}: the document was closed" }
        start()
        if (!primaryBorrowed) {
            primaryBorrowed = true
            return primary
        }
        val view = EditorView(EditorState.create(primary.state.doc.toString(), extensions = extensions(withLsp = false)))
        lateinit var m: Mirror
        val remove = view.addListener { tr -> if (tr.docChanged) mirror(view, tr) }
        m = Mirror(view, remove, view.startPlugins(env.scope), null)
        mirrors += m
        withBackend { b -> if (m in mirrors && m.syntax == null) m.syntax = syntaxHost(view, b) }
        return view
    }

    /** A pane stopped showing [view]: the primary stays (with its plugins); a mirror is dropped. */
    fun release(view: EditorView) {
        if (view === primary) { primaryBorrowed = false; return }
        val m = mirrors.firstOrNull { it.view === view } ?: return
        mirrors -= m
        m.close()
    }

    /** Every view of this document now (the primary first). */
    val views: List<EditorView> get() = listOf(primary) + mirrors.map { it.view }

    /** The current text (one copy of the rope: for a save or the markdown preview, never per keystroke). */
    fun text(): String = primary.state.doc.toString()

    /** The text now counts as saved ([rope]: what was written). */
    fun markSaved(rope: Rope) { savedDoc = rope }

    /**
     * The file changed on disk and the user reloaded: the new [text] replaces the document in ONE
     * transaction with userEvent `disk` (never an undo step; folds and history map through it), as
     * the smallest change that turns one into the other, so the caret stays near where it was.
     */
    fun replaceFromDisk(text: String) {
        replace(text, "disk")
        markSaved(primary.state.doc)
    }

    /** A host-set text (the old engine path's `DocumentStore.update`): one programmatic change. */
    fun replaceText(text: String) = replace(text, null)

    private fun replace(text: String, userEvent: String?) {
        val change = minimalChange(primary.state.doc.toString(), text) ?: return
        primary.dispatch(TransactionSpec(changes = listOf(change), userEvent = userEvent))
    }

    /** New settings for every view (the settings screen, a zoom persisted by another editor). */
    fun applySettings(settings: EditorSettings) {
        for (v in views) ViewSettings.apply(v, settings)
    }

    /** The document closed: stop every view's plugins (the LSP plugin sends didClose) and syntax host. */
    fun dispose() {
        if (disposed) return
        disposed = true
        removePrimaryListener()
        for (m in mirrors) m.close()
        mirrors.clear()
        stopPrimaryPlugins?.invoke()
        stopPrimaryPlugins = null
        primarySyntax?.close()
        primarySyntax = null
    }

    private fun Mirror.close() {
        removeListener()
        stopPlugins?.invoke()
        stopPlugins = null
        syntax?.close()
        syntax = null
    }

    /** [source]'s edit, applied to every other view of this document (they hold the same text). */
    private fun mirror(source: EditorView, tr: dev.supermux.editor.core.Transaction) {
        if (tr.annotation(mirrored) == true) return
        for (v in views) {
            if (v === source) continue
            v.dispatch(TransactionSpec(
                changeSet = tr.changes,
                userEvent = "remote",
                annotations = listOf(mirrored.of(true), EditorAnnotations.remote.of(true)),
            ))
        }
    }

    private fun withBackend(then: (SyntaxBackend) -> Unit) {
        if (language == null) return
        env.syntax.loaded?.let { then(it); return }
        env.scope.launch {
            val b = env.syntax.backend() ?: return@launch
            if (!disposed) then(b)
        }
    }

    private fun syntaxHost(view: EditorView, backend: SyntaxBackend): SyntaxHost? = runCatching {
        SyntaxHost(view, backend, env.syntax.registry, env.scope).also { host ->
            env.scope.launch {
                host.precompile()
                host.start()
            }
        }
    }.onFailure { println("[editor] ${document.path}: no syntax host: $it") }.getOrNull()

    private fun extensions(withLsp: Boolean): Extension = extensionOf(
        // The host's save (spec §7.1 puts Mod-S in basics; basics has none): above every plugin.
        Prec.highest(keymapOf(KeyBinding("Mod-s", Command { onSave(); true }))),
        highlight(language),
        basics(),
        history(),
        fold(),
        viewSettings(env.settings),
        search(),
        autocompletion(),
        lint(),
        // The LSP client's plugin goes here once the broker says a server is ready (M5 A4).
        if (withLsp) lspSlot.of(extensionOf()) else extensionOf(),
        env.extraExtensions(document),
    )

    companion object {
        /** Where the LSP client's plugin for this document goes (reconfigured when it connects). */
        val lspSlot: Compartment = Compartment("ui.lsp")

        /** On a transaction that mirrors another view's edit: not mirrored again. */
        private val mirrored: AnnotationType<Boolean> = AnnotationType("ui.mirrored")
    }
}

/** The smallest single change from [old] to [new] (common prefix and suffix kept), or null when equal. */
internal fun minimalChange(old: String, new: String): ChangeSpec? {
    if (old == new) return null
    val max = minOf(old.length, new.length)
    var p = 0
    while (p < max && old[p] == new[p]) p++
    // Never split a surrogate pair.
    if (p > 0 && p < max && old[p - 1].isHighSurrogate()) p--
    var s = 0
    while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
    if (s > 0 && s < max - p && old[old.length - s].isLowSurrogate()) s--
    return ChangeSpec(p, old.length - s, new.substring(p, new.length - s))
}
