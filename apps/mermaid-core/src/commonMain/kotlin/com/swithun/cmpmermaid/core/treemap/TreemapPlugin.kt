package com.swithun.cmpmermaid.core.treemap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.treemap.upstream.mermaid.TreemapParser

class TreemapPlugin : MermaidDiagramPlugin {
    override val id: String = "treemap"
    override val headers: Set<String> = setOf("treemap", "treemap-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = TreemapParser(
            config = context.options.treemap,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> TreemapLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
