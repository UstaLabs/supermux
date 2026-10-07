package com.swithun.cmpmermaid.core.eventmodeling

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.eventmodeling.upstream.mermaid.EventModelingParser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/eventmodeling/detector.ts and diagram.ts.
 */
class EventModelingPlugin : MermaidDiagramPlugin {
    override val id: String = "eventmodeling"
    override val headers: Set<String> = setOf("eventmodeling")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = EventModelingParser(
            options = context.options,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> EventModelingLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
