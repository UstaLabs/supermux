package com.swithun.cmpmermaid.core.flowchart.upstream.dagre

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.flowchart.upstream.graphlib.Graph
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin source port of dagre-d3-es 7.0.14:
 * src/dagre/layout.js
 */
internal object DagreLayout {
    fun run(input: DagreGraph): GMResult<Unit, MermaidError> = run(input, null)

    internal fun run(
        input: DagreGraph,
        observer: ((String, DagreGraph) -> Unit)?,
    ): GMResult<Unit, MermaidError> {
        DagreUtil.resetIds()
        return try {
            val layout = buildLayoutGraph(input)
            runLayout(layout, observer)
            updateInputGraph(input, layout)
            GMResult.Ok(Unit)
        } catch (failure: IllegalArgumentException) {
            GMResult.Err(MermaidError.Layout(failure.message ?: "Invalid Dagre graph"))
        } catch (failure: IllegalStateException) {
            GMResult.Err(MermaidError.Layout(failure.message ?: "Dagre layout failed"))
        }
    }

    private fun runLayout(
        graph: DagreGraph,
        observer: ((String, DagreGraph) -> Unit)?,
    ) {
        stage("makeSpaceForEdgeLabels", graph, observer) { makeSpaceForEdgeLabels(graph) }
        stage("removeSelfEdges", graph, observer) { removeSelfEdges(graph) }
        stage("acyclic", graph, observer) { Acyclic.run(graph) }
        stage("nestingGraph.run", graph, observer) { NestingGraph.run(graph) }
        stage("rank", graph, observer) { Rank.run(DagreUtil.asNonCompoundGraph(graph)) }
        stage("injectEdgeLabelProxies", graph, observer) { injectEdgeLabelProxies(graph) }
        stage("removeEmptyRanks", graph, observer) { DagreUtil.removeEmptyRanks(graph) }
        stage("nestingGraph.cleanup", graph, observer) { NestingGraph.cleanup(graph) }
        stage("normalizeRanks", graph, observer) { DagreUtil.normalizeRanks(graph) }
        stage("assignRankMinMax", graph, observer) { assignRankMinMax(graph) }
        stage("removeEdgeLabelProxies", graph, observer) { removeEdgeLabelProxies(graph) }
        stage("normalize.run", graph, observer) { Normalize.run(graph) }
        stage("parentDummyChains", graph, observer) { Compound.parentDummyChains(graph) }
        stage("addBorderSegments", graph, observer) { Compound.addBorderSegments(graph) }
        stage("order", graph, observer) { Order.run(graph) }
        stage("insertSelfEdges", graph, observer) { insertSelfEdges(graph) }
        stage("adjustCoordinateSystem", graph, observer) { CoordinateSystem.adjust(graph) }
        stage("position", graph, observer) { Position.run(graph) }
        stage("positionSelfEdges", graph, observer) { positionSelfEdges(graph) }
        stage("removeBorderNodes", graph, observer) { removeBorderNodes(graph) }
        stage("normalize.undo", graph, observer) { Normalize.undo(graph) }
        stage("fixupEdgeLabelCoords", graph, observer) { fixupEdgeLabelCoordinates(graph) }
        stage("undoCoordinateSystem", graph, observer) { CoordinateSystem.undo(graph) }
        stage("translateGraph", graph, observer) { translateGraph(graph) }
        stage("assignNodeIntersects", graph, observer) { assignNodeIntersects(graph) }
        stage("reversePoints", graph, observer) { reversePointsForReversedEdges(graph) }
        stage("acyclic.undo", graph, observer) { Acyclic.undo(graph) }
    }

    private fun stage(
        name: String,
        graph: DagreGraph,
        observer: ((String, DagreGraph) -> Unit)?,
        action: () -> Unit,
    ) {
        try {
            action()
            observer?.invoke(name, graph)
        } catch (failure: IllegalArgumentException) {
            throw IllegalStateException("$name: ${failure.message ?: "invalid graph"}")
        } catch (failure: IllegalStateException) {
            throw IllegalStateException("$name: ${failure.message ?: "layout failed"}")
        }
    }

    private fun buildLayoutGraph(input: DagreGraph): DagreGraph {
        val sourceLabel = input.graph()
        val graph = Graph<DagreNode, DagreEdge, DagreGraphLabel>(
            multigraph = true,
            compound = true,
        ).setGraph(
            DagreGraphLabel(
                nodesep = sourceLabel.nodesep,
                edgesep = sourceLabel.edgesep,
                ranksep = sourceLabel.ranksep,
                marginx = sourceLabel.marginx,
                marginy = sourceLabel.marginy,
                acyclicer = sourceLabel.acyclicer,
                ranker = sourceLabel.ranker,
                rankdir = sourceLabel.rankdir,
                align = sourceLabel.align,
            ),
        )
        input.nodes().forEach { id ->
            val source = input.nodeOrThrow(id)
            graph.setNode(
                id,
                DagreNode(
                    width = source.width,
                    height = source.height,
                    originalId = source.originalId,
                ),
            )
        }
        input.nodes().forEach { id ->
            input.parent(id)?.let { graph.setParent(id, it) }
        }
        input.edges().forEach { edgeRef ->
            val source = input.edgeOrThrow(edgeRef)
            graph.setEdge(
                edgeRef,
                DagreEdge(
                    minlen = source.minlen,
                    weight = source.weight,
                    width = source.width,
                    height = source.height,
                    labeloffset = source.labeloffset,
                    labelpos = source.labelpos,
                    originalIndex = source.originalIndex,
                    fromCluster = source.fromCluster,
                    toCluster = source.toCluster,
                    selfLoop = source.selfLoop,
                    originalEdge = source.originalEdge,
                ),
            )
        }
        return graph
    }

    private fun updateInputGraph(input: DagreGraph, layout: DagreGraph) {
        input.nodes().forEach { id ->
            val inputNode = input.nodeOrThrow(id)
            val layoutNode = layout.nodeOrThrow(id)
            inputNode.x = layoutNode.x
            inputNode.y = layoutNode.y
            if (layout.children(id).isNotEmpty()) {
                inputNode.width = layoutNode.width
                inputNode.height = layoutNode.height
            }
        }
        input.edges().forEach { edgeRef ->
            val inputEdge = input.edgeOrThrow(edgeRef)
            val layoutEdge = layout.edgeOrThrow(edgeRef)
            inputEdge.points.clear()
            inputEdge.points += layoutEdge.points
            inputEdge.x = layoutEdge.x
            inputEdge.y = layoutEdge.y
        }
        input.graph().width = layout.graph().width
        input.graph().height = layout.graph().height
    }

    private fun makeSpaceForEdgeLabels(graph: DagreGraph) {
        graph.graph().ranksep /= 2f
        graph.edges().forEach { edgeRef ->
            val edge = graph.edgeOrThrow(edgeRef)
            edge.minlen *= 2
            if (edge.labelpos.lowercase() != "c") {
                if (graph.graph().rankdir.uppercase() in setOf("TB", "BT")) {
                    edge.width += edge.labeloffset
                } else {
                    edge.height += edge.labeloffset
                }
            }
        }
    }

    private fun injectEdgeLabelProxies(graph: DagreGraph) {
        graph.edges().forEach { edgeRef ->
            val edge = graph.edgeOrThrow(edgeRef)
            if (edge.width != 0f && edge.height != 0f) {
                val fromRank = graph.nodeOrThrow(edgeRef.v).rankOrThrow()
                val toRank = graph.nodeOrThrow(edgeRef.w).rankOrThrow()
                DagreUtil.addDummyNode(
                    graph,
                    "edge-proxy",
                    DagreNode(
                        rank = (toRank - fromRank) / 2 + fromRank,
                        edgeObj = edgeRef,
                    ),
                    "_ep",
                )
            }
        }
    }

    private fun assignRankMinMax(graph: DagreGraph) {
        var maximumRank = 0
        graph.nodes().forEach { id ->
            val node = graph.nodeOrThrow(id)
            val top = node.borderTop ?: return@forEach
            val bottom = node.borderBottom ?: return@forEach
            node.minRank = graph.nodeOrThrow(top).rank
            node.maxRank = graph.nodeOrThrow(bottom).rank
            maximumRank = max(maximumRank, node.maxRank!!)
        }
        graph.graph().maxRank = maximumRank
    }

    private fun removeEdgeLabelProxies(graph: DagreGraph) {
        graph.nodes().toList().forEach { id ->
            val node = graph.nodeOrThrow(id)
            if (node.dummy == "edge-proxy") {
                node.edgeObj?.let { graph.edgeOrThrow(it).labelRank = node.rank }
                graph.removeNode(id)
            }
        }
    }

    private fun removeSelfEdges(graph: DagreGraph) {
        graph.edges().toList().forEach { edgeRef ->
            if (edgeRef.v == edgeRef.w) {
                graph.nodeOrThrow(edgeRef.v).selfEdges +=
                    SelfEdge(edgeRef, graph.edgeOrThrow(edgeRef))
                graph.removeEdge(edgeRef)
            }
        }
    }

    private fun insertSelfEdges(graph: DagreGraph) {
        DagreUtil.buildLayerMatrix(graph).forEach { layer ->
            var orderShift = 0
            layer.forEachIndexed { index, id ->
                val node = graph.nodeOrThrow(id)
                node.order = index + orderShift
                node.selfEdges.forEach { selfEdge ->
                    orderShift += 1
                    DagreUtil.addDummyNode(
                        graph,
                        "selfedge",
                        DagreNode(
                            width = selfEdge.label.width,
                            height = selfEdge.label.height,
                            rank = node.rank,
                            order = index + orderShift,
                            edgeObj = selfEdge.edge,
                            edgeLabel = selfEdge.label,
                        ),
                        "_se",
                    )
                }
                node.selfEdges.clear()
            }
        }
    }

    private fun positionSelfEdges(graph: DagreGraph) {
        graph.nodes().toList().forEach { id ->
            val node = graph.nodeOrThrow(id)
            if (node.dummy != "selfedge") return@forEach
            val edgeRef = requireNotNull(node.edgeObj)
            val edge = requireNotNull(node.edgeLabel)
            val target = graph.nodeOrThrow(edgeRef.v)
            val x = target.x + target.width / 2f
            val y = target.y
            val dx = node.x - x
            val dy = target.height / 2f
            graph.setEdge(edgeRef, edge)
            graph.removeNode(id)
            edge.points.clear()
            edge.points += listOf(
                ScenePoint(x + 2f * dx / 3f, y - dy),
                ScenePoint(x + 5f * dx / 6f, y - dy),
                ScenePoint(x + dx, y),
                ScenePoint(x + 5f * dx / 6f, y + dy),
                ScenePoint(x + 2f * dx / 3f, y + dy),
            )
            edge.x = node.x
            edge.y = node.y
        }
    }

    private fun removeBorderNodes(graph: DagreGraph) {
        graph.nodes().forEach { id ->
            if (graph.children(id).isEmpty()) return@forEach
            val node = graph.nodeOrThrow(id)
            val top = node.borderTop?.let(graph::nodeOrThrow) ?: return@forEach
            val bottom = node.borderBottom?.let(graph::nodeOrThrow) ?: return@forEach
            val left = node.borderLeft.maxByOrNull { it.key }?.value?.let(graph::nodeOrThrow)
                ?: return@forEach
            val right = node.borderRight.maxByOrNull { it.key }?.value?.let(graph::nodeOrThrow)
                ?: return@forEach
            node.width = abs(right.x - left.x)
            node.height = abs(bottom.y - top.y)
            node.x = left.x + node.width / 2f
            node.y = top.y + node.height / 2f
        }
        graph.nodes().toList().forEach { id ->
            if (graph.nodeOrThrow(id).dummy == "border") graph.removeNode(id)
        }
    }

    private fun fixupEdgeLabelCoordinates(graph: DagreGraph) {
        graph.edges().forEach { edgeRef ->
            val edge = graph.edgeOrThrow(edgeRef)
            val x = edge.x ?: return@forEach
            if (edge.labelpos in setOf("l", "r")) edge.width -= edge.labeloffset
            edge.x = when (edge.labelpos) {
                "l" -> x - edge.width / 2f - edge.labeloffset
                "r" -> x + edge.width / 2f + edge.labeloffset
                else -> x
            }
        }
    }

    private fun translateGraph(graph: DagreGraph) {
        var minimumX = Float.POSITIVE_INFINITY
        var maximumX = 0f
        var minimumY = Float.POSITIVE_INFINITY
        var maximumY = 0f

        fun include(x: Float, y: Float, width: Float, height: Float) {
            minimumX = min(minimumX, x - width / 2f)
            maximumX = max(maximumX, x + width / 2f)
            minimumY = min(minimumY, y - height / 2f)
            maximumY = max(maximumY, y + height / 2f)
        }

        graph.nodes().forEach {
            val node = graph.nodeOrThrow(it)
            include(node.x, node.y, node.width, node.height)
        }
        graph.edges().forEach {
            val edge = graph.edgeOrThrow(it)
            if (edge.x != null && edge.y != null) {
                include(edge.x!!, edge.y!!, edge.width, edge.height)
            }
        }
        minimumX -= graph.graph().marginx
        minimumY -= graph.graph().marginy
        graph.nodes().forEach {
            graph.nodeOrThrow(it).apply {
                x -= minimumX
                y -= minimumY
            }
        }
        graph.edges().forEach {
            graph.edgeOrThrow(it).apply {
                points.indices.forEach { index ->
                    points[index] = ScenePoint(points[index].x - minimumX, points[index].y - minimumY)
                }
                x = x?.minus(minimumX)
                y = y?.minus(minimumY)
            }
        }
        graph.graph().width = maximumX - minimumX + graph.graph().marginx
        graph.graph().height = maximumY - minimumY + graph.graph().marginy
    }

    private fun assignNodeIntersects(graph: DagreGraph) {
        graph.edges().forEach { edgeRef ->
            val edge = graph.edgeOrThrow(edgeRef)
            val from = graph.nodeOrThrow(edgeRef.v)
            val to = graph.nodeOrThrow(edgeRef.w)
            val first = edge.points.firstOrNull() ?: ScenePoint(to.x, to.y)
            val last = edge.points.lastOrNull() ?: ScenePoint(from.x, from.y)
            edge.points.add(0, DagreUtil.intersectRect(from, first))
            edge.points += DagreUtil.intersectRect(to, last)
        }
    }

    private fun reversePointsForReversedEdges(graph: DagreGraph) {
        graph.edges().forEach {
            val edge = graph.edgeOrThrow(it)
            if (edge.reversed) edge.points.reverse()
        }
    }
}
