package com.swithun.cmpmermaid.core.gantt

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.gantt.upstream.mermaid.GanttJisonParser

class GanttPlugin : MermaidDiagramPlugin {
    override val id: String = "gantt"
    override val headers: Set<String> = setOf("gantt")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = GanttJisonParser(
            diagramTitle = context.diagramTitle,
            securityLevel = context.options.securityLevel,
            defaultWeekday = context.options.ganttWeekday,
        ).parse(source)
    ) {
        is GMResult.Ok -> GanttLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
