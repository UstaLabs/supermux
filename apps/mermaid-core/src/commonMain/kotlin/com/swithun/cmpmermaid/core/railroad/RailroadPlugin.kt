package com.swithun.cmpmermaid.core.railroad

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.railroad.upstream.mermaid.RailroadParser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/railroad detector and diagram modules.
 */
internal class RailroadPlugin : MermaidDiagramPlugin {
    override val id: String = "railroad"
    override val headers: Set<String> = setOf(
        "railroad-beta",
        "railroad-ebnf-beta",
        "railroad-abnf-beta",
        "railroad-peg-beta",
    )

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = RailroadParser(
            config = context.options.railroad,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> RailroadLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
