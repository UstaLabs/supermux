package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.ScenePoint

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/normalize.js
 */
internal object Normalize {
    fun run(graph: DagreGraph) {
        graph.graph().dummyChains.clear()
        graph.edges().toList().forEach { normalizeEdge(graph, it) }
    }

    fun undo(graph: DagreGraph) {
        graph.graph().dummyChains.toList().forEach { firstDummy ->
            var current = firstDummy
            var node = graph.nodeOrThrow(current)
            val originalEdge = requireNotNull(node.edgeObj)
            val originalLabel = requireNotNull(node.edgeLabel)
            graph.setEdge(originalEdge, originalLabel)
            while (node.dummy != null) {
                val next = graph.successors(current).first()
                graph.removeNode(current)
                originalLabel.points += ScenePoint(node.x, node.y)
                if (node.dummy == "edge-label") {
                    originalLabel.x = node.x
                    originalLabel.y = node.y
                    originalLabel.width = node.width
                    originalLabel.height = node.height
                }
                current = next
                node = graph.nodeOrThrow(current)
            }
        }
    }

    private fun normalizeEdge(
        graph: DagreGraph,
        edgeRef: com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.EdgeRef,
    ) {
        var from = edgeRef.v
        var fromRank = requireNotNull(graph.nodeOrThrow(from).rank) {
            "Source $from has no rank for edge $edgeRef"
        }
        val to = edgeRef.w
        val toRank = requireNotNull(graph.nodeOrThrow(to).rank) {
            "Target $to has no rank for edge $edgeRef"
        }
        val edge = graph.edgeOrThrow(edgeRef)
        if (toRank == fromRank + 1) return

        graph.removeEdge(edgeRef)
        var index = 0
        fromRank += 1
        while (fromRank < toRank) {
            edge.points.clear()
            val node = DagreNode(
                width = 0f,
                height = 0f,
                rank = fromRank,
                edgeLabel = edge,
                edgeObj = edgeRef,
                labelpos = edge.labelpos,
            )
            val dummy = DagreUtil.addDummyNode(graph, "edge", node, "_d")
            if (fromRank == edge.labelRank) {
                node.width = edge.width
                node.height = edge.height
                node.dummy = "edge-label"
            }
            graph.setEdge(
                from,
                dummy,
                DagreEdge(weight = edge.weight),
                edgeRef.name,
            )
            if (index == 0) graph.graph().dummyChains += dummy
            from = dummy
            fromRank += 1
            index += 1
        }
        graph.setEdge(from, to, DagreEdge(weight = edge.weight), edgeRef.name)
    }
}
