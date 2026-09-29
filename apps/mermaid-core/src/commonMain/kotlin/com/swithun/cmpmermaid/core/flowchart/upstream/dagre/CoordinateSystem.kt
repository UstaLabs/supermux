package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.ScenePoint

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/coordinate-system.js
 */
internal object CoordinateSystem {
    fun adjust(graph: DagreGraph) {
        when (graph.graph().rankdir.lowercase()) {
            "lr", "rl" -> swapWidthHeight(graph)
        }
    }

    fun undo(graph: DagreGraph) {
        when (graph.graph().rankdir.lowercase()) {
            "bt", "rl" -> reverseY(graph)
        }
        when (graph.graph().rankdir.lowercase()) {
            "lr", "rl" -> {
                swapXY(graph)
                swapWidthHeight(graph)
            }
        }
    }

    private fun swapWidthHeight(graph: DagreGraph) {
        graph.nodes().forEach {
            graph.nodeOrThrow(it).apply {
                val oldWidth = width
                width = height
                height = oldWidth
            }
        }
        graph.edges().forEach {
            graph.edgeOrThrow(it).apply {
                val oldWidth = width
                width = height
                height = oldWidth
            }
        }
    }

    private fun reverseY(graph: DagreGraph) {
        graph.nodes().forEach { graph.nodeOrThrow(it).y = -graph.nodeOrThrow(it).y }
        graph.edges().forEach { edgeRef ->
            val edge = graph.edgeOrThrow(edgeRef)
            edge.points.indices.forEach { index ->
                edge.points[index] = edge.points[index].copy(y = -edge.points[index].y)
            }
            edge.y = edge.y?.let { -it }
        }
    }

    private fun swapXY(graph: DagreGraph) {
        graph.nodes().forEach {
            graph.nodeOrThrow(it).apply {
                val oldX = x
                x = y
                y = oldX
            }
        }
        graph.edges().forEach { edgeRef ->
            val edge = graph.edgeOrThrow(edgeRef)
            edge.points.indices.forEach { index ->
                val point = edge.points[index]
                edge.points[index] = ScenePoint(point.y, point.x)
            }
            if (edge.x != null || edge.y != null) {
                val oldX = edge.x
                edge.x = edge.y
                edge.y = oldX
            }
        }
    }
}
