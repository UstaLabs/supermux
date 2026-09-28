package com.swithun.cmpmermaid.core.sankey.upstream.d3

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidSankeyOptions
import com.swithun.cmpmermaid.core.sankey.upstream.mermaid.SankeyGraph
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Kotlin translation of d3-sankey 0.12.3:
 * src/sankey.js and src/align.js.
 *
 * Copyright 2015 Mike Bostock. Distributed under BSD-3-Clause.
 */
internal object D3SankeyLayout {
    fun layout(
        source: SankeyGraph,
        options: MermaidSankeyOptions,
    ): GMResult<D3SankeyGraph, MermaidError> {
        val invalidLink = source.links.firstOrNull { link ->
            !link.value.isFinite() || link.value < 0.0
        }
        if (invalidLink != null) {
            return layoutError("link values must be finite and non-negative")
        }
        val nodes = source.nodes.mapIndexed { index, node ->
            D3SankeyNode(id = node.id, index = index)
        }
        val nodeById = nodes.associateBy(D3SankeyNode::id)
        val links = mutableListOf<D3SankeyLink>()
        source.links.forEachIndexed { index, link ->
            val sourceNode = nodeById[link.source.id]
                ?: return layoutError("missing source node '${link.source.id}'")
            val targetNode = nodeById[link.target.id]
                ?: return layoutError("missing target node '${link.target.id}'")
            D3SankeyLink(
                source = sourceNode,
                target = targetNode,
                value = link.value,
                index = index,
            ).also { resolved ->
                links += resolved
                sourceNode.sourceLinks += resolved
                targetNode.targetLinks += resolved
            }
        }
        val graph = D3SankeyGraph(nodes = nodes, links = links)
        computeNodeValues(nodes)
        when (val depths = computeNodeDepths(nodes)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return depths
        }
        when (val heights = computeNodeHeights(nodes)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return heights
        }
        computeNodeBreadths(graph, options)
        computeLinkBreadths(nodes)
        val finite = nodes.all(D3SankeyNode::hasFiniteGeometry) &&
            links.all(D3SankeyLink::hasFiniteGeometry)
        return if (finite) {
            GMResult.Ok(graph)
        } else {
            layoutError("layout produced non-finite geometry")
        }
    }

    private fun computeNodeValues(nodes: List<D3SankeyNode>) {
        nodes.forEach { node ->
            node.value = max(
                node.sourceLinks.sumOf(D3SankeyLink::value),
                node.targetLinks.sumOf(D3SankeyLink::value),
            )
        }
    }

    private fun computeNodeDepths(
        nodes: List<D3SankeyNode>,
    ): GMResult<Unit, MermaidError> {
        var current = LinkedHashSet(nodes)
        var depth = 0
        while (current.isNotEmpty()) {
            val next = linkedSetOf<D3SankeyNode>()
            current.forEach { node ->
                node.depth = depth
                node.sourceLinks.forEach { link -> next += link.target }
            }
            depth += 1
            if (depth > nodes.size) {
                return layoutError("circular link")
            }
            current = next
        }
        return GMResult.Ok(Unit)
    }

    private fun computeNodeHeights(
        nodes: List<D3SankeyNode>,
    ): GMResult<Unit, MermaidError> {
        var current = LinkedHashSet(nodes)
        var height = 0
        while (current.isNotEmpty()) {
            val next = linkedSetOf<D3SankeyNode>()
            current.forEach { node ->
                node.height = height
                node.targetLinks.forEach { link -> next += link.source }
            }
            height += 1
            if (height > nodes.size) {
                return layoutError("circular link")
            }
            current = next
        }
        return GMResult.Ok(Unit)
    }

    private fun computeNodeBreadths(
        graph: D3SankeyGraph,
        options: MermaidSankeyOptions,
    ) {
        val columns = computeNodeLayers(graph.nodes, options)
        val maxColumnSize = columns.maxOf(List<D3SankeyNode>::size)
        val availableHeight = options.height.toDouble()
        val nodePadding = options.nodePadding.toDouble() +
            if (options.showValues) SHOW_VALUES_PADDING else 0.0
        val paddingLimit = if (maxColumnSize == 1) {
            Double.POSITIVE_INFINITY
        } else {
            availableHeight / (maxColumnSize - 1)
        }
        val padding = min(nodePadding, paddingLimit)
        initializeNodeBreadths(columns, availableHeight, padding)
        repeat(ITERATIONS) { iteration ->
            val alpha = 0.99.pow(iteration)
            val beta = max(1.0 - alpha, (iteration + 1).toDouble() / ITERATIONS)
            relaxRightToLeft(columns, availableHeight, padding, alpha, beta)
            relaxLeftToRight(columns, availableHeight, padding, alpha, beta)
        }
    }

    private fun computeNodeLayers(
        nodes: List<D3SankeyNode>,
        options: MermaidSankeyOptions,
    ): List<MutableList<D3SankeyNode>> {
        val layerCount = nodes.maxOf(D3SankeyNode::depth) + 1
        val layerWidth = if (layerCount == 1) {
            0.0
        } else {
            (options.width - options.nodeWidth).toDouble() / (layerCount - 1)
        }
        val columns = List(layerCount) { mutableListOf<D3SankeyNode>() }
        nodes.forEach { node ->
            val aligned = when (options.nodeAlignment) {
                "left" -> node.depth
                "right" -> layerCount - 1 - node.height
                "center" -> when {
                    node.targetLinks.isNotEmpty() -> node.depth
                    node.sourceLinks.isNotEmpty() ->
                        node.sourceLinks.minOf { link -> link.target.depth } - 1
                    else -> 0
                }
                else -> if (node.sourceLinks.isNotEmpty()) node.depth else layerCount - 1
            }
            val layer = floor(aligned.toDouble())
                .toInt()
                .coerceIn(0, layerCount - 1)
            node.layer = layer
            node.x0 = layer * layerWidth
            node.x1 = node.x0 + options.nodeWidth
            columns[layer] += node
        }
        return columns
    }

    private fun initializeNodeBreadths(
        columns: List<MutableList<D3SankeyNode>>,
        height: Double,
        padding: Double,
    ) {
        val scale = columns.minOf { column ->
            (height - (column.size - 1) * padding) / column.sumOf(D3SankeyNode::value)
        }
        columns.forEach { column ->
            var y = 0.0
            column.forEach { node ->
                node.y0 = y
                node.y1 = y + node.value * scale
                y = node.y1 + padding
                node.sourceLinks.forEach { link ->
                    link.width = link.value * scale
                }
            }
            val gap = (height - y + padding) / (column.size + 1)
            column.forEachIndexed { index, node ->
                node.y0 += gap * (index + 1)
                node.y1 += gap * (index + 1)
            }
            reorderLinks(column)
        }
    }

    private fun relaxLeftToRight(
        columns: List<MutableList<D3SankeyNode>>,
        height: Double,
        padding: Double,
        alpha: Double,
        beta: Double,
    ) {
        for (index in 1 until columns.size) {
            val column = columns[index]
            column.forEach { target ->
                var weightedY = 0.0
                var weight = 0.0
                target.targetLinks.forEach { link ->
                    val contribution = link.value * (target.layer - link.source.layer)
                    weightedY += targetTop(link.source, target, padding) * contribution
                    weight += contribution
                }
                if (weight > 0.0) {
                    val delta = (weightedY / weight - target.y0) * alpha
                    target.y0 += delta
                    target.y1 += delta
                    reorderNodeLinks(target)
                }
            }
            column.sortWith(compareBy(D3SankeyNode::y0))
            resolveCollisions(column, height, padding, beta)
        }
    }

    private fun relaxRightToLeft(
        columns: List<MutableList<D3SankeyNode>>,
        height: Double,
        padding: Double,
        alpha: Double,
        beta: Double,
    ) {
        for (index in columns.size - 2 downTo 0) {
            val column = columns[index]
            column.forEach { source ->
                var weightedY = 0.0
                var weight = 0.0
                source.sourceLinks.forEach { link ->
                    val contribution = link.value * (link.target.layer - source.layer)
                    weightedY += sourceTop(source, link.target, padding) * contribution
                    weight += contribution
                }
                if (weight > 0.0) {
                    val delta = (weightedY / weight - source.y0) * alpha
                    source.y0 += delta
                    source.y1 += delta
                    reorderNodeLinks(source)
                }
            }
            column.sortWith(compareBy(D3SankeyNode::y0))
            resolveCollisions(column, height, padding, beta)
        }
    }

    private fun resolveCollisions(
        nodes: List<D3SankeyNode>,
        height: Double,
        padding: Double,
        alpha: Double,
    ) {
        val middle = nodes.size shr 1
        val subject = nodes[middle]
        resolveBottomToTop(nodes, subject.y0 - padding, middle - 1, padding, alpha)
        resolveTopToBottom(nodes, subject.y1 + padding, middle + 1, padding, alpha)
        resolveBottomToTop(nodes, height, nodes.lastIndex, padding, alpha)
        resolveTopToBottom(nodes, 0.0, 0, padding, alpha)
    }

    private fun resolveTopToBottom(
        nodes: List<D3SankeyNode>,
        initialY: Double,
        initialIndex: Int,
        padding: Double,
        alpha: Double,
    ) {
        var y = initialY
        for (index in initialIndex until nodes.size) {
            val node = nodes[index]
            val delta = (y - node.y0) * alpha
            if (delta > COLLISION_EPSILON) {
                node.y0 += delta
                node.y1 += delta
            }
            y = node.y1 + padding
        }
    }

    private fun resolveBottomToTop(
        nodes: List<D3SankeyNode>,
        initialY: Double,
        initialIndex: Int,
        padding: Double,
        alpha: Double,
    ) {
        var y = initialY
        for (index in initialIndex downTo 0) {
            val node = nodes[index]
            val delta = (node.y1 - y) * alpha
            if (delta > COLLISION_EPSILON) {
                node.y0 -= delta
                node.y1 -= delta
            }
            y = node.y0 - padding
        }
    }

    private fun reorderNodeLinks(node: D3SankeyNode) {
        node.targetLinks.forEach { targetLink ->
            targetLink.source.sourceLinks.sortWith(
                compareBy<D3SankeyLink>({ link -> link.target.y0 }, D3SankeyLink::index),
            )
        }
        node.sourceLinks.forEach { sourceLink ->
            sourceLink.target.targetLinks.sortWith(
                compareBy<D3SankeyLink>({ link -> link.source.y0 }, D3SankeyLink::index),
            )
        }
    }

    private fun reorderLinks(nodes: List<D3SankeyNode>) {
        nodes.forEach { node ->
            node.sourceLinks.sortWith(
                compareBy<D3SankeyLink>({ link -> link.target.y0 }, D3SankeyLink::index),
            )
            node.targetLinks.sortWith(
                compareBy<D3SankeyLink>({ link -> link.source.y0 }, D3SankeyLink::index),
            )
        }
    }

    private fun targetTop(
        source: D3SankeyNode,
        target: D3SankeyNode,
        padding: Double,
    ): Double {
        var y = source.y0 - (source.sourceLinks.size - 1) * padding / 2
        for (link in source.sourceLinks) {
            if (link.target === target) {
                break
            }
            y += link.width + padding
        }
        for (link in target.targetLinks) {
            if (link.source === source) {
                break
            }
            y -= link.width
        }
        return y
    }

    private fun sourceTop(
        source: D3SankeyNode,
        target: D3SankeyNode,
        padding: Double,
    ): Double {
        var y = target.y0 - (target.targetLinks.size - 1) * padding / 2
        for (link in target.targetLinks) {
            if (link.source === source) {
                break
            }
            y += link.width + padding
        }
        for (link in source.sourceLinks) {
            if (link.target === target) {
                break
            }
            y -= link.width
        }
        return y
    }

    private fun computeLinkBreadths(nodes: List<D3SankeyNode>) {
        nodes.forEach { node ->
            var sourceY = node.y0
            var targetY = sourceY
            node.sourceLinks.forEach { link ->
                link.y0 = sourceY + link.width / 2
                sourceY += link.width
            }
            node.targetLinks.forEach { link ->
                link.y1 = targetY + link.width / 2
                targetY += link.width
            }
        }
    }

    private fun <T> layoutError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Layout("Mermaid Sankey $message"))

    private const val ITERATIONS = 6
    private const val SHOW_VALUES_PADDING = 15.0
    private const val COLLISION_EPSILON = 1e-6
}

internal data class D3SankeyGraph(
    val nodes: List<D3SankeyNode>,
    val links: List<D3SankeyLink>,
)

internal class D3SankeyNode(
    val id: String,
    val index: Int,
) {
    val sourceLinks: MutableList<D3SankeyLink> = mutableListOf()
    val targetLinks: MutableList<D3SankeyLink> = mutableListOf()
    var value: Double = 0.0
    var depth: Int = 0
    var height: Int = 0
    var layer: Int = 0
    var x0: Double = 0.0
    var x1: Double = 0.0
    var y0: Double = 0.0
    var y1: Double = 0.0

    fun hasFiniteGeometry(): Boolean =
        value.isFinite() &&
            x0.isFinite() &&
            x1.isFinite() &&
            y0.isFinite() &&
            y1.isFinite()
}

internal class D3SankeyLink(
    val source: D3SankeyNode,
    val target: D3SankeyNode,
    val value: Double,
    val index: Int,
) {
    var width: Double = 0.0
    var y0: Double = 0.0
    var y1: Double = 0.0

    fun hasFiniteGeometry(): Boolean =
        width.isFinite() && y0.isFinite() && y1.isFinite()
}
