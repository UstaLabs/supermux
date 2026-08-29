package dev.supermux.workspace

import dev.supermux.net.FsEntry

data class TreeNode(
    val entry: FsEntry,
    val path: String,
    val children: MutableList<TreeNode>? = null,
    var loaded: Boolean = false,
)
