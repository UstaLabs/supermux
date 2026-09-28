package com.swithun.cmpmermaid.core.swimlane

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.flowchart.FlowchartDataAdapter
import com.swithun.cmpmermaid.core.flowchart.FlowchartLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowJisonParser

/**
 * Mermaid.js 12.0.0:
 * diagrams/swimlanes/detector.ts and swimlanesDiagram.ts.
 *
 * Swimlanes intentionally reuse Flowchart's parser, database, and renderer.
 * Their distinct behavior is supplied by the dedicated "swimlane" layout.
 */
internal class SwimlanePlugin : MermaidDiagramPlugin {
    override val id: String = "swimlane"
    override val headers: Set<String> = setOf("swimlane-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = FlowJisonParser(
            config = context.options,
            diagramTitle = context.diagramTitle,
        ).parse(source)
    ) {
        is GMResult.Ok -> when (val converted = FlowchartDataAdapter.convert(parsed.value)) {
            is GMResult.Ok -> FlowchartLayout().layout(
                document = if (context.options.layout == "swimlane") {
                    SwimlaneLayout.prepareDocument(
                        document = converted.value,
                        look = context.options.look,
                    )
                } else {
                    converted.value
                },
                context = context,
            )
            is GMResult.Err -> converted
        }
        is GMResult.Err -> parsed
    }
}
