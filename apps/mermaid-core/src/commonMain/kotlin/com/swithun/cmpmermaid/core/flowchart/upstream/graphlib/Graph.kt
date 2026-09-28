package com.swithun.cmpmermaid.core.flowchart.upstream.graphlib

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/graphlib/graph.js
 *
 * Linked maps intentionally preserve JavaScript object insertion order.
 */
internal class Graph<N : Any, E : Any, G : Any>(
    val directed: Boolean = true,
    val multigraph: Boolean = false,
    val compound: Boolean = false,
) {
    private var graphLabel: G? = null
    private val nodeLabels = linkedMapOf<String, N>()
    private val edgeLabels = linkedMapOf<EdgeRef, E>()
    private val parents = linkedMapOf<String, String?>()
    private val children = linkedMapOf<String?, LinkedHashSet<String>>()

    init {
        if (compound) children[null] = linkedSetOf()
    }

    fun setGraph(label: G): Graph<N, E, G> = apply {
        graphLabel = label
    }

    fun graph(): G = requireNotNull(graphLabel) { "Graph label has not been set" }

    fun setNode(id: String, label: N): Graph<N, E, G> = apply {
        if (id !in nodeLabels && compound) {
            parents[id] = null
            children.getOrPut(null) { linkedSetOf() } += id
            children[id] = linkedSetOf()
        }
        nodeLabels[id] = label
    }

    fun node(id: String): N? = nodeLabels[id]

    fun nodeOrThrow(id: String): N =
        requireNotNull(nodeLabels[id]) { "Graph does not have node: $id" }

    fun hasNode(id: String): Boolean = id in nodeLabels

    fun nodes(): List<String> = nodeLabels.keys.toList()

    fun nodeCount(): Int = nodeLabels.size

    fun removeNode(id: String): Graph<N, E, G> = apply {
        if (id !in nodeLabels) return@apply
        nodeEdges(id).toList().forEach(::removeEdge)
        if (compound) {
            children[parents[id]]?.remove(id)
            children[id].orEmpty().toList().forEach { setParent(it, null) }
            parents.remove(id)
            children.remove(id)
        }
        nodeLabels.remove(id)
    }

    fun setParent(id: String, parent: String?): Graph<N, E, G> = apply {
        require(compound) { "Cannot set parent in a non-compound graph" }
        require(id in nodeLabels) { "Graph does not have node: $id" }
        if (parent != null) {
            require(parent in nodeLabels) { "Graph does not have parent node: $parent" }
            var ancestor: String? = parent
            while (ancestor != null) {
                require(ancestor != id) { "Setting $parent as parent of $id would create a cycle" }
                ancestor = parents[ancestor]
            }
        }
        children[parents[id]]?.remove(id)
        parents[id] = parent
        children.getOrPut(parent) { linkedSetOf() } += id
    }

    fun parent(id: String): String? = if (compound) parents[id] else null

    fun children(id: String? = null): List<String> = when {
        compound -> children[id]?.toList().orEmpty()
        id == null -> nodes()
        id in nodeLabels -> emptyList()
        else -> emptyList()
    }

    fun setEdge(
        from: String,
        to: String,
        label: E,
        name: String? = null,
    ): Graph<N, E, G> = apply {
        require(from in nodeLabels) { "Graph does not have node: $from" }
        require(to in nodeLabels) { "Graph does not have node: $to" }
        require(multigraph || name == null) { "Cannot set a named edge in a non-multigraph" }
        edgeLabels[canonicalEdge(from, to, name)] = label
    }

    fun setEdge(edge: EdgeRef, label: E): Graph<N, E, G> =
        setEdge(edge.v, edge.w, label, edge.name)

    fun edge(edge: EdgeRef): E? = edgeLabels[canonicalEdge(edge.v, edge.w, edge.name)]

    fun edge(from: String, to: String, name: String? = null): E? =
        edgeLabels[canonicalEdge(from, to, name)]

    fun edgeOrThrow(edge: EdgeRef): E =
        requireNotNull(this.edge(edge)) { "Graph does not have edge: $edge" }

    fun hasEdge(from: String, to: String, name: String? = null): Boolean =
        canonicalEdge(from, to, name) in edgeLabels

    fun hasEdge(edge: EdgeRef): Boolean = hasEdge(edge.v, edge.w, edge.name)

    fun removeEdge(edge: EdgeRef): Graph<N, E, G> = apply {
        edgeLabels.remove(canonicalEdge(edge.v, edge.w, edge.name))
    }

    fun removeEdge(from: String, to: String, name: String? = null): Graph<N, E, G> =
        removeEdge(canonicalEdge(from, to, name))

    fun edges(): List<EdgeRef> = edgeLabels.keys.toList()

    fun edgeCount(): Int = edgeLabels.size

    fun inEdges(id: String, from: String? = null): List<EdgeRef> =
        edges().filter { it.w == id && (from == null || it.v == from) }

    fun outEdges(id: String, to: String? = null): List<EdgeRef> =
        edges().filter { it.v == id && (to == null || it.w == to) }

    fun nodeEdges(id: String, other: String? = null): List<EdgeRef> =
        edges().filter {
            (it.v == id && (other == null || it.w == other)) ||
                (it.w == id && (other == null || it.v == other))
        }

    fun predecessors(id: String): List<String> =
        inEdges(id).map(EdgeRef::v).distinct()

    fun successors(id: String): List<String> =
        outEdges(id).map(EdgeRef::w).distinct()

    fun neighbors(id: String): List<String> =
        (predecessors(id) + successors(id)).distinct()

    fun sources(): List<String> = nodes().filter { inEdges(it).isEmpty() }

    fun sinks(): List<String> = nodes().filter { outEdges(it).isEmpty() }

    fun isLeaf(id: String): Boolean =
        if (directed) successors(id).isEmpty() else neighbors(id).isEmpty()

    private fun canonicalEdge(from: String, to: String, name: String?): EdgeRef =
        if (!directed && from > to) EdgeRef(to, from, name) else EdgeRef(from, to, name)
}

internal data class EdgeRef(
    val v: String,
    val w: String,
    val name: String? = null,
)
