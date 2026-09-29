package com.swithun.cmpmermaid.core.treemap.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidTreemapOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/treemap/db.ts -> TreeMapDB.
 */
internal class TreemapDb(
    val config: MermaidTreemapOptions,
    diagramTitle: String?,
) {
    private val nodes = mutableListOf<TreemapNode>()
    private val levels = mutableListOf<Pair<TreemapNode, Int>>()
    private val outerNodes = mutableListOf<TreemapNode>()
    private val classes = linkedMapOf<String, TreemapClassDefinition>()

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun getNodes(): List<TreemapNode> = nodes

    fun getLevels(): List<Pair<TreemapNode, Int>> = levels

    fun addNode(
        node: TreemapNode,
        level: Int,
    ) {
        nodes += node
        levels += node to level
        if (level == 0) {
            outerNodes += node
        }
    }

    fun getRoot(): TreemapNode = TreemapNode(
        name = "",
        children = outerNodes.toMutableList(),
    )

    fun addClass(
        id: String,
        style: String,
    ) {
        val styleClass = classes.getOrPut(id) { TreemapClassDefinition(id = id) }
        style
            .replace("\\,", ESCAPED_COMMA)
            .replace(',', ';')
            .replace(ESCAPED_COMMA, ",")
            .split(';')
            .forEach { declaration ->
                if (isLabelStyle(declaration)) {
                    styleClass.textStyles += declaration
                }
                styleClass.styles += declaration
            }
    }

    fun getClasses(): Map<String, TreemapClassDefinition> = classes

    fun getStylesForClass(classSelector: String): List<String> =
        classes[classSelector]?.styles.orEmpty()

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value
    }

    private fun isLabelStyle(style: String): Boolean =
        style.substringBefore(':').trim() in LABEL_STYLE_KEYS

    private companion object {
        const val ESCAPED_COMMA = "\u00A7\u00A7\u00A7"
        val LABEL_STYLE_KEYS = setOf(
            "color",
            "font-size",
            "font-family",
            "font-weight",
            "font-style",
            "text-decoration",
            "text-align",
            "text-transform",
            "line-height",
            "letter-spacing",
            "word-spacing",
            "text-shadow",
            "text-overflow",
            "white-space",
            "word-wrap",
            "word-break",
            "overflow-wrap",
            "hyphens",
        )
    }
}
