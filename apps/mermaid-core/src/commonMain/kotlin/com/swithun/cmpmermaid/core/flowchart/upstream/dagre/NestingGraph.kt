package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/nesting-graph.js
 */
internal object NestingGraph {
    fun run(graph: DagreGraph) {
        val root = DagreUtil.addDummyNode(graph, "root", DagreNode(), "_root")
        val depths = treeDepths(graph)
        val height = (depths.values.maxOrNull() ?: 1) - 1
        val nodeSeparation = 2 * height + 1
        graph.graph().nestingRoot = root
        graph.edges().forEach { graph.edgeOrThrow(it).minlen *= nodeSeparation }
        val weight = graph.edges().sumOf { graph.edgeOrThrow(it).weight.toDouble() }.toFloat() + 1f
        graph.children().toList().forEach { child ->
            visit(graph, root, nodeSeparation, weight, height, depths, child)
        }
        graph.graph().nodeRankFactor = nodeSeparation
    }

    fun cleanup(graph: DagreGraph) {
        graph.graph().nestingRoot?.let(graph::removeNode)
        graph.graph().nestingRoot = null
        graph.edges().toList().forEach { edge ->
            if (graph.edgeOrThrow(edge).nestingEdge) graph.removeEdge(edge)
        }
    }

    private fun visit(
        graph: DagreGraph,
        root: String,
        nodeSeparation: Int,
        weight: Float,
        height: Int,
        depths: Map<String, Int>,
        id: String,
    ) {
        val children = graph.children(id)
        if (children.isEmpty()) {
            if (id != root) {
                graph.setEdge(root, id, DagreEdge(weight = 0f, minlen = nodeSeparation))
            }
            return
        }

        val top = DagreUtil.addBorderNode(graph, "_bt")
        val bottom = DagreUtil.addBorderNode(graph, "_bb")
        val node = graph.nodeOrThrow(id)
        graph.setParent(top, id)
        graph.setParent(bottom, id)
        node.borderTop = top
        node.borderBottom = bottom

        children.forEach { child ->
            visit(graph, root, nodeSeparation, weight, height, depths, child)
            val childNode = graph.nodeOrThrow(child)
            val childTop = childNode.borderTop ?: child
            val childBottom = childNode.borderBottom ?: child
            val childWeight = if (childNode.borderTop != null) weight else 2f * weight
            val minimumLength =
                if (childTop != childBottom) 1 else height - depths.getValue(id) + 1
            graph.setEdge(
                top,
                childTop,
                DagreEdge(
                    weight = childWeight,
                    minlen = minimumLength,
                    nestingEdge = true,
                ),
            )
            graph.setEdge(
                childBottom,
                bottom,
                DagreEdge(
                    weight = childWeight,
                    minlen = minimumLength,
                    nestingEdge = true,
                ),
            )
        }
        if (graph.parent(id) == null) {
            graph.setEdge(
                root,
                top,
                DagreEdge(
                    weight = 0f,
                    minlen = height + depths.getValue(id),
                ),
            )
        }
    }

    private fun treeDepths(graph: DagreGraph): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        fun visit(id: String, depth: Int) {
            graph.children(id).forEach { visit(it, depth + 1) }
            result[id] = depth
        }
        graph.children().forEach { visit(it, 1) }
        return result
    }
}
