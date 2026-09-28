package com.swithun.cmpmermaid.core.treeview.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidTreeViewOptions
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/treeView/db.ts.
 */
internal class TreeViewDb(
    val config: MermaidTreeViewOptions,
    diagramTitle: String?,
) {
    private var count: Int = 1
    private val root = TreeViewNode(
        id = 0,
        level = -1,
        name = "/",
        nodeType = TreeViewNodeType.Directory,
    )
    private val stack = mutableListOf(root)

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun addNode(
        level: Int,
        name: String,
        nodeType: TreeViewNodeType,
        cssClass: String?,
        icon: String?,
        description: String?,
    ): GMResult<Unit, MermaidError> {
        val sanitizedName = when (val result = sanitize(name)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val sanitizedDescription = when (description) {
            null -> null
            else -> when (val result = sanitize(description)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        }
        while (level <= stack.last().level) {
            stack.removeAt(stack.lastIndex)
        }
        val node = TreeViewNode(
            id = count++,
            level = level,
            name = sanitizedName,
            nodeType = nodeType,
            icon = icon,
            cssClass = cssClass,
            description = sanitizedDescription,
        )
        stack.last().children += node
        stack += node
        return GMResult.Ok(Unit)
    }

    fun setDiagramTitle(value: String): GMResult<Unit, MermaidError> =
        sanitize(value).map { sanitized ->
            diagramTitle = sanitized
        }

    fun setAccessibilityTitle(value: String): GMResult<Unit, MermaidError> =
        sanitize(value).map { sanitized ->
            accessibilityTitle = sanitized.trimStart()
        }

    fun setAccessibilityDescription(value: String): GMResult<Unit, MermaidError> =
        sanitize(value).map { sanitized ->
            accessibilityDescription = sanitized.replace(INDENTED_NEWLINE, "\n")
        }

    fun getRoot(): TreeViewNode = root

    fun getCount(): Int = count

    private fun sanitize(value: String): GMResult<String, MermaidError> =
        MermaidTextPort.sanitizeText(value).map(MermaidPreprocessor::decodeEntities)

    private inline fun <T, R> GMResult<T, MermaidError>.map(
        transform: (T) -> R,
    ): GMResult<R, MermaidError> = when (this) {
        is GMResult.Ok -> GMResult.Ok(transform(value))
        is GMResult.Err -> this
    }

    private companion object {
        val INDENTED_NEWLINE = Regex("""\n\s+""")
    }
}
