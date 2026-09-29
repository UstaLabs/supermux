package com.swithun.cmpmermaid.core.treeview.upstream.mermaid

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/treeView/types.ts.
 */
internal enum class TreeViewNodeType {
    File,
    Directory,
}

internal data class TreeViewNode(
    val id: Int,
    val level: Int,
    val name: String,
    val nodeType: TreeViewNodeType,
    val icon: String? = null,
    val cssClass: String? = null,
    val description: String? = null,
    val children: MutableList<TreeViewNode> = mutableListOf(),
)
