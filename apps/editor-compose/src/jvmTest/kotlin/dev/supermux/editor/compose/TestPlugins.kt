package dev.supermux.editor.compose

import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.extensionOf

/**
 * A plugin as M4's will be: a state field holding a [RangeSet] that follows every edit (RangeSet
 * mapping) and is replaced by [set], provided to [facet]. Tests put markers, widgets and folds in.
 */
internal class RangePlugin<T>(name: String, facet: Facet<RangeSet<T>, List<RangeSet<T>>>, initial: List<Ranged<T>> = emptyList()) {
    val set = StateEffectType<RangeSet<T>>("$name.set")
    val stateField: StateField<RangeSet<T>> = StateField(
        name,
        { RangeSet.of(initial) },
        { value, tr ->
            var v = value.map(tr.changes)
            for (e in tr.effects) e.valueIf(set)?.let { v = it }
            v
        },
        { f -> facet.compute(dev.supermux.editor.core.FacetDep.field(f)) { it.field(f) } },
    )
    val extension: Extension
        get() = extensionOf(stateField)

    fun value(state: EditorState): RangeSet<T> = state.field(stateField)

    fun replace(view: EditorView, ranges: List<Ranged<T>>) =
        view.dispatch(TransactionSpec(effects = listOf(set.of(RangeSet.of(ranges)))))
}
