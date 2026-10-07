package dev.supermux.editor.plugins.lsp

import dev.supermux.editor.compose.Hover
import dev.supermux.editor.compose.ModClickHandler
import dev.supermux.editor.compose.ViewPlugin
import dev.supermux.editor.compose.hoverTooltip
import dev.supermux.editor.compose.modClickFacet
import dev.supermux.editor.compose.viewPluginsFacet
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.Panel
import dev.supermux.editor.core.Prec
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Tooltip
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapOf
import dev.supermux.editor.core.panelsFacet
import dev.supermux.editor.core.tooltipsFacet
import dev.supermux.editor.plugins.autocomplete.CompletionSource
import dev.supermux.editor.plugins.autocomplete.completionSourcesFacet
import dev.supermux.editor.plugins.autocomplete.completionTriggersFacet

/**
 * The rename prompt: the word it renames ([from], [to]), the focus requests the panel follows, a
 * rename in flight ([pending]) and why the last one did not apply ([error]).
 */
data class RenamePrompt(val word: String, val from: Int, val to: Int, val focus: Int, val pending: Boolean = false, val error: String? = null)

/**
 * The LSP plugin's per-view state: what the server can do ([features], for the completion triggers),
 * the signature help shown, the references panel's results, the rename prompt.
 */
data class LspViewState(
    val features: ServerFeatures = ServerFeatures(),
    val signature: SignatureData? = null,
    val references: List<Reference>? = null,
    val selectedReference: Int = -1,
    val rename: RenamePrompt? = null,
)

/**
 * The LSP client's editor side ([LspClient.plugin] builds it): sync and the features, CM6's
 * `@codemirror/lsp-client` keys:
 *
 * | key | command | |
 * |---|---|---|
 * | `F12` | [jumpToDefinition] | same document: the cursor moves there (a fold opens); another: `onNavigate` |
 * | Mod-click | [jumpToDefinition] | at the click ([modClickFacet]); Mod-hover underlines the word |
 * | `Shift-F12` | [findReferences] | the `lsp-references` panel; `Escape` closes it |
 * | `F2` | [renameSymbol] | the `lsp-rename` prompt, then the server's edits (`edit.rename`) |
 * | `Shift-Alt-f` | [formatDocument] | the server's edits (`edit.format`) |
 * | `Mod-Shift-Space` | [showSignatureHelp] | also shown on the server's trigger characters (`(`, `,`) |
 * | `Mod-Shift-ArrowUp` / `Mod-Shift-ArrowDown` | previous / next signature | |
 *
 * Hover (`tooltip:lsp-hover`) is a [hoverTooltip] (mouse); on touch, [Hover.showHover].
 */
object LspPlugin {
    const val HOVER_ID = "lsp"
    const val HOVER_TOOLTIP = "tooltip:lsp-hover"
    const val SIGNATURE_TOOLTIP = "tooltip:lsp-signature"
    const val REFERENCES_PANEL = "lsp-references"
    const val RENAME_PANEL = "lsp-rename"

    val setFeatures: StateEffectType<ServerFeatures> = StateEffectType("lsp.features")
    val setSignature: StateEffectType<SignatureData?> = StateEffectType("lsp.signature") { v, c -> v?.copy(pos = c.mapPos(v.pos, 1)) }
    val setReferences: StateEffectType<List<Reference>?> = StateEffectType("lsp.references")
    val selectReference: StateEffectType<Int> = StateEffectType("lsp.references.select")
    val setRename: StateEffectType<RenamePrompt?> = StateEffectType("lsp.rename")
    internal val requestSignature: StateEffectType<Unit> = StateEffectType("lsp.signature.request")

    val field: StateField<LspViewState> = StateField(
        "lsp",
        { LspViewState() },
        { v, tr ->
            var s = v
            if (tr.docChanged) {
                s = s.copy(
                    signature = s.signature?.let { it.copy(pos = tr.changes.mapPos(it.pos, 1)) },
                    references = s.references?.map { r -> if (r.from == null) r else r.copy(from = tr.changes.mapPos(r.from, 1), to = tr.changes.mapPos(r.to!!, -1).coerceAtLeast(tr.changes.mapPos(r.from, 1))) },
                    rename = s.rename?.let { it.copy(from = tr.changes.mapPos(it.from, -1), to = tr.changes.mapPos(it.to, 1)) },
                )
            }
            for (e in tr.effects) {
                e.valueIf(setFeatures)?.let { s = s.copy(features = it) }
                if (e.isOf(setSignature)) s = s.copy(signature = e.valueIf(setSignature))
                if (e.isOf(setReferences)) s = s.copy(references = e.valueIf(setReferences), selectedReference = -1)
                e.valueIf(selectReference)?.let { s = s.copy(selectedReference = it) }
                if (e.isOf(setRename)) s = s.copy(rename = e.valueIf(setRename))
            }
            s
        },
    )

    fun state(st: EditorState): LspViewState = st.fieldOrNull(field) ?: LspViewState()

    /** The LSP view of the editor a command runs in (never another view of the same URI). */
    private fun view(client: LspClient, t: CommandTarget): LspView? = client.workspace.viewFor(t)

    fun jumpToDefinition(client: LspClient) = Command { t -> view(client, t)?.definition() == true }
    fun findReferences(client: LspClient) = Command { t -> view(client, t)?.references() == true }
    fun formatDocument(client: LspClient) = Command { t -> view(client, t)?.format() == true }

    /** CM6's `renameSymbol`: the prompt with the word at the cursor (F2). False without a word or rename support. */
    fun renameSymbol(client: LspClient) = Command { t ->
        val d = view(client, t) ?: return@Command false
        val w = d.renameWord() ?: return@Command false
        val cur = state(t.state).rename
        t.dispatch(TransactionSpec(effects = listOf(setRename.of(RenamePrompt(t.state.sliceDoc(w.first, w.last + 1), w.first, w.last + 1, (cur?.focus ?: 0) + 1)))))
        true
    }

    /**
     * Rename the prompt's word to [newName] (the prompt's Enter). The prompt stays, `pending`, until
     * the rename applied (then it closes) or did not (then it shows why: "Rename out of date — try again").
     */
    fun submitRename(client: LspClient, t: CommandTarget, newName: String) {
        val p = state(t.state).rename ?: return
        if (newName.isBlank() || newName == p.word) { t.dispatch(TransactionSpec(effects = listOf(setRename.of(null)))); return }
        val v = view(client, t) ?: return
        t.dispatch(TransactionSpec(effects = listOf(setRename.of(p.copy(pending = true, error = null)))))
        v.rename(newName, p.from)
    }

    val closeRename: Command = Command { t ->
        if (state(t.state).rename == null) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(setRename.of(null))))
        true
    }

    val closeReferencePanel: Command = Command { t ->
        if (state(t.state).references == null) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(setReferences.of(null))))
        true
    }

    /** Go to reference [i] of the panel: this document's is selected and scrolled to; another's goes to `onNavigate`. */
    fun goToReference(client: LspClient, t: CommandTarget, i: Int) {
        val r = state(t.state).references?.getOrNull(i) ?: return
        t.dispatch(TransactionSpec(effects = listOf(selectReference.of(i))))
        if (r.from != null && r.to != null) {
            t.dispatch(TransactionSpec(selection = EditorSelection.single(r.from, r.to), scrollIntoView = true, userEvent = "select.reference"))
        } else {
            client.config.onNavigate?.invoke(r.location.uri, r.location.range)
        }
    }

    val showSignatureHelp: Command = Command { t ->
        if (t.state.fieldOrNull(field) == null || !state(t.state).features.signatureHelp) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(requestSignature.of(Unit))))
        true
    }

    private fun moveSignature(dir: Int) = Command { t ->
        val s = state(t.state).signature ?: return@Command false
        val n = (s.active + dir).coerceIn(0, s.signatures.size - 1)
        if (n != s.active) t.dispatch(TransactionSpec(effects = listOf(setSignature.of(s.copy(active = n)))))
        true
    }
    val nextSignature: Command = moveSignature(1)
    val prevSignature: Command = moveSignature(-1)

    val closeSignature: Command = Command { t ->
        if (state(t.state).signature == null) return@Command false
        t.dispatch(TransactionSpec(effects = listOf(setSignature.of(null))))
        true
    }

    internal fun extension(client: LspClient, uri: String, languageId: String): Extension {
        val runner = ViewPlugin { host -> LspView(client, host, uri, languageId) }
        val source = CompletionSource { ctx -> client.workspace.viewFor(ctx.state)?.complete(ctx) }
        val def = jumpToDefinition(client)
        val refs = findReferences(client)
        val rename = renameSymbol(client)
        val format = formatDocument(client)
        return extensionOf(
            field,
            viewPluginsFacet.of(runner),
            completionSourcesFacet.of(source),
            completionTriggersFacet.compute(FacetDep.field(field)) { st ->
                val f = state(st).features
                if (!f.completion) emptySet() else f.completionTriggers.ifEmpty { client.config.fallbackTriggers }
            },
            // Cmd-click (Ctrl-click): VS Code's go to definition, once the server says it has one.
            modClickFacet.compute(FacetDep.field(field)) { st -> if (state(st).features.definition) ModClickHandler { t, _ -> def.run(t) } else null },
            hoverTooltip(HOVER_ID) { st, pos, _ -> client.workspace.viewFor(st)?.hover(st, pos) },
            tooltipsFacet.compute(FacetDep.field(field)) { st ->
                state(st).signature?.let { Tooltip(it.pos.coerceIn(0, st.doc.length), WidgetKey(SIGNATURE_TOOLTIP, "sig"), above = true) }
            },
            panelsFacet.compute(FacetDep.field(field)) { st -> if (state(st).references != null) Panel(REFERENCES_PANEL, top = false) else null },
            panelsFacet.compute(FacetDep.field(field)) { st -> if (state(st).rename != null) Panel(RENAME_PANEL, top = false) else null },
            keymapOf(
                KeyBinding("F12", def),
                KeyBinding("Shift-F12", refs),
                KeyBinding("Escape", closeReferencePanel),
                KeyBinding("F2", rename),
                KeyBinding("Shift-Alt-f", format),
            ),
            Prec.high(keymapOf(
                KeyBinding("Mod-Shift-Space", showSignatureHelp),
                KeyBinding("Mod-Shift-ArrowUp", prevSignature),
                KeyBinding("Mod-Shift-ArrowDown", nextSignature),
                KeyBinding("Escape", closeSignature),
            )),
            commandsFacet.of(listOf(
                NamedCommand("lsp.definition", "Go to definition", def),
                NamedCommand("lsp.references", "Find references", refs),
                NamedCommand("lsp.rename", "Rename symbol", rename),
                NamedCommand("lsp.format", "Format document", format),
                NamedCommand("lsp.signature", "Show signature help", showSignatureHelp),
                NamedCommand("lsp.hover", "Show hover", Hover.showHover),
            )),
        )
    }
}
