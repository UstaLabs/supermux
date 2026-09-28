package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.subGraphTitleTotalMargin
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreEdge
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreGraph
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreGraphLabel
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreNode
import com.swithun.cmpmermaid.core.flowchart.upstream.dagre.DagreSelfLoop
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidGraphAdapter
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Native adapter for Mermaid 12.0.0's Flowchart Dagre renderer.
 * Layout decisions stay in the translated upstream modules; this class only
 * converts measured Native nodes to and from Dagre labels.
 */
internal object FlowDagreLayout {
    fun layout(
        document: FlowchartDocument,
        nodeSizes: Map<String, SceneSize>,
        nodeShapeLayouts: Map<String, MermaidShapeLayout>,
        edgeLabelSizes: Map<Int, SceneSize>,
        options: MermaidRenderOptions,
    ): GMResult<FlowLayoutPlacement, MermaidError> {
        val graph = buildGraph(document, nodeSizes, edgeLabelSizes, options)
        when (val adjusted = MermaidGraphAdapter.adjustClustersAndEdges(graph)) {
            is GMResult.Err -> return adjusted
            is GMResult.Ok -> Unit
        }
        when (val result = layoutRecursively(graph)) {
            is GMResult.Err -> return result
            is GMResult.Ok -> Unit
        }

        val nodeBounds = linkedMapOf<String, SceneRect>()
        val subgraphBounds = linkedMapOf<String, SceneRect>()
        collectBounds(
            graph = graph,
            document = document,
            offsetX = 0f,
            offsetY = 0f,
            nodeBounds = nodeBounds,
            subgraphBounds = subgraphBounds,
        )
        val edges = collectEdges(
            graph = graph,
            document = document,
            nodeShapeLayouts = nodeShapeLayouts,
            nodeBounds = nodeBounds,
            subgraphBounds = subgraphBounds,
        )
        return GMResult.Ok(
            applySubgraphTitleMargins(
                placement = FlowLayoutPlacement(nodeBounds, subgraphBounds, edges),
                totalMargin = options.subGraphTitleTotalMargin,
            ),
        )
    }

    private fun applySubgraphTitleMargins(
        placement: FlowLayoutPlacement,
        totalMargin: Float,
    ): FlowLayoutPlacement {
        if (totalMargin == 0f) {
            return placement
        }
        val edgeOffset = totalMargin / 2f
        return FlowLayoutPlacement(
            nodeBounds = placement.nodeBounds.mapValues { (_, bounds) ->
                bounds.translate(0f, edgeOffset)
            },
            subgraphBounds = placement.subgraphBounds.mapValues { (_, bounds) ->
                SceneRect(
                    left = bounds.left,
                    top = bounds.top - edgeOffset,
                    right = bounds.right,
                    bottom = bounds.bottom + edgeOffset,
                )
            },
            edges = placement.edges.mapValues { (_, edge) ->
                edge.copy(
                    points = edge.points.map { point -> point.translate(0f, edgeOffset) },
                    labelAnchor = edge.labelAnchor.translate(0f, edgeOffset),
                )
            },
        )
    }

    private fun buildGraph(
        document: FlowchartDocument,
        nodeSizes: Map<String, SceneSize>,
        edgeLabelSizes: Map<Int, SceneSize>,
        options: MermaidRenderOptions,
    ): DagreGraph {
        val graph = Graph<DagreNode, DagreEdge, DagreGraphLabel>(
            multigraph = true,
            compound = true,
        ).setGraph(
            DagreGraphLabel(
                rankdir = document.direction.toDagreDirection(),
                nodesep = options.nodeSpacing,
                ranksep = options.rankSpacing,
                marginx = 8f,
                marginy = 8f,
            ),
        )
        val subgraphDepths = document.subgraphs.associate { subgraph ->
            subgraph.id to generateSequence(subgraph.parentId) { parent ->
                document.subgraphs.firstOrNull { it.id == parent }?.parentId
            }.count()
        }

        // Mermaid: rendering-util/layout-algorithms/dagre/index.js
        // -> prepareLayoutForDagre. FlowDB emits groups before regular nodes,
        // and Dagre uses that insertion order to break equal-crossing ties.
        document.subgraphs.forEach { subgraph ->
            graph.setNode(
                subgraph.id,
                DagreNode(
                    originalId = subgraph.id,
                    dir = subgraph.direction?.toDagreDirection(),
                ),
            )
            subgraph.parentId?.let { graph.setParent(subgraph.id, it) }
        }
        document.nodes.forEach { (nodeId, _) ->
            val size = nodeSizes.getValue(nodeId)
            graph.setNode(
                nodeId,
                DagreNode(width = size.width, height = size.height, originalId = nodeId),
            )
            document.subgraphs
                .filter { nodeId in it.nodeIds }
                .maxByOrNull { subgraphDepths.getValue(it.id) }
                ?.let { graph.setParent(nodeId, it.id) }
        }
        document.edges.forEachIndexed { index, edge ->
            val label = edgeLabelSizes[index]
            val dagreEdge = DagreEdge(
                minlen = edge.minimumLength,
                weight = 1f,
                width = label?.width ?: 0f,
                height = label?.height ?: 0f,
                labelpos = "c",
                originalIndex = index,
            )
            if (edge.from == edge.to) {
                addSelfLoop(graph, edge.from, edge.id, dagreEdge)
            } else {
                graph.setEdge(edge.from, edge.to, dagreEdge, edge.id)
            }
        }
        return graph
    }

    private fun layoutRecursively(
        graph: DagreGraph,
        parentCluster: ParentCluster? = null,
    ): GMResult<Unit, MermaidError> {
        graph.nodes().toList().forEach { id ->
            val node = graph.nodeOrThrow(id)
            val nested = node.graph ?: return@forEach
            val clusterData = node.clusterData
                ?: return GMResult.Err(
                    MermaidError.Layout("Extracted cluster '$id' has no cluster data"),
                )
            // Mermaid 12.0.0: rendering-util/layout-algorithms/dagre/index.js
            // -> measureDagreGraph. Extracted subgraphs inherit parent spacing,
            // with extra rank separation reserved for the cluster boundary.
            val graphSettings = graph.graph()
            nested.setGraph(
                nested.graph().copy(
                    ranksep = graphSettings.ranksep + 25f,
                    nodesep = graphSettings.nodesep,
                ),
            )
            when (
                val nestedResult = layoutRecursively(
                    graph = nested,
                    parentCluster = ParentCluster(id, clusterData),
                )
            ) {
                is GMResult.Err -> return nestedResult
                is GMResult.Ok -> Unit
            }
            val nestedRoot = nested.node(id)
                ?: return GMResult.Err(
                    MermaidError.Layout("Extracted cluster '$id' has no measured root"),
                )
            node.width = nestedRoot.width
            node.height = nestedRoot.height
        }

        parentCluster?.let { parent ->
            val rootChildren = graph.children()
            graph.setNode(
                parent.id,
                parent.data.copy(
                    width = 0f,
                    height = 0f,
                    x = 0f,
                    y = 0f,
                    rank = null,
                    order = null,
                    dummy = null,
                    edgeObj = null,
                    edgeLabel = null,
                    minRank = null,
                    maxRank = null,
                    borderTop = null,
                    borderBottom = null,
                    borderLeft = mutableMapOf(),
                    borderRight = mutableMapOf(),
                    selfEdges = mutableListOf(),
                    clusterNode = false,
                    clusterData = null,
                    graph = null,
                ),
            )
            rootChildren.forEach { child ->
                if (child != parent.id && graph.parent(child) == null) {
                    graph.setParent(child, parent.id)
                }
            }
        }

        return DagreLayout.run(graph)
    }

    private fun collectBounds(
        graph: DagreGraph,
        document: FlowchartDocument,
        offsetX: Float,
        offsetY: Float,
        nodeBounds: MutableMap<String, SceneRect>,
        subgraphBounds: MutableMap<String, SceneRect>,
    ) {
        graph.nodes().forEach { id ->
            val node = graph.nodeOrThrow(id)
            val nested = node.graph
            if (node.clusterNode && nested != null) {
                val nestedRoot = nested.node(id) ?: return@forEach
                collectBounds(
                    graph = nested,
                    document = document,
                    offsetX = offsetX + node.x - nestedRoot.x,
                    offsetY = offsetY + node.y - nestedRoot.y,
                    nodeBounds = nodeBounds,
                    subgraphBounds = subgraphBounds,
                )
                return@forEach
            }

            val originalId = node.originalId ?: return@forEach
            val bounds = node.toBounds().translate(offsetX, offsetY)
            if (originalId in document.nodes) {
                nodeBounds[originalId] = bounds
            }
            if (document.subgraphs.any { it.id == originalId } &&
                graph.children(id).isNotEmpty()
            ) {
                subgraphBounds[originalId] = bounds
            }
        }
    }

    private fun addSelfLoop(
        graph: DagreGraph,
        nodeId: String,
        edgeId: String,
        originalEdge: DagreEdge,
    ) {
        val loopPrefix = "$nodeId---$edgeId"
        val firstDummy = "$loopPrefix---1"
        val secondDummy = "$loopPrefix---2"
        val parent = graph.parent(nodeId)
        graph.setNode(firstDummy, DagreNode(width = 10f, height = 10f))
        graph.setNode(secondDummy, DagreNode(width = 10f, height = 10f))
        graph.setParent(firstDummy, parent)
        graph.setParent(secondDummy, parent)

        fun segment(order: Int, keepLabel: Boolean): DagreEdge = originalEdge.copy(
            width = if (keepLabel) originalEdge.width else 0f,
            height = if (keepLabel) originalEdge.height else 0f,
            points = mutableListOf(),
            selfLoop = DagreSelfLoop(edgeId, order),
            originalEdge = originalEdge,
        )

        graph.setEdge(
            nodeId,
            firstDummy,
            segment(order = 0, keepLabel = false),
            "$loopPrefix-cyclic-special-0",
        )
        graph.setEdge(
            firstDummy,
            secondDummy,
            segment(order = 1, keepLabel = true),
            "$loopPrefix-cyclic-special-1",
        )
        graph.setEdge(
            secondDummy,
            nodeId,
            segment(order = 2, keepLabel = false),
            "$loopPrefix-cyclic-special-2",
        )
    }

    private fun collectEdges(
        graph: DagreGraph,
        document: FlowchartDocument,
        nodeShapeLayouts: Map<String, MermaidShapeLayout>,
        nodeBounds: Map<String, SceneRect>,
        subgraphBounds: Map<String, SceneRect>,
    ): Map<Int, FlowRoutedEdge> {
        val result = linkedMapOf<Int, FlowRoutedEdge>()
        collectEdges(
            graph = graph,
            document = document,
            nodeShapeLayouts = nodeShapeLayouts,
            nodeBounds = nodeBounds,
            subgraphBounds = subgraphBounds,
            offsetX = 0f,
            offsetY = 0f,
            result = result,
        )
        return result
    }

    private fun collectEdges(
        graph: DagreGraph,
        document: FlowchartDocument,
        nodeShapeLayouts: Map<String, MermaidShapeLayout>,
        nodeBounds: Map<String, SceneRect>,
        subgraphBounds: Map<String, SceneRect>,
        offsetX: Float,
        offsetY: Float,
        result: MutableMap<Int, FlowRoutedEdge>,
    ) {
        val selfLoops = linkedMapOf<String, MutableList<SelfLoopSegment>>()
        graph.edges().forEach { edgeRef ->
            val edge = graph.edgeOrThrow(edgeRef)
            val selfLoop = edge.selfLoop
            if (selfLoop != null) {
                selfLoops.getOrPut(selfLoop.id) { mutableListOf() } +=
                    SelfLoopSegment(edgeRef.v, edgeRef.w, edge)
                return@forEach
            }
            addRoutedEdge(
                result = result,
                edge = edge,
                document = document,
                nodeShapeLayouts = nodeShapeLayouts,
                nodeBounds = nodeBounds,
                subgraphBounds = subgraphBounds,
                offsetX = offsetX,
                offsetY = offsetY,
            )
        }
        selfLoops.values.forEach { entries ->
            val segments = entries.sortedBy { it.edge.selfLoop?.order }
            val middle = segments.firstOrNull { it.edge.selfLoop?.order == 1 }?.edge
                ?: return@forEach
            val original = middle.originalEdge ?: return@forEach
            val index = original.originalIndex ?: return@forEach
            val source = document.edges.getOrNull(index) ?: return@forEach
            val node = graph.node(source.from) ?: return@forEach
            val side = selfLoopSide(
                graph = graph,
                node = node,
                dummyIds = segments.flatMap { listOf(it.start, it.end) }
                    .filter { it != source.from }
                    .distinct(),
            )
            val localPoints = selfLoopPoints(
                node = node,
                side = side,
                labelWidth = middle.width,
            )
            val points = localPoints.map { it.translate(offsetX, offsetY) }
            val localLabelAnchor = selfLoopLabelPosition(
                node = node,
                points = localPoints,
                side = side,
                labelWidth = middle.width,
                labelHeight = middle.height,
            )
            result[index] = FlowRoutedEdge(
                points = points,
                labelAnchor = localLabelAnchor.translate(offsetX, offsetY),
            )
        }

        graph.nodes().forEach { id ->
            val node = graph.nodeOrThrow(id)
            val nested = node.graph ?: return@forEach
            if (!node.clusterNode) return@forEach
            val nestedRoot = nested.node(id) ?: return@forEach
            collectEdges(
                graph = nested,
                document = document,
                nodeShapeLayouts = nodeShapeLayouts,
                nodeBounds = nodeBounds,
                subgraphBounds = subgraphBounds,
                offsetX = offsetX + node.x - nestedRoot.x,
                offsetY = offsetY + node.y - nestedRoot.y,
                result = result,
            )
        }
    }

    private fun addRoutedEdge(
        result: MutableMap<Int, FlowRoutedEdge>,
        edge: DagreEdge,
        document: FlowchartDocument,
        nodeShapeLayouts: Map<String, MermaidShapeLayout>,
        nodeBounds: Map<String, SceneRect>,
        subgraphBounds: Map<String, SceneRect>,
        offsetX: Float,
        offsetY: Float,
    ) {
        val index = edge.originalIndex ?: return
        val source = document.edges.getOrNull(index) ?: return
        val points = edge.points.mapTo(mutableListOf()) { it.translate(offsetX, offsetY) }
        if (points.size < 2) return

        val startBounds = edge.fromCluster?.let(subgraphBounds::get)
            ?: nodeBounds[source.from]
            ?: subgraphBounds[source.from]
        val endBounds = edge.toCluster?.let(subgraphBounds::get)
            ?: nodeBounds[source.to]
            ?: subgraphBounds[source.to]
        if (startBounds != null) {
            points[0] = intersect(
                shapeLayout = nodeShapeLayouts[source.from],
                bounds = startBounds,
                toward = points[1],
            )
        }
        if (endBounds != null) {
            points[points.lastIndex] = intersect(
                shapeLayout = nodeShapeLayouts[source.to],
                bounds = endBounds,
                toward = points[points.lastIndex - 1],
            )
        }
        // Mermaid.js 12.0.0: rendering-util/rendering-elements/edges.js
        // -> insertEdge. Marker offsets require a non-zero terminal segment.
        val routedPoints = points.removeAdjacentDuplicates()
        val labelAnchor = edge.x?.let { x ->
            edge.y?.let { y -> ScenePoint(x + offsetX, y + offsetY) }
        } ?: routedPoints.halfLengthPoint()
        result[index] = FlowRoutedEdge(routedPoints, labelAnchor)
    }

    private fun selfLoopSide(
        graph: DagreGraph,
        node: DagreNode,
        dummyIds: List<String>,
    ): SelfLoopSide {
        val hints = dummyIds.mapNotNull(graph::node)
        if (hints.isEmpty()) return defaultSelfLoopSide(graph.graph().rankdir)
        val centerX = hints.sumOf { it.x.toDouble() }.toFloat() / hints.size
        val centerY = hints.sumOf { it.y.toDouble() }.toFloat() / hints.size
        val deltaX = centerX - node.x
        val deltaY = centerY - node.y
        return if (abs(deltaX) > abs(deltaY)) {
            if (deltaX > 0f) SelfLoopSide.Right else SelfLoopSide.Left
        } else if (abs(deltaY) > 0f) {
            if (deltaY > 0f) SelfLoopSide.Bottom else SelfLoopSide.Top
        } else {
            defaultSelfLoopSide(graph.graph().rankdir)
        }
    }

    private fun defaultSelfLoopSide(rankdir: String): SelfLoopSide = when (rankdir.uppercase()) {
        "BT" -> SelfLoopSide.Bottom
        "LR" -> SelfLoopSide.Right
        "RL" -> SelfLoopSide.Left
        else -> SelfLoopSide.Top
    }

    private fun selfLoopPoints(
        node: DagreNode,
        side: SelfLoopSide,
        labelWidth: Float,
    ): List<ScenePoint> {
        val halfWidth = node.width / 2f
        val halfHeight = node.height / 2f
        val maximumSpan = max(36f, min(100f, node.width * 0.8f))
        val span = max(36f, min(max(labelWidth, node.width * 0.35f), maximumSpan))
        val depth = max(24f, min(min(node.width, node.height) * 0.45f, 48f))
        return when (side) {
            SelfLoopSide.Bottom -> {
                val bottom = node.y + halfHeight
                listOf(
                    ScenePoint(node.x - span / 2f, bottom),
                    ScenePoint(node.x - span / 2f, bottom + depth),
                    ScenePoint(node.x + span / 2f, bottom + depth),
                    ScenePoint(node.x + span / 2f, bottom),
                )
            }
            SelfLoopSide.Right -> {
                val right = node.x + halfWidth
                listOf(
                    ScenePoint(right, node.y - span / 2f),
                    ScenePoint(right + depth, node.y - span / 2f),
                    ScenePoint(right + depth, node.y + span / 2f),
                    ScenePoint(right, node.y + span / 2f),
                )
            }
            SelfLoopSide.Left -> {
                val left = node.x - halfWidth
                listOf(
                    ScenePoint(left, node.y - span / 2f),
                    ScenePoint(left - depth, node.y - span / 2f),
                    ScenePoint(left - depth, node.y + span / 2f),
                    ScenePoint(left, node.y + span / 2f),
                )
            }
            SelfLoopSide.Top -> {
                val top = node.y - halfHeight
                listOf(
                    ScenePoint(node.x - span / 2f, top),
                    ScenePoint(node.x - span / 2f, top - depth),
                    ScenePoint(node.x + span / 2f, top - depth),
                    ScenePoint(node.x + span / 2f, top),
                )
            }
        }
    }

    private fun selfLoopLabelPosition(
        node: DagreNode,
        points: List<ScenePoint>,
        side: SelfLoopSide,
        labelWidth: Float,
        labelHeight: Float,
    ): ScenePoint {
        val gap = 4f
        return when (side) {
            SelfLoopSide.Bottom ->
                ScenePoint(node.x, points.maxOf(ScenePoint::y) + labelHeight / 2f + gap)
            SelfLoopSide.Right ->
                ScenePoint(points.maxOf(ScenePoint::x) + labelWidth / 2f + gap, node.y)
            SelfLoopSide.Left ->
                ScenePoint(points.minOf(ScenePoint::x) - labelWidth / 2f - gap, node.y)
            SelfLoopSide.Top ->
                ScenePoint(node.x, points.minOf(ScenePoint::y) - labelHeight / 2f - gap)
        }
    }

    private enum class SelfLoopSide {
        Top,
        Bottom,
        Left,
        Right,
    }

    private data class SelfLoopSegment(
        val start: String,
        val end: String,
        val edge: DagreEdge,
    )

    private data class ParentCluster(
        val id: String,
        val data: DagreNode,
    )

    private fun DagreNode.toBounds(): SceneRect = SceneRect(
        left = x - width / 2f,
        top = y - height / 2f,
        right = x + width / 2f,
        bottom = y + height / 2f,
    )

    private fun ScenePoint.translate(dx: Float, dy: Float): ScenePoint =
        ScenePoint(x + dx, y + dy)

    private fun FlowDirection.toDagreDirection(): String = when (this) {
        FlowDirection.TopToBottom -> "TB"
        FlowDirection.BottomToTop -> "BT"
        FlowDirection.LeftToRight -> "LR"
        FlowDirection.RightToLeft -> "RL"
    }

    private fun intersect(
        shapeLayout: MermaidShapeLayout?,
        bounds: SceneRect,
        toward: ScenePoint,
    ): ScenePoint {
        val center = bounds.center
        val outline = if (shapeLayout == null) {
            listOf(
                ScenePoint(bounds.left, bounds.top),
                ScenePoint(bounds.right, bounds.top),
                ScenePoint(bounds.right, bounds.bottom),
                ScenePoint(bounds.left, bounds.bottom),
            )
        } else {
            shapeLayout.geometry.outline.map { point ->
                ScenePoint(center.x + point.x, center.y + point.y)
            }
        }
        val directionX = toward.x - center.x
        val directionY = toward.y - center.y
        var best: Pair<Float, ScenePoint>? = null
        (outline + outline.first()).zipWithNext().forEach { (first, second) ->
            val segmentX = second.x - first.x
            val segmentY = second.y - first.y
            val denominator = directionX * segmentY - directionY * segmentX
            if (abs(denominator) < 0.0001f) return@forEach
            val offsetX = first.x - center.x
            val offsetY = first.y - center.y
            val ray = (offsetX * segmentY - offsetY * segmentX) / denominator
            val segment = (offsetX * directionY - offsetY * directionX) / denominator
            val currentBest = best
            if (ray in 0f..1f &&
                segment in 0f..1f &&
                (currentBest == null || ray > currentBest.first)
            ) {
                best = ray to ScenePoint(
                    center.x + directionX * ray,
                    center.y + directionY * ray,
                )
            }
        }
        return best?.second ?: center
    }

    private fun List<ScenePoint>.halfLengthPoint(): ScenePoint {
        if (size == 1) return first()
        val lengths = zipWithNext { first, second ->
            abs(second.x - first.x) + abs(second.y - first.y)
        }
        val target = lengths.sum() / 2f
        var traversed = 0f
        lengths.forEachIndexed { index, length ->
            if (traversed + length >= target && length > 0f) {
                val ratio = (target - traversed) / length
                return ScenePoint(
                    x = this[index].x + (this[index + 1].x - this[index].x) * ratio,
                    y = this[index].y + (this[index + 1].y - this[index].y) * ratio,
                )
            }
            traversed += length
        }
        return last()
    }

    private fun List<ScenePoint>.removeAdjacentDuplicates(): List<ScenePoint> =
        filterIndexed { index, point ->
            index == 0 || point != this[index - 1]
        }
}
