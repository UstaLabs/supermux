package com.swithun.cmpmermaid.core.agentflow

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidDiagramPlugin
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.agentflow.upstream.mermaid.AgentflowJisonParser
import com.swithun.cmpmermaid.core.flowchart.FlowchartDataAdapter
import com.swithun.cmpmermaid.core.flowchart.FlowchartLayout

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/agentflow/afDetector.ts
 * packages/mermaid/src/diagrams/agentflow/renderer.ts
 */
class AgentflowPlugin : MermaidDiagramPlugin {
    override val id: String = "agentflow"
    override val headers: Set<String> = setOf("agentflow-beta")

    override fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = when (
        val parsed = AgentflowJisonParser(
            config = context.options,
            diagramTitle = context.diagramTitle,
            frontmatterLineOffset = context.frontmatterLineOffset,
        ).parse(source)
    ) {
        is GMResult.Ok -> {
            val db = parsed.value
            when (
                val document = FlowchartDataAdapter.convert(
                    data = db.getData(
                        paletteLength = context.theme.borderColorArray.size,
                        colorTheme = context.options.themeName?.lowercase() in COLOR_THEMES,
                        hasBackgroundPalette = context.theme.bkgColorArray.isNotEmpty(),
                    ),
                    config = db.config(),
                    directionSource = db.getDirection(),
                    titleSource = db.diagramTitle,
                    accessibilityTitleSource = db.accessibilityTitle,
                    accessibilityDescriptionSource = db.accessibilityDescription,
                )
            ) {
                is GMResult.Ok -> when (
                    val scene = FlowchartLayout().layout(document.value, context)
                ) {
                    is GMResult.Ok -> GMResult.Ok(
                        scene.value.copy(
                            // Mermaid.js 12.0.0:
                            // diagrams/agentflow/renderer.ts -> setupViewPortForSVG.
                            viewportSizing = if (context.options.agentflow.useMaxWidth) {
                                MermaidSceneViewportSizing.ResponsiveMaxWidth
                            } else {
                                MermaidSceneViewportSizing.Intrinsic
                            },
                        ),
                    )
                    is GMResult.Err -> scene
                }
                is GMResult.Err -> document
            }
        }
        is GMResult.Err -> parsed
    }

    private companion object {
        // Mermaid.js 12.0.0:
        // packages/mermaid/src/diagrams/common/colorThemeGate.ts -> COLOR_THEMES.
        val COLOR_THEMES = setOf("redux-color", "redux-dark-color")
    }
}
