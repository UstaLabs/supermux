package com.swithun.cmpmermaid.core.radar

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarParser

class RadarPlugin : MermaidDiagramPlugin {
    override val id: String = "radar"
    override val headers: Set<String> = setOf("radar-beta", "radar-beta:")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = RadarParser(
            config = context.options.radar,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> RadarLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
