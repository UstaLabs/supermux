package dev.supermux.ui.editor

import dev.supermux.net.FsEntry

data class TreeNode(
    val entry: FsEntry,
    val path: String,
    val children: MutableList<TreeNode>? = null,
    var loaded: Boolean = false,
)
