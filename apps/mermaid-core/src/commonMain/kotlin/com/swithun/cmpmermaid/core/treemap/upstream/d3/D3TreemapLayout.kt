package com.swithun.cmpmermaid.core.treemap.upstream.d3

import com.swithun.cmpmermaid.core.treemap.upstream.mermaid.TreemapNode
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Kotlin translation of d3-hierarchy 3.1.2:
 * src/hierarchy/{index,sum,sort,eachBefore,eachAfter}.js and
 * src/treemap/{index,squarify,dice,slice,round}.js.
 */
internal object D3TreemapLayout {
    fun layout(
        data: TreemapNode,
        width: Double,
        height: Double,
        paddingInner: Double,
    ): D3TreemapNode {
        val root = hierarchy(data)
        sum(root)
        sort(root)
        root.x0 = 0.0
        root.y0 = 0.0
        root.x1 = width
        root.y1 = height
        val paddingStack = mutableListOf(0.0)
        eachBefore(root) { node ->
            positionNode(node, paddingInner, paddingStack)
        }
        eachBefore(root, ::roundNode)
        return root
    }

    private fun hierarchy(data: TreemapNode): D3TreemapNode {
        fun create(
            current: TreemapNode,
            parent: D3TreemapNode?,
            depth: Int,
        ): D3TreemapNode {
            val node = D3TreemapNode(
                data = current,
                parent = parent,
                depth = depth,
            )
            node.children = current.children
                ?.takeIf(List<TreemapNode>::isNotEmpty)
                ?.map { child -> create(child, node, depth + 1) }
                ?.toMutableList()
            return node
        }
        val root = create(data, parent = null, depth = 0)
        eachBefore(root) { node ->
            var height = 0
            var parent = node
            do {
                parent.height = height
                val next = parent.parent
                height += 1
                parent = next ?: break
            } while (parent.height < height)
        }
        return root
    }

    private fun sum(root: D3TreemapNode) {
        eachAfter(root) { node ->
            val ownValue = node.data.value ?: 0.0
            var total = if (ownValue.isNaN() || ownValue == 0.0) 0.0 else ownValue
            val children = node.children
            children?.indices?.reversed()?.forEach { index ->
                total += children[index].value
            }
            node.value = total
        }
    }

    private fun sort(root: D3TreemapNode) {
        eachBefore(root) { node ->
            node.children?.sortWith(
                compareByDescending<D3TreemapNode> { child -> child.value },
            )
        }
    }

    private fun positionNode(
        node: D3TreemapNode,
        paddingInner: Double,
        paddingStack: MutableList<Double>,
    ) {
        var padding = paddingStack.getOrElse(node.depth) { 0.0 }
        var x0 = node.x0 + padding
        var y0 = node.y0 + padding
        var x1 = node.x1 - padding
        var y1 = node.y1 - padding
        if (x1 < x0) {
            x0 = (x0 + x1) / 2.0
            x1 = x0
        }
        if (y1 < y0) {
            y0 = (y0 + y1) / 2.0
            y1 = y0
        }
        node.x0 = x0
        node.y0 = y0
        node.x1 = x1
        node.y1 = y1
        val children = node.children ?: return

        padding = paddingInner / 2.0
        while (paddingStack.size <= node.depth + 1) {
            paddingStack += 0.0
        }
        paddingStack[node.depth + 1] = padding
        x0 += SECTION_SIDE_PADDING - padding
        y0 += SECTION_HEADER_HEIGHT + SECTION_SIDE_PADDING - padding
        x1 -= SECTION_SIDE_PADDING - padding
        y1 -= SECTION_SIDE_PADDING - padding
        if (x1 < x0) {
            x0 = (x0 + x1) / 2.0
            x1 = x0
        }
        if (y1 < y0) {
            y0 = (y0 + y1) / 2.0
            y1 = y0
        }
        squarify(
            parent = node,
            nodes = children,
            x0 = x0,
            y0 = y0,
            x1 = x1,
            y1 = y1,
        )
    }

    private fun squarify(
        parent: D3TreemapNode,
        nodes: List<D3TreemapNode>,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ) {
        var currentX0 = x0
        var currentY0 = y0
        var i0 = 0
        var i1 = 0
        var remainingValue = parent.value
        while (i0 < nodes.size) {
            val dx = x1 - currentX0
            val dy = y1 - currentY0
            var sumValue: Double
            do {
                sumValue = nodes[i1].value
                i1 += 1
            } while (!jsTruthy(sumValue) && i1 < nodes.size)
            var minValue = sumValue
            var maxValue = sumValue
            val alpha = max(dy / dx, dx / dy) / (remainingValue * PHI)
            var beta = sumValue * sumValue * alpha
            var minRatio = max(maxValue / beta, beta / minValue)
            while (i1 < nodes.size) {
                val nodeValue = nodes[i1].value
                sumValue += nodeValue
                if (nodeValue < minValue) {
                    minValue = nodeValue
                }
                if (nodeValue > maxValue) {
                    maxValue = nodeValue
                }
                beta = sumValue * sumValue * alpha
                val newRatio = max(maxValue / beta, beta / minValue)
                if (newRatio > minRatio) {
                    sumValue -= nodeValue
                    break
                }
                minRatio = newRatio
                i1 += 1
            }

            val row = nodes.subList(i0, i1)
            if (dx < dy) {
                val rowY0 = currentY0
                val rowY1 = if (jsTruthy(remainingValue)) {
                    currentY0 + dy * sumValue / remainingValue
                } else {
                    y1
                }
                dice(
                    nodes = row,
                    value = sumValue,
                    x0 = currentX0,
                    y0 = rowY0,
                    x1 = x1,
                    y1 = rowY1,
                )
                currentY0 = rowY1
            } else {
                val rowX0 = currentX0
                val rowX1 = if (jsTruthy(remainingValue)) {
                    currentX0 + dx * sumValue / remainingValue
                } else {
                    x1
                }
                slice(
                    nodes = row,
                    value = sumValue,
                    x0 = rowX0,
                    y0 = currentY0,
                    x1 = rowX1,
                    y1 = y1,
                )
                currentX0 = rowX1
            }
            remainingValue -= sumValue
            i0 = i1
        }
    }

    private fun dice(
        nodes: List<D3TreemapNode>,
        value: Double,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ) {
        var currentX = x0
        val scale = if (jsTruthy(value)) (x1 - x0) / value else 0.0
        nodes.forEach { node ->
            node.y0 = y0
            node.y1 = y1
            node.x0 = currentX
            currentX += node.value * scale
            node.x1 = currentX
        }
    }

    private fun slice(
        nodes: List<D3TreemapNode>,
        value: Double,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
    ) {
        var currentY = y0
        val scale = if (jsTruthy(value)) (y1 - y0) / value else 0.0
        nodes.forEach { node ->
            node.x0 = x0
            node.x1 = x1
            node.y0 = currentY
            currentY += node.value * scale
            node.y1 = currentY
        }
    }

    private fun roundNode(node: D3TreemapNode) {
        node.x0 = jsRound(node.x0)
        node.y0 = jsRound(node.y0)
        node.x1 = jsRound(node.x1)
        node.y1 = jsRound(node.y1)
    }

    private fun eachBefore(
        root: D3TreemapNode,
        callback: (D3TreemapNode) -> Unit,
    ) {
        val nodes = mutableListOf(root)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeAt(nodes.lastIndex)
            callback(node)
            val children = node.children
            children?.indices?.reversed()?.forEach { index ->
                nodes += children[index]
            }
        }
    }

    private fun eachAfter(
        root: D3TreemapNode,
        callback: (D3TreemapNode) -> Unit,
    ) {
        val nodes = mutableListOf(root)
        val next = mutableListOf<D3TreemapNode>()
        while (nodes.isNotEmpty()) {
            val node = nodes.removeAt(nodes.lastIndex)
            next += node
            node.children?.forEach(nodes::add)
        }
        while (next.isNotEmpty()) {
            callback(next.removeAt(next.lastIndex))
        }
    }

    private fun jsTruthy(value: Double): Boolean = value != 0.0 && !value.isNaN()

    private fun jsRound(value: Double): Double = floor(value + 0.5)

    private const val SECTION_SIDE_PADDING = 10.0
    private const val SECTION_HEADER_HEIGHT = 25.0
    private val PHI = (1.0 + sqrt(5.0)) / 2.0
}

internal data class D3TreemapNode(
    val data: TreemapNode,
    val parent: D3TreemapNode?,
    val depth: Int,
    var height: Int = 0,
    var children: MutableList<D3TreemapNode>? = null,
    var value: Double = 0.0,
    var x0: Double = 0.0,
    var y0: Double = 0.0,
    var x1: Double = 0.0,
    var y1: Double = 0.0,
) {
    fun descendants(): List<D3TreemapNode> {
        val result = mutableListOf<D3TreemapNode>()
        val nodes = ArrayDeque<D3TreemapNode>()
        nodes.addLast(this)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            result += node
            node.children?.forEach { child ->
                nodes.addLast(child)
            }
        }
        return result
    }

    fun leaves(): List<D3TreemapNode> {
        val result = mutableListOf<D3TreemapNode>()
        val nodes = mutableListOf(this)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeAt(nodes.lastIndex)
            val children = node.children
            if (children == null) {
                result += node
            } else {
                children.indices.reversed().forEach { index ->
                    nodes += children[index]
                }
            }
        }
        return result
    }
}
