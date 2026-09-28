package com.swithun.cmpmermaid.core

import com.swithun.cmpmermaid.core.agentflow.AgentflowPlugin
import com.swithun.cmpmermaid.core.architecture.ArchitecturePlugin
import com.swithun.cmpmermaid.core.block.BlockPlugin
import com.swithun.cmpmermaid.core.c4.C4Plugin
import com.swithun.cmpmermaid.core.classdiagram.ClassPlugin
import com.swithun.cmpmermaid.core.cynefin.CynefinPlugin
import com.swithun.cmpmermaid.core.erdiagram.ErPlugin
import com.swithun.cmpmermaid.core.eventmodeling.EventModelingPlugin
import com.swithun.cmpmermaid.core.flowchart.FlowchartPlugin
import com.swithun.cmpmermaid.core.gantt.GanttPlugin
import com.swithun.cmpmermaid.core.gitgraph.GitGraphPlugin
import com.swithun.cmpmermaid.core.ishikawa.IshikawaPlugin
import com.swithun.cmpmermaid.core.journey.JourneyPlugin
import com.swithun.cmpmermaid.core.kanban.KanbanPlugin
import com.swithun.cmpmermaid.core.mindmap.MindmapPlugin
import com.swithun.cmpmermaid.core.packet.PacketPlugin
import com.swithun.cmpmermaid.core.pie.PiePlugin
import com.swithun.cmpmermaid.core.quadrant.QuadrantPlugin
import com.swithun.cmpmermaid.core.radar.RadarPlugin
import com.swithun.cmpmermaid.core.railroad.RailroadPlugin
import com.swithun.cmpmermaid.core.requirement.RequirementPlugin
import com.swithun.cmpmermaid.core.sankey.SankeyPlugin
import com.swithun.cmpmermaid.core.sequence.SequencePlugin
import com.swithun.cmpmermaid.core.statediagram.StatePlugin
import com.swithun.cmpmermaid.core.swimlane.SwimlanePlugin
import com.swithun.cmpmermaid.core.timeline.TimelinePlugin
import com.swithun.cmpmermaid.core.treemap.TreemapPlugin
import com.swithun.cmpmermaid.core.treeview.TreeViewPlugin
import com.swithun.cmpmermaid.core.usecase.UsecasePlugin
import com.swithun.cmpmermaid.core.venn.VennPlugin
import com.swithun.cmpmermaid.core.wardley.WardleyPlugin
import com.swithun.cmpmermaid.core.xychart.XyChartPlugin
import com.swithun.cmpmermaid.core.zenuml.ZenUmlPlugin
import kotlinx.coroutines.CancellationException

data class MermaidRenderContext(
    val textMetrics: TextMetricProvider,
    val theme: MermaidTheme = MermaidTheme.FlowchartDefault,
    val options: MermaidRenderOptions = MermaidRenderOptions(),
    val assetMetrics: Map<String, SceneSize> = emptyMap(),
    internal val diagramTitle: String? = null,
    internal val frontmatterLineOffset: Int = 0,
)

interface MermaidDiagramPlugin {
    val id: String
    val headers: Set<String>

    fun compile(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError>
}

class MermaidEngine(
    plugins: List<MermaidDiagramPlugin> = listOf(
        FlowchartPlugin(),
        BlockPlugin(),
        ArchitecturePlugin(),
        C4Plugin(),
        AgentflowPlugin(),
        SequencePlugin(),
        ClassPlugin(),
        StatePlugin(),
        ErPlugin(),
        GanttPlugin(),
        PiePlugin(),
        QuadrantPlugin(),
        XyChartPlugin(),
        JourneyPlugin(),
        RequirementPlugin(),
        GitGraphPlugin(),
        MindmapPlugin(),
        PacketPlugin(),
        RadarPlugin(),
        RailroadPlugin(),
        TreeViewPlugin(),
        SankeyPlugin(),
        IshikawaPlugin(),
        CynefinPlugin(),
        EventModelingPlugin(),
        SwimlanePlugin(),
        TreemapPlugin(),
        VennPlugin(),
        TimelinePlugin(),
        KanbanPlugin(),
        UsecasePlugin(),
        WardleyPlugin(),
        ZenUmlPlugin(),
    ),
) {
    private val pluginsByHeader: Map<String, MermaidDiagramPlugin> = buildMap {
        plugins.forEach { plugin ->
            plugin.headers.forEach { header ->
                put(header.lowercase(), plugin)
            }
        }
    }

    /**
     * Mermaid.js 12.0.0: packages/mermaid/src/mermaid.ts -> handleError/render.
     *
     * Expected parser and layout failures remain typed [MermaidError] values. This final public
     * boundary converts unexpected library exceptions without swallowing coroutine cancellation
     * or fatal [Error] instances.
     */
    fun render(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> = renderSafely {
        renderInternal(source, context)
    }

    private fun renderInternal(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val preprocessed = when (val result = MermaidPreprocessor.preprocess(source)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val header = preprocessed.code.cleaned
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?.takeWhile { character -> !character.isWhitespace() }
            ?.lowercase()
            .orEmpty()
        val detectedOptions = when (header) {
            "flowchart-elk" -> context.options.copy(layout = "elk")
            else -> context.options
        }
        val resolvedOptions = when (val result = preprocessed.config.applyTo(detectedOptions)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val diagramOptions = when {
            header == "requirementdiagram" || header == "requirement" ->
                resolvedOptions.copy(
                    themeName = resolvedOptions.requirementThemeName
                        ?: resolvedOptions.themeName,
                    look = resolvedOptions.requirementLook ?: resolvedOptions.look,
                )
            header == "timeline" ->
                resolvedOptions.copy(
                    themeName = resolvedOptions.timeline.theme ?: resolvedOptions.themeName,
                    look = resolvedOptions.timeline.look ?: resolvedOptions.look,
                    layout = resolvedOptions.timeline.layout ?: resolvedOptions.layout,
                )
            header == "agentflow-beta" -> resolvedOptions.agentflow.let { agentflow ->
                resolvedOptions.copy(
                    themeName = agentflow.theme
                        ?: resolvedOptions.themeName
                        ?: MermaidThemePreset.ReduxColor.configName.takeIf {
                            context.theme == MermaidTheme.FlowchartDefault
                        },
                    look = agentflow.look ?: resolvedOptions.look,
                    titleTopMargin = agentflow.titleTopMargin,
                    diagramPadding = agentflow.diagramPadding,
                    nodeSpacing = agentflow.nodeSpacing,
                    rankSpacing = agentflow.rankSpacing,
                    wrappingWidth = agentflow.wrappingWidth,
                    minNodeWidth = agentflow.minNodeWidth,
                    flowchartPadding = 8f,
                )
            }
            header == "usecase-beta" -> resolvedOptions.usecase.let { usecase ->
                resolvedOptions.copy(
                    themeName = usecase.theme
                        ?: resolvedOptions.themeName
                        ?: MermaidThemePreset.ReduxColor.configName.takeIf {
                            context.theme == MermaidTheme.FlowchartDefault
                        },
                    look = usecase.look ?: resolvedOptions.look,
                    diagramPadding = usecase.diagramPadding,
                    nodeSpacing = usecase.nodeSpacing,
                    rankSpacing = usecase.rankSpacing,
                    wrappingWidth = usecase.wrappingWidth,
                    minNodeWidth = usecase.minNodeWidth,
                )
            }
            header == "swimlane-beta" -> resolvedOptions.swimlane.let { swimlane ->
                resolvedOptions.copy(
                    themeName = swimlane.theme
                        ?: resolvedOptions.themeName
                        ?: MermaidThemePreset.ReduxColor.configName.takeIf {
                            context.theme == MermaidTheme.FlowchartDefault
                        },
                    look = swimlane.look ?: resolvedOptions.look,
                    layout = preprocessed.config.swimlane?.layout
                        ?: preprocessed.config.layout
                        ?: swimlane.layout,
                )
            }
            header == "venn-beta" &&
                resolvedOptions.themeName == null &&
                context.theme == MermaidTheme.FlowchartDefault ->
                resolvedOptions.copy(themeName = MermaidThemePreset.ReduxColor.configName)
            else -> resolvedOptions
        }
        if (diagramOptions.look == "handDrawn") {
            return GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "handDrawn look",
                    message = "Native Mermaid has not translated Mermaid's roughjs handDrawn renderer",
                ),
            )
        }
        val textSize = preprocessed.code.cleaned.length
        if (textSize > diagramOptions.maxTextSize) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "maxTextSize",
                    actual = textSize,
                    maximum = diagramOptions.maxTextSize,
                    message = "Maximum text size in diagram exceeded " +
                        "($textSize > ${diagramOptions.maxTextSize})",
                ),
            )
        }
        val baseTheme = when (val themeName = diagramOptions.themeName) {
            null -> context.theme
            "null" -> MermaidTheme.MermaidDefault
            else -> MermaidTheme.named(themeName) ?: context.theme
        }
        val resolvedThemeVariables = if (
            "fontFamily" in diagramOptions.themeVariables ||
            diagramOptions.fontFamily == null
        ) {
            diagramOptions.themeVariables
        } else {
            diagramOptions.themeVariables + ("fontFamily" to diagramOptions.fontFamily)
        }
        val resolvedTheme = when (
            val result = MermaidTheme.withVariables(
                theme = baseTheme,
                values = resolvedThemeVariables,
                colorArrays = diagramOptions.themeColorArrays,
                themeName = diagramOptions.themeName,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }

        val plugin = pluginsByHeader[header]
            ?: return GMResult.Err(MermaidError.UnsupportedDiagram(header.ifEmpty { "<empty>" }))

        val sourceForParser = if (plugin.id == "agentflow") {
            preprocessed.code.withComments
        } else {
            preprocessed.code.cleaned
        }
        val parserSource = MermaidPreprocessor.encodeEntities(sourceForParser) + "\n"
        return plugin.compileSafely(
            source = parserSource,
            context = context.copy(
                theme = resolvedTheme,
                options = diagramOptions,
                diagramTitle = preprocessed.title,
                frontmatterLineOffset = preprocessed.code.frontmatterLineOffset,
            ),
        )
    }
}

private fun MermaidDiagramPlugin.compileSafely(
    source: String,
    context: MermaidRenderContext,
): GMResult<MermaidScene, MermaidError> = renderSafely {
    compile(source, context)
}

private inline fun <T> renderSafely(
    block: () -> GMResult<T, MermaidError>,
): GMResult<T, MermaidError> = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (exception: Exception) {
    GMResult.Err(MermaidError.Unexpected.from(exception))
}
