package com.swithun.cmpmermaid.core.classdiagram

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassJisonParser

class ClassPlugin : MermaidDiagramPlugin {
    override val id: String = "class"
    override val headers: Set<String> = setOf("classdiagram", "classdiagram-v2")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = ClassJisonParser(
            securityLevel = context.options.securityLevel,
            diagramTitle = context.diagramTitle,
        ).parse(source)
    ) {
        is GMResult.Ok -> ClassLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
