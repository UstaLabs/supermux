package com.swithun.cmpmermaid.core.treeview.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidTreeViewOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/treeView/icons.ts.
 */
internal object TreeViewIcons {
    const val BUILT_IN_PREFIX = "mermaid-treeview"
    const val BUILT_IN_FILE = "$BUILT_IN_PREFIX:file"
    const val BUILT_IN_FOLDER = "$BUILT_IN_PREFIX:folder"

    fun detectIcon(
        name: String,
        config: MermaidTreeViewOptions,
    ): String? {
        config.filenameIcons[name]?.let { return it }
        val dotIndex = name.lastIndexOf('.')
        if (dotIndex > 0) {
            val extension = name.substring(dotIndex).lowercase()
            return config.extensionIcons[extension]
                ?: config.extensionIcons[extension.drop(1)]
        }
        return null
    }

    fun getNodeIcon(
        node: TreeViewNode,
        config: MermaidTreeViewOptions,
    ): String? {
        if (node.icon == NONE) {
            return null
        }
        node.icon?.let { return qualifyIcon(it, config.defaultIconPack) }
        if (!config.showIcons) {
            return null
        }
        if (node.nodeType == TreeViewNodeType.File) {
            detectIcon(node.name, config)?.let { detected ->
                if (detected == NONE) {
                    return null
                }
                return qualifyIcon(detected, config.defaultIconPack)
            }
        }
        return if (node.nodeType == TreeViewNodeType.Directory) {
            BUILT_IN_FOLDER
        } else {
            BUILT_IN_FILE
        }
    }

    private fun qualifyIcon(
        icon: String,
        defaultIconPack: String,
    ): String = when {
        ':' in icon -> icon
        icon == "file" || icon == "folder" || defaultIconPack.isEmpty() ->
            "$BUILT_IN_PREFIX:$icon"
        else -> "$defaultIconPack:$icon"
    }

    private const val NONE = "none"
}
