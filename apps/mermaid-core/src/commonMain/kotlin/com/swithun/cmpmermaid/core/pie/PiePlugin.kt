package com.swithun.cmpmermaid.core.pie

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.pie.upstream.mermaid.PieParser

class PiePlugin : MermaidDiagramPlugin {
    override val id: String = "pie"
    override val headers: Set<String> = setOf("pie")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = PieParser(
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> PieLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
