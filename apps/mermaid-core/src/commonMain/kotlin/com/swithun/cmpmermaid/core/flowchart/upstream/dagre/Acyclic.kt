package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.EdgeRef

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/acyclic.js
 *
 * The default DFS feedback arc set is Mermaid's normal path. The optional
 * greedy acyclicer is delegated to [GreedyFas].
 */
internal object Acyclic {
    fun run(graph: DagreGraph) {
        val feedback = if (graph.graph().acyclicer == "greedy") {
            GreedyFas.find(graph)
        } else {
            dfsFeedbackArcSet(graph)
        }
        feedback.forEach { edge ->
            val label = graph.edgeOrThrow(edge)
            graph.removeEdge(edge)
            label.forwardName = edge.name
            label.reversed = true
            graph.setEdge(
                edge.w,
                edge.v,
                label,
                DagreUtil.uniqueId("rev"),
            )
        }
    }

    fun undo(graph: DagreGraph) {
        graph.edges().toList().forEach { edge ->
            val label = graph.edgeOrThrow(edge)
            if (!label.reversed) return@forEach
            graph.removeEdge(edge)
            val forwardName = label.forwardName
            label.reversed = false
            label.forwardName = null
            graph.setEdge(edge.w, edge.v, label, forwardName)
        }
    }

    private fun dfsFeedbackArcSet(graph: DagreGraph): List<EdgeRef> {
        val result = mutableListOf<EdgeRef>()
        val stack = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String) {
            if (!visited.add(id)) return
            stack += id
            graph.outEdges(id).forEach { edge ->
                if (edge.w in stack) {
                    result += edge
                } else {
                    visit(edge.w)
                }
            }
            stack -= id
        }

        graph.nodes().forEach(::visit)
        return result
    }
}
