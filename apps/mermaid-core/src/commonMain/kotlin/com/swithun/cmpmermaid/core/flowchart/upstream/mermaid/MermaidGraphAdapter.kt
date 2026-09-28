package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreGraph
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreGraphLabel
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreNode
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph

/**
 * Kotlin source port of Mermaid 12.0.0:
 * packages/mermaid/src/rendering-util/layout-algorithms/dagre/mermaid-graphlib.js
 */
internal object MermaidGraphAdapter {
    fun adjustClustersAndEdges(graph: DagreGraph): GMResult<Unit, MermaidError> =
        try {
            Context().adjustClustersAndEdges(graph, depth = 0)
            GMResult.Ok(Unit)
        } catch (failure: IllegalArgumentException) {
            GMResult.Err(
                MermaidError.Layout(
                    failure.message ?: "Invalid Mermaid cluster graph",
                ),
            )
        } catch (failure: IllegalStateException) {
            GMResult.Err(
                MermaidError.Layout(
                    failure.message ?: "Mermaid cluster extraction failed",
                ),
            )
        }

    private class Context {
        private val descendants = linkedMapOf<String, List<String>>()
        private val clusters = linkedMapOf<String, Cluster>()

        fun adjustClustersAndEdges(graph: DagreGraph, depth: Int) {
            if (depth > MAX_DEPTH) return

            graph.nodes().forEach { id ->
                if (graph.children(id).isNotEmpty()) {
                    descendants[id] = extractDescendants(id, graph)
                    clusters[id] = Cluster(
                        id = findNonClusterChild(id, graph, id),
                        clusterData = graph.nodeOrThrow(id),
                    )
                }
            }

            graph.nodes().forEach { id ->
                if (graph.children(id).isEmpty()) return@forEach
                graph.edges().forEach { edge ->
                    val fromInside = isDescendant(edge.v, id)
                    val toInside = isDescendant(edge.w, id)
                    if (fromInside xor toInside) {
                        clusters.getValue(id).externalConnections = true
                    }
                }
            }

            clusters.forEach { (id, cluster) ->
                val nonClusterChild = cluster.id ?: return@forEach
                val parent = graph.parent(nonClusterChild)
                if (parent != id && parent != null && clusters[parent]?.externalConnections == false) {
                    cluster.id = parent
                }

                val hasDirectOutgoingEdge = graph.edges().any { it.v == id }
                if (cluster.externalConnections &&
                    hasDirectOutgoingEdge &&
                    isNodeInExtractableCluster(graph, nonClusterChild, id)
                ) {
                    findSafeAnchorNode(graph, id, graph.parent(nonClusterChild))?.let {
                        cluster.id = it
                    }
                }
            }

            graph.edges().toList().forEach { edgeRef ->
                val edge = graph.edgeOrThrow(edgeRef)
                val fromCluster = clusters[edgeRef.v]
                val toCluster = clusters[edgeRef.w]
                if (fromCluster == null && toCluster == null) return@forEach

                val from = getAnchorId(edgeRef.v)
                val to = getAnchorId(edgeRef.w)
                graph.removeEdge(edgeRef)
                if (from != edgeRef.v) {
                    graph.parent(from)?.let { clusters[it]?.externalConnections = true }
                    edge.fromCluster = edgeRef.v
                }
                if (to != edgeRef.w) {
                    graph.parent(to)?.let { clusters[it]?.externalConnections = true }
                    edge.toCluster = edgeRef.w
                }
                graph.setEdge(from, to, edge, edgeRef.name)
            }

            extractor(graph, depth = 0)
        }

        private fun extractor(graph: DagreGraph, depth: Int) {
            if (depth > MAX_DEPTH) return
            if (graph.nodes().none { graph.children(it).isNotEmpty() }) return

            graph.nodes().toList().forEach { id ->
                val cluster = clusters[id] ?: return@forEach
                if (cluster.externalConnections || graph.children(id).isEmpty()) return@forEach

                val graphSettings = graph.graph()
                val direction = cluster.clusterData.dir
                    ?: if (graphSettings.rankdir.uppercase() == "TB") "LR" else "TB"
                val clusterGraph = Graph<
                    com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreNode,
                    com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreEdge,
                    DagreGraphLabel,
                    >(
                    multigraph = true,
                    compound = true,
                ).setGraph(
                    DagreGraphLabel(
                        rankdir = direction,
                        nodesep = DEFAULT_NODE_SEPARATION,
                        ranksep = DEFAULT_RANK_SEPARATION,
                        marginx = DEFAULT_MARGIN,
                        marginy = DEFAULT_MARGIN,
                        root = id,
                    ),
                )
                copy(id, graph, clusterGraph, id)
                graph.setNode(
                    id,
                    DagreNode(
                        originalId = id,
                        dir = cluster.clusterData.dir,
                        clusterNode = true,
                        clusterData = cluster.clusterData,
                        graph = clusterGraph,
                    ),
                )
            }

            graph.nodes().forEach { id ->
                graph.nodeOrThrow(id).graph?.let { nested ->
                    extractor(nested, depth + 1)
                }
            }
        }

        private fun copy(
            clusterId: String,
            graph: DagreGraph,
            newGraph: DagreGraph,
            rootId: String,
        ) {
            val nodes = graph.children(clusterId).toMutableList()
            if (clusterId != rootId) nodes += clusterId

            nodes.forEach { id ->
                if (graph.children(id).isNotEmpty()) {
                    copy(id, graph, newGraph, rootId)
                } else {
                    copyNode(id, graph, newGraph)
                    val originalParent = graph.parent(id)
                    if (rootId != originalParent && originalParent != null) {
                        copyNode(originalParent, graph, newGraph)
                        newGraph.setParent(id, originalParent)
                    }
                    if (clusterId != rootId && id != clusterId) {
                        copyNode(clusterId, graph, newGraph)
                        newGraph.setParent(id, clusterId)
                    }

                    graph.nodeEdges(id).toList().forEach { edge ->
                        if (edgeInCluster(edge.v, edge.w, rootId)) {
                            copyNode(edge.v, graph, newGraph)
                            copyNode(edge.w, graph, newGraph)
                            newGraph.setEdge(edge, graph.edgeOrThrow(edge))
                        }
                    }
                    graph.removeNode(id)
                }
            }
        }

        private fun copyNode(id: String, graph: DagreGraph, target: DagreGraph) {
            if (!target.hasNode(id)) {
                target.setNode(id, graph.nodeOrThrow(id))
            }
        }

        private fun edgeInCluster(from: String, to: String, clusterId: String): Boolean {
            if (from == clusterId || to == clusterId) return false
            return isDescendant(from, clusterId) || isDescendant(to, clusterId)
        }

        private fun extractDescendants(id: String, graph: DagreGraph): List<String> =
            graph.children(id).flatMap { child ->
                listOf(child) + extractDescendants(child, graph)
            }

        private fun isDescendant(id: String, ancestorId: String?): Boolean =
            ancestorId != null && id in descendants[ancestorId].orEmpty()

        private fun findNonClusterChild(
            id: String,
            graph: DagreGraph,
            clusterId: String,
        ): String? {
            val children = graph.children(id)
            if (children.isEmpty()) return id
            var reserve: String? = null
            for (child in children) {
                val candidate = findNonClusterChild(child, graph, clusterId) ?: continue
                if (findCommonEdges(graph, clusterId, candidate)) {
                    reserve = candidate
                } else {
                    return candidate
                }
            }
            return reserve
        }

        private fun findCommonEdges(
            graph: DagreGraph,
            first: String,
            second: String,
        ): Boolean {
            val firstEdges = graph.nodeEdges(first).map { edge ->
                if (edge.v == first) {
                    second to edge.w
                } else {
                    edge.v to first
                }
            }
            val secondEdges = graph.nodeEdges(second).map { it.v to it.w }.toSet()
            return firstEdges.any { it in secondEdges }
        }

        private fun getAnchorId(id: String): String {
            val cluster = clusters[id] ?: return id
            if (!cluster.externalConnections) return id
            return cluster.id ?: id
        }

        private fun isNodeInExtractableCluster(
            graph: DagreGraph,
            node: String,
            rootId: String,
        ): Boolean {
            var parent = graph.parent(node)
            while (parent != null && parent != rootId) {
                if (clusters[parent]?.externalConnections == false) return true
                parent = graph.parent(parent)
            }
            return false
        }

        private fun findSafeAnchorNode(
            graph: DagreGraph,
            clusterId: String,
            excludedCluster: String?,
        ): String? {
            for (child in graph.children(clusterId)) {
                if (child == excludedCluster || isDescendant(child, excludedCluster)) continue
                val candidate = findNonClusterChild(child, graph, clusterId) ?: continue
                if (!isNodeInExtractableCluster(graph, candidate, clusterId)) return candidate
            }
            return null
        }
    }

    private data class Cluster(
        var id: String?,
        var externalConnections: Boolean = false,
        val clusterData: DagreNode,
    )

    private const val MAX_DEPTH = 10
    private const val DEFAULT_NODE_SEPARATION = 50f
    private const val DEFAULT_RANK_SEPARATION = 50f
    private const val DEFAULT_MARGIN = 8f
}
