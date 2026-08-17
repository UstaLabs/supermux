package dev.supermux.desktop.editor

import dev.supermux.net.DiffFile

sealed class DiffTreeNode {
    abstract val name: String
    abstract val path: String

    data class Folder(
        override val name: String,
        override val path: String,
        val children: List<DiffTreeNode>,
    ) : DiffTreeNode()

    data class File(
        override val name: String,
        override val path: String,
        val file: DiffFile,
    ) : DiffTreeNode()
}

data class DiffTreeRow(val node: DiffTreeNode, val depth: Int)

private val diffTreeOrder = compareBy<DiffTreeNode> { if (it is DiffTreeNode.Folder) 0 else 1 }
    .thenBy { it.name.lowercase() }

private class MutableFolder(val name: String, val path: String) {
    val folders = linkedMapOf<String, MutableFolder>()
    val files = mutableListOf<DiffTreeNode.File>()

    fun freeze(): DiffTreeNode.Folder {
        val children = folders.values.map { it.freeze() } + files
        return DiffTreeNode.Folder(name, path, children.sortedWith(diffTreeOrder))
    }
}

internal fun buildDiffTree(files: List<DiffFile>): List<DiffTreeNode> {
    val rootFolders = linkedMapOf<String, MutableFolder>()
    val rootFiles = mutableListOf<DiffTreeNode.File>()

    for (file in files) {
        val parts = file.path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) continue
        if (parts.size == 1) {
            rootFiles.add(DiffTreeNode.File(parts[0], file.path, file))
            continue
        }
        var current = rootFolders.getOrPut(parts[0]) { MutableFolder(parts[0], parts[0]) }
        var path = parts[0]
        for (i in 1 until parts.lastIndex) {
            path = "$path/${parts[i]}"
            current = current.folders.getOrPut(parts[i]) { MutableFolder(parts[i], path) }
        }
        current.files.add(DiffTreeNode.File(parts.last(), file.path, file))
    }

    return (rootFolders.values.map { it.freeze() } + rootFiles).sortedWith(diffTreeOrder)
}

internal fun flattenVisible(nodes: List<DiffTreeNode>, expanded: Set<String>): List<DiffTreeRow> {
    val out = mutableListOf<DiffTreeRow>()
    fun walk(list: List<DiffTreeNode>, depth: Int) {
        for (node in list) {
            out.add(DiffTreeRow(node, depth))
            if (node is DiffTreeNode.Folder && node.path in expanded) walk(node.children, depth + 1)
        }
    }
    walk(nodes, 0)
    return out
}

internal fun allFolderPaths(nodes: List<DiffTreeNode>): Set<String> {
    val out = mutableSetOf<String>()
    fun walk(node: DiffTreeNode) {
        if (node is DiffTreeNode.Folder) {
            out.add(node.path)
            node.children.forEach(::walk)
        }
    }
    nodes.forEach(::walk)
    return out
}

internal fun folderDiffStats(folder: DiffTreeNode.Folder): Pair<Int, Int> {
    var add = 0
    var del = 0
    fun walk(node: DiffTreeNode) {
        when (node) {
            is DiffTreeNode.File -> if (!node.file.binary) {
                val stats = diffStats(node.file.diff)
                add += stats.first
                del += stats.second
            }
            is DiffTreeNode.Folder -> node.children.forEach(::walk)
        }
    }
    folder.children.forEach(::walk)
    return add to del
}
