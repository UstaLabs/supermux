package com.swithun.cmpmermaid.core.packet

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.packet.upstream.mermaid.PacketParser

class PacketPlugin : MermaidDiagramPlugin {
    override val id: String = "packet"
    override val headers: Set<String> = setOf("packet", "packet-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = PacketParser(
            config = context.options.packet,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> PacketLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
