package dev.supermux.editor.core

/**
 * Anything a plugin contributes to an editor: facet values, state fields, compartments, or a
 * nested group of those. Plugins are plain values of this type; there is no plugin base class.
 */
sealed interface Extension

internal class ExtensionGroup(val items: List<Extension>) : Extension

/** Group several extensions into one. */
fun extensionOf(vararg items: Extension): Extension = ExtensionGroup(items.toList())
fun List<Extension>.toExtension(): Extension = ExtensionGroup(this)

/** When two extensions both provide something, the higher precedence one comes first. */
enum class Precedence { HIGHEST, HIGH, DEFAULT, LOW, LOWEST }

internal class PrecExtension(val inner: Extension, val prec: Precedence) : Extension

object Prec {
    fun highest(e: Extension): Extension = PrecExtension(e, Precedence.HIGHEST)
    fun high(e: Extension): Extension = PrecExtension(e, Precedence.HIGH)
    fun default(e: Extension): Extension = PrecExtension(e, Precedence.DEFAULT)
    fun low(e: Extension): Extension = PrecExtension(e, Precedence.LOW)
    fun lowest(e: Extension): Extension = PrecExtension(e, Precedence.LOWEST)
}

/**
 * A configuration slot that several extensions can fill, combined into one output value.
 *
 * `Facet.list<KeyBinding>()` collects every binding in precedence order; `Facet.first(4)` takes
 * the highest-precedence tab size or falls back to 4. Inputs are either static ([of]) or derived
 * from the state ([compute]), which is how a plugin turns its own state field into, say,
 * decorations.
 *
 * Output values are reused, so consumers can compare by identity to skip work: a facet fed only
 * by [of] is computed once per configuration and shared by every state using it, and when a
 * computed output [compare]s equal to the previous state's, the previous instance is kept. A
 * [compute] input declared with [FacetDep]s is only recomputed when one of them changed.
 */
class Facet<I, O> private constructor(
    val name: String,
    private val compare: (O, O) -> Boolean,
    private val combine: (List<I>) -> O,
) {
    fun of(value: I): Extension = FacetProvider(this, value, null)
    /**
     * An input derived from the state. With [deps], [get] runs only when one of them changed since
     * the previous state (else the previous input is reused), so [get] must read nothing else
     * that can change. Without deps it runs for every new state. A facet read from inside a
     * field's create/update (a state still being built) skips this reuse and calls [get].
     */
    fun compute(vararg deps: FacetDep, get: (EditorState) -> I): Extension = FacetProvider(this, null, get, deps.toList())

    /** The output when nothing provides this facet: computed once, the same instance everywhere. */
    internal val emptyValue: O by lazy { combine(emptyList()) }

    @Suppress("UNCHECKED_CAST")
    internal fun combineIn(providers: List<FacetProvider<*>>, state: EditorState?): O =
        combine(providers.map { (it as FacetProvider<I>).valueIn(state) })

    @Suppress("UNCHECKED_CAST")
    internal fun combineAny(values: List<Any?>): O = combine(values as List<I>)

    @Suppress("UNCHECKED_CAST")
    internal fun same(a: Any?, b: Any?): Boolean = a === b || compare(a as O, b as O)

    override fun toString() = "Facet($name)"

    companion object {
        /** [compare] decides when a recomputed output counts as unchanged (default: `==`). */
        fun <I, O> define(name: String, compare: (O, O) -> Boolean = { a, b -> a == b }, combine: (List<I>) -> O): Facet<I, O> =
            Facet(name, compare, combine)
        fun <T> list(name: String): Facet<T, List<T>> = Facet(name, { a, b -> a == b }) { it }
        fun <T> first(name: String, default: T): Facet<T, T> = Facet(name, { a, b -> a == b }) { it.firstOrNull() ?: default }
    }
}

/**
 * What a [Facet.compute] input depends on. Unchanged means: [Doc] the same rope instance,
 * [Selection] an equal selection, [field] an equal (==) value, [facet] the same output instance.
 */
sealed class FacetDep {
    data object Doc : FacetDep()
    data object Selection : FacetDep()
    internal class OfField(val field: StateField<*>) : FacetDep()
    internal class OfFacet(val facet: Facet<*, *>) : FacetDep()

    companion object {
        fun field(f: StateField<*>): FacetDep = OfField(f)
        fun facet(f: Facet<*, *>): FacetDep = OfFacet(f)
    }
}

internal class FacetProvider<I>(
    val facet: Facet<I, *>,
    val static: I?,
    val dynamic: ((EditorState) -> I)?,
    val deps: List<FacetDep> = emptyList(),
) : Extension {
    /** [state] may be null only for a static provider. */
    @Suppress("UNCHECKED_CAST")
    fun valueIn(state: EditorState?): I = if (dynamic != null) dynamic.invoke(state!!) else static as I
}

/**
 * A plugin's own memory, stored in the state and updated by every transaction.
 *
 * [provide] lets the field feed facets from its value (for example decorations), so the field
 * and what it shows travel as one extension. What it returns is created again on every resolve,
 * so after any reconfigure its computed inputs start fresh (no reuse from the previous state).
 */
class StateField<V>(
    val name: String,
    private val create: (EditorState) -> V,
    private val update: (V, Transaction) -> V,
    private val provide: ((StateField<V>) -> Extension)? = null,
) : Extension {
    internal fun createIn(state: EditorState): V = create(state)
    internal fun updateWith(value: Any?, tr: Transaction): V {
        @Suppress("UNCHECKED_CAST")
        return update(value as V, tr)
    }
    internal fun provided(): Extension? = provide?.invoke(this)
    override fun toString() = "StateField($name)"
}

/**
 * A replaceable slot in the configuration, for things that change while the editor is open:
 * the language when a file is renamed, line wrap when the setting flips.
 */
class Compartment(val name: String = "compartment") {
    fun of(ext: Extension): Extension = CompartmentExtension(this, ext)
    fun reconfigure(ext: Extension): StateEffect<*> = StateEffect.compartmentReconfigure.of(this to ext)
    fun get(state: EditorState): Extension? = state.config.compartments[this]
    override fun toString() = "Compartment($name)"
}

internal class CompartmentExtension(val compartment: Compartment, val inner: Extension) : Extension

/** The flattened, precedence-ordered form of an extension tree. */
internal class Configuration(
    val root: Extension,
    val fields: List<StateField<*>>,
    val providers: Map<Facet<*, *>, List<FacetProvider<*>>>,
    val compartments: Map<Compartment, Extension>,
) {
    /** The output of every facet whose providers are all static: computed once, shared by every state. */
    val staticValues: Map<Facet<*, *>, Any?> get() = statics
    private val statics = HashMap<Facet<*, *>, Any?>().apply {
        for ((f, ps) in providers) if (ps.all { it.dynamic == null }) put(f, f.combineIn(ps, null))
    }

    /**
     * After a reconfigure: adopt [old]'s instance for every static output that compares equal to
     * it, so identity survives the reconfigure. Called before any state uses this configuration.
     */
    fun reuseStaticValues(old: Map<Facet<*, *>, Any?>) {
        for ((f, v) in statics) if (old.containsKey(f)) { val o = old[f]; if (o !== v && f.same(o, v)) statics[f] = o }
    }

    companion object {
        fun resolve(root: Extension, compartmentContent: Map<Compartment, Extension>): Configuration {
            // Every occurrence of a field or provider in tree order, and the highest precedence each
            // one appears at: a duplicate is kept once, at its highest-precedence place.
            val occurrences = ArrayList<Pair<Extension, Precedence>>()
            val best = HashMap<Extension, Precedence>()
            val provided = HashMap<StateField<*>, Extension?>() // provide() runs once per field
            // What a field provides is walked once per precedence, so a field that provides itself
            // (or two that provide each other) cannot recurse forever.
            val walked = HashSet<Pair<StateField<*>, Precedence>>()
            val compartments = LinkedHashMap<Compartment, Extension>()

            fun visit(e: Extension, prec: Precedence) {
                when (e) {
                    is ExtensionGroup -> e.items.forEach { visit(it, prec) }
                    is PrecExtension -> visit(e.inner, e.prec)
                    is CompartmentExtension -> {
                        val content = compartmentContent[e.compartment] ?: e.inner
                        compartments[e.compartment] = content
                        visit(content, prec)
                    }
                    is StateField<*>, is FacetProvider<*> -> {
                        occurrences += e to prec
                        val b = best[e]
                        if (b == null || prec < b) best[e] = prec
                        if (e is StateField<*> && walked.add(e to prec)) provided.getOrPut(e) { e.provided() }?.let { visit(it, prec) }
                    }
                }
            }
            visit(root, Precedence.DEFAULT)

            val buckets = Precedence.entries.associateWith { ArrayList<Extension>() }
            val placed = HashSet<Extension>()
            for ((e, prec) in occurrences) if (best[e] == prec && placed.add(e)) buckets.getValue(prec) += e
            val ordered = Precedence.entries.flatMap { buckets.getValue(it) }
            val fields = ordered.filterIsInstance<StateField<*>>()
            val providers = LinkedHashMap<Facet<*, *>, MutableList<FacetProvider<*>>>()
            for (p in ordered.filterIsInstance<FacetProvider<*>>()) providers.getOrPut(p.facet) { ArrayList() } += p
            checkDepCycles(providers)
            return Configuration(root, fields, providers, compartments)
        }

        /** Declared [FacetDep.facet] edges must not form a cycle; caught here, when configuring. */
        private fun checkDepCycles(providers: Map<Facet<*, *>, List<FacetProvider<*>>>) {
            val done = HashSet<Facet<*, *>>()
            val path = ArrayList<Facet<*, *>>()
            fun visit(f: Facet<*, *>) {
                if (f in done) return
                val at = path.indexOf(f)
                require(at < 0) { "facet dependency cycle: " + (path.subList(at, path.size) + f).joinToString(" -> ") }
                path += f
                for (p in providers[f].orEmpty()) for (d in p.deps) if (d is FacetDep.OfFacet) visit(d.facet)
                path.removeAt(path.size - 1)
                done += f
            }
            providers.keys.forEach { visit(it) }
        }
    }
}
