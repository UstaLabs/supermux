package dev.supermux.editor.core

import kotlin.concurrent.Volatile

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
 *
 * A completed state (one returned by [create] or [update]) is safe to read from any thread: every
 * facet with providers is computed eagerly when the state completes, so reads never mutate it.
 */
class EditorState private constructor(
    val doc: Rope,
    val selection: EditorSelection,
    internal val config: Configuration,
) {
    // Filled while the state is being built, never changed after [complete].
    private val values = HashMap<StateField<*>, Any?>()

    // Null while fields are still being created/updated: a facet read then may see a partial state,
    // so it is computed but not kept (a field may read static facets and EARLIER fields). Set once,
    // by [complete]; the volatile write also publishes [values] to readers on other threads.
    @Volatile private var facetValues: Map<Facet<*, *>, Any?>? = null

    // Only while [complete] runs, on the building thread: the facets computed so far, so a facet
    // read by another facet's provider is computed once.
    private var building: HashMap<Facet<*, *>, Any?>? = null
    private var previous: EditorState? = null

    // The facets being computed right now, innermost last: a facet met again is a cycle.
    private var inProgress: ArrayList<Facet<*, *>>? = null

    @Suppress("UNCHECKED_CAST")
    fun <V> field(f: StateField<V>): V {
        val values = fieldValues()
        require(values.containsKey(f)) { "$f is not part of this state's configuration" }
        return values[f] as V
    }

    @Suppress("UNCHECKED_CAST")
    fun <V> fieldOrNull(f: StateField<V>): V? = fieldValues()[f] as V?

    // Reads the volatile first, so a completed state's field values are visible on this thread.
    private fun fieldValues(): Map<StateField<*>, Any?> { facetValues; return values }

    @Suppress("UNCHECKED_CAST")
    fun <I, O> facet(f: Facet<I, O>): O {
        val done = facetValues
        if (done != null) return if (done.containsKey(f)) done[f] as O else f.emptyValue
        if (config.staticValues.containsKey(f)) return config.staticValues[f] as O
        val providers = config.providers[f] ?: return f.emptyValue
        building?.let { if (it.containsKey(f)) return it[f] as O }
        return computeFacet(f, providers) as O
    }

    private fun computeFacet(f: Facet<*, *>, providers: List<FacetProvider<*>>): Any? {
        val stack = inProgress ?: ArrayList<Facet<*, *>>().also { inProgress = it }
        check(f !in stack) { "facet cycle: " + (stack.subList(stack.indexOf(f), stack.size) + f).joinToString(" -> ") }
        stack += f
        try {
            val v = f.combineIn(providers, this)
            val b = building ?: return v // a partial state: computed, not kept
            val before = previous?.facetValues
            val out = if (before != null && before.containsKey(f) && f.same(before[f], v)) before[f] else v
            b[f] = out
            return out
        } finally {
            stack.removeAt(stack.size - 1)
        }
    }

    /**
     * Marks the state complete: computes every facet with providers, keeping [previous]'s instance
     * wherever the new output compares equal to it.
     */
    private fun complete(previous: EditorState?) {
        val b = HashMap<Facet<*, *>, Any?>()
        building = b
        this.previous = previous
        try {
            for ((f, providers) in config.providers) {
                if (b.containsKey(f)) continue
                if (config.staticValues.containsKey(f)) b[f] = config.staticValues[f] else computeFacet(f, providers)
            }
        } finally {
            building = null
            this.previous = null
            inProgress = null
        }
        facetValues = b
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
        next.complete(this)
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
            state.complete(null)
            return state
        }
    }
}
