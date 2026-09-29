package com.swithun.cmpmermaid.core.cynefin

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinParser

class CynefinPlugin : MermaidDiagramPlugin {
    override val id: String = "cynefin"
    override val headers: Set<String> = setOf("cynefin-beta", "cynefin-beta:")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = CynefinParser(
            options = context.options,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> CynefinLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
