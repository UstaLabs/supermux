package com.swithun.cmpmermaid.core.requirement

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.requirement.upstream.mermaid.RequirementJisonParser

class RequirementPlugin : MermaidDiagramPlugin {
    override val id: String = "requirement"
    override val headers: Set<String> = setOf("requirementdiagram", "requirement")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = RequirementJisonParser(
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> RequirementLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
