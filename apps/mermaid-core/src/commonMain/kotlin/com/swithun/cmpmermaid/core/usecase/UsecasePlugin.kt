package com.swithun.cmpmermaid.core.usecase

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseParser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/usecase/usecaseDetector.ts,
 * usecaseDiagram.ts, and usecaseRenderer.ts.
 */
class UsecasePlugin : MermaidDiagramPlugin {
    override val id: String = "usecase"
    override val headers: Set<String> = setOf("usecase-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = UsecaseParser(
            // Mermaid.js 12.0.0: usecase.chevrotain.ts -> parse calls db.clear()
            // after Diagram.fromText has set the frontmatter title.
            diagramTitle = null,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> UsecaseLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
