package com.swithun.cmpmermaid.core.treeview

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewParser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/treeView/detector.ts and diagram.ts.
 */
internal class TreeViewPlugin : MermaidDiagramPlugin {
    override val id: String = "treeView"
    override val headers: Set<String> = setOf("treeview-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = TreeViewParser(
            config = context.options.treeView,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> TreeViewLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
