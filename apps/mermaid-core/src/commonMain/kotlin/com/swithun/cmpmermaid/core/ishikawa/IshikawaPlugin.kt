package com.swithun.cmpmermaid.core.ishikawa

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.ishikawa.upstream.mermaid.IshikawaParser

class IshikawaPlugin : MermaidDiagramPlugin {
    override val id: String = "ishikawa"
    override val headers: Set<String> = setOf("ishikawa", "ishikawa-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = IshikawaParser(
            options = context.options,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> IshikawaLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
