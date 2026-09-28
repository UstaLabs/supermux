package com.swithun.cmpmermaid.core.flowchart.upstream.graphlib

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/graphlib/alg/dfs.js
 * src/graphlib/alg/preorder.js
 * src/graphlib/alg/postorder.js
 * src/graphlib/alg/components.js
 */
internal object Algorithms {
    fun <N : Any, E : Any, G : Any> preorder(
        graph: Graph<N, E, G>,
        starts: List<String>,
    ): List<String> = dfs(graph, starts, postorder = false)

    fun <N : Any, E : Any, G : Any> preorder(
        graph: Graph<N, E, G>,
        start: String,
    ): List<String> = preorder(graph, listOf(start))

    fun <N : Any, E : Any, G : Any> postorder(
        graph: Graph<N, E, G>,
        starts: List<String>,
    ): List<String> = dfs(graph, starts, postorder = true)

    fun <N : Any, E : Any, G : Any> postorder(
        graph: Graph<N, E, G>,
        start: String,
    ): List<String> = postorder(graph, listOf(start))

    fun <N : Any, E : Any, G : Any> components(
        graph: Graph<N, E, G>,
    ): List<List<String>> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<List<String>>()

        fun visit(id: String, component: MutableList<String>) {
            if (!visited.add(id)) return
            component += id
            graph.successors(id).forEach { visit(it, component) }
            graph.predecessors(id).forEach { visit(it, component) }
        }

        graph.nodes().forEach { id ->
            val component = mutableListOf<String>()
            visit(id, component)
            if (component.isNotEmpty()) result += component
        }
        return result
    }

    private fun <N : Any, E : Any, G : Any> dfs(
        graph: Graph<N, E, G>,
        starts: List<String>,
        postorder: Boolean,
    ): List<String> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<String>()

        fun visit(id: String) {
            if (!visited.add(id)) return
            if (!postorder) result += id
            val next = if (graph.directed) graph.successors(id) else graph.neighbors(id)
            next.forEach(::visit)
            if (postorder) result += id
        }

        starts.forEach { id ->
            require(graph.hasNode(id)) { "Graph does not have node: $id" }
            visit(id)
        }
        return result
    }
}
