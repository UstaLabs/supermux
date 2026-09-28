package com.swithun.cmpmermaid.core.sequence

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.sequence.upstream.mermaid.SequenceJisonParser

class SequencePlugin : MermaidDiagramPlugin {
    override val id: String = "sequence"
    override val headers: Set<String> = setOf("sequencediagram")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = SequenceJisonParser(
            diagramTitle = context.diagramTitle,
        ).parse(source)
    ) {
        is GMResult.Ok -> SequenceLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
