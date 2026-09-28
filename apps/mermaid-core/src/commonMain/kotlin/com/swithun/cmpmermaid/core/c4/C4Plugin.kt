package com.swithun.cmpmermaid.core.c4

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.c4.upstream.mermaid.C4Parser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/c4/c4Detector.ts and c4Diagram.ts.
 */
internal class C4Plugin : MermaidDiagramPlugin {
    override val id: String = "c4"
    override val headers: Set<String> = setOf(
        "c4context",
        "c4container",
        "c4component",
        "c4dynamic",
        "c4deployment",
    )

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = C4Parser(
            options = context.options,
            frontmatterTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> C4Layout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
