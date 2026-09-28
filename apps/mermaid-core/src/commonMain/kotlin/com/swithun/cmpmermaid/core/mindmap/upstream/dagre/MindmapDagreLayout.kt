package com.swithun.cmpmermaid.core.mindmap.upstream.dagre

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreEdge
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreGraphLabel
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreNode
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.mindmap.MindmapPlacement
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDocument
import kotlin.math.abs

/**
 * Mermaid 12.0.0:
 * rendering-util/layout-algorithms/dagre/index.js -> prepareLayoutForDagre,
 * runDagreGraphLayout, normalizeDagreLayout.
 *
 * Mindmap supplies a flat TB graph with measured node dimensions, 50 px node
 * and rank spacing, and 8 px Dagre margins.
 */
internal object MindmapDagreLayout {
    fun layout(
        document: MindmapDocument,
        shapeLayouts: Map<Int, MermaidShapeLayout>,
    ): GMResult<MindmapPlacement, MermaidError> {
        val graph = Graph<DagreNode, DagreEdge, DagreGraphLabel>(
            multigraph = true,
            compound = true,
        ).setGraph(
            DagreGraphLabel(
                rankdir = "TB",
                nodesep = NODE_SPACING,
                ranksep = RANK_SPACING,
                marginx = GRAPH_MARGIN,
                marginy = GRAPH_MARGIN,
            ),
        )

        document.nodes.forEach { node ->
            val shape = shapeLayouts[node.id]
                ?: return layoutError("Mindmap node '${node.nodeId}' has no measured shape")
            graph.setNode(
                node.id.toString(),
                DagreNode(
                    width = shape.size.width,
                    height = shape.size.height,
                    originalId = node.id.toString(),
                ),
            )
        }
        document.edges.forEachIndexed { index, edge ->
            if (!graph.hasNode(edge.start.toString()) || !graph.hasNode(edge.end.toString())) {
                return layoutError("Mindmap edge '${edge.id}' references an unknown node")
            }
            graph.setEdge(
                from = edge.start.toString(),
                to = edge.end.toString(),
                label = DagreEdge(originalIndex = index),
                name = edge.id,
            )
        }

        when (val result = DagreLayout.run(graph)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }

        val centers = linkedMapOf<Int, ScenePoint>()
        document.nodes.forEach { node ->
            val positioned = graph.node(node.id.toString())
                ?: return layoutError("Dagre did not position Mindmap node '${node.nodeId}'")
            centers[node.id] = ScenePoint(positioned.x, positioned.y)
        }

        val edgePoints = linkedMapOf<String, List<ScenePoint>>()
        document.edges.forEach { edge ->
            val routed = graph.edge(edge.start.toString(), edge.end.toString(), edge.id)
                ?: return layoutError("Dagre did not route Mindmap edge '${edge.id}'")
            val points = routed.points.toMutableList()
            if (points.size < 2) {
                return layoutError("Dagre returned fewer than two points for '${edge.id}'")
            }
            val sourceCenter = centers[edge.start]
                ?: return layoutError("Mindmap edge '${edge.id}' has no source center")
            val targetCenter = centers[edge.end]
                ?: return layoutError("Mindmap edge '${edge.id}' has no target center")
            val sourceShape = shapeLayouts[edge.start]
                ?: return layoutError("Mindmap edge '${edge.id}' has no source shape")
            val targetShape = shapeLayouts[edge.end]
                ?: return layoutError("Mindmap edge '${edge.id}' has no target shape")
            points[0] = intersect(sourceShape, sourceCenter, points[1])
            points[points.lastIndex] = intersect(
                targetShape,
                targetCenter,
                points[points.lastIndex - 1],
            )
            edgePoints[edge.id] = points
        }
        return GMResult.Ok(
            MindmapPlacement(
                nodeCenters = centers,
                edgePoints = edgePoints,
            ),
        )
    }

    private fun intersect(
        shape: MermaidShapeLayout,
        center: ScenePoint,
        toward: ScenePoint,
    ): ScenePoint {
        val outline = shape.geometry.outline
            .takeIf(List<ScenePoint>::isNotEmpty)
            ?.map { point -> ScenePoint(center.x + point.x, center.y + point.y) }
            ?: shape.size.rectangleOutline(center)
        val directionX = toward.x - center.x
        val directionY = toward.y - center.y
        var best: Pair<Float, ScenePoint>? = null
        (outline + outline.first()).zipWithNext().forEach { (first, second) ->
            val segmentX = second.x - first.x
            val segmentY = second.y - first.y
            val denominator = directionX * segmentY - directionY * segmentX
            if (abs(denominator) < POINT_EPSILON) {
                return@forEach
            }
            val offsetX = first.x - center.x
            val offsetY = first.y - center.y
            val ray = (offsetX * segmentY - offsetY * segmentX) / denominator
            val segment = (offsetX * directionY - offsetY * directionX) / denominator
            val current = best
            if (
                ray in 0f..1f &&
                segment in 0f..1f &&
                (current == null || ray > current.first)
            ) {
                best = ray to ScenePoint(
                    x = center.x + directionX * ray,
                    y = center.y + directionY * ray,
                )
            }
        }
        return best?.second ?: center
    }

    private fun com.swithun.cmpmermaid.core.SceneSize.rectangleOutline(
        center: ScenePoint,
    ): List<ScenePoint> {
        val bounds = SceneRect(
            left = center.x - width / 2f,
            top = center.y - height / 2f,
            right = center.x + width / 2f,
            bottom = center.y + height / 2f,
        )
        return listOf(
            ScenePoint(bounds.left, bounds.top),
            ScenePoint(bounds.right, bounds.top),
            ScenePoint(bounds.right, bounds.bottom),
            ScenePoint(bounds.left, bounds.bottom),
        )
    }

    private fun <T> layoutError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Layout(message))

    private const val NODE_SPACING = 50f
    private const val RANK_SPACING = 50f
    private const val GRAPH_MARGIN = 8f
    private const val POINT_EPSILON = 0.0001f
}
