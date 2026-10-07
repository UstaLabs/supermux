package com.swithun.cmpmermaid.core.xychart

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.xychart.upstream.mermaid.XyJisonParser

class XyChartPlugin : MermaidDiagramPlugin {
    override val id: String = "xychart"
    override val headers: Set<String> = setOf("xychart", "xychart-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val parsed = when (
            val result = XyJisonParser(
                diagramTitle = context.diagramTitle,
                plotColorPalette = context.theme.xyChart.plotColorPalette,
                initialOrientation = context.options.xyChart.chartOrientation,
                lineOffset = context.frontmatterLineOffset,
            ).parse(source)
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val data = when (val result = parsed.compile()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return XyChartLayout().layout(data, context)
    }
}
