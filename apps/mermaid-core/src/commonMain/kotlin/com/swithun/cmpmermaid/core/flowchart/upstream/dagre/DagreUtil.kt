package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.EdgeRef
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph
import kotlin.math.abs
import kotlin.math.max

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/util.js
 */
internal object DagreUtil {
    private var nextId = 0

    fun resetIds() {
        nextId = 0
    }

    fun uniqueId(prefix: String): String = "$prefix${++nextId}"

    fun addDummyNode(
        graph: DagreGraph,
        type: String,
        attrs: DagreNode,
        prefix: String,
    ): String {
        var id: String
        do {
            id = uniqueId(prefix)
        } while (graph.hasNode(id))
        attrs.dummy = type
        graph.setNode(id, attrs)
        return id
    }

    fun simplify(graph: DagreGraph): DagreGraph {
        val result = Graph<DagreNode, DagreEdge, DagreGraphLabel>()
            .setGraph(graph.graph())
        graph.nodes().forEach { result.setNode(it, graph.nodeOrThrow(it)) }
        graph.edges().forEach { edge ->
            val existing = result.edge(edge.v, edge.w)
            val label = graph.edgeOrThrow(edge)
            result.setEdge(
                edge.v,
                edge.w,
                DagreEdge(
                    weight = (existing?.weight ?: 0f) + label.weight,
                    minlen = max(existing?.minlen ?: 1, label.minlen),
                ),
            )
        }
        return result
    }

    fun asNonCompoundGraph(graph: DagreGraph): DagreGraph {
        val result = Graph<DagreNode, DagreEdge, DagreGraphLabel>(
            multigraph = graph.multigraph,
        ).setGraph(graph.graph())
        graph.nodes().filter { graph.children(it).isEmpty() }.forEach {
            result.setNode(it, graph.nodeOrThrow(it))
        }
        graph.edges().forEach { edge ->
            if (result.hasNode(edge.v) && result.hasNode(edge.w)) {
                result.setEdge(edge, graph.edgeOrThrow(edge))
            }
        }
        return result
    }

    fun successorWeights(graph: DagreGraph): Map<String, Map<String, Float>> =
        graph.nodes().associateWith { id ->
            buildMap {
                graph.outEdges(id).forEach { edge ->
                    put(edge.w, (get(edge.w) ?: 0f) + graph.edgeOrThrow(edge).weight)
                }
            }
        }

    fun predecessorWeights(graph: DagreGraph): Map<String, Map<String, Float>> =
        graph.nodes().associateWith { id ->
            buildMap {
                graph.inEdges(id).forEach { edge ->
                    put(edge.v, (get(edge.v) ?: 0f) + graph.edgeOrThrow(edge).weight)
                }
            }
        }

    fun intersectRect(rect: DagreNode, point: ScenePoint): ScenePoint {
        val dx = point.x - rect.x
        val dy = point.y - rect.y
        var halfWidth = rect.width / 2f
        var halfHeight = rect.height / 2f
        if (abs(dx) < EPSILON && abs(dy) < EPSILON) {
            return ScenePoint(rect.x, rect.y)
        }
        val sx: Float
        val sy: Float
        if (abs(dy) * halfWidth > abs(dx) * halfHeight) {
            if (dy < 0f) halfHeight = -halfHeight
            sx = halfHeight * dx / dy
            sy = halfHeight
        } else {
            if (dx < 0f) halfWidth = -halfWidth
            sx = halfWidth
            sy = halfWidth * dy / dx
        }
        return ScenePoint(rect.x + sx, rect.y + sy)
    }

    fun buildLayerMatrix(graph: DagreGraph): List<List<String>> {
        val layers = MutableList(maxRank(graph) + 1) { mutableListOf<String?>() }
        graph.nodes().forEach { id ->
            val node = graph.nodeOrThrow(id)
            val rank = node.rank ?: return@forEach
            val order = node.orderOrThrow()
            val layer = layers[rank]
            while (layer.size <= order) layer += null
            layer[order] = id
        }
        return layers.map { layer -> layer.filterNotNull() }
    }

    fun normalizeRanks(graph: DagreGraph) {
        val minimum = graph.nodes().mapNotNull { graph.nodeOrThrow(it).rank }.minOrNull() ?: 0
        graph.nodes().forEach {
            graph.nodeOrThrow(it).rank = graph.nodeOrThrow(it).rank?.minus(minimum)
        }
    }

    fun removeEmptyRanks(graph: DagreGraph) {
        val minimum = graph.nodes().mapNotNull { graph.nodeOrThrow(it).rank }.minOrNull() ?: 0
        val layers = mutableMapOf<Int, MutableList<String>>()
        graph.nodes().forEach { id ->
            val rank = graph.nodeOrThrow(id).rank ?: return@forEach
            layers.getOrPut(rank - minimum) { mutableListOf() } += id
        }
        var delta = 0
        val factor = graph.graph().nodeRankFactor
        for (rank in 0..(layers.keys.maxOrNull() ?: 0)) {
            val ids = layers[rank]
            if (ids == null && rank % factor != 0) {
                delta -= 1
            } else if (delta != 0) {
                ids.orEmpty().forEach {
                    val node = graph.nodeOrThrow(it)
                    node.rank = node.rankOrThrow() + delta
                }
            }
        }
    }

    fun addBorderNode(
        graph: DagreGraph,
        prefix: String,
        rank: Int? = null,
        order: Int? = null,
    ): String = addDummyNode(
        graph,
        "border",
        DagreNode(
            width = 0f,
            height = 0f,
            rank = rank,
            order = order,
        ),
        prefix,
    )

    fun maxRank(graph: DagreGraph): Int =
        graph.nodes().mapNotNull { graph.nodeOrThrow(it).rank }.maxOrNull() ?: 0

    fun copyEdge(edge: DagreEdge): DagreEdge = edge.copy(
        points = edge.points.toMutableList(),
    )

    private const val EPSILON = 0.0001f
}

internal typealias DagreGraph = Graph<DagreNode, DagreEdge, DagreGraphLabel>

internal fun EdgeRef.other(id: String): String = if (v == id) w else v
