package com.swithun.cmpmermaid.core.sankey.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidSankeyOptions

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/sankey/sankeyDB.ts.
 */
internal class SankeyDb(
    val config: MermaidSankeyOptions,
    diagramTitle: String?,
) {
    private val links = mutableListOf<SankeyLink>()
    private val nodes = mutableListOf<SankeyNode>()
    private val nodesById = mutableMapOf<String, SankeyNode>()

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun addLink(
        source: SankeyNode,
        target: SankeyNode,
        value: Double,
    ) {
        links += SankeyLink(source, target, value)
    }

    fun findOrCreateNode(sourceId: String): SankeyNode {
        val id = MermaidPreprocessor.decodeEntities(sourceId)
        return nodesById.getOrPut(id) {
            SankeyNode(id).also(nodes::add)
        }
    }

    fun getNodes(): List<SankeyNode> = nodes

    fun getLinks(): List<SankeyLink> = links

    fun getGraph(): SankeyGraph = SankeyGraph(
        nodes = nodes.toList(),
        links = links.toList(),
    )

    fun clear() {
        links.clear()
        nodes.clear()
        nodesById.clear()
        diagramTitle = null
        accessibilityTitle = null
        accessibilityDescription = null
    }
}
