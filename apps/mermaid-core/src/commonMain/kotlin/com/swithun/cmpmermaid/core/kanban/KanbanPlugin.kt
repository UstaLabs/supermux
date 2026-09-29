package com.swithun.cmpmermaid.core.kanban

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene

class KanbanPlugin : MermaidDiagramPlugin {
    override val id: String = "kanban"
    override val headers: Set<String> = setOf("kanban")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = KanbanParser(
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> KanbanLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
