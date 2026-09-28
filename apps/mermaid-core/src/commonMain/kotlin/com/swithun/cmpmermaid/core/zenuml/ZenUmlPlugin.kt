package com.swithun.cmpmermaid.core.zenuml

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlParser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagram-api/detectType.ts -> zenuml detector, backed by
 * @zenuml/core 3.49.2 parsing and SVG geometry semantics.
 */
class ZenUmlPlugin : MermaidDiagramPlugin {
    override val id: String = "zenuml"
    override val headers: Set<String> = setOf("zenuml")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = ZenUmlParser(
            options = context.options,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> ZenUmlLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
