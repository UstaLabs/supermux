package dev.supermux.ui.editor

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
        // Chain compression: a folder holding exactly one sub-folder and no files of its own is
        // drawn as ONE row (`a/b/c`) instead of one row — and one indent level — per segment. Deep
        // single-child chains are the norm in a JVM/KMP source tree, and on a phone each level
        // eats horizontal space the diff itself needs. The row keeps the DEEPEST path as its
        // identity, so expansion state still keys on the folder whose children it lists.
        if (files.isEmpty() && folders.size == 1) {
            val child = folders.values.first().freeze()
            return DiffTreeNode.Folder("$name/${child.name}", child.path, child.children)
        }
        val children = folders.values.map { it.freeze() } + files
        return DiffTreeNode.Folder(name, path, children.sortedWith(diffTreeOrder))
    }
}

fun buildDiffTree(files: List<DiffFile>): List<DiffTreeNode> {
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

fun flattenVisible(nodes: List<DiffTreeNode>, expanded: Set<String>): List<DiffTreeRow> {
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

fun allFolderPaths(nodes: List<DiffTreeNode>): Set<String> {
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

/**
 * +/- counts, ignoring the `+++`/`---` file headers (parity with web `diffStats`). Moved here from
 * desktop's `DiffView.kt` in cluster C1 because [folderDiffStats] needs it; the diff VIEW itself
 * (and Android's byte-identical copy of this function) follows in C3.
 */
fun diffStats(diff: String): Pair<Int, Int> {
    var added = 0
    var deleted = 0
    for (line in diff.split("\n")) {
        if (line.startsWith("+") && !line.startsWith("+++")) added += 1
        else if (line.startsWith("-") && !line.startsWith("---")) deleted += 1
    }
    return added to deleted
}

fun folderDiffStats(folder: DiffTreeNode.Folder): Pair<Int, Int> {
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
