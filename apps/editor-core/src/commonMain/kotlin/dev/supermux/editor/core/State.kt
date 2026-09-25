package dev.supermux.editor.core

/** A typed message a transaction carries to plugins ("fold lines 10–20", "set diagnostics"). */
class StateEffectType<T>(val name: String, private val mapper: ((T, ChangeSet) -> T?)? = null) {
    fun of(value: T): StateEffect<T> = StateEffect(this, value)
    internal fun mapValue(value: T, changes: ChangeSet): T? = if (mapper == null) value else mapper.invoke(value, changes)
    override fun toString() = "StateEffectType($name)"
}

class StateEffect<T> internal constructor(val type: StateEffectType<T>, val value: T) {
    fun isOf(t: StateEffectType<*>): Boolean = type === t

    /** This effect moved through [changes], or null when its target was deleted. */
    fun map(changes: ChangeSet): StateEffect<T>? = type.mapValue(value, changes)?.let { StateEffect(type, it) }

    /** [value] as [t]'s type when this effect is of [t], else null. */
    @Suppress("UNCHECKED_CAST")
    fun <U> valueIf(t: StateEffectType<U>): U? = if (type === t) value as U else null

    override fun toString() = "${type.name}($value)"

    companion object {
        /** Replace the whole configuration. */
        val reconfigure = StateEffectType<Extension>("reconfigure")
        internal val compartmentReconfigure = StateEffectType<Pair<Compartment, Extension>>("compartment.reconfigure")
    }
}

/** A typed label on a transaction that plugins read but never store (its origin, a timestamp). */
class AnnotationType<T>(val name: String) {
    fun of(value: T): Annotation<T> = Annotation(this, value)
    override fun toString() = "AnnotationType($name)"
}

class Annotation<T> internal constructor(val type: AnnotationType<T>, val value: T)

/**
 * What a caller asks for. [changes] (or a prebuilt [changeSet], never both) are in the coordinates
 * of the CURRENT document; [selection], when given, is in the coordinates of the NEW one.
 *
 * [userEvent] names where the change came from, dot-separated from general to specific:
 * `input`, `input.ime`, `paste`, `undo`, `redo`, `disk`, `lsp`, `command`, and later `agent`.
 */
data class TransactionSpec(
    val changes: List<ChangeSpec> = emptyList(),
    val changeSet: ChangeSet? = null,
    val selection: EditorSelection? = null,
    val effects: List<StateEffect<*>> = emptyList(),
    val annotations: List<Annotation<*>> = emptyList(),
    val userEvent: String? = null,
    val scrollIntoView: Boolean = false,
) {
    init { require(changes.isEmpty() || changeSet == null) { "give either changes or a changeSet, not both" } }
}

/** One applied update: the old state, what changed, and the resulting [state]. Plain data. */
class Transaction internal constructor(
    val startState: EditorState,
    val changes: ChangeSet,
    val selection: EditorSelection,
    val effects: List<StateEffect<*>>,
    private val annotations: List<Annotation<*>>,
    val scrollIntoView: Boolean,
    /** True when the spec set the selection explicitly (instead of mapping the old one). */
    val selectionSet: Boolean,
) {
    val docChanged: Boolean get() = !changes.isEmpty
    val newDoc: Rope = if (changes.isEmpty) startState.doc else changes.apply(startState.doc)

    lateinit var state: EditorState
        internal set

    /** True when this transaction replaced (part of) the configuration. */
    var reconfigured: Boolean = false
        internal set

    @Suppress("UNCHECKED_CAST")
    fun <T> annotation(type: AnnotationType<T>): T? = annotations.lastOrNull { it.type === type }?.value as T?

    /** `isUserEvent("input")` is true for `input` and `input.ime`, not for `inputs`. */
    fun isUserEvent(event: String): Boolean {
        val e = annotation(userEvent) ?: return false
        return e == event || (e.length > event.length && e.startsWith(event) && e[event.length] == '.')
    }

    companion object {
        val userEvent = AnnotationType<String>("userEvent")
    }
}

/**
 * The whole editor state as ONE immutable value: the document, the selection and every plugin's
 * field. The only way to get a new one is [update].
 */
class EditorState private constructor(
    val doc: Rope,
    val selection: EditorSelection,
    internal val config: Configuration,
) {
    private val values = HashMap<StateField<*>, Any?>()
    private val facetCache = HashMap<Facet<*, *>, Any?>()
    // False while fields are still being created/updated: a facet read then may see a partial
    // state, so it is computed but not cached. A field may read static facets and EARLIER fields.
    private var complete = false

    @Suppress("UNCHECKED_CAST")
    fun <V> field(f: StateField<V>): V {
        require(values.containsKey(f)) { "$f is not part of this state's configuration" }
        return values[f] as V
    }

    @Suppress("UNCHECKED_CAST")
    fun <V> fieldOrNull(f: StateField<V>): V? = values[f] as V?

    @Suppress("UNCHECKED_CAST")
    fun <I, O> facet(f: Facet<I, O>): O {
        if (facetCache.containsKey(f)) return facetCache[f] as O
        val providers = config.providers[f].orEmpty()
        val value = f.combineValues(providers.map { (it as FacetProvider<I>).valueIn(this) })
        if (complete) facetCache[f] = value
        return value
    }

    fun sliceDoc(from: Int = 0, to: Int = doc.length): String = doc.slice(from, to)

    fun update(spec: TransactionSpec): Transaction {
        val changes = spec.changeSet ?: ChangeSet.of(doc.length, spec.changes)
        require(changes.lengthBefore == doc.length) { "change set for length ${changes.lengthBefore}, doc is ${doc.length}" }
        val selection = spec.selection ?: selection.map(changes)
        selection.ranges.forEach { require(it.to <= changes.lengthAfter) { "selection $it beyond new doc length ${changes.lengthAfter}" } }
        val annotations = if (spec.userEvent != null) spec.annotations + Transaction.userEvent.of(spec.userEvent) else spec.annotations
        val tr = Transaction(this, changes, selection, spec.effects, annotations, spec.scrollIntoView, spec.selection != null)

        // Reconfiguration: a whole new root, and/or new compartment contents.
        var root = config.root
        var compartments = config.compartments
        var reconfigured = false
        for (e in spec.effects) {
            e.valueIf(StateEffect.reconfigure)?.let { root = it; reconfigured = true }
            e.valueIf(StateEffect.compartmentReconfigure)?.let { (c, ext) -> compartments = compartments + (c to ext); reconfigured = true }
        }
        val newConfig = if (reconfigured) Configuration.resolve(root, compartments) else config
        tr.reconfigured = reconfigured

        val next = EditorState(tr.newDoc, selection, newConfig)
        tr.state = next
        for (f in newConfig.fields) {
            next.values[f] = if (values.containsKey(f)) f.updateWith(values[f], tr) else f.createIn(next)
        }
        next.complete = true
        return tr
    }

    fun update(
        vararg changes: ChangeSpec,
        selection: EditorSelection? = null,
        userEvent: String? = null,
    ): Transaction = update(TransactionSpec(changes.toList(), selection = selection, userEvent = userEvent))

    companion object {
        fun create(
            doc: String = "",
            selection: EditorSelection? = null,
            extensions: Extension = extensionOf(),
        ): EditorState {
            val rope = Rope.of(doc)
            val sel = selection ?: EditorSelection.cursor(0)
            sel.ranges.forEach { require(it.to <= rope.length) { "selection $it beyond doc length ${rope.length}" } }
            val state = EditorState(rope, sel, Configuration.resolve(extensions, emptyMap()))
            for (f in state.config.fields) state.values[f] = f.createIn(state)
            state.complete = true
            return state
        }
    }
}
