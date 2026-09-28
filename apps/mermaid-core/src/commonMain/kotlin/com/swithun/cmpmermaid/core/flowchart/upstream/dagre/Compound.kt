package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/parent-dummy-chains.js
 * src/dagre/add-border-segments.js
 */
internal object Compound {
    fun parentDummyChains(graph: DagreGraph) {
        val postorder = postorderNumbers(graph)
        graph.graph().dummyChains.forEach { firstDummy ->
            var dummy = firstDummy
            var node = graph.nodeOrThrow(dummy)
            val edge = requireNotNull(node.edgeObj)
            val pathData = findPath(graph, postorder, edge.v, edge.w)
            var pathIndex = 0
            var pathNode = pathData.path[pathIndex]
            var ascending = true

            while (dummy != edge.w) {
                node = graph.nodeOrThrow(dummy)
                if (ascending) {
                    while (pathNode != pathData.lca &&
                        (graph.nodeOrThrow(requireNotNull(pathNode)).maxRank ?: Int.MIN_VALUE) <
                        node.rankOrThrow()
                    ) {
                        pathIndex += 1
                        pathNode = pathData.path[pathIndex]
                    }
                    if (pathNode == pathData.lca) ascending = false
                }
                if (!ascending) {
                    while (pathIndex < pathData.path.lastIndex &&
                        (graph.nodeOrThrow(requireNotNull(pathData.path[pathIndex + 1])).minRank ?: Int.MAX_VALUE) <=
                        node.rankOrThrow()
                    ) {
                        pathIndex += 1
                    }
                    pathNode = pathData.path[pathIndex]
                }
                graph.setParent(dummy, pathNode)
                dummy = graph.successors(dummy).first()
            }
        }
    }

    fun addBorderSegments(graph: DagreGraph) {
        fun visit(id: String) {
            graph.children(id).forEach(::visit)
            val node = graph.nodeOrThrow(id)
            val minimum = node.minRank ?: return
            val maximum = node.maxRank ?: return
            node.borderLeft.clear()
            node.borderRight.clear()
            for (rank in minimum..maximum) {
                addBorderNode(graph, node.borderLeft, "borderLeft", "_bl", id, rank)
                addBorderNode(graph, node.borderRight, "borderRight", "_br", id, rank)
            }
        }
        graph.children().forEach(::visit)
    }

    private fun addBorderNode(
        graph: DagreGraph,
        border: MutableMap<Int, String>,
        borderType: String,
        prefix: String,
        subgraph: String,
        rank: Int,
    ) {
        val previous = border[rank - 1]
        val current = DagreUtil.addDummyNode(
            graph,
            "border",
            DagreNode(
                width = 0f,
                height = 0f,
                rank = rank,
                borderType = borderType,
            ),
            prefix,
        )
        border[rank] = current
        graph.setParent(current, subgraph)
        if (previous != null) graph.setEdge(previous, current, DagreEdge(weight = 1f))
    }

    private fun findPath(
        graph: DagreGraph,
        postorder: Map<String, Range>,
        from: String,
        to: String,
    ): PathData {
        val fromPath = mutableListOf<String?>()
        val toPath = mutableListOf<String?>()
        val low = min(postorder.getValue(from).low, postorder.getValue(to).low)
        val limit = max(postorder.getValue(from).limit, postorder.getValue(to).limit)
        var parent: String? = from
        do {
            parent = parent?.let(graph::parent)
            fromPath += parent
        } while (
            parent != null &&
            (postorder.getValue(parent).low > low || limit > postorder.getValue(parent).limit)
        )
        val lca = parent
        parent = to
        while (true) {
            parent = parent?.let(graph::parent)
            if (parent == lca) break
            toPath += parent
        }
        return PathData(fromPath + toPath.asReversed(), lca)
    }

    private fun postorderNumbers(graph: DagreGraph): Map<String, Range> {
        val result = mutableMapOf<String, Range>()
        var limit = 0
        fun visit(id: String) {
            val low = limit
            graph.children(id).forEach(::visit)
            result[id] = Range(low, limit)
            limit += 1
        }
        graph.children().forEach(::visit)
        return result
    }

    private data class Range(val low: Int, val limit: Int)
    private data class PathData(val path: List<String?>, val lca: String?)
}
