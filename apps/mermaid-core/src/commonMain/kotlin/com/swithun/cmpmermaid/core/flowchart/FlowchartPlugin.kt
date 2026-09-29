package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowJisonParser

class FlowchartPlugin : MermaidDiagramPlugin {
    override val id: String = "flowchart"
    override val headers: Set<String> = setOf("flowchart", "flowchart-elk", "graph")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = FlowJisonParser(
            config = context.options,
            diagramTitle = context.diagramTitle,
        ).parse(source)
    ) {
        is GMResult.Ok -> when (val document = FlowchartDataAdapter.convert(parsed.value)) {
            is GMResult.Ok -> FlowchartLayout().layout(document.value, context)
            is GMResult.Err -> document
        }
        is GMResult.Err -> parsed
    }
}
