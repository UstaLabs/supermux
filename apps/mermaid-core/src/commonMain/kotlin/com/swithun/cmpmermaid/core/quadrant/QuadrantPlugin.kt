package com.swithun.cmpmermaid.core.quadrant

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene

class QuadrantPlugin : MermaidDiagramPlugin {
    override val id: String = "quadrantChart"
    override val headers: Set<String> = setOf("quadrantchart")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = QuadrantParser(
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> QuadrantLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
