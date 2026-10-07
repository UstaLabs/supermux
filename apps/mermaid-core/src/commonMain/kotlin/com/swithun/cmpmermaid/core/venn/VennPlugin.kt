package com.swithun.cmpmermaid.core.venn

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennParser

class VennPlugin : MermaidDiagramPlugin {
    override val id: String = "venn"
    override val headers: Set<String> = setOf("venn-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = VennParser(
            config = context.options.venn,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
            maximumStatements = context.options.maxTextSize,
        ).parse(source)
    ) {
        is GMResult.Ok -> VennLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
