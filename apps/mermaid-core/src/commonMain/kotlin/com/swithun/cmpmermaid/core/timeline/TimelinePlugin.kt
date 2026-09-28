package com.swithun.cmpmermaid.core.timeline

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene

class TimelinePlugin : MermaidDiagramPlugin {
    override val id: String = "timeline"
    override val headers: Set<String> = setOf("timeline")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = TimelineParser(
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> TimelineLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
