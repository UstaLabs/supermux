package com.swithun.cmpmermaid.core.treemap.upstream.mermaid

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/treemap/types.ts and utils.ts -> buildHierarchy.
 */
internal data class TreemapNode(
    val name: String,
    val children: MutableList<TreemapNode>? = null,
    val value: Double? = null,
    val classSelector: String? = null,
    val cssCompiledStyles: List<String>? = null,
)

internal enum class TreemapItemType {
    Section,
    Leaf,
}

internal data class TreemapFlatItem(
    val level: Int,
    val name: String,
    val type: TreemapItemType,
    val value: Double? = null,
    val classSelector: String? = null,
    val cssCompiledStyles: List<String>? = null,
)

internal data class TreemapClassDefinition(
    val id: String,
    val styles: MutableList<String> = mutableListOf(),
    val textStyles: MutableList<String> = mutableListOf(),
)

internal fun buildTreemapHierarchy(items: List<TreemapFlatItem>): List<TreemapNode> {
    if (items.isEmpty()) {
        return emptyList()
    }
    val root = mutableListOf<TreemapNode>()
    val stack = mutableListOf<TreemapStackEntry>()
    items.forEach { item ->
        val node = TreemapNode(
            name = item.name,
            children = if (item.type == TreemapItemType.Leaf) null else mutableListOf(),
            value = item.value.takeIf { item.type == TreemapItemType.Leaf },
            classSelector = item.classSelector,
            cssCompiledStyles = item.cssCompiledStyles,
        )
        while (stack.isNotEmpty() && stack.last().level >= item.level) {
            stack.removeAt(stack.lastIndex)
        }
        if (stack.isEmpty()) {
            root += node
        } else {
            val parent = stack.last().node
            parent.children?.add(node)
        }
        if (item.type != TreemapItemType.Leaf) {
            stack += TreemapStackEntry(node = node, level = item.level)
        }
    }
    return root
}

private data class TreemapStackEntry(
    val node: TreemapNode,
    val level: Int,
)
