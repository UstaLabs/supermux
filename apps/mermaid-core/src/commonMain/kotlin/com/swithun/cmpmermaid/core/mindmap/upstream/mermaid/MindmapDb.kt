package com.swithun.cmpmermaid.core.mindmap.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor

/**
 * Kotlin translation of Mermaid 12.0.0 mindmapDb.ts.
 */
internal class MindmapDb(
    private val padding: Float,
    private val maxNodeWidth: Float,
    val diagramTitle: String?,
) {
    private val nodes = mutableListOf<MindmapNode>()
    private var count = 0
    private var baseLevel: Int? = null

    fun getMindmap(): MindmapNode? = nodes.firstOrNull()

    fun addNode(
        sourceLevel: Int,
        id: String,
        description: String,
        type: MindmapNodeType,
        line: Int,
        column: Int,
    ): GMResult<Unit, MermaidError> {
        var level = sourceLevel
        val isRoot = nodes.isEmpty()
        if (isRoot) {
            baseLevel = level
            level = 0
        } else {
            level -= baseLevel ?: 0
        }

        val nodePadding = when (type) {
            MindmapNodeType.RoundedRectangle,
            MindmapNodeType.Rectangle,
            MindmapNodeType.Hexagon,
            -> padding * 2f
            else -> padding
        }
        val node = MindmapNode(
            id = count++,
            nodeId = sanitize(id),
            level = level,
            description = sanitize(description),
            type = type,
            width = maxNodeWidth,
            padding = nodePadding,
            isRoot = isRoot,
        )
        val parent = getParent(level)
        return when {
            parent != null -> {
                parent.children += node
                nodes += node
                GMResult.Ok(Unit)
            }
            isRoot -> {
                nodes += node
                GMResult.Ok(Unit)
            }
            else -> GMResult.Err(
                MermaidError.Parse(
                    line = line,
                    column = column,
                    message = "There can be only one root. No parent could be found for " +
                        "(\"${node.description}\")",
                ),
            )
        }
    }

    fun decorateNode(
        classNames: String? = null,
        icon: String? = null,
        line: Int,
        column: Int,
    ): GMResult<Unit, MermaidError> {
        val node = nodes.lastOrNull()
            ?: return GMResult.Err(
                MermaidError.Parse(
                    line = line,
                    column = column,
                    message = "Mindmap decoration requires a preceding node",
                ),
            )
        if (classNames != null) {
            node.classNames = sanitize(classNames)
        }
        if (icon != null) {
            node.icon = sanitize(icon)
        }
        return GMResult.Ok(Unit)
    }

    fun getType(
        start: String,
        end: String,
    ): MindmapNodeType = when (start) {
        "[" -> MindmapNodeType.Rectangle
        "(" -> if (end == ")") {
            MindmapNodeType.RoundedRectangle
        } else {
            MindmapNodeType.Cloud
        }
        "((" -> MindmapNodeType.Circle
        ")" -> MindmapNodeType.Cloud
        "))" -> MindmapNodeType.Bang
        "{{" -> MindmapNodeType.Hexagon
        else -> MindmapNodeType.Default
    }

    fun getData(): GMResult<MindmapDocument, MermaidError> {
        val root = getMindmap()
            ?: return GMResult.Err(
                MermaidError.Parse(
                    line = 1,
                    column = 1,
                    message = "Mindmap requires a root node",
                ),
            )
        assignSections(root)
        val edges = mutableListOf<MindmapEdge>()
        generateEdges(root, edges)
        return GMResult.Ok(
            MindmapDocument(
                root = root,
                nodes = nodes.toList(),
                edges = edges,
                title = diagramTitle,
            ),
        )
    }

    private fun getParent(level: Int): MindmapNode? =
        nodes.asReversed().firstOrNull { node -> node.level < level }

    private fun assignSections(
        node: MindmapNode,
        sectionNumber: Int? = null,
    ) {
        node.section = if (node.level == 0) null else sectionNumber
        node.children.forEachIndexed { index, child ->
            val childSection = if (node.level == 0) {
                index % (MAX_SECTIONS - 1)
            } else {
                sectionNumber
            }
            assignSections(child, childSection)
        }
    }

    private fun generateEdges(
        node: MindmapNode,
        edges: MutableList<MindmapEdge>,
    ) {
        node.children.forEach { child ->
            edges += MindmapEdge(
                id = "edge_${node.id}_${child.id}",
                start = node.id,
                end = child.id,
                depth = node.level,
                section = child.section,
            )
            generateEdges(child, edges)
        }
    }

    private fun sanitize(value: String): String =
        MermaidPreprocessor.decodeEntities(value)

    private companion object {
        const val MAX_SECTIONS = 12
    }
}
