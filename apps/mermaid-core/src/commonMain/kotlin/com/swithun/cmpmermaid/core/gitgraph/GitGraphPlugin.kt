package com.swithun.cmpmermaid.core.gitgraph

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid.GitGraphParser

class GitGraphPlugin : MermaidDiagramPlugin {
    override val id: String = "gitGraph"
    override val headers: Set<String> = setOf("gitgraph", "gitgraph:")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = GitGraphParser(
            mainBranchName = context.options.gitGraph.mainBranchName,
            mainBranchOrder = context.options.gitGraph.mainBranchOrder,
            diagramTitle = context.diagramTitle,
            lineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> GitGraphLayout().layout(parsed.value, context)
        is GMResult.Err -> parsed
    }
}
