package com.swithun.cmpmermaid.core.mindmap.upstream.tidytree

import kotlin.math.max
import kotlin.math.min

/*
 * Kotlin translation of non-layered-tidy-tree-layout 2.0.2:
 * src/algorithm.js and src/helpers.js.
 *
 * Copyright (c) 2019 Michael Wong
 * Licensed under the MIT License.
 */
internal object NonLayeredTidyTreeLayout {
    fun layout(
        treeData: TidyTreeNode,
        gap: Float,
        bottomPadding: Float,
    ): TidyTreeBounds {
        val tree = convert(
            treeData = treeData,
            y = 0f,
            gap = gap,
            bottomPadding = bottomPadding,
        )
        firstWalk(tree)
        secondWalk(tree, 0f)
        return assignLayout(
            tree = tree,
            treeData = treeData,
            gap = gap,
            box = null,
        )
    }

    private fun convert(
        treeData: TidyTreeNode,
        y: Float,
        gap: Float,
        bottomPadding: Float,
    ): Tree {
        val width = treeData.width + gap
        val height = treeData.height + bottomPadding
        return Tree(
            width = width,
            height = height,
            y = y,
            children = treeData.children.map { child ->
                convert(
                    treeData = child,
                    y = y + height,
                    gap = gap,
                    bottomPadding = bottomPadding,
                )
            },
        )
    }

    private fun setExtremes(tree: Tree) {
        if (tree.children.isEmpty()) {
            tree.extremeLeft = tree
            tree.extremeRight = tree
            tree.modSumExtremeLeft = 0f
            tree.modSumExtremeRight = 0f
            return
        }
        val first = tree.children.first()
        val last = tree.children.last()
        tree.extremeLeft = first.extremeLeft
        tree.modSumExtremeLeft = first.modSumExtremeLeft
        tree.extremeRight = last.extremeRight
        tree.modSumExtremeRight = last.modSumExtremeRight
    }

    private fun bottom(tree: Tree): Float = tree.y + tree.height

    private fun updateIyl(
        minimumY: Float,
        index: Int,
        head: IndexedYList?,
    ): IndexedYList {
        var current = head
        while (current != null && minimumY >= current.lowY) {
            current = current.next
        }
        return IndexedYList(minimumY, index, current)
    }

    private fun distributeExtra(
        tree: Tree,
        index: Int,
        siblingIndex: Int,
        distance: Float,
    ) {
        if (siblingIndex == index - 1) {
            return
        }
        val count = index - siblingIndex
        tree.children[siblingIndex + 1].shift += distance / count
        tree.children[index].shift -= distance / count
        tree.children[index].change -= distance - distance / count
    }

    private fun moveSubtree(
        tree: Tree,
        index: Int,
        siblingIndex: Int,
        distance: Float,
    ) {
        val child = tree.children[index]
        child.modifier += distance
        child.modSumExtremeLeft += distance
        child.modSumExtremeRight += distance
        distributeExtra(tree, index, siblingIndex, distance)
    }

    private fun nextLeftContour(tree: Tree): Tree? =
        if (tree.children.isEmpty()) tree.leftThread else tree.children.first()

    private fun nextRightContour(tree: Tree): Tree? =
        if (tree.children.isEmpty()) tree.rightThread else tree.children.last()

    private fun setLeftThread(
        tree: Tree,
        index: Int,
        contourLeft: Tree,
        modifierSumContourLeft: Float,
    ) {
        val leftmost = tree.children.firstOrNull()?.extremeLeft ?: return
        leftmost.leftThread = contourLeft
        val difference =
            (modifierSumContourLeft - contourLeft.modifier) -
                tree.children.first().modSumExtremeLeft
        leftmost.modifier += difference
        leftmost.preliminary -= difference
        tree.children.first().extremeLeft = tree.children[index].extremeLeft
        tree.children.first().modSumExtremeLeft = tree.children[index].modSumExtremeLeft
    }

    private fun setRightThread(
        tree: Tree,
        index: Int,
        contourRight: Tree,
        modifierSumContourRight: Float,
    ) {
        val rightmost = tree.children.getOrNull(index)?.extremeRight ?: return
        rightmost.rightThread = contourRight
        val difference =
            (modifierSumContourRight - contourRight.modifier) -
                tree.children[index].modSumExtremeRight
        rightmost.modifier += difference
        rightmost.preliminary -= difference
        tree.children[index].extremeRight = tree.children[index - 1].extremeRight
        tree.children[index].modSumExtremeRight =
            tree.children[index - 1].modSumExtremeRight
    }

    // Upstream retains the historical "seperate" spelling.
    private fun seperate(
        tree: Tree,
        index: Int,
        initialIyl: IndexedYList,
    ) {
        var siblingRight: Tree? = tree.children[index - 1]
        var modifierSumSiblingRight = siblingRight?.modifier ?: 0f
        var contourLeft: Tree? = tree.children[index]
        var modifierSumContourLeft = contourLeft?.modifier ?: 0f
        var iyl = initialIyl

        while (siblingRight != null && contourLeft != null) {
            if (bottom(siblingRight) > iyl.lowY) {
                iyl.next?.let { next -> iyl = next }
            }
            val distance =
                modifierSumSiblingRight + siblingRight.preliminary + siblingRight.width -
                    (modifierSumContourLeft + contourLeft.preliminary)
            if (distance > 0f) {
                modifierSumContourLeft += distance
                moveSubtree(tree, index, iyl.index, distance)
            }

            val siblingY = bottom(siblingRight)
            val contourY = bottom(contourLeft)
            if (siblingY <= contourY) {
                siblingRight = nextRightContour(siblingRight)?.also { next ->
                    modifierSumSiblingRight += next.modifier
                }
            }
            if (siblingY >= contourY) {
                contourLeft = nextLeftContour(contourLeft)?.also { next ->
                    modifierSumContourLeft += next.modifier
                }
            }
        }

        when {
            siblingRight == null && contourLeft != null ->
                setLeftThread(tree, index, contourLeft, modifierSumContourLeft)
            siblingRight != null ->
                setRightThread(tree, index, siblingRight, modifierSumSiblingRight)
        }
    }

    private fun positionRoot(tree: Tree) {
        val first = tree.children.firstOrNull() ?: return
        val last = tree.children.lastOrNull() ?: return
        tree.preliminary =
            (
                first.preliminary +
                    first.modifier +
                    last.modifier +
                    last.preliminary +
                    last.width
                ) / 2f -
            tree.width / 2f
    }

    private fun firstWalk(tree: Tree) {
        if (tree.children.isEmpty()) {
            setExtremes(tree)
            return
        }

        firstWalk(tree.children.first())
        var iyl = updateIyl(
            minimumY = bottom(tree.children.first().extremeLeft ?: tree.children.first()),
            index = 0,
            head = null,
        )
        for (index in 1 until tree.children.size) {
            val child = tree.children[index]
            firstWalk(child)
            val minimumY = bottom(child.extremeRight ?: child)
            seperate(tree, index, iyl)
            iyl = updateIyl(minimumY, index, iyl)
        }
        positionRoot(tree)
        setExtremes(tree)
    }

    private fun addChildSpacing(tree: Tree) {
        var distance = 0f
        var modifierSumDelta = 0f
        tree.children.forEach { child ->
            distance += child.shift
            modifierSumDelta += distance + child.change
            child.modifier += modifierSumDelta
        }
    }

    private fun secondWalk(
        tree: Tree,
        parentModifierSum: Float,
    ) {
        val modifierSum = parentModifierSum + tree.modifier
        tree.x = tree.preliminary + modifierSum
        addChildSpacing(tree)
        tree.children.forEach { child -> secondWalk(child, modifierSum) }
    }

    private fun assignLayout(
        tree: Tree,
        treeData: TidyTreeNode,
        gap: Float,
        box: TidyTreeBounds?,
    ): TidyTreeBounds {
        val x = tree.x + gap / 2f
        val y = tree.y
        treeData.x = x
        treeData.y = y
        val bounds = box ?: TidyTreeBounds(
            left = x,
            right = x + treeData.width,
            top = y,
            bottom = y + treeData.height,
        )
        bounds.left = min(bounds.left, x)
        bounds.right = max(bounds.right, x + treeData.width)
        bounds.top = min(bounds.top, y)
        bounds.bottom = max(bounds.bottom, y + treeData.height)
        tree.children.indices.forEach { index ->
            assignLayout(tree.children[index], treeData.children[index], gap, bounds)
        }
        return bounds
    }

    private class Tree(
        val width: Float,
        val height: Float,
        val y: Float,
        val children: List<Tree>,
    ) {
        var x: Float = 0f
        var preliminary: Float = 0f
        var modifier: Float = 0f
        var shift: Float = 0f
        var change: Float = 0f
        var leftThread: Tree? = null
        var rightThread: Tree? = null
        var extremeLeft: Tree? = null
        var extremeRight: Tree? = null
        var modSumExtremeLeft: Float = 0f
        var modSumExtremeRight: Float = 0f
    }

    private data class IndexedYList(
        val lowY: Float,
        val index: Int,
        val next: IndexedYList?,
    )
}

internal data class TidyTreeNode(
    val id: String,
    val width: Float,
    val height: Float,
    val children: List<TidyTreeNode> = emptyList(),
    var x: Float = 0f,
    var y: Float = 0f,
)

internal data class TidyTreeBounds(
    var left: Float,
    var right: Float,
    var top: Float,
    var bottom: Float,
)
