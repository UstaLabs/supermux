package com.swithun.cmpmermaid.core.architecture

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureParser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/architecture/architectureDetector.ts and
 * packages/mermaid/src/diagrams/architecture/architectureDiagram.ts.
 */
internal class ArchitecturePlugin : MermaidDiagramPlugin {
    override val id: String = "architecture"
    override val headers: Set<String> = setOf("architecture", "architecture-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = ArchitectureParser(
            options = context.options,
            frontmatterTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> ArchitectureLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
