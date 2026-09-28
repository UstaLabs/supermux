package com.swithun.cmpmermaid.core.journey

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.journey.upstream.mermaid.JourneyJisonParser

class JourneyPlugin : MermaidDiagramPlugin {
    override val id: String = "journey"
    override val headers: Set<String> = setOf("journey")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = JourneyJisonParser(
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> JourneyLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
