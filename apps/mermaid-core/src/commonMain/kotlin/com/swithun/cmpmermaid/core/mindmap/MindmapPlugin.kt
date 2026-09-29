package com.swithun.cmpmermaid.core.mindmap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapJisonParser

class MindmapPlugin : MermaidDiagramPlugin {
    override val id: String = "mindmap"
    override val headers: Set<String> = setOf("mindmap")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val db = when (
            val result = MindmapJisonParser(
                padding = context.options.mindmap.padding,
                maxNodeWidth = context.options.mindmap.maxNodeWidth,
                diagramTitle = context.diagramTitle,
                lineOffset = context.frontmatterLineOffset,
            ).parse(source)
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val document = when (val result = db.getData()) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return MindmapLayout().layout(document, context)
    }
}
