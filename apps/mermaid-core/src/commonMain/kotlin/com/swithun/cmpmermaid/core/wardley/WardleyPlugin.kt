package com.swithun.cmpmermaid.core.wardley

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyParser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/wardley/wardleyDetector.ts and
 * packages/mermaid/src/diagrams/wardley/wardleyDiagram.ts.
 */
class WardleyPlugin : MermaidDiagramPlugin {
    override val id: String = "wardley"
    override val headers: Set<String> = setOf("wardley-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = WardleyParser(
            options = context.options,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> WardleyLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
