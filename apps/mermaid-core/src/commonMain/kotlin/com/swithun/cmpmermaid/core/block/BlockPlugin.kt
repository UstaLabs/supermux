package com.swithun.cmpmermaid.core.block

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockJisonParser

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/block/blockDetector.ts and blockDiagram.ts.
 */
class BlockPlugin : MermaidDiagramPlugin {
    override val id: String = "block"
    override val headers: Set<String> = setOf("block", "block-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val parsed = try {
            BlockJisonParser(
                config = context.options,
                diagramTitle = context.diagramTitle,
                lineOffset = context.frontmatterLineOffset,
            ).parse(source)
        } catch (failure: Exception) {
            return GMResult.Err(MermaidError.Unexpected.from(failure, "Block parser failed"))
        }
        return when (parsed) {
            is GMResult.Ok -> try {
                BlockLayout().layout(parsed.value, context)
            } catch (failure: Exception) {
                GMResult.Err(
                    MermaidError.Unexpected.from(failure, "Block layout failed"),
                )
            }
            is GMResult.Err -> parsed
        }
    }
}
