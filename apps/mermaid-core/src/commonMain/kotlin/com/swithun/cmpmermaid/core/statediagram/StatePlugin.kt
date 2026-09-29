package com.swithun.cmpmermaid.core.statediagram

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.statediagram.upstream.mermaid.StateJisonParser

class StatePlugin : MermaidDiagramPlugin {
    override val id: String = "state"
    override val headers: Set<String> = setOf("statediagram", "statediagram-v2")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = StateJisonParser(
            diagramTitle = context.diagramTitle,
        ).parse(source)
    ) {
        is GMResult.Ok -> StateLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
