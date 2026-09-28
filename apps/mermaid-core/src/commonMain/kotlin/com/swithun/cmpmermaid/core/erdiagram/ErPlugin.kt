package com.swithun.cmpmermaid.core.erdiagram

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErJisonParser

class ErPlugin : MermaidDiagramPlugin {
    override val id: String = "er"
    override val headers: Set<String> = setOf("erdiagram")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = ErJisonParser(
            diagramTitle = context.diagramTitle,
        ).parse(source)
    ) {
        is GMResult.Ok -> ErLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
