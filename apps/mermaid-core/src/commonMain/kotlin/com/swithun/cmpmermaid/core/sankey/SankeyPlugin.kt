package com.swithun.cmpmermaid.core.sankey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.sankey.upstream.mermaid.SankeyParser

class SankeyPlugin : MermaidDiagramPlugin {
    override val id: String = "sankey"
    override val headers: Set<String> = setOf("sankey", "sankey-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = SankeyParser(
            config = context.options.sankey,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> SankeyLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
