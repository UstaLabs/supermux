package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph
import kotlin.math.min

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/order/index.js
 * src/dagre/order/init-order.js
 * src/dagre/order/cross-count.js
 * src/dagre/order/build-layer-graph.js
 * src/dagre/order/barycenter.js
 * src/dagre/order/resolve-conflicts.js
 * src/dagre/order/sort-subgraph.js
 * src/dagre/order/sort.js
 * src/dagre/order/add-subgraph-constraints.js
 */
internal object Order {
    fun run(graph: DagreGraph) {
        val maximumRank = DagreUtil.maxRank(graph)
        val down = (1..maximumRank).map { buildLayerGraph(graph, it, incoming = true) }
        val up = (maximumRank - 1 downTo 0).map { buildLayerGraph(graph, it, incoming = false) }

        var layering = initOrder(graph)
        assignOrder(graph, layering)
        var bestCrossings = Float.POSITIVE_INFINITY
        var best = layering.map(List<String>::toList)
        var iteration = 0
        var lastBest = 0
        while (lastBest < 4) {
            sweep(if (iteration % 2 == 1) down else up, biasRight = iteration % 4 >= 2)
            layering = DagreUtil.buildLayerMatrix(graph)
            val crossings = crossCount(graph, layering)
            if (crossings < bestCrossings) {
                lastBest = 0
                best = layering.map(List<String>::toList)
                bestCrossings = crossings
            }
            iteration += 1
            lastBest += 1
        }
        assignOrder(graph, best)
    }

    private fun initOrder(graph: DagreGraph): List<List<String>> {
        val visited = mutableSetOf<String>()
        val simpleNodes = graph.nodes().filter { graph.children(it).isEmpty() }
        val maximumRank = simpleNodes.maxOfOrNull { graph.nodeOrThrow(it).rankOrThrow() } ?: 0
        val layers = MutableList(maximumRank + 1) { mutableListOf<String>() }

        fun visit(id: String) {
            if (!visited.add(id)) return
            val node = graph.nodeOrThrow(id)
            layers[node.rankOrThrow()] += id
            graph.successors(id).forEach(::visit)
        }

        simpleNodes.sortedBy { graph.nodeOrThrow(it).rankOrThrow() }.forEach(::visit)
        return layers
    }

    private fun assignOrder(graph: DagreGraph, layers: List<List<String>>) {
        layers.forEach { layer ->
            layer.forEachIndexed { index, id -> graph.nodeOrThrow(id).order = index }
        }
    }

    private fun sweep(layerGraphs: List<DagreGraph>, biasRight: Boolean) {
        val constraints = Graph<Unit, Unit, Unit>().setGraph(Unit)
        layerGraphs.forEach { layerGraph ->
            val root = requireNotNull(layerGraph.graph().root) {
                "Layer graph root is missing"
            }
            val sorted = sortSubgraph(layerGraph, root, constraints, biasRight)
            sorted.vs.forEachIndexed { index, id ->
                layerGraph.nodeOrThrow(id).order = index
            }
            addSubgraphConstraints(layerGraph, constraints, sorted.vs)
        }
    }

    private fun buildLayerGraph(
        graph: DagreGraph,
        rank: Int,
        incoming: Boolean,
    ): DagreGraph {
        var root: String
        do {
            root = DagreUtil.uniqueId("_root")
        } while (graph.hasNode(root))
        val result = Graph<DagreNode, DagreEdge, DagreGraphLabel>(compound = true)
            .setGraph(DagreGraphLabel(root = root))
        result.setNode(root, DagreNode())

        graph.nodes().forEach { id ->
            val node = graph.nodeOrThrow(id)
            val inRank = node.rank == rank ||
                (node.minRank != null && node.maxRank != null &&
                    rank in node.minRank!!..node.maxRank!!)
            if (!inRank) return@forEach

            val parent = graph.parent(id) ?: root
            if (!result.hasNode(parent)) {
                result.setNode(parent, graph.node(parent) ?: DagreNode())
            }
            result.setNode(id, node)
            result.setParent(id, parent)

            val related = if (incoming) graph.inEdges(id) else graph.outEdges(id)
            related.forEach { edge ->
                val other = if (edge.v == id) edge.w else edge.v
                if (!result.hasNode(other)) {
                    result.setNode(other, graph.node(other) ?: DagreNode())
                }
                val existing = result.edge(other, id)
                result.setEdge(
                    other,
                    id,
                    DagreEdge(weight = (existing?.weight ?: 0f) + graph.edgeOrThrow(edge).weight),
                )
            }

            if (node.minRank != null) {
                result.setNode(
                    id,
                    DagreNode(
                        borderLeft = mutableMapOf(
                            rank to requireNotNull(node.borderLeft[rank]) {
                                "Missing left border for $id at rank $rank; " +
                                    "range=${node.minRank}..${node.maxRank}"
                            },
                        ),
                        borderRight = mutableMapOf(
                            rank to requireNotNull(node.borderRight[rank]) {
                                "Missing right border for $id at rank $rank; " +
                                    "range=${node.minRank}..${node.maxRank}"
                            },
                        ),
                    ),
                )
            }
        }
        return result
    }

    private fun sortSubgraph(
        graph: DagreGraph,
        id: String,
        constraints: ConstraintGraph,
        biasRight: Boolean,
    ): SortResult {
        var movable = graph.children(id)
        val node = graph.node(id)
        val borderLeft = node?.borderLeft?.values?.firstOrNull()
        val borderRight = node?.borderRight?.values?.firstOrNull()
        if (borderLeft != null) {
            movable = movable.filter { it != borderLeft && it != borderRight }
        }

        val barycenters = barycenter(graph, movable)
        val subgraphs = mutableMapOf<String, SortResult>()
        barycenters.forEach { entry ->
            if (graph.children(entry.v).isNotEmpty()) {
                val nested = sortSubgraph(graph, entry.v, constraints, biasRight)
                subgraphs[entry.v] = nested
                if (nested.barycenter != null) mergeBarycenters(entry, nested)
            }
        }

        val entries = resolveConflicts(barycenters, constraints)
        entries.forEach { entry ->
            entry.vs = entry.vs.flatMap { child -> subgraphs[child]?.vs ?: listOf(child) }
        }
        val result = sort(entries, biasRight)
        if (borderLeft != null && borderRight != null) {
            result.vs = listOf(borderLeft) + result.vs + borderRight
            val leftPred = graph.predecessors(borderLeft).firstOrNull()
            val rightPred = graph.predecessors(borderRight).firstOrNull()
            if (leftPred != null && rightPred != null) {
                val oldWeight = result.weight ?: 0f
                result.barycenter =
                    ((result.barycenter ?: 0f) * oldWeight +
                        graph.nodeOrThrow(leftPred).orderOrThrow() +
                        graph.nodeOrThrow(rightPred).orderOrThrow()) / (oldWeight + 2f)
                result.weight = oldWeight + 2f
            }
        }
        return result
    }

    private fun barycenter(graph: DagreGraph, movable: List<String>): MutableList<OrderEntry> =
        movable.mapIndexedTo(mutableListOf()) { index, id ->
            val incoming = graph.inEdges(id)
            if (incoming.isEmpty()) {
                OrderEntry(v = id, index = index)
            } else {
                var sum = 0f
                var weight = 0f
                incoming.forEach { edge ->
                    val edgeWeight = graph.edgeOrThrow(edge).weight
                    sum += edgeWeight * graph.nodeOrThrow(edge.v).orderOrThrow()
                    weight += edgeWeight
                }
                OrderEntry(id, index, sum / weight, weight)
            }
        }

    private fun resolveConflicts(
        entries: List<OrderEntry>,
        constraints: ConstraintGraph,
    ): MutableList<OrderEntry> {
        val mapped = entries.associate { source ->
            source.v to ConflictEntry(
                vs = mutableListOf(source.v),
                index = source.index,
                barycenter = source.barycenter,
                weight = source.weight,
            )
        }
        constraints.edges().forEach { edge ->
            val from = mapped[edge.v]
            val to = mapped[edge.w]
            if (from != null && to != null) {
                to.indegree += 1
                from.out += to
            }
        }
        val sources = mapped.values.filterTo(mutableListOf()) { it.indegree == 0 }
        val ordered = mutableListOf<ConflictEntry>()
        while (sources.isNotEmpty()) {
            val entry = sources.removeAt(sources.lastIndex)
            ordered += entry
            entry.incoming.asReversed().forEach { previous ->
                if (!previous.merged &&
                    (previous.barycenter == null || entry.barycenter == null ||
                        previous.barycenter!! >= entry.barycenter!!)
                ) {
                    mergeEntries(entry, previous)
                }
            }
            entry.out.forEach { next ->
                next.incoming += entry
                next.indegree -= 1
                if (next.indegree == 0) sources += next
            }
        }
        return ordered.filterNot { it.merged }.mapTo(mutableListOf()) {
            OrderEntry(
                v = it.vs.first(),
                index = it.index,
                barycenter = it.barycenter,
                weight = it.weight,
                vs = it.vs,
            )
        }
    }

    private fun mergeEntries(target: ConflictEntry, source: ConflictEntry) {
        var sum = 0f
        var weight = 0f
        if ((target.weight ?: 0f) != 0f) {
            sum += target.barycenter!! * target.weight!!
            weight += target.weight!!
        }
        if ((source.weight ?: 0f) != 0f) {
            sum += source.barycenter!! * source.weight!!
            weight += source.weight!!
        }
        target.vs = source.vs + target.vs
        target.barycenter = if (weight == 0f) Float.NaN else sum / weight
        target.weight = weight
        target.index = min(source.index, target.index)
        source.merged = true
    }

    private fun mergeBarycenters(target: OrderEntry, other: SortResult) {
        val otherCenter = other.barycenter ?: return
        val otherWeight = other.weight ?: return
        if (target.barycenter != null) {
            target.barycenter =
                (target.barycenter!! * target.weight!! + otherCenter * otherWeight) /
                (target.weight!! + otherWeight)
            target.weight = target.weight!! + otherWeight
        } else {
            target.barycenter = otherCenter
            target.weight = otherWeight
        }
    }

    private fun sort(entries: MutableList<OrderEntry>, biasRight: Boolean): SortResult {
        val sortable = entries.filter { it.barycenter != null }.toMutableList()
        val unsortable = entries.filter { it.barycenter == null }
            .sortedByDescending(OrderEntry::index)
            .toMutableList()
        sortable.sortWith { first, second ->
            when {
                first.barycenter!! < second.barycenter!! -> -1
                first.barycenter!! > second.barycenter!! -> 1
                !biasRight -> first.index - second.index
                else -> second.index - first.index
            }
        }

        val result = mutableListOf<String>()
        var index = consumeUnsortable(result, unsortable, 0)
        var sum = 0f
        var weight = 0f
        sortable.forEach { entry ->
            index += entry.vs.size
            result += entry.vs
            sum += entry.barycenter!! * entry.weight!!
            weight += entry.weight!!
            index = consumeUnsortable(result, unsortable, index)
        }
        return SortResult(
            vs = result,
            barycenter = if (weight != 0f) sum / weight else null,
            weight = if (weight != 0f) weight else null,
        )
    }

    private fun consumeUnsortable(
        result: MutableList<String>,
        entries: MutableList<OrderEntry>,
        startIndex: Int,
    ): Int {
        var index = startIndex
        while (entries.isNotEmpty() && entries.last().index <= index) {
            result += entries.removeAt(entries.lastIndex).vs
            index += 1
        }
        return index
    }

    private fun addSubgraphConstraints(
        graph: DagreGraph,
        constraints: ConstraintGraph,
        ids: List<String>,
    ) {
        val previous = mutableMapOf<String, String>()
        var rootPrevious: String? = null
        ids.forEach { id ->
            var child = graph.parent(id)
            while (child != null) {
                val currentChild = child
                val parent = graph.parent(currentChild)
                val previousChild = if (parent != null) {
                    previous[parent].also { previous[parent] = currentChild }
                } else {
                    rootPrevious.also { rootPrevious = currentChild }
                }
                if (previousChild != null && previousChild != currentChild) {
                    if (!constraints.hasNode(previousChild)) constraints.setNode(previousChild, Unit)
                    if (!constraints.hasNode(currentChild)) constraints.setNode(currentChild, Unit)
                    constraints.setEdge(previousChild, currentChild, Unit)
                    return@forEach
                }
                child = parent
            }
        }
    }

    private fun crossCount(graph: DagreGraph, layering: List<List<String>>): Float {
        var result = 0f
        for (index in 1 until layering.size) {
            result += twoLayerCrossCount(graph, layering[index - 1], layering[index])
        }
        return result
    }

    private fun twoLayerCrossCount(
        graph: DagreGraph,
        north: List<String>,
        south: List<String>,
    ): Float {
        val southPosition = south.withIndex().associate { it.value to it.index }
        val entries = north.flatMap { id ->
            graph.outEdges(id).mapNotNull { edge ->
                southPosition[edge.w]?.let { position ->
                    position to graph.edgeOrThrow(edge).weight
                }
            }.sortedBy(Pair<Int, Float>::first)
        }
        var firstIndex = 1
        while (firstIndex < south.size) firstIndex = firstIndex shl 1
        val tree = FloatArray(2 * firstIndex - 1)
        firstIndex -= 1
        var crossings = 0f
        entries.forEach { (position, weight) ->
            var index = position + firstIndex
            tree[index] += weight
            var weightSum = 0f
            while (index > 0) {
                if (index % 2 == 1) weightSum += tree[index + 1]
                index = (index - 1) shr 1
                tree[index] += weight
            }
            crossings += weight * weightSum
        }
        return crossings
    }

    private data class OrderEntry(
        val v: String,
        var index: Int,
        var barycenter: Float? = null,
        var weight: Float? = null,
        var vs: List<String> = listOf(v),
    )

    private class ConflictEntry(
        var indegree: Int = 0,
        val incoming: MutableList<ConflictEntry> = mutableListOf(),
        val out: MutableList<ConflictEntry> = mutableListOf(),
        var vs: List<String>,
        var index: Int,
        var barycenter: Float?,
        var weight: Float?,
        var merged: Boolean = false,
    )

    private data class SortResult(
        var vs: List<String>,
        var barycenter: Float? = null,
        var weight: Float? = null,
    )
}

private typealias ConstraintGraph = Graph<Unit, Unit, Unit>
