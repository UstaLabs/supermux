package com.swithun.cmpmermaid.core

import kotlinx.coroutines.CancellationException

private const val MERMAID_CLASSIC_FONT_FAMILY =
    "\"trebuchet ms\", verdana, arial, sans-serif"
private const val MERMAID_REDUX_FONT_FAMILY =
    "\"Recursive Variable\", arial, sans-serif"
private const val MERMAID_NEO_FONT_FAMILY = "arial, sans-serif"

object MermaidCompatibility {
    const val BASELINE_VERSION: String = "12.0.0"
}

enum class MermaidRenderErrorType {
    CONTENT_ERROR,
    UNEXPECTED_EXCEPTION,
}

sealed interface MermaidError {
    val message: String
    val renderErrorType: MermaidRenderErrorType
        get() = MermaidRenderErrorType.CONTENT_ERROR

    data class UnsupportedDiagram(
        val header: String,
    ) : MermaidError {
        override val message: String = "Unsupported Mermaid diagram: $header"
    }

    data class Parse(
        val line: Int,
        val column: Int,
        override val message: String,
    ) : MermaidError

    data class Layout(
        override val message: String,
    ) : MermaidError

    data class Configuration(
        override val message: String,
    ) : MermaidError

    data class ResourceLimit(
        val resource: String,
        val actual: Int,
        val maximum: Int,
        override val message: String =
            "Mermaid $resource limit exceeded: $actual > $maximum",
    ) : MermaidError

    data class UnsupportedFeature(
        val feature: String,
        override val message: String = "Unsupported Mermaid feature: $feature",
    ) : MermaidError

    data class Unexpected(
        val exceptionType: String,
        override val message: String,
    ) : MermaidError {
        override val renderErrorType: MermaidRenderErrorType =
            MermaidRenderErrorType.UNEXPECTED_EXCEPTION

        companion object {
            fun from(
                exception: Exception,
                context: String? = null,
            ): Unexpected {
                if (exception is CancellationException) {
                    throw exception
                }
                val exceptionType = exception::class.simpleName ?: "Exception"
                val detail = exception.message?.takeIf(String::isNotBlank)
                val prefix = context?.takeIf(String::isNotBlank)
                    ?: "Unexpected Mermaid rendering exception"
                return Unexpected(
                    exceptionType = exceptionType,
                    message = if (detail == null) {
                        "$prefix: $exceptionType"
                    } else {
                        "$prefix ($exceptionType): $detail"
                    },
                )
            }
        }
    }
}

/**
 * Stable host-facing render failure payload.
 *
 * [source] is the unmodified input supplied to the renderer.
 */
data class MermaidRenderErrorInfo(
    val type: MermaidRenderErrorType,
    val message: String,
    val source: String,
)

data class MermaidXyAxisOptions(
    val showLabel: Boolean = true,
    val labelFontSize: Float = 14f,
    val labelPadding: Float = 5f,
    val showTitle: Boolean = true,
    val titleFontSize: Float = 16f,
    val titlePadding: Float = 5f,
    val showTick: Boolean = true,
    val tickLength: Float = 5f,
    val tickWidth: Float = 2f,
    val showAxisLine: Boolean = true,
    val axisLineWidth: Float = 2f,
    val labelRotation: Float = 0f,
)

data class MermaidXyChartOptions(
    val width: Float = 700f,
    val height: Float = 500f,
    val titleFontSize: Float = 20f,
    val titlePadding: Float = 10f,
    val showTitle: Boolean = true,
    val showLegend: Boolean = true,
    val legendFontSize: Float = 14f,
    val legendPadding: Float = 10f,
    val showDataLabel: Boolean = false,
    val showDataLabelOutsideBar: Boolean = false,
    val chartOrientation: String = "vertical",
    val plotReservedSpacePercent: Float = 50f,
    val xAxis: MermaidXyAxisOptions = MermaidXyAxisOptions(),
    val yAxis: MermaidXyAxisOptions = MermaidXyAxisOptions(),
)

data class MermaidQuadrantChartOptions(
    val chartWidth: Float = 500f,
    val chartHeight: Float = 500f,
    val titleFontSize: Float = 20f,
    val titlePadding: Float = 10f,
    val quadrantPadding: Float = 5f,
    val xAxisLabelPadding: Float = 5f,
    val yAxisLabelPadding: Float = 5f,
    val xAxisLabelFontSize: Float = 16f,
    val yAxisLabelFontSize: Float = 16f,
    val quadrantLabelFontSize: Float = 16f,
    val quadrantTextTopPadding: Float = 5f,
    val pointTextPadding: Float = 5f,
    val pointLabelFontSize: Float = 12f,
    val pointRadius: Float = 5f,
    val xAxisPosition: String = "top",
    val yAxisPosition: String = "left",
    val quadrantInternalBorderStrokeWidth: Float = 1f,
    val quadrantExternalBorderStrokeWidth: Float = 2f,
    val useMaxWidth: Boolean = true,
)

data class MermaidJourneyOptions(
    val diagramMarginX: Float = 50f,
    val diagramMarginY: Float = 10f,
    val leftMargin: Float = 150f,
    val maxLabelWidth: Float = 360f,
    val width: Float = 150f,
    val height: Float = 50f,
    val boxMargin: Float = 10f,
    val boxTextMargin: Float = 5f,
    val noteMargin: Float = 10f,
    val messageMargin: Float = 35f,
    val messageAlign: String = "center",
    val bottomMarginAdj: Float = 1f,
    val rightAngles: Boolean = false,
    val taskFontSize: Float = 14f,
    val taskFontFamily: String = "\"Open Sans\", sans-serif",
    val taskMargin: Float = 50f,
    val activationWidth: Float = 10f,
    val textPlacement: String = "fo",
    val actorColours: List<SceneColor> = listOf(
        SceneColor(0xFF8FBC8F),
        SceneColor(0xFF7CFC00),
        SceneColor(0xFF00FFFF),
        SceneColor(0xFF20B2AA),
        SceneColor(0xFFB0E0E6),
        SceneColor(0xFFFFFFE0),
    ),
    val sectionFills: List<SceneColor> = listOf(
        SceneColor(0xFF191970),
        SceneColor(0xFF8B008B),
        SceneColor(0xFF4B0082),
        SceneColor(0xFF2F4F4F),
        SceneColor(0xFF800000),
        SceneColor(0xFF8B4513),
        SceneColor(0xFF00008B),
    ),
    val sectionColours: List<SceneColor> = listOf(SceneColor(0xFFFFFFFF)),
    val titleColor: SceneColor? = null,
    val titleFontFamily: String = MERMAID_CLASSIC_FONT_FAMILY,
    val titleFontSize: String = "4ex",
)

data class MermaidGitGraphOptions(
    val titleTopMargin: Float = 25f,
    val diagramPadding: Float = 8f,
    val mainBranchName: String = "main",
    val mainBranchOrder: Float = 0f,
    val showCommitLabel: Boolean = true,
    val showBranches: Boolean = true,
    val rotateCommitLabel: Boolean = true,
    val parallelCommits: Boolean = false,
)

data class MermaidMindmapOptions(
    val padding: Float = 10f,
    val maxNodeWidth: Float = 200f,
    val useMaxWidth: Boolean = true,
    val layoutAlgorithm: String = "cose-bilkent",
)

data class MermaidPacketOptions(
    val rowHeight: Float = 32f,
    val bitWidth: Float = 32f,
    val bitsPerRow: Int = 32,
    val showBits: Boolean = true,
    val paddingX: Float = 5f,
    val paddingY: Float = 5f,
    val useMaxWidth: Boolean = true,
)

data class MermaidRadarOptions(
    val width: Float = 600f,
    val height: Float = 600f,
    val marginTop: Float = 50f,
    val marginRight: Float = 50f,
    val marginBottom: Float = 50f,
    val marginLeft: Float = 50f,
    val axisScaleFactor: Float = 1f,
    val axisLabelFactor: Float = 1.05f,
    val curveTension: Float = 0.17f,
    val useMaxWidth: Boolean = true,
)

data class MermaidSankeyOptions(
    val width: Float = 600f,
    val height: Float = 400f,
    val linkColor: String = "gradient",
    val nodeAlignment: String = "justify",
    val useMaxWidth: Boolean = false,
    val showValues: Boolean = true,
    val prefix: String = "",
    val suffix: String = "",
    val nodeWidth: Float = 10f,
    val nodePadding: Float = 12f,
    val labelStyle: String = "legacy",
    val nodeColors: Map<String, SceneColor> = emptyMap(),
)

data class MermaidIshikawaOptions(
    val diagramPadding: Float = 20f,
    val useMaxWidth: Boolean = false,
)

data class MermaidCynefinOptions(
    val width: Float = 800f,
    val height: Float = 600f,
    val padding: Float = 40f,
    val showDomainDescriptions: Boolean = true,
    val boundaryAmplitude: Float = 8f,
    val seed: Float = 0f,
    val useMaxWidth: Boolean = true,
)

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/config.type.ts -> WardleyDiagramConfig.
 */
data class MermaidWardleyOptions(
    val width: Float = 900f,
    val height: Float = 600f,
    val padding: Float = 48f,
    val nodeRadius: Float = 6f,
    val nodeLabelOffset: Float = 8f,
    val axisFontSize: Float = 12f,
    val labelFontSize: Float = 10f,
    val showGrid: Boolean = false,
    val useMaxWidth: Boolean = true,
)

data class MermaidEventModelingOptions(
    val padding: Float = 30f,
    val rowHeight: Float = 32f,
    val useMaxWidth: Boolean = true,
)

data class MermaidBlockOptions(
    val padding: Float = 8f,
    val useMaxWidth: Boolean = true,
)

data class MermaidArchitectureOptions(
    val useMaxWidth: Boolean = true,
    val padding: Float = 40f,
    val iconSize: Float = 80f,
    val fontSize: Float = 16f,
    val randomize: Boolean = false,
    val nodeSeparation: Float = 75f,
    val idealEdgeLengthMultiplier: Float = 1.5f,
    val edgeElasticity: Float = 0.45f,
    val numIter: Float = 2500f,
    val seed: Float = 1f,
)

data class MermaidC4ElementOptions(
    val fontSize: Float = 14f,
    val fontFamily: String = "\"Open Sans\", sans-serif",
    val fontWeight: String = "normal",
    val background: SceneColor? = null,
    val border: SceneColor? = null,
)

data class MermaidC4Options(
    val diagramMarginX: Float = 50f,
    val diagramMarginY: Float = 10f,
    val c4ShapeMargin: Float = 50f,
    val c4ShapePadding: Float = 20f,
    val width: Float = 216f,
    val height: Float = 60f,
    val boxMargin: Float = 10f,
    val useMaxWidth: Boolean = true,
    val c4ShapeInRow: Int = 4,
    val nextLinePaddingX: Float = 0f,
    val c4BoundaryInRow: Int = 2,
    val wrap: Boolean = true,
    val wrapPadding: Float = 10f,
    val boundaryFontSize: Float = 14f,
    val boundaryFontFamily: String = "\"Open Sans\", sans-serif",
    val boundaryFontWeight: String = "normal",
    val messageFontSize: Float = 12f,
    val messageFontFamily: String = "\"Open Sans\", sans-serif",
    val messageFontWeight: String = "normal",
    val elementStyles: Map<String, MermaidC4ElementOptions> = emptyMap(),
)

data class MermaidRailroadOptions(
    val useMaxWidth: Boolean = true,
    val compactMode: Boolean = false,
    val padding: Float = 10f,
    val verticalSeparation: Float = 8f,
    val horizontalSeparation: Float = 10f,
    val arcRadius: Float = 10f,
    val fontSize: Float? = null,
    val fontFamily: String? = null,
    val terminalFill: SceneColor? = null,
    val terminalStroke: SceneColor? = null,
    val terminalTextColor: SceneColor? = null,
    val nonTerminalFill: SceneColor? = null,
    val nonTerminalStroke: SceneColor? = null,
    val nonTerminalTextColor: SceneColor? = null,
    val lineColor: SceneColor? = null,
    val strokeWidth: Float = 2f,
    val markerFill: SceneColor? = null,
    val commentFill: SceneColor? = null,
    val commentStroke: SceneColor? = null,
    val commentTextColor: SceneColor? = null,
    val specialFill: SceneColor? = null,
    val specialStroke: SceneColor? = null,
    val ruleNameColor: SceneColor? = null,
    val showMarkers: Boolean = true,
    val markerRadius: Float = 5f,
)

data class MermaidTreeViewOptions(
    val useMaxWidth: Boolean = true,
    val rowIndent: Float = 10f,
    val paddingX: Float = 5f,
    val paddingY: Float = 5f,
    val lineThickness: Float = 1f,
    val showIcons: Boolean = false,
    val defaultIconPack: String = "",
    val filenameIcons: Map<String, String> = emptyMap(),
    val extensionIcons: Map<String, String> = emptyMap(),
)

data class MermaidTreemapOptions(
    val useMaxWidth: Boolean = true,
    val padding: Float = 10f,
    val diagramPadding: Float = 8f,
    val showValues: Boolean = true,
    val nodeWidth: Float = 100f,
    val nodeHeight: Float = 40f,
    val borderWidth: Float = 1f,
    val valueFontSize: Float = 12f,
    val labelFontSize: Float = 14f,
    val valueFormat: String = ",",
)

data class MermaidVennOptions(
    val width: Float = 800f,
    val height: Float = 450f,
    val padding: Float = 8f,
    val useDebugLayout: Boolean = false,
    val useMaxWidth: Boolean = true,
)

data class MermaidKanbanOptions(
    val padding: Float = 8f,
    val sectionWidth: Float = 200f,
    val ticketBaseUrl: String = "",
)

data class MermaidTimelineOptions(
    val useWidth: Float? = null,
    val useMaxWidth: Boolean = true,
    val theme: String? = null,
    val look: String? = null,
    val layout: String? = null,
    val diagramMarginX: Float = 50f,
    val diagramMarginY: Float = 10f,
    val leftMargin: Float = 150f,
    val width: Float = 150f,
    val height: Float = 50f,
    val padding: Float = 50f,
    val boxMargin: Float = 10f,
    val boxTextMargin: Float = 5f,
    val noteMargin: Float = 10f,
    val messageMargin: Float = 35f,
    val messageAlign: String = "center",
    val bottomMarginAdj: Float = 1f,
    val rightAngles: Boolean = false,
    val taskFontSize: Float = 14f,
    val taskFontFamily: String = "\"Open Sans\", sans-serif",
    val taskMargin: Float = 50f,
    val activationWidth: Float = 10f,
    val textPlacement: String = "fo",
    val actorColours: List<SceneColor> = listOf(
        SceneColor(0xFF8FBC8F),
        SceneColor(0xFF7CFC00),
        SceneColor(0xFF00FFFF),
        SceneColor(0xFF20B2AA),
        SceneColor(0xFFB0E0E6),
        SceneColor(0xFFFFFFE0),
    ),
    val sectionFills: List<SceneColor> = listOf(
        SceneColor(0xFF191970),
        SceneColor(0xFF8B008B),
        SceneColor(0xFF4B0082),
        SceneColor(0xFF2F4F4F),
        SceneColor(0xFF800000),
        SceneColor(0xFF8B4513),
        SceneColor(0xFF00008B),
    ),
    val sectionColours: List<SceneColor> = listOf(SceneColor(0xFFFFFFFF)),
    val disableMulticolor: Boolean = false,
)

data class MermaidAgentflowOptions(
    val useMaxWidth: Boolean = true,
    val theme: String? = null,
    val look: String? = null,
    val titleTopMargin: Float = 25f,
    val diagramPadding: Float = 8f,
    val nodeSpacing: Float = 50f,
    val rankSpacing: Float = 50f,
    val wrappingWidth: Float = 120f,
    val minNodeWidth: Float = 120f,
)

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/config.type.ts -> UsecaseDiagramConfig.
 */
data class MermaidUsecaseOptions(
    val useMaxWidth: Boolean = true,
    val theme: String? = null,
    val look: String? = null,
    val wrappingWidth: Float = 120f,
    val minNodeWidth: Float = 120f,
    val actorFontSize: Float = 14f,
    val actorFontFamily: String = "\"Open Sans\", sans-serif",
    val actorFontWeight: String = "normal",
    val usecaseFontSize: Float = 12f,
    val usecaseFontFamily: String = "\"Open Sans\", sans-serif",
    val usecaseFontWeight: String = "normal",
    val nodeSpacing: Float = 50f,
    val rankSpacing: Float = 50f,
    val diagramPadding: Float = 20f,
    val colorScheme: String = "role",
)

data class MermaidSwimlaneOptions(
    val theme: String? = null,
    val look: String? = null,
    val layout: String = "swimlane",
    val lineHops: MermaidElkLineHops = MermaidElkLineHops.Arc,
    val ignoreCrossLaneEdges: Boolean = true,
    val optimizeRanksByCrossings: Boolean = true,
    val automaticLaneOrdering: Boolean = false,
)

data class MermaidRenderOptions(
    val layout: String = "dagre",
    val classLayout: String? = null,
    val stateLayout: String? = null,
    val requirementThemeName: String? = null,
    val requirementLook: String? = null,
    val elk: MermaidElkOptions = MermaidElkOptions(),
    val nodeSpacing: Float = 50f,
    val rankSpacing: Float = 50f,
    val diagramPadding: Float = 8f,
    val wrappingWidth: Float = 120f,
    val minNodeWidth: Float = 120f,
    val flowchartPadding: Float = 15f,
    val classPadding: Float = 12f,
    val classNotePadding: Float = 6f,
    val classHideEmptyMembersBox: Boolean = false,
    val classHierarchicalNamespaces: Boolean = true,
    val statePadding: Float = 8f,
    val stateWrappingWidth: Float = 120f,
    val stateMinNodeWidth: Float = 120f,
    val stateNodeSpacing: Float? = null,
    val stateRankSpacing: Float? = null,
    val stateTitleTopMargin: Float = 25f,
    val erDiagramPadding: Float = 20f,
    val erEntityPadding: Float = 15f,
    val erMinEntityWidth: Float = 100f,
    val erMinEntityHeight: Float = 75f,
    val erNodeSpacing: Float = 140f,
    val erRankSpacing: Float = 80f,
    val erTitleTopMargin: Float = 25f,
    val ganttTitleTopMargin: Float = 25f,
    val ganttBarHeight: Float = 20f,
    val ganttBarGap: Float = 4f,
    val ganttTopPadding: Float = 50f,
    val ganttRightPadding: Float = 75f,
    val ganttLeftPadding: Float = 75f,
    val ganttGridLineStartPadding: Float = 35f,
    val ganttFontSize: Float = 11f,
    val ganttSectionFontSize: Float = 11f,
    val ganttNumberSectionStyles: Int = 4,
    val ganttAxisFormat: String = "%Y-%m-%d",
    val ganttTickInterval: String? = null,
    val ganttTopAxis: Boolean = false,
    val ganttDisplayMode: String = "",
    val ganttWeekday: String = "sunday",
    val ganttUseWidth: Float = 1200f,
    val pieTextPosition: Float = 0.75f,
    val pieDonutHole: Float = 0f,
    val pieLegendPosition: String = "right",
    val pieHighlightSlice: String = "",
    val xyChart: MermaidXyChartOptions = MermaidXyChartOptions(),
    val quadrantChart: MermaidQuadrantChartOptions = MermaidQuadrantChartOptions(),
    val journey: MermaidJourneyOptions = MermaidJourneyOptions(),
    val gitGraph: MermaidGitGraphOptions = MermaidGitGraphOptions(),
    val mindmap: MermaidMindmapOptions = MermaidMindmapOptions(),
    val packet: MermaidPacketOptions = MermaidPacketOptions(),
    val radar: MermaidRadarOptions = MermaidRadarOptions(),
    val sankey: MermaidSankeyOptions = MermaidSankeyOptions(),
    val ishikawa: MermaidIshikawaOptions = MermaidIshikawaOptions(),
    val cynefin: MermaidCynefinOptions = MermaidCynefinOptions(),
    val wardley: MermaidWardleyOptions = MermaidWardleyOptions(),
    val eventModeling: MermaidEventModelingOptions = MermaidEventModelingOptions(),
    val block: MermaidBlockOptions = MermaidBlockOptions(),
    val architecture: MermaidArchitectureOptions = MermaidArchitectureOptions(),
    val c4: MermaidC4Options = MermaidC4Options(),
    val railroad: MermaidRailroadOptions = MermaidRailroadOptions(),
    val treeView: MermaidTreeViewOptions = MermaidTreeViewOptions(),
    val treemap: MermaidTreemapOptions = MermaidTreemapOptions(),
    val venn: MermaidVennOptions = MermaidVennOptions(),
    val kanban: MermaidKanbanOptions = MermaidKanbanOptions(),
    val timeline: MermaidTimelineOptions = MermaidTimelineOptions(),
    val agentflow: MermaidAgentflowOptions = MermaidAgentflowOptions(),
    val usecase: MermaidUsecaseOptions = MermaidUsecaseOptions(),
    val swimlane: MermaidSwimlaneOptions = MermaidSwimlaneOptions(),
    val curve: String = "basis",
    val fontSize: Float? = null,
    val fontFamily: String? = null,
    val look: String = "neo",
    val titleTopMargin: Float = 25f,
    val subGraphTitleTopMargin: Float = 0f,
    val subGraphTitleBottomMargin: Float = 0f,
    val inheritDirection: Boolean = false,
    val htmlLabels: Boolean = true,
    val markdownAutoWrap: Boolean = true,
    val securityLevel: MermaidSecurityLevel = MermaidSecurityLevel.Strict,
    val maxTextSize: Int = 50_000,
    val maxEdges: Int = 500,
    val themeName: String? = null,
    val themeVariables: Map<String, String> = emptyMap(),
    val themeColorArrays: Map<String, List<String>> = emptyMap(),
)

internal val MermaidRenderOptions.subGraphTitleTotalMargin: Float
    get() = subGraphTitleTopMargin + subGraphTitleBottomMargin

data class MermaidElkOptions(
    val mergeEdges: Boolean = false,
    val nodePlacementStrategy: String? = null,
    val nodePlacementAlignment: String? = null,
    val preset: String = "default",
    val straightenEdges: Boolean = true,
    val lineHops: MermaidElkLineHops = MermaidElkLineHops.Arc,
    val layeringStrategy: String? = null,
    val layeringLayerBound: Int = 4,
    val cycleBreakingStrategy: String? = null,
    val forceNodeModelOrder: Boolean = false,
    val considerModelOrder: String = "NODES_AND_EDGES",
    val keepEntryNodeOnTop: Boolean = false,
)

enum class MermaidElkLineHops {
    Disabled,
    Arc,
    Gap,
}

enum class MermaidSecurityLevel {
    Strict,
    Loose,
    Antiscript,
    Sandbox,
}

/**
 * Mermaid 12 built-in theme names, available without parsing diagram configuration.
 */
enum class MermaidThemePreset(
    val configName: String,
) {
    Default("default"),
    Dark("dark"),
    Forest("forest"),
    Neutral("neutral"),
    Base("base"),
    Neo("neo"),
    NeoDark("neo-dark"),
    Redux("redux"),
    ReduxColor("redux-color"),
    ReduxDark("redux-dark"),
    ReduxDarkColor("redux-dark-color"),
}

data class MermaidPieTheme(
    val colors: List<SceneColor> = listOf(
        SceneColor(0xFFECECFF),
        SceneColor(0xFFFFFFDE),
        SceneColor(0xFFB5FF20),
        SceneColor(0xFFB9B9FF),
        SceneColor(0xFFFFFF45),
        SceneColor(0xFFD7FF86),
        SceneColor(0xFFFF86FF),
        SceneColor(0xFF20FFFF),
        SceneColor(0xFFFF2020),
        SceneColor(0xFFFF20FF),
        SceneColor(0xFF20FF90),
        SceneColor(0xFFFF5353),
    ),
    val titleTextSize: Float = 25f,
    val titleTextColor: SceneColor = SceneColor(0xFF000000),
    val sectionTextSize: Float = 17f,
    val sectionTextColor: SceneColor = SceneColor(0xFF333333),
    val legendTextSize: Float = 17f,
    val legendTextColor: SceneColor = SceneColor(0xFF000000),
    val strokeColor: SceneColor = SceneColor(0xFF000000),
    val strokeWidth: Float = 2f,
    val outerStrokeWidth: Float = 2f,
    val outerStrokeColor: SceneColor = SceneColor(0xFF000000),
    val opacity: Float = 0.7f,
)

data class MermaidXyChartTheme(
    val backgroundColor: SceneColor = SceneColor(0xFFFFFFFF),
    // Mermaid 12.0.0: themes/theme-default.js -> Theme.calculate.
    val titleColor: SceneColor = SceneColor(0xFF131300),
    val dataLabelColor: SceneColor = SceneColor(0xFF131300),
    val legendTextColor: SceneColor = SceneColor(0xFF131300),
    val xAxisLabelColor: SceneColor = SceneColor(0xFF131300),
    val xAxisTitleColor: SceneColor = SceneColor(0xFF131300),
    val xAxisTickColor: SceneColor = SceneColor(0xFF131300),
    val xAxisLineColor: SceneColor = SceneColor(0xFF131300),
    val yAxisLabelColor: SceneColor = SceneColor(0xFF131300),
    val yAxisTitleColor: SceneColor = SceneColor(0xFF131300),
    val yAxisTickColor: SceneColor = SceneColor(0xFF131300),
    val yAxisLineColor: SceneColor = SceneColor(0xFF131300),
    val plotColorPalette: List<SceneColor> = listOf(
        SceneColor(0xFFECECFF),
        SceneColor(0xFF8493A6),
        SceneColor(0xFFFFC3A0),
        SceneColor(0xFFDCDDE1),
        SceneColor(0xFFB8E994),
        SceneColor(0xFFD1A36F),
        SceneColor(0xFFC3CDE6),
        SceneColor(0xFFFFB6C1),
        SceneColor(0xFF496078),
        SceneColor(0xFFF8F3E3),
    ),
)

data class MermaidGanttTheme(
    val sectionBackground: SceneColor = SceneColor(0x7D6666FF),
    val alternateSectionBackground: SceneColor = SceneColor(0xFFFFFFFF),
    val secondSectionBackground: SceneColor = SceneColor(0xFFFFF400),
    val excludedBackground: SceneColor = SceneColor(0xFFEEEEEE),
    val taskFill: SceneColor = SceneColor(0xFF8A90DD),
    val taskStroke: SceneColor = SceneColor(0xFF534FBC),
    val activeTaskFill: SceneColor = SceneColor(0xFFBFC7FF),
    val activeTaskStroke: SceneColor = SceneColor(0xFF534FBC),
    val doneTaskFill: SceneColor = SceneColor(0xFFD3D3D3),
    val doneTaskStroke: SceneColor = SceneColor(0xFF808080),
    val criticalTaskFill: SceneColor = SceneColor(0xFFFF0000),
    val criticalTaskStroke: SceneColor = SceneColor(0xFFFF8888),
    val taskText: SceneColor = SceneColor(0xFFFFFFFF),
    val darkTaskText: SceneColor = SceneColor(0xFF000000),
    val outsideTaskText: SceneColor = SceneColor(0xFF000000),
    val clickableTaskText: SceneColor = SceneColor(0xFF003163),
    val text: SceneColor = SceneColor(0xFF333333),
    val grid: SceneColor = SceneColor(0xFFD3D3D3),
    val todayLine: SceneColor = SceneColor(0xFFFF0000),
    val verticalLine: SceneColor = SceneColor(0xFF000080),
    val title: SceneColor = SceneColor(0xFF333333),
)

data class MermaidJourneyTheme(
    val sectionFills: List<SceneColor> = listOf(
        SceneColor(0xFFECECFF),
        SceneColor(0xFFFFFFDE),
        SceneColor(0xFFFFECFE),
        SceneColor(0xFFDEFFE0),
        SceneColor(0xFFECFFFE),
        SceneColor(0xFFFFDEE0),
        SceneColor(0xFFFFEFEC),
        SceneColor(0xFFDEFBFF),
    ),
    val actorColors: List<SceneColor?> = List(6) { null },
    val textColor: SceneColor = SceneColor(0xFF333333),
    val faceColor: SceneColor = SceneColor(0xFFFFF8DC),
    val faceStroke: SceneColor = SceneColor(0xFF999999),
    val detailStroke: SceneColor = SceneColor(0xFF666666),
    val boxStroke: SceneColor = SceneColor(0xFF666666),
)

data class MermaidRequirementTheme(
    val background: SceneColor = SceneColor(0xFFECECFF),
    val borderColor: SceneColor = SceneColor(0xFF9370DB),
    val borderSize: Float = 1f,
    val textColor: SceneColor = SceneColor(0xFF333333),
    val relationColor: SceneColor = SceneColor(0xFF333333),
    val relationLabelBackground: SceneColor = SceneColor(0xCCE8E8E8),
    val relationLabelColor: SceneColor = SceneColor(0xFF333333),
    val edgeLabelBackground: SceneColor = SceneColor(0xCCE8E8E8),
)

data class MermaidGitGraphTheme(
    val colors: List<SceneColor> = listOf(
        0xFF0000EC,
        0xFFDEDE00,
        0xFF9DEC00,
        0xFF0076EC,
        0xFF00ECEC,
        0xFF00EC76,
        0xFFEC00EC,
        0xFFEC0000,
    ).map(::SceneColor),
    val inverseColors: List<SceneColor> = listOf(
        0xFF131300,
        0xFF0000A1,
        0xFF310093,
        0xFF934900,
        0xFF930000,
        0xFF930049,
        0xFF009300,
        0xFF009393,
    ).map(::SceneColor),
    val branchLabelColors: List<SceneColor> = listOf(
        0xFFFFFFFF,
        0xFF000000,
        0xFF000000,
        0xFFFFFFFF,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
    ).map(::SceneColor),
    val commitLineColor: SceneColor = SceneColor(0xFF333333),
    val commitLabelColor: SceneColor = SceneColor(0xFF000021),
    val commitLabelBackground: SceneColor = SceneColor(0xFFFFFFDE),
    val tagLabelColor: SceneColor = SceneColor(0xFF131300),
    val tagLabelBackground: SceneColor = SceneColor(0xFFECECFF),
    val tagLabelBorder: SceneColor = SceneColor(0xFFC7C7F1),
    val nodeBorder: SceneColor = SceneColor(0xFF9370DB),
    val mainBackground: SceneColor = SceneColor(0xFFECECFF),
    val primaryColor: SceneColor = SceneColor(0xFFECECFF),
    val textColor: SceneColor = SceneColor(0xFF333333),
    val useGradient: Boolean = false,
    val gradientStart: SceneColor = SceneColor(0xFF0042EB),
    val gradientStop: SceneColor = SceneColor(0xFFEB0042),
    val commitLabelFontSize: Float = 10f,
    val tagLabelFontSize: Float = 10f,
)

data class MermaidMindmapTheme(
    val mainBackground: SceneColor = SceneColor(0xFFECECFF),
    val nodeBorder: SceneColor = SceneColor(0xFF9370DB),
    val rootFill: SceneColor = SceneColor(0xFF0000EC),
    val rootText: SceneColor = SceneColor(0xFFFFFFFF),
    val sectionFills: List<SceneColor> = listOf(
        0xFF8686FF,
        0xFFFFFF78,
        0xFFD7FF86,
        0xFFC286FF,
        0xFFFF86FF,
        0xFFFF86C2,
        0xFFFF8686,
        0xFFFFC286,
        0xFFC2FF86,
        0xFF86FFC2,
        0xFF86FFFF,
        0xFF86C2FF,
    ).map(::SceneColor),
    val sectionInverseColors: List<SceneColor> = listOf(
        0xFFFFFFB9,
        0xFFABABFF,
        0xFFD0B9FF,
        0xFFDCFFB9,
        0xFFB9FFB9,
        0xFFB9FFDC,
        0xFFB9FFFF,
        0xFFB9DCFF,
        0xFFDCB9FF,
        0xFFFFB9DC,
        0xFFFFB9B9,
        0xFFFFDCB9,
    ).map(::SceneColor),
    val sectionLabelColors: List<SceneColor> = listOf(
        0xFFFFFFFF,
        0xFF000000,
        0xFF000000,
        0xFFFFFFFF,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
    ).map(::SceneColor),
    val useGradient: Boolean = false,
    val gradientStart: SceneColor = SceneColor(0xFFC7C7F1),
    val gradientStop: SceneColor = SceneColor(0xFFEEEEBC),
)

data class MermaidRadarTheme(
    val colors: List<SceneColor> = MermaidMindmapTheme().sectionFills,
    val axisColor: SceneColor = SceneColor(0xFF333333),
    val axisStrokeWidth: Float = 2f,
    val axisLabelFontSize: Float = 12f,
    val curveOpacity: Float = 0.5f,
    val curveStrokeWidth: Float = 2f,
    val graticuleColor: SceneColor = SceneColor(0xFFDEDEDE),
    val graticuleOpacity: Float = 0.3f,
    val graticuleStrokeWidth: Float = 1f,
    val legendBoxSize: Float = 12f,
    val legendFontSize: Float = 12f,
)

data class MermaidCynefinTheme(
    val domainFontSize: Float = 16f,
    val itemFontSize: Float = 12f,
    val boundaryColor: SceneColor = SceneColor(0xFF333333),
    val boundaryWidth: Float = 2f,
    val cliffColor: SceneColor = SceneColor(0xFF8B0000),
    val cliffWidth: Float = 4f,
    val arrowColor: SceneColor = SceneColor(0xFF333333),
    val arrowWidth: Float = 2f,
    val complexBackground: SceneColor = SceneColor(0xFFE8F5E9),
    val complicatedBackground: SceneColor = SceneColor(0xFFE3F2FD),
    val chaoticBackground: SceneColor = SceneColor(0xFFFBE9E7),
    val clearBackground: SceneColor = SceneColor(0xFFFFF8E1),
    val confusionBackground: SceneColor = SceneColor(0xFFF3E5F5),
    val textColor: SceneColor = SceneColor(0xFF333333),
    val labelColor: SceneColor = SceneColor(0xFF131300),
)

data class MermaidEventModelingTheme(
    val uiFill: SceneColor = SceneColor(0xFFFFFFFF),
    val uiStroke: SceneColor = SceneColor(0xFFDBDADA),
    val processorFill: SceneColor = SceneColor(0xFFEDB3F6),
    val processorStroke: SceneColor = SceneColor(0xFFB88CBF),
    val readModelFill: SceneColor = SceneColor(0xFFD3F1A2),
    val readModelStroke: SceneColor = SceneColor(0xFFA3B732),
    val commandFill: SceneColor = SceneColor(0xFFBCD6FE),
    val commandStroke: SceneColor = SceneColor(0xFF679AC3),
    val eventFill: SceneColor = SceneColor(0xFFFFB778),
    val eventStroke: SceneColor = SceneColor(0xFFC19A0F),
    val swimlaneBackgroundOdd: SceneColor = SceneColor(0xFFFAFAFA),
    val swimlaneBackgroundStroke: SceneColor = SceneColor(0xFFF0F0F0),
    val arrowhead: SceneColor? = null,
    val relationStroke: SceneColor? = null,
)

data class MermaidTimelineTheme(
    val mainBackground: SceneColor = SceneColor(0xFFECECFF),
    val nodeBorder: SceneColor = SceneColor(0xFF9370DB),
    val sectionFills: List<SceneColor> = listOf(
        0xFF8686FF,
        0xFFFFFF78,
        0xFFD7FF86,
        0xFFC286FF,
        0xFFFF86FF,
        0xFFFF86C2,
        0xFFFF8686,
        0xFFFFC286,
        0xFFC2FF86,
        0xFF86FFC2,
        0xFF86FFFF,
        0xFF86C2FF,
    ).map(::SceneColor),
    val sectionInverseColors: List<SceneColor> = listOf(
        0xFFFFFFB9,
        0xFFABABFF,
        0xFFD0B9FF,
        0xFFDCFFB9,
        0xFFB9FFB9,
        0xFFB9FFDC,
        0xFFB9FFFF,
        0xFFB9DCFF,
        0xFFDCB9FF,
        0xFFFFB9DC,
        0xFFFFB9B9,
        0xFFFFDCB9,
    ).map(::SceneColor),
    val sectionLabelColors: List<SceneColor> = listOf(
        0xFFFFFFFF,
        0xFF000000,
        0xFF000000,
        0xFFFFFFFF,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
        0xFF000000,
    ).map(::SceneColor),
    val useGradient: Boolean = false,
    val gradientStart: SceneColor = SceneColor(0xFFC7C7F1),
    val gradientStop: SceneColor = SceneColor(0xFFEEEEBC),
)

data class MermaidTheme(
    val background: SceneColor = SceneColor(0xFFFFFFFF),
    val nodeFill: SceneColor = SceneColor(0xFFECECFF),
    val nodeStroke: SceneColor = SceneColor(0xFF9370DB),
    val nodeText: SceneColor = SceneColor(0xFF131300),
    val edge: SceneColor = SceneColor(0xFF333333),
    val edgeLabelFill: SceneColor = SceneColor(0xCCE8E8E8),
    val groupFill: SceneColor = SceneColor(0xFFFFFFDE),
    val groupStroke: SceneColor = SceneColor(0xFFAAAA33),
    val flowContainerStroke: SceneColor = SceneColor(0xFFEEEEBC),
    val groupText: SceneColor = SceneColor(0xFF333333),
    val noteFill: SceneColor = SceneColor(0xFFFFF5AD),
    val noteStroke: SceneColor = SceneColor(0xFFAAAA33),
    val noteText: SceneColor = SceneColor(0xFF000000),
    val textColor: SceneColor = SceneColor(0xFF333333),
    /** ER attribute row fills (Mermaid themeVariables rowOdd/rowEven); null derives them from [nodeFill]. */
    val rowOdd: SceneColor? = null,
    val rowEven: SceneColor? = null,
    val fontSize: Float = 16f,
    val fontFamily: String = MERMAID_CLASSIC_FONT_FAMILY,
    val strokeWidth: Float = 1f,
    val bkgColorArray: List<SceneColor> = emptyList(),
    val borderColorArray: List<SceneColor> = emptyList(),
    val pie: MermaidPieTheme = MermaidPieTheme(),
    val xyChart: MermaidXyChartTheme = MermaidXyChartTheme(),
    val gantt: MermaidGanttTheme = MermaidGanttTheme(),
    val journey: MermaidJourneyTheme = MermaidJourneyTheme(),
    val requirement: MermaidRequirementTheme = MermaidRequirementTheme(),
    val gitGraph: MermaidGitGraphTheme = MermaidGitGraphTheme(),
    val mindmap: MermaidMindmapTheme = MermaidMindmapTheme(),
    val radar: MermaidRadarTheme = MermaidRadarTheme(),
    val cynefin: MermaidCynefinTheme = MermaidCynefinTheme(),
    val eventModeling: MermaidEventModelingTheme = MermaidEventModelingTheme(),
    val timeline: MermaidTimelineTheme = MermaidTimelineTheme(),
    val dropShadow: SceneShadow? = SceneShadow(
        color = SceneColor(0xFFB9B9B9),
        offsetX = 1f,
        offsetY = 2f,
        blurRadius = 2f,
    ),
) {
    internal fun colorFill(index: Int?): SceneColor =
        paletteColor(bkgColorArray, index) ?: groupFill

    internal fun colorStroke(index: Int?): SceneColor =
        paletteColor(borderColorArray, index) ?: groupStroke

    companion object {
        val MermaidDefault = MermaidTheme()

        val Dark = MermaidTheme(
            background = SceneColor(0xFF333333),
            nodeFill = SceneColor(0xFF1F2020),
            nodeStroke = SceneColor(0xFFCCCCCC),
            nodeText = SceneColor(0xFFE0DFDF),
            edge = SceneColor(0xFFD3D3D3),
            edgeLabelFill = SceneColor(0xFF585858),
            groupFill = SceneColor(0xFF474949),
            groupStroke = SceneColor(0x40FFFFFF),
            flowContainerStroke = SceneColor(0xFF2F2F2F),
            groupText = SceneColor(0xFFF9FFFE),
            noteFill = SceneColor(0xFF474949),
            noteStroke = SceneColor(0xFF2F2F2F),
            noteText = SceneColor(0xFFB8B6B6),
            textColor = SceneColor(0xFFCCCCCC),
            gantt = darkGanttTheme(),
            journey = darkJourneyTheme(),
            requirement = darkRequirementTheme(),
            gitGraph = gitGraphTheme("dark"),
            mindmap = mindmapTheme("dark"),
            radar = radarTheme("dark", 0xFFD3D3D3),
            cynefin = cynefinTheme(
                edge = 0xFFD3D3D3,
                text = 0xFFCCCCCC,
                label = 0xFFE0DFDF,
                cliff = 0xFFFF6B6B,
                complex = 0xFF1B5E20,
                complicated = 0xFF0D47A1,
                chaotic = 0xFFBF360C,
                clear = 0xFFF57F17,
                confusion = 0xFF4A148C,
            ),
            eventModeling = MermaidEventModelingTheme(
                uiFill = SceneColor(0xFF2D2D2D),
                uiStroke = SceneColor(0xFF555555),
                processorFill = SceneColor(0xFF78517B),
                processorStroke = SceneColor(0xFF8A6D8C),
                readModelFill = SceneColor(0xFF547C3E),
                readModelStroke = SceneColor(0xFF6D8C5C),
                commandFill = SceneColor(0xFF3E547C),
                commandStroke = SceneColor(0xFF5C6D8C),
                eventFill = SceneColor(0xFF7C5F3E),
                eventStroke = SceneColor(0xFF8C755C),
                swimlaneBackgroundOdd = SceneColor(0xFF404040),
                swimlaneBackgroundStroke = SceneColor(0xFF525252),
                arrowhead = SceneColor(0xFFD3D3D3),
                relationStroke = SceneColor(0xFFD3D3D3),
            ),
            timeline = timelineTheme("dark"),
            pie = darkPieTheme(),
            xyChart = xyChartTheme(
                background = 0xFF333333,
                text = 0xFFCCCCCC,
                palette = listOf(
                    0xFF3498DB,
                    0xFF2ECC71,
                    0xFFE74C3C,
                    0xFFF1C40F,
                    0xFFBDC3C7,
                    0xFFFFFFFF,
                    0xFF34495E,
                    0xFF9B59B6,
                    0xFF1ABC9C,
                    0xFFE67E22,
                ),
            ),
        )

        val FlowchartDefault = MermaidTheme(
            background = SceneColor(0xFFFFFFFF),
            nodeFill = SceneColor(0xFFFFFFFF),
            nodeStroke = SceneColor(0xFF28253D),
            nodeText = SceneColor(0xFF28253D),
            edge = SceneColor(0xFF000000),
            edgeLabelFill = SceneColor(0xFFCCCCCC),
            groupFill = SceneColor(0xFFF9F9FB),
            groupStroke = SceneColor(0xFFBDBCCC),
            flowContainerStroke = SceneColor(0xFFB3B3B3),
            groupText = SceneColor(0xFF000000),
            noteFill = SceneColor(0xFFFFF5AD),
            noteStroke = SceneColor(0xFFFACC15),
            noteText = SceneColor(0xFF28253D),
            textColor = SceneColor(0xFF28253D),
            gantt = reduxColorGanttTheme(),
            journey = reduxColorJourneyTheme(dark = false),
            fontSize = 14f,
            fontFamily = MERMAID_REDUX_FONT_FAMILY,
            strokeWidth = 2f,
            bkgColorArray = reduxColorBackgrounds(),
            borderColorArray = reduxColorBorders(),
            pie = reduxColorPieTheme(dark = false),
            xyChart = reduxXyChartTheme(background = 0xFFFFFFFF, text = 0xFF28253D),
            requirement = reduxRequirementTheme(dark = false),
            gitGraph = gitGraphTheme("redux"),
            mindmap = mindmapTheme("redux-color"),
            radar = radarTheme("redux-color", 0xFF000000),
            cynefin = cynefinTheme(
                edge = 0xFF000000,
                text = 0xFF28253D,
                label = 0xFF28253D,
            ),
            timeline = timelineTheme("redux-color"),
            dropShadow = reduxShadow(dark = false),
        )

        /**
         * Resolves a Mermaid 12 built-in theme for host-controlled runtime switching.
         */
        fun preset(preset: MermaidThemePreset): MermaidTheme =
            named(preset.configName) ?: MermaidDefault

        /**
         * Resolves a Mermaid 12 theme name without throwing on persisted or remote input.
         */
        fun fromName(name: String): GMResult<MermaidTheme, MermaidError> =
            named(name.trim())?.let { theme -> GMResult.Ok(theme) } ?: GMResult.Err(
                MermaidError.Configuration(
                    "Unsupported Mermaid theme '$name'",
                ),
            )

        internal fun named(name: String): MermaidTheme? = when (name.lowercase()) {
            "default" -> MermaidDefault
            "dark" -> Dark
            "forest" -> MermaidTheme(
                background = SceneColor(0xFFFFFFFF),
                nodeFill = SceneColor(0xFFCDE498),
                nodeStroke = SceneColor(0xFF13540C),
                nodeText = SceneColor(0xFF321B67),
                edge = SceneColor(0xFF000000),
                edgeLabelFill = SceneColor(0xFFE8E8E8),
                groupFill = SceneColor(0xFFCDFFB2),
                groupStroke = SceneColor(0xFF6EAA49),
                flowContainerStroke = SceneColor(0xFFB4E599),
                groupText = SceneColor(0xFF333333),
                noteFill = SceneColor(0xFFFFF5AD),
                noteStroke = SceneColor(0xFF6EAA49),
                noteText = SceneColor(0xFF000000),
                textColor = SceneColor(0xFF000000),
                gantt = forestGanttTheme(),
                journey = forestJourneyTheme(),
                requirement = forestRequirementTheme(),
                gitGraph = gitGraphTheme("forest"),
                mindmap = mindmapTheme("forest"),
                radar = radarTheme("forest", 0xFF000000),
                cynefin = cynefinTheme(
                    edge = 0xFF000000,
                    text = 0xFF000000,
                    label = 0xFF321B67,
                    cliff = 0xFF8B4513,
                    complex = 0xFFC8E6C9,
                    complicated = 0xFFDCEDC8,
                    chaotic = 0xFFFFE0B2,
                    clear = 0xFFFFF9C4,
                    confusion = 0xFFD7CCC8,
                ),
                timeline = timelineTheme("forest"),
                pie = forestPieTheme(),
                xyChart = xyChartTheme(
                    background = 0xFFFFFFFF,
                    text = 0xFF000000,
                    palette = listOf(
                        0xFFCDE498,
                        0xFFFF6B6B,
                        0xFFA0D2DB,
                        0xFFD7BDE2,
                        0xFFF0F0F0,
                        0xFFFFC3A0,
                        0xFF7FD8BE,
                        0xFFFF9A8B,
                        0xFFFAF3E0,
                        0xFFFFF176,
                    ),
                ),
                dropShadow = SceneShadow(
                    color = SceneColor(0x80B9B9B9),
                    offsetX = 1f,
                    offsetY = 2f,
                    blurRadius = 2f,
                ),
            )
            "neutral" -> MermaidTheme(
                background = SceneColor(0xFFFFFFFF),
                nodeFill = SceneColor(0xFFEEEEEE),
                nodeStroke = SceneColor(0xFF999999),
                nodeText = SceneColor(0xFF111111),
                edge = SceneColor(0xFF666666),
                edgeLabelFill = SceneColor(0xFFFFFFFF),
                groupFill = SceneColor(0xFFFCFCFC),
                groupStroke = SceneColor(0xFF707070),
                flowContainerStroke = SceneColor(0xFFE3E3E3),
                groupText = SceneColor(0xFF333333),
                noteFill = SceneColor(0xFF666666),
                noteStroke = SceneColor(0xFF999999),
                noteText = SceneColor(0xFFFFFFFF),
                textColor = SceneColor(0xFF333333),
                gantt = neutralGanttTheme(),
                journey = neutralJourneyTheme(),
                requirement = neutralRequirementTheme(),
                gitGraph = gitGraphTheme("neutral"),
                mindmap = mindmapTheme("neutral"),
                radar = radarTheme("neutral", 0xFF666666),
                cynefin = cynefinTheme(
                    edge = 0xFF666666,
                    text = 0xFF333333,
                    label = 0xFF111111,
                ),
                timeline = timelineTheme("neutral"),
                pie = neutralPieTheme(),
                xyChart = xyChartTheme(
                    background = 0xFFFFFFFF,
                    text = 0xFF333333,
                    palette = listOf(
                        0xFFEEEEEE,
                        0xFF6BB8E4,
                        0xFF8ACB88,
                        0xFFC7ACD6,
                        0xFFE8DCC2,
                        0xFFFFB2A8,
                        0xFFFFF380,
                        0xFF7E8D91,
                        0xFFFFD8B1,
                        0xFFFAF3E0,
                    ),
                ),
            )
            "base" -> MermaidTheme(
                background = SceneColor(0xFFF4F4F4),
                nodeFill = SceneColor(0xFFFFF4DD),
                nodeStroke = SceneColor(0xFFEEDEBB),
                nodeText = SceneColor(0xFF333333),
                edge = SceneColor(0xFF0B0B0B),
                edgeLabelFill = SceneColor(0xFFF4DDFF),
                groupFill = SceneColor(0xFFF7F9FF),
                groupStroke = SceneColor(0xFFCFDBF3),
                flowContainerStroke = SceneColor(0xFFDDBBEE),
                groupText = SceneColor(0xFF090600),
                noteFill = SceneColor(0xFFFFF5AD),
                noteStroke = SceneColor(0xFFE4DB95),
                noteText = SceneColor(0xFF333333),
                textColor = SceneColor(0xFF333333),
                gantt = baseGanttTheme(),
                journey = baseJourneyTheme(),
                requirement = baseRequirementTheme(),
                gitGraph = gitGraphTheme("base"),
                mindmap = mindmapTheme("base"),
                radar = radarTheme("base", 0xFF0B0B0B),
                cynefin = cynefinTheme(
                    edge = 0xFF0B0B0B,
                    text = 0xFF333333,
                    label = 0xFF333333,
                ),
                timeline = timelineTheme("base"),
                pie = basePieTheme(),
                xyChart = reduxXyChartTheme(background = 0xFFF4F4F4, text = 0xFF333333),
            )
            "neo" -> MermaidTheme(
                background = SceneColor(0xFFFFFFFF),
                nodeFill = SceneColor(0xFFFFFFFF),
                nodeStroke = SceneColor(0xFF000000),
                nodeText = SceneColor(0xFF333333),
                edge = SceneColor(0xFF000000),
                edgeLabelFill = SceneColor(0xFFCCCCCC),
                groupFill = SceneColor(0xFFFFFFFF),
                groupStroke = SceneColor(0xFFE6E6E6),
                flowContainerStroke = SceneColor(0xFFB3B3B3),
                groupText = SceneColor(0xFF000000),
                noteFill = SceneColor(0xFFFFF5AD),
                noteStroke = SceneColor(0xFFE4DB95),
                noteText = SceneColor(0xFF333333),
                textColor = SceneColor(0xFF333333),
                gantt = neoGanttTheme(),
                journey = neoJourneyTheme(dark = false, redux = false),
                requirement = neoRequirementTheme(dark = false),
                gitGraph = gitGraphTheme("neo"),
                mindmap = mindmapTheme("neo"),
                radar = radarTheme("neo", 0xFF000000),
                cynefin = cynefinTheme(
                    edge = 0xFF000000,
                    text = 0xFF333333,
                    label = 0xFF333333,
                ),
                timeline = timelineTheme("neo"),
                fontSize = 14f,
                fontFamily = MERMAID_NEO_FONT_FAMILY,
                strokeWidth = 2f,
                pie = neoPieTheme(dark = false),
                xyChart = reduxXyChartTheme(background = 0xFFFFFFFF, text = 0xFF333333),
                dropShadow = SceneShadow(
                    color = SceneColor(0x40000000),
                    offsetX = 0f,
                    offsetY = 1f,
                    blurRadius = 2f,
                ),
            )
            "neo-dark" -> MermaidTheme(
                background = SceneColor(0xFF333333),
                nodeFill = SceneColor(0xFF2A2020),
                nodeStroke = SceneColor(0xFFCCCCCC),
                nodeText = SceneColor(0xFFE0DFDF),
                edge = SceneColor(0xFFCCCCCC),
                edgeLabelFill = SceneColor(0xFF585858),
                groupFill = SceneColor(0xFF201F1F),
                groupStroke = SceneColor(0xFF060606),
                flowContainerStroke = SceneColor(0xFF2F2F2F),
                groupText = SceneColor(0xFFDFE0E0),
                noteFill = SceneColor(0xFFFFF5AD),
                noteStroke = SceneColor(0xFFE4DB95),
                noteText = SceneColor(0xFF333333),
                textColor = SceneColor(0xFFE0DFDF),
                gantt = neoDarkGanttTheme(),
                journey = neoJourneyTheme(dark = true, redux = false),
                requirement = neoRequirementTheme(dark = true),
                gitGraph = gitGraphTheme("neo-dark"),
                mindmap = mindmapTheme("neo-dark"),
                radar = radarTheme("neo-dark", 0xFFCCCCCC),
                cynefin = cynefinTheme(
                    edge = 0xFFCCCCCC,
                    text = 0xFFE0DFDF,
                    label = 0xFFE0DFDF,
                ),
                timeline = timelineTheme("neo-dark"),
                fontSize = 14f,
                fontFamily = MERMAID_NEO_FONT_FAMILY,
                pie = neoPieTheme(dark = true),
                xyChart = reduxXyChartTheme(background = 0xFF333333, text = 0xFFE0DFDF),
                dropShadow = SceneShadow(
                    color = SceneColor(0x33B9B9B9),
                    offsetX = 1f,
                    offsetY = 2f,
                    blurRadius = 2f,
                ),
            )
            "redux" -> MermaidTheme(
                background = SceneColor(0xFFFFFFFF),
                nodeFill = SceneColor(0xFFFFFFFF),
                nodeStroke = SceneColor(0xFF28253D),
                nodeText = SceneColor(0xFF28253D),
                edge = SceneColor(0xFF000000),
                edgeLabelFill = SceneColor(0xFFCCCCCC),
                groupFill = SceneColor(0xFFF9F9FB),
                groupStroke = SceneColor(0xFFBDBCCC),
                flowContainerStroke = SceneColor(0xFFB3B3B3),
                groupText = SceneColor(0xFF000000),
                noteFill = SceneColor(0xFFFFF5AD),
                noteStroke = SceneColor(0xFFFACC15),
                noteText = SceneColor(0xFF28253D),
                textColor = SceneColor(0xFF28253D),
                gantt = reduxGanttTheme(),
                journey = neoJourneyTheme(dark = false, redux = true),
                requirement = reduxRequirementTheme(dark = false),
                gitGraph = gitGraphTheme("redux"),
                mindmap = mindmapTheme("redux"),
                radar = radarTheme("redux", 0xFF000000),
                cynefin = cynefinTheme(
                    edge = 0xFF000000,
                    text = 0xFF28253D,
                    label = 0xFF28253D,
                ),
                timeline = timelineTheme("redux"),
                fontSize = 14f,
                fontFamily = MERMAID_REDUX_FONT_FAMILY,
                strokeWidth = 2f,
                pie = reduxPieTheme(dark = false),
                xyChart = reduxXyChartTheme(background = 0xFFFFFFFF, text = 0xFF28253D),
                dropShadow = reduxShadow(dark = false),
            )
            "redux-color" -> FlowchartDefault
            "redux-dark" -> reduxDark()
            "redux-dark-color" -> reduxDark().copy(
                borderColorArray = reduxColorBorders(),
                gantt = reduxDarkColorGanttTheme(),
                journey = reduxColorJourneyTheme(dark = true),
                pie = reduxColorPieTheme(dark = true),
                xyChart = reduxXyChartTheme(background = 0xFF333333, text = 0xFFE0DFDF),
                mindmap = mindmapTheme("redux-dark-color"),
                radar = radarTheme("redux-dark-color", 0xFFCCCCCC),
                timeline = timelineTheme("redux-dark-color"),
            )
            else -> null
        }

        /**
         * Applies Mermaid themeVariables to a base theme for application brand styling.
         */
        fun withVariables(
            theme: MermaidTheme,
            values: Map<String, String>,
            colorArrays: Map<String, List<String>> = emptyMap(),
            themeName: String? = null,
        ): GMResult<MermaidTheme, MermaidError> {
            var invalidVariable: Pair<String, String>? = null

            fun color(vararg names: String): SceneColor? {
                val entry = names.firstNotNullOfOrNull { name ->
                    values[name]?.let { value -> name to value }
                } ?: return null
                return CssColorParser.parse(entry.second).also { parsed ->
                    if (parsed == null && invalidVariable == null) {
                        invalidVariable = entry
                    }
                }
            }

            fun number(name: String): Float? {
                val value = values[name] ?: return null
                val parsed = value.removeSuffix("px").trim().toFloatOrNull()
                if (parsed == null && invalidVariable == null) {
                    invalidVariable = name to value
                }
                return parsed
            }

            fun boolean(name: String): Boolean? {
                val value = values[name] ?: return null
                return when (value.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> {
                        if (invalidVariable == null) {
                            invalidVariable = name to value
                        }
                        null
                    }
                }
            }

            fun colorArray(name: String): GMResult<List<SceneColor>?, MermaidError> {
                val source = colorArrays[name] ?: return GMResult.Ok(null)
                val parsed = source.map(CssColorParser::parse)
                val invalidIndex = parsed.indexOfFirst { color -> color == null }
                if (invalidIndex >= 0) {
                    return GMResult.Err(
                        MermaidError.Configuration(
                            "Mermaid theme variable '$name' has invalid color " +
                                "'${source[invalidIndex]}' at index $invalidIndex",
                        ),
                    )
                }
                return GMResult.Ok(parsed.filterNotNull())
            }

            val bkgColorArray = when (val parsed = colorArray("bkgColorArray")) {
                is GMResult.Ok -> parsed.value ?: theme.bkgColorArray
                is GMResult.Err -> return parsed
            }
            val borderColorArray = when (val parsed = colorArray("borderColorArray")) {
                is GMResult.Ok -> parsed.value ?: theme.borderColorArray
                is GMResult.Err -> return parsed
            }
            val pieColors = MutableList(12) { index ->
                theme.pie.colors.getOrElse(index) { SceneColor(0xFF000000) }
            }
            pieColors.indices.forEach { index ->
                color("pie${index + 1}")?.let { parsed ->
                    pieColors[index] = parsed
                }
            }
            val pieStrokeWidth = number("pieStrokeWidth") ?: theme.pie.strokeWidth
            val pieOuterStrokeWidth =
                number("pieOuterStrokeWidth") ?: theme.pie.outerStrokeWidth
            val pieOpacity = number("pieOpacity") ?: theme.pie.opacity
            if (
                pieStrokeWidth < 0f ||
                pieOuterStrokeWidth < 0f ||
                pieOpacity !in 0f..1f
            ) {
                val invalidName = when {
                    pieStrokeWidth < 0f -> "pieStrokeWidth"
                    pieOuterStrokeWidth < 0f -> "pieOuterStrokeWidth"
                    else -> "pieOpacity"
                }
                invalidVariable = invalidName to values[invalidName].orEmpty()
            }
            val pie = theme.pie.copy(
                colors = pieColors,
                titleTextSize = number("pieTitleTextSize") ?: theme.pie.titleTextSize,
                titleTextColor =
                    color("pieTitleTextColor") ?: theme.pie.titleTextColor,
                sectionTextSize =
                    number("pieSectionTextSize") ?: theme.pie.sectionTextSize,
                sectionTextColor =
                    color("pieSectionTextColor") ?: theme.pie.sectionTextColor,
                legendTextSize =
                    number("pieLegendTextSize") ?: theme.pie.legendTextSize,
                legendTextColor =
                    color("pieLegendTextColor") ?: theme.pie.legendTextColor,
                strokeColor = color("pieStrokeColor") ?: theme.pie.strokeColor,
                strokeWidth = pieStrokeWidth,
                outerStrokeWidth = pieOuterStrokeWidth,
                outerStrokeColor =
                    color("pieOuterStrokeColor") ?: theme.pie.outerStrokeColor,
                opacity = pieOpacity,
            )
            val gantt = theme.gantt.copy(
                sectionBackground =
                    color("sectionBkgColor") ?: theme.gantt.sectionBackground,
                alternateSectionBackground =
                    color("altSectionBkgColor") ?: theme.gantt.alternateSectionBackground,
                secondSectionBackground =
                    color("sectionBkgColor2") ?: theme.gantt.secondSectionBackground,
                excludedBackground =
                    color("excludeBkgColor") ?: theme.gantt.excludedBackground,
                taskFill = color("taskBkgColor") ?: theme.gantt.taskFill,
                taskStroke = color("taskBorderColor") ?: theme.gantt.taskStroke,
                activeTaskFill =
                    color("activeTaskBkgColor") ?: theme.gantt.activeTaskFill,
                activeTaskStroke =
                    color("activeTaskBorderColor") ?: theme.gantt.activeTaskStroke,
                doneTaskFill =
                    color("doneTaskBkgColor") ?: theme.gantt.doneTaskFill,
                doneTaskStroke =
                    color("doneTaskBorderColor") ?: theme.gantt.doneTaskStroke,
                criticalTaskFill =
                    color("critBkgColor") ?: theme.gantt.criticalTaskFill,
                criticalTaskStroke =
                    color("critBorderColor") ?: theme.gantt.criticalTaskStroke,
                taskText = color("taskTextColor") ?: theme.gantt.taskText,
                darkTaskText =
                    color("taskTextDarkColor") ?: theme.gantt.darkTaskText,
                outsideTaskText =
                    color("taskTextOutsideColor") ?: theme.gantt.outsideTaskText,
                clickableTaskText =
                    color("taskTextClickableColor") ?: theme.gantt.clickableTaskText,
                text = color("textColor") ?: theme.gantt.text,
                grid = color("gridColor") ?: theme.gantt.grid,
                todayLine = color("todayLineColor") ?: theme.gantt.todayLine,
                verticalLine = color("vertLineColor") ?: theme.gantt.verticalLine,
                title = color("titleColor") ?: theme.gantt.title,
            )
            val journeySectionFills = MutableList(8) { index ->
                theme.journey.sectionFills.getOrElse(index) { SceneColor(0xFF000000) }
            }
            journeySectionFills.indices.forEach { index ->
                color("fillType$index")?.let { parsed ->
                    journeySectionFills[index] = parsed
                }
            }
            val journey = theme.journey.copy(
                sectionFills = journeySectionFills,
                textColor = color("textColor") ?: theme.journey.textColor,
            )
            val requirementBorderSize =
                number("requirementBorderSize") ?: theme.requirement.borderSize
            if (requirementBorderSize < 0f) {
                invalidVariable =
                    "requirementBorderSize" to values["requirementBorderSize"].orEmpty()
            }
            val requirement = theme.requirement.copy(
                background = color("requirementBackground", "mainBkg", "primaryColor")
                    ?: theme.requirement.background,
                borderColor = color(
                    "requirementBorderColor",
                    "nodeBorder",
                    "primaryBorderColor",
                ) ?: theme.requirement.borderColor,
                borderSize = requirementBorderSize,
                textColor = color(
                    "requirementTextColor",
                    "nodeTextColor",
                    "primaryTextColor",
                    "textColor",
                ) ?: theme.requirement.textColor,
                relationColor = color("relationColor", "defaultLinkColor", "lineColor")
                    ?: theme.requirement.relationColor,
                relationLabelBackground = color(
                    "relationLabelBackground",
                    "edgeLabelBackground",
                ) ?: theme.requirement.relationLabelBackground,
                relationLabelColor = color("relationLabelColor")
                    ?: theme.requirement.relationLabelColor,
                edgeLabelBackground = color(
                    "requirementEdgeLabelBackground",
                    "edgeLabelBackground",
                ) ?: theme.requirement.edgeLabelBackground,
            )
            val gitColors = theme.gitGraph.colors.toMutableList()
            val gitInverseColors = theme.gitGraph.inverseColors.toMutableList()
            val gitBranchLabelColors = theme.gitGraph.branchLabelColors.toMutableList()
            repeat(8) { index ->
                val gitColor = color("git$index")
                if (gitColor != null) {
                    gitColors[index] = gitColor
                }
                val gitInverseColor = color("gitInv$index")
                gitInverseColors[index] = when {
                    gitInverseColor != null -> gitInverseColor
                    gitColor != null -> deriveGitInverseColor(
                        themeName = themeName,
                        gitColor = gitColor,
                        inherited = gitInverseColors[index],
                    )
                    else -> gitInverseColors[index]
                }
                color("gitBranchLabel$index")?.let { parsed ->
                    gitBranchLabelColors[index] = parsed
                }
            }
            val gitCommitLabelFontSize =
                number("commitLabelFontSize") ?: theme.gitGraph.commitLabelFontSize
            val gitTagLabelFontSize =
                number("tagLabelFontSize") ?: theme.gitGraph.tagLabelFontSize
            // Mermaid 12.0.0: themes/theme-base.js -> calculate(overrides).
            // A Base nodeBorder override disables the inherited Neo gradient unless
            // useGradient is also explicitly provided.
            val useGradientOverride = boolean("useGradient")
            val disablesBaseGradient =
                themeName?.lowercase() == "base" &&
                    "nodeBorder" in values &&
                    "useGradient" !in values
            if (gitCommitLabelFontSize < 0f || gitTagLabelFontSize < 0f) {
                val invalidName = if (gitCommitLabelFontSize < 0f) {
                    "commitLabelFontSize"
                } else {
                    "tagLabelFontSize"
                }
                invalidVariable = invalidName to values[invalidName].orEmpty()
            }
            val gitGraph = theme.gitGraph.copy(
                colors = gitColors,
                inverseColors = gitInverseColors,
                branchLabelColors = gitBranchLabelColors,
                commitLineColor =
                    color("commitLineColor", "lineColor") ?: theme.gitGraph.commitLineColor,
                commitLabelColor =
                    color("commitLabelColor") ?: theme.gitGraph.commitLabelColor,
                commitLabelBackground =
                    color("commitLabelBackground") ?: theme.gitGraph.commitLabelBackground,
                tagLabelColor = color("tagLabelColor") ?: theme.gitGraph.tagLabelColor,
                tagLabelBackground =
                    color("tagLabelBackground") ?: theme.gitGraph.tagLabelBackground,
                tagLabelBorder =
                    color("tagLabelBorder", "tagBorder") ?: theme.gitGraph.tagLabelBorder,
                nodeBorder = color("nodeBorder", "primaryBorderColor")
                    ?: theme.gitGraph.nodeBorder,
                mainBackground = color("mainBkg") ?: theme.gitGraph.mainBackground,
                primaryColor = color("primaryColor") ?: theme.gitGraph.primaryColor,
                textColor = color("textColor") ?: theme.gitGraph.textColor,
                useGradient = useGradientOverride
                    ?: if (disablesBaseGradient) false else theme.gitGraph.useGradient,
                gradientStart = color("gradientStart") ?: theme.gitGraph.gradientStart,
                gradientStop = color("gradientStop") ?: theme.gitGraph.gradientStop,
                commitLabelFontSize = gitCommitLabelFontSize,
                tagLabelFontSize = gitTagLabelFontSize,
            )
            val mindmapSectionFills = MutableList(12) { index ->
                theme.mindmap.sectionFills.getOrElse(index) { SceneColor(0xFF000000) }
            }
            val mindmapSectionInverseColors = MutableList(12) { index ->
                theme.mindmap.sectionInverseColors.getOrElse(index) {
                    SceneColor(0xFF000000)
                }
            }
            val mindmapSectionLabelColors = MutableList(12) { index ->
                theme.mindmap.sectionLabelColors.getOrElse(index) {
                    SceneColor(0xFF000000)
                }
            }
            val radarColors = MutableList(12) { index ->
                theme.radar.colors.getOrElse(index) { SceneColor(0xFF000000) }
            }
            repeat(12) { index ->
                color("cScale$index")?.let { parsed ->
                    mindmapSectionFills[index] = parsed
                    radarColors[index] = parsed
                }
                color("cScaleInv$index")?.let { parsed ->
                    mindmapSectionInverseColors[index] = parsed
                }
                color("cScaleLabel$index")?.let { parsed ->
                    mindmapSectionLabelColors[index] = parsed
                }
            }
            val mindmap = theme.mindmap.copy(
                mainBackground = color("mainBkg") ?: theme.mindmap.mainBackground,
                nodeBorder = color("nodeBorder") ?: theme.mindmap.nodeBorder,
                rootFill = color("git0") ?: theme.mindmap.rootFill,
                rootText = color("gitBranchLabel0") ?: theme.mindmap.rootText,
                sectionFills = mindmapSectionFills,
                sectionInverseColors = mindmapSectionInverseColors,
                sectionLabelColors = mindmapSectionLabelColors,
                useGradient = useGradientOverride
                    ?: if (disablesBaseGradient) false else theme.mindmap.useGradient,
                gradientStart = color("gradientStart") ?: theme.mindmap.gradientStart,
                gradientStop = color("gradientStop") ?: theme.mindmap.gradientStop,
            )
            val radarAxisStrokeWidth =
                number("radar.axisStrokeWidth") ?: theme.radar.axisStrokeWidth
            val radarAxisLabelFontSize =
                number("radar.axisLabelFontSize") ?: theme.radar.axisLabelFontSize
            val radarCurveOpacity =
                number("radar.curveOpacity") ?: theme.radar.curveOpacity
            val radarCurveStrokeWidth =
                number("radar.curveStrokeWidth") ?: theme.radar.curveStrokeWidth
            val radarGraticuleOpacity =
                number("radar.graticuleOpacity") ?: theme.radar.graticuleOpacity
            val radarGraticuleStrokeWidth =
                number("radar.graticuleStrokeWidth") ?: theme.radar.graticuleStrokeWidth
            val radarLegendBoxSize =
                number("radar.legendBoxSize") ?: theme.radar.legendBoxSize
            val radarLegendFontSize =
                number("radar.legendFontSize") ?: theme.radar.legendFontSize
            listOf(
                "radar.axisStrokeWidth" to radarAxisStrokeWidth,
                "radar.axisLabelFontSize" to radarAxisLabelFontSize,
                "radar.curveStrokeWidth" to radarCurveStrokeWidth,
                "radar.graticuleStrokeWidth" to radarGraticuleStrokeWidth,
                "radar.legendBoxSize" to radarLegendBoxSize,
                "radar.legendFontSize" to radarLegendFontSize,
            ).firstOrNull { (_, value) -> !value.isFinite() || value < 0f }?.let { invalid ->
                invalidVariable = invalid.first to values[invalid.first].orEmpty()
            }
            listOf(
                "radar.curveOpacity" to radarCurveOpacity,
                "radar.graticuleOpacity" to radarGraticuleOpacity,
            ).firstOrNull { (_, value) -> !value.isFinite() || value !in 0f..1f }
                ?.let { invalid ->
                    invalidVariable = invalid.first to values[invalid.first].orEmpty()
                }
            val radar = theme.radar.copy(
                colors = radarColors,
                axisColor = color(
                    "radar.axisColor",
                    "lineColor",
                    "defaultLinkColor",
                ) ?: theme.radar.axisColor,
                axisStrokeWidth = radarAxisStrokeWidth,
                axisLabelFontSize = radarAxisLabelFontSize,
                curveOpacity = radarCurveOpacity,
                curveStrokeWidth = radarCurveStrokeWidth,
                graticuleColor =
                    color("radar.graticuleColor") ?: theme.radar.graticuleColor,
                graticuleOpacity = radarGraticuleOpacity,
                graticuleStrokeWidth = radarGraticuleStrokeWidth,
                legendBoxSize = radarLegendBoxSize,
                legendFontSize = radarLegendFontSize,
            )
            // Mermaid.js 12.0.0: cynefin/styles.ts -> getCynefinTheme and
            // cynefinRenderer.ts -> getCynefinDomainColors. A partial nested
            // override is merged onto the default Cynefin block, not the preset block.
            val cynefinBase = if (values.keys.any { key -> key.startsWith("cynefin.") }) {
                MermaidDefault.cynefin
            } else {
                theme.cynefin
            }
            val cynefinDomainFontSize =
                number("cynefin.domainFontSize") ?: cynefinBase.domainFontSize
            val cynefinItemFontSize =
                number("cynefin.itemFontSize") ?: cynefinBase.itemFontSize
            val cynefinBoundaryWidth =
                number("cynefin.boundaryWidth") ?: cynefinBase.boundaryWidth
            val cynefinCliffWidth =
                number("cynefin.cliffWidth") ?: cynefinBase.cliffWidth
            val cynefinArrowWidth =
                number("cynefin.arrowWidth") ?: cynefinBase.arrowWidth
            listOf(
                "cynefin.domainFontSize" to cynefinDomainFontSize,
                "cynefin.itemFontSize" to cynefinItemFontSize,
            ).firstOrNull { (_, value) -> !value.isFinite() || value <= 0f }
                ?.let { invalid ->
                    invalidVariable = invalid.first to values[invalid.first].orEmpty()
                }
            listOf(
                "cynefin.boundaryWidth" to cynefinBoundaryWidth,
                "cynefin.cliffWidth" to cynefinCliffWidth,
                "cynefin.arrowWidth" to cynefinArrowWidth,
            ).firstOrNull { (_, value) -> !value.isFinite() || value < 0f }
                ?.let { invalid ->
                    invalidVariable = invalid.first to values[invalid.first].orEmpty()
                }
            val cynefin = cynefinBase.copy(
                domainFontSize = cynefinDomainFontSize,
                itemFontSize = cynefinItemFontSize,
                boundaryColor =
                    color("cynefin.boundaryColor") ?: cynefinBase.boundaryColor,
                boundaryWidth = cynefinBoundaryWidth,
                cliffColor = color("cynefin.cliffColor") ?: cynefinBase.cliffColor,
                cliffWidth = cynefinCliffWidth,
                arrowColor = color("cynefin.arrowColor") ?: cynefinBase.arrowColor,
                arrowWidth = cynefinArrowWidth,
                complexBackground =
                    color("cynefin.complexBg") ?: cynefinBase.complexBackground,
                complicatedBackground =
                    color("cynefin.complicatedBg") ?: cynefinBase.complicatedBackground,
                chaoticBackground =
                    color("cynefin.chaoticBg") ?: cynefinBase.chaoticBackground,
                clearBackground =
                    color("cynefin.clearBg") ?: cynefinBase.clearBackground,
                confusionBackground =
                    color("cynefin.confusionBg") ?: cynefinBase.confusionBackground,
                textColor = color("cynefin.textColor") ?: cynefinBase.textColor,
                labelColor = color("cynefin.labelColor") ?: cynefinBase.labelColor,
            )
            val eventModeling = theme.eventModeling.copy(
                uiFill = color("emUiFill") ?: theme.eventModeling.uiFill,
                uiStroke = color("emUiStroke") ?: theme.eventModeling.uiStroke,
                processorFill =
                    color("emProcessorFill") ?: theme.eventModeling.processorFill,
                processorStroke =
                    color("emProcessorStroke") ?: theme.eventModeling.processorStroke,
                readModelFill =
                    color("emReadModelFill") ?: theme.eventModeling.readModelFill,
                readModelStroke =
                    color("emReadModelStroke") ?: theme.eventModeling.readModelStroke,
                commandFill = color("emCommandFill") ?: theme.eventModeling.commandFill,
                commandStroke =
                    color("emCommandStroke") ?: theme.eventModeling.commandStroke,
                eventFill = color("emEventFill") ?: theme.eventModeling.eventFill,
                eventStroke = color("emEventStroke") ?: theme.eventModeling.eventStroke,
                swimlaneBackgroundOdd =
                    color("emSwimlaneBackgroundOdd")
                        ?: theme.eventModeling.swimlaneBackgroundOdd,
                swimlaneBackgroundStroke =
                    color("emSwimlaneBackgroundStroke")
                        ?: theme.eventModeling.swimlaneBackgroundStroke,
                arrowhead = color("emArrowhead") ?: theme.eventModeling.arrowhead,
                relationStroke =
                    color("emRelationStroke") ?: theme.eventModeling.relationStroke,
            )
            val timeline = theme.timeline.copy(
                mainBackground = color("mainBkg") ?: theme.timeline.mainBackground,
                nodeBorder = color("nodeBorder") ?: theme.timeline.nodeBorder,
                sectionFills = mindmapSectionFills,
                sectionInverseColors = mindmapSectionInverseColors,
                sectionLabelColors = mindmapSectionLabelColors,
                useGradient = useGradientOverride
                    ?: if (disablesBaseGradient) false else theme.timeline.useGradient,
                gradientStart = color("gradientStart") ?: theme.timeline.gradientStart,
                gradientStop = color("gradientStop") ?: theme.timeline.gradientStop,
            )
            val xyPaletteSource = values["xyChart.plotColorPalette"]
            val xyPalette = if (xyPaletteSource == null) {
                theme.xyChart.plotColorPalette
            } else {
                val sourceColors = xyPaletteSource
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                val parsedColors = sourceColors.map(CssColorParser::parse)
                val invalidIndex = parsedColors.indexOfFirst { parsed -> parsed == null }
                when {
                    sourceColors.isEmpty() -> {
                        invalidVariable = "xyChart.plotColorPalette" to xyPaletteSource
                        emptyList()
                    }
                    invalidIndex >= 0 -> {
                        invalidVariable = "xyChart.plotColorPalette" to sourceColors[invalidIndex]
                        emptyList()
                    }
                    else -> parsedColors.filterNotNull()
                }
            }
            val xyChart = theme.xyChart.copy(
                backgroundColor =
                    color("xyChart.backgroundColor") ?: theme.xyChart.backgroundColor,
                titleColor = color("xyChart.titleColor") ?: theme.xyChart.titleColor,
                dataLabelColor =
                    color("xyChart.dataLabelColor") ?: theme.xyChart.dataLabelColor,
                legendTextColor =
                    color("xyChart.legendTextColor") ?: theme.xyChart.legendTextColor,
                xAxisLabelColor =
                    color("xyChart.xAxisLabelColor") ?: theme.xyChart.xAxisLabelColor,
                xAxisTitleColor =
                    color("xyChart.xAxisTitleColor") ?: theme.xyChart.xAxisTitleColor,
                xAxisTickColor =
                    color("xyChart.xAxisTickColor") ?: theme.xyChart.xAxisTickColor,
                xAxisLineColor =
                    color("xyChart.xAxisLineColor") ?: theme.xyChart.xAxisLineColor,
                yAxisLabelColor =
                    color("xyChart.yAxisLabelColor") ?: theme.xyChart.yAxisLabelColor,
                yAxisTitleColor =
                    color("xyChart.yAxisTitleColor") ?: theme.xyChart.yAxisTitleColor,
                yAxisTickColor =
                    color("xyChart.yAxisTickColor") ?: theme.xyChart.yAxisTickColor,
                yAxisLineColor =
                    color("xyChart.yAxisLineColor") ?: theme.xyChart.yAxisLineColor,
                plotColorPalette = xyPalette,
            )
            val dropShadow = when (
                val parsed = parseDropShadow(
                    source = values["dropShadow"],
                    inherited = theme.dropShadow,
                )
            ) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return GMResult.Err(parsed.error)
            }
            val resolved = theme.copy(
                background = color("background") ?: theme.background,
                nodeFill = color("mainBkg", "primaryColor") ?: theme.nodeFill,
                nodeStroke = color("nodeBorder", "primaryBorderColor") ?: theme.nodeStroke,
                nodeText = color("nodeTextColor", "primaryTextColor", "textColor") ?: theme.nodeText,
                edge = color("defaultLinkColor", "lineColor") ?: theme.edge,
                edgeLabelFill = color("edgeLabelBackground") ?: theme.edgeLabelFill,
                groupFill = color("clusterBkg") ?: theme.groupFill,
                groupStroke = color("clusterBorder") ?: theme.groupStroke,
                flowContainerStroke =
                    color("flowContainerStroke") ?: theme.flowContainerStroke,
                groupText = color("clusterText", "titleColor") ?: theme.groupText,
                noteFill = color("noteBkgColor") ?: theme.noteFill,
                noteStroke = color("noteBorderColor") ?: theme.noteStroke,
                noteText = color("noteTextColor") ?: theme.noteText,
                textColor = color("textColor") ?: theme.textColor,
                rowOdd = color("rowOdd") ?: theme.rowOdd,
                rowEven = color("rowEven") ?: theme.rowEven,
                fontSize = number("fontSize") ?: theme.fontSize,
                fontFamily = values["fontFamily"] ?: theme.fontFamily,
                strokeWidth = number("strokeWidth") ?: theme.strokeWidth,
                bkgColorArray = bkgColorArray,
                borderColorArray = borderColorArray,
                pie = pie,
                xyChart = xyChart,
                gantt = gantt,
                journey = journey,
                requirement = requirement,
                gitGraph = gitGraph,
                mindmap = mindmap,
                radar = radar,
                cynefin = cynefin,
                eventModeling = eventModeling,
                timeline = timeline,
                dropShadow = dropShadow,
            )
            val invalid = invalidVariable
            return if (invalid == null) {
                GMResult.Ok(resolved)
            } else {
                GMResult.Err(
                    MermaidError.Configuration(
                        "Mermaid theme variable '${invalid.first}' has invalid value " +
                            "'${invalid.second}'",
                    ),
                )
            }
        }

        private fun deriveGitInverseColor(
            themeName: String?,
            gitColor: SceneColor,
            inherited: SceneColor,
        ): SceneColor {
            // Mermaid: packages/mermaid/src/themes/theme-*.js -> updateColors / calculate.
            return when (themeName?.lowercase()) {
                "base",
                "forest",
                "neo",
                "redux",
                "redux-color",
                "redux-dark",
                "redux-dark-color",
                -> gitColor.mermaidDarken(25.0).mermaidInvert()
                "neo-dark" -> gitColor.mermaidLighten(25.0).mermaidInvert()
                // Default, dark, and neutral derive gitInv from fixed generated palettes
                // before user gitN overrides are restored.
                else -> inherited
            }
        }

        private fun cynefinTheme(
            edge: Long,
            text: Long,
            label: Long,
            cliff: Long = 0xFF8B0000,
            complex: Long = 0xFFE8F5E9,
            complicated: Long = 0xFFE3F2FD,
            chaotic: Long = 0xFFFBE9E7,
            clear: Long = 0xFFFFF8E1,
            confusion: Long = 0xFFF3E5F5,
        ): MermaidCynefinTheme = MermaidCynefinTheme(
            boundaryColor = SceneColor(edge),
            cliffColor = SceneColor(cliff),
            arrowColor = SceneColor(edge),
            complexBackground = SceneColor(complex),
            complicatedBackground = SceneColor(complicated),
            chaoticBackground = SceneColor(chaotic),
            clearBackground = SceneColor(clear),
            confusionBackground = SceneColor(confusion),
            textColor = SceneColor(text),
            labelColor = SceneColor(label),
        )

        private fun reduxDark(): MermaidTheme = MermaidTheme(
            background = SceneColor(0xFF333333),
            nodeFill = SceneColor(0xFF111113),
            nodeStroke = SceneColor(0xFFFFFFFF),
            nodeText = SceneColor(0xFFE0DFDF),
            edge = SceneColor(0xFFCCCCCC),
            edgeLabelFill = SceneColor(0xFF585858),
            groupFill = SceneColor(0xFF1E1A2E),
            groupStroke = SceneColor(0xFFBDBCCC),
            flowContainerStroke = SceneColor(0xFF2F2F2F),
            groupText = SceneColor(0xFFDFE0E0),
            noteFill = SceneColor(0xFFFEF9C3),
            noteStroke = SceneColor(0xFFFACC15),
            noteText = SceneColor(0xFF28253D),
            textColor = SceneColor(0xFFE0DFDF),
            gantt = reduxDarkGanttTheme(),
            journey = neoJourneyTheme(dark = true, redux = true),
            requirement = reduxRequirementTheme(dark = true),
            gitGraph = gitGraphTheme("redux-dark"),
            mindmap = mindmapTheme("redux-dark"),
            radar = radarTheme("redux-dark", 0xFFCCCCCC),
            cynefin = cynefinTheme(
                edge = 0xFFCCCCCC,
                text = 0xFFE0DFDF,
                label = 0xFFE0DFDF,
            ),
            timeline = timelineTheme("redux-dark"),
            fontSize = 14f,
            fontFamily = MERMAID_REDUX_FONT_FAMILY,
            strokeWidth = 2f,
            pie = reduxPieTheme(dark = true),
            xyChart = reduxXyChartTheme(background = 0xFF333333, text = 0xFFE0DFDF),
            dropShadow = reduxShadow(dark = true),
        )

        private fun darkJourneyTheme(): MermaidJourneyTheme = MermaidJourneyTheme(
            sectionFills = listOf(
                0xFF1F2020,
                0xFF474949,
                0xFF1F1F20,
                0xFF474749,
                0xFF1F201F,
                0xFF474947,
                0xFF201F20,
                0xFF494749,
            ).map(::SceneColor),
            textColor = SceneColor(0xFFCCCCCC),
        )

        private fun forestJourneyTheme(): MermaidJourneyTheme = MermaidJourneyTheme(
            sectionFills = listOf(
                0xFFCDE498,
                0xFFCDFFB2,
                0xFF98E4B4,
                0xFFB2FFE9,
                0xFFE4AA98,
                0xFFFFDFB2,
                0xFF98C3E4,
                0xFFB2C3FF,
            ).map(::SceneColor),
            textColor = SceneColor(0xFF000000),
        )

        private fun neutralJourneyTheme(): MermaidJourneyTheme = MermaidJourneyTheme(
            sectionFills = listOf(
                0xFFEEEEEE,
                0xFFFCFCFC,
                0xFFEEEEEE,
                0xFFFCFCFC,
                0xFFEEEEEE,
                0xFFFCFCFC,
                0xFFEEEEEE,
                0xFFFCFCFC,
            ).map(::SceneColor),
            textColor = SceneColor(0xFF000000),
        )

        private fun baseJourneyTheme(): MermaidJourneyTheme = MermaidJourneyTheme(
            sectionFills = listOf(
                0xFFFFF4DD,
                0xFFF4DDFF,
                0xFFE6FFDD,
                0xFFFFDDE6,
                0xFFFFDDEA,
                0xFFDDEAFF,
                0xFFDDFFF9,
                0xFFFFF9DD,
            ).map(::SceneColor),
        )

        private fun neoJourneyTheme(
            dark: Boolean,
            redux: Boolean,
        ): MermaidJourneyTheme = if (dark) {
            darkJourneyTheme()
        } else {
            MermaidJourneyTheme(
                sectionFills = listOf(
                    0xFFECECFE,
                    0xFFE9E9F1,
                    0xFFFEECFD,
                    0xFFF1E9F0,
                    0xFFECFEFD,
                    0xFFE9F1F0,
                    0xFFFEEEEC,
                    0xFFF1EAE9,
                ).map(::SceneColor),
                textColor = SceneColor(if (redux) 0xFF28253D else 0xFF333333),
            )
        }

        private fun reduxColorJourneyTheme(dark: Boolean): MermaidJourneyTheme =
            MermaidJourneyTheme(
                sectionFills = if (dark) {
                    listOf(
                        0xFF701A75,
                        0xFF134E4A,
                        0xFF7C2D12,
                        0xFF581C87,
                        0xFF14532D,
                        0xFF4C1D95,
                        0xFF7F1D1D,
                        0xFF713F12,
                    )
                } else {
                    listOf(
                        0xFFFDF4FF,
                        0xFFF0FDFA,
                        0xFFFFF7ED,
                        0xFFECFEFF,
                        0xFFF0FDF4,
                        0xFFF5F3FF,
                        0xFFFEF2F2,
                        0xFFFEFCE8,
                    )
                }.map(::SceneColor),
                textColor = SceneColor(if (dark) 0xFFCCCCCC else 0xFF28253D),
            )

        /**
         * Mermaid 12.0.0 themes/theme-*.js values consumed by mindmap/styles.ts.
         */
        private fun mindmapTheme(name: String): MermaidMindmapTheme {
            fun colors(vararg values: Long): List<SceneColor> = values.map(::SceneColor)
            return when (name) {
                "dark" -> MermaidMindmapTheme(
                    mainBackground = SceneColor(0xFF1F2020),
                    nodeBorder = SceneColor(0xFFCCCCCC),
                    rootFill = SceneColor(0xFF797D7D),
                    rootText = SceneColor(0xFF2C2C2C),
                    sectionFills = colors(
                        0xFF1F2020, 0xFF0B0000, 0xFF4D1037, 0xFF3F5258,
                        0xFF4F2F1B, 0xFF6E0A0A, 0xFF3B0048, 0xFF995A01,
                        0xFF154706, 0xFF161722, 0xFF00296F, 0xFF01629C,
                    ),
                    sectionInverseColors = colors(
                        0xFFE0DFDF, 0xFFF4FFFF, 0xFFB2EFC8, 0xFFC0ADA7,
                        0xFFB0D0E4, 0xFF91F5F5, 0xFFC4FFB7, 0xFF66A5FE,
                        0xFFEAB8F9, 0xFFE9E8DD, 0xFFFFD690, 0xFFFE9D63,
                    ),
                    sectionLabelColors = List(12) { SceneColor(0xFFD3D3D3) },
                    useGradient = true,
                    gradientStart = SceneColor(0xFFCCCCCC),
                    gradientStop = SceneColor(0xFF2F2F2F),
                )
                "forest" -> MermaidMindmapTheme(
                    mainBackground = SceneColor(0xFFCDE498),
                    nodeBorder = SceneColor(0xFF13540C),
                    rootFill = SceneColor(0xFF9BC834),
                    rootText = SceneColor(0xFFFFFFFF),
                    sectionFills = colors(
                        0xFFB9D970, 0xFFACFF7F, 0xFFCDE498, 0xFF84D970,
                        0xFF70D990, 0xFF70D9C5, 0xFF70B9D9, 0xFF7084D9,
                        0xFFC570D9, 0xFFD97084, 0xFFD99070, 0xFFD9C570,
                    ),
                    sectionInverseColors = colors(
                        0xFF9070D9, 0xFFD27FFF, 0xFFAF98E4, 0xFFC570D9,
                        0xFFD970B9, 0xFFD97084, 0xFFD99070, 0xFFD9C570,
                        0xFF84D970, 0xFF70D9C5, 0xFF70B9D9, 0xFF7084D9,
                    ),
                    sectionLabelColors = List(12) { SceneColor(0xFF000000) },
                    useGradient = true,
                    gradientStart = SceneColor(0xFFABB594),
                    gradientStop = SceneColor(0xFFB4E599),
                )
                "neutral" -> MermaidMindmapTheme(
                    mainBackground = SceneColor(0xFFEEEEEE),
                    nodeBorder = SceneColor(0xFF999999),
                    rootFill = SceneColor(0xFFB4B4B4),
                    rootText = SceneColor(0xFF333333),
                    sectionFills = colors(
                        0xFF555555, 0xFFF4F4F4, 0xFF555555, 0xFFBBBBBB,
                        0xFF777777, 0xFF999999, 0xFFDDDDDD, 0xFFFFFFFF,
                        0xFFDDDDDD, 0xFFBBBBBB, 0xFF999999, 0xFF777777,
                    ),
                    sectionInverseColors = colors(
                        0xFFAAAAAA, 0xFF0B0B0B, 0xFFAAAAAA, 0xFF444444,
                        0xFF888888, 0xFF666666, 0xFF222222, 0xFF000000,
                        0xFF222222, 0xFF444444, 0xFF666666, 0xFF888888,
                    ),
                    sectionLabelColors = colors(
                        0xFFF4F4F4, 0xFF333333, 0xFFF4F4F4, 0xFF333333,
                        0xFF333333, 0xFF333333, 0xFF333333, 0xFF333333,
                        0xFF333333, 0xFF333333, 0xFF333333, 0xFF333333,
                    ),
                    useGradient = true,
                    gradientStart = SceneColor(0xFFD4D4D4),
                    gradientStop = SceneColor(0xFFE3E3E3),
                )
                "base" -> MermaidMindmapTheme(
                    mainBackground = SceneColor(0xFFFFF4DD),
                    nodeBorder = SceneColor(0xFFEEDDBB),
                    rootFill = SceneColor(0xFFFFCB5D),
                    rootText = SceneColor(0xFF333333),
                    sectionFills = colors(
                        0xFFFFCB5D, 0xFFCB5DFF, 0xFF77A3FF, 0xFFE3FF5D,
                        0xFF92FF5D, 0xFF5DFF7A, 0xFF5DFFCB, 0xFF5DE3FF,
                        0xFF9680FF, 0xFFFF5DE3, 0xFFFF5D92, 0xFFFF7A5D,
                    ),
                    sectionInverseColors = colors(
                        0xFF0034A2, 0xFF34A200, 0xFF885C00, 0xFF1D00A2,
                        0xFF6D00A2, 0xFFA20085, 0xFFA20034, 0xFFA21D00,
                        0xFF698000, 0xFF00A21D, 0xFF00A26D, 0xFF0085A2,
                    ),
                    sectionLabelColors = List(12) { SceneColor(0xFF333333) },
                    useGradient = true,
                    gradientStart = SceneColor(0xFFEEDDBB),
                    gradientStop = SceneColor(0xFFDDBBEE),
                )
                "neo" -> MermaidMindmapTheme(
                    mainBackground = SceneColor(0xFFFFFFFF),
                    nodeBorder = SceneColor(0xFF000000),
                    rootFill = SceneColor(0xFF7373F8),
                    rootText = SceneColor(0xFF333333),
                    sectionFills = colors(
                        0xFF7373F8, 0xFF9B9BBF, 0xFFF9F986, 0xFFB573F8,
                        0xFFF873F8, 0xFFF873B5, 0xFFF87373, 0xFFF8B573,
                        0xFFBFF986, 0xFF73F8B5, 0xFF73F8F8, 0xFF73B5F8,
                    ),
                    sectionInverseColors = colors(
                        0xFF8C8C07, 0xFF646440, 0xFF060679, 0xFF4A8C07,
                        0xFF078C07, 0xFF078C4A, 0xFF078C8C, 0xFF074A8C,
                        0xFF400679, 0xFF8C074A, 0xFF8C0707, 0xFF8C4A07,
                    ),
                    sectionLabelColors = List(12) { SceneColor(0xFF333333) },
                    useGradient = true,
                    gradientStart = SceneColor(0xFF0042EB),
                    gradientStop = SceneColor(0xFFEB0042),
                )
                "neo-dark",
                "redux-dark",
                -> MermaidMindmapTheme(
                    mainBackground = SceneColor(
                        if (name == "redux-dark") 0xFF111113 else 0xFF2A2020,
                    ),
                    nodeBorder = SceneColor(
                        if (name == "redux-dark") 0xFFFFFFFF else 0xFFCCCCCC,
                    ),
                    rootFill = SceneColor(
                        if (name == "redux-dark") 0xFF000000 else 0xFF8B0000,
                    ),
                    rootText = SceneColor(0xFFE0DFDF),
                    sectionFills = colors(
                        0xFF000000, 0xFF080909, 0xFF000000, 0xFF000000,
                        0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000,
                        0xFFC0BFBE, 0xFF000000, 0xFF000000, 0xFF000000,
                    ),
                    sectionInverseColors = colors(
                        0xFFFFFFFF, 0xFFF7F6F6, 0xFFFFFFFF, 0xFFFFFFFF,
                        0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                        0xFF3F4041, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                    ),
                    sectionLabelColors = List(12) { SceneColor(0xFFE0DFDF) },
                    useGradient = name == "neo-dark",
                    gradientStart = SceneColor(0xFF0042EB),
                    gradientStop = SceneColor(0xFFEB0042),
                )
                "redux" -> MermaidMindmapTheme(
                    mainBackground = SceneColor(0xFFFFFFFF),
                    nodeBorder = SceneColor(0xFF28253D),
                    rootFill = SceneColor(0xFF7373F8),
                    rootText = SceneColor(0xFF28253D),
                    sectionFills = List(12) { SceneColor(0xFFBFBFBF) },
                    sectionInverseColors = List(12) { SceneColor(0xFF404040) },
                    sectionLabelColors = List(12) { SceneColor(0xFF28253D) },
                    gradientStart = SceneColor(0xFF0042EB),
                    gradientStop = SceneColor(0xFFEB0042),
                )
                "redux-color",
                "redux-dark-color",
                -> MermaidMindmapTheme(
                    mainBackground = SceneColor(
                        if (name == "redux-dark-color") 0xFF111113 else 0xFFFFFFFF,
                    ),
                    nodeBorder = SceneColor(
                        if (name == "redux-dark-color") 0xFFFFFFFF else 0xFF28253D,
                    ),
                    rootFill = SceneColor(
                        if (name == "redux-dark-color") 0xFF000000 else 0xFF7373F8,
                    ),
                    rootText = SceneColor(
                        if (name == "redux-dark-color") 0xFFE0DFDF else 0xFF28253D,
                    ),
                    sectionFills = colors(
                        0xFFF4A8FF, 0xFF46ECD5, 0xFFFFB86A, 0xFFDAB2FF,
                        0xFF7BF1A8, 0xFFC4B4FF, 0xFFFFA2A2, 0xFFFFDF20,
                        0xFFA3B3FF, 0xFFBBF451, 0xFF74D4FF, 0xFFFFA1AD,
                    ),
                    sectionInverseColors = colors(
                        0xFF0B5700, 0xFFB9132A, 0xFF004795, 0xFF254D00,
                        0xFF840E57, 0xFF3B4B00, 0xFF005D5D, 0xFF0020DF,
                        0xFF5C4C00, 0xFF440BAE, 0xFF8B2B00, 0xFF005E52,
                    ),
                    sectionLabelColors = if (name == "redux-dark-color") {
                        colors(
                            0xFF230029, 0xFF000000, 0xFF000000, 0xFF1A0032,
                            0xFF000000, 0xFF0B0035, 0xFF230000, 0xFF000000,
                            0xFF000623, 0xFF000000, 0xFF000000, 0xFF220004,
                        )
                    } else {
                        List(12) { SceneColor(0xFF28253D) }
                    },
                    gradientStart = SceneColor(0xFF0042EB),
                    gradientStop = SceneColor(0xFFEB0042),
                )
                else -> MermaidMindmapTheme()
            }
        }

        /**
         * Mermaid.js 12.0.0 themes/theme-*.js -> calculate radar and cScale0..11.
         */
        private fun radarTheme(
            name: String,
            axisColor: Long,
        ): MermaidRadarTheme = MermaidRadarTheme(
            colors = mindmapTheme(name).sectionFills,
            axisColor = SceneColor(axisColor),
        )

        private fun timelineTheme(name: String): MermaidTimelineTheme {
            val colorScale = mindmapTheme(name)
            return MermaidTimelineTheme(
                mainBackground = colorScale.mainBackground,
                nodeBorder = colorScale.nodeBorder,
                sectionFills = colorScale.sectionFills,
                sectionInverseColors = colorScale.sectionInverseColors,
                sectionLabelColors = colorScale.sectionLabelColors,
                useGradient = colorScale.useGradient,
                gradientStart = colorScale.gradientStart,
                gradientStop = colorScale.gradientStop,
            )
        }

        private fun gitGraphTheme(name: String): MermaidGitGraphTheme {
            fun colors(vararg values: Long): List<SceneColor> = values.map(::SceneColor)
            return when (name) {
                "dark" -> MermaidGitGraphTheme(
                    colors = colors(
                        0xFF797D7D,
                        0xFFA12273,
                        0xFF6A8993,
                        0xFF9B5C35,
                        0xFFCC1212,
                        0xFF65007B,
                        0xFFCC7801,
                        0xFF31A50E,
                    ),
                    inverseColors = colors(
                        0xFF868282,
                        0xFF5EDD8C,
                        0xFF95766C,
                        0xFF64A3CA,
                        0xFF34EDED,
                        0xFF9AFF84,
                        0xFF3387FE,
                        0xFFCE5AF1,
                    ),
                    branchLabelColors = colors(
                        0xFF2C2C2C,
                        0xFFD3D3D3,
                        0xFFD3D3D3,
                        0xFF2C2C2C,
                        0xFFD3D3D3,
                        0xFFD3D3D3,
                        0xFFD3D3D3,
                        0xFFD3D3D3,
                    ),
                    commitLineColor = SceneColor(0xFFD3D3D3),
                    commitLabelColor = SceneColor(0xFFB8B6B6),
                    commitLabelBackground = SceneColor(0xFF474949),
                    tagLabelColor = SceneColor(0xFFE0DFDF),
                    tagLabelBackground = SceneColor(0xFF1F2020),
                    tagLabelBorder = SceneColor(0xFFCCCCCC),
                    nodeBorder = SceneColor(0xFFCCCCCC),
                    mainBackground = SceneColor(0xFF1F2020),
                    primaryColor = SceneColor(0xFF1F2020),
                    textColor = SceneColor(0xFFCCCCCC),
                )
                "forest" -> MermaidGitGraphTheme(
                    colors = colors(
                        0xFF9BC834,
                        0xFF7AFF32,
                        0xFFB0D45B,
                        0xFFC8AB34,
                        0xFFC86134,
                        0xFFC83452,
                        0xFF34C861,
                        0xFF349BC8,
                    ),
                    inverseColors = colors(
                        0xFF6437CB,
                        0xFF8500CD,
                        0xFF4F2BA4,
                        0xFF3754CB,
                        0xFF379ECB,
                        0xFF37CBAD,
                        0xFFCB379E,
                        0xFFCB6437,
                    ),
                    commitLineColor = SceneColor(0xFF000000),
                    commitLabelColor = SceneColor(0xFF32004D),
                    commitLabelBackground = SceneColor(0xFFCDFFB2),
                    tagLabelColor = SceneColor(0xFF321B67),
                    tagLabelBackground = SceneColor(0xFFCDE498),
                    tagLabelBorder = SceneColor(0xFFABB594),
                    nodeBorder = SceneColor(0xFF13540C),
                    mainBackground = SceneColor(0xFFCDE498),
                    primaryColor = SceneColor(0xFFCDE498),
                    textColor = SceneColor(0xFF000000),
                )
                "neutral" -> MermaidGitGraphTheme(
                    colors = colors(
                        0xFFB4B4B4,
                        0xFF555555,
                        0xFFBBBBBB,
                        0xFF777777,
                        0xFF999999,
                        0xFFDDDDDD,
                        0xFFFFFFFF,
                        0xFFDDDDDD,
                    ),
                    inverseColors = colors(
                        0xFF4B4B4B,
                        0xFFAAAAAA,
                        0xFF444444,
                        0xFF888888,
                        0xFF666666,
                        0xFF222222,
                        0xFF000000,
                        0xFF222222,
                    ),
                    branchLabelColors = colors(
                        0xFF333333,
                        0xFFFFFFFF,
                        0xFF333333,
                        0xFFFFFFFF,
                        0xFF333333,
                        0xFF333333,
                        0xFF333333,
                        0xFF333333,
                    ),
                    commitLineColor = SceneColor(0xFF666666),
                    commitLabelColor = SceneColor(0xFF030303),
                    commitLabelBackground = SceneColor(0xFFFCFCFC),
                    tagLabelColor = SceneColor(0xFF111111),
                    tagLabelBackground = SceneColor(0xFFEEEEEE),
                    tagLabelBorder = SceneColor(0xFFD5D5D5),
                    nodeBorder = SceneColor(0xFF999999),
                    mainBackground = SceneColor(0xFFEEEEEE),
                    primaryColor = SceneColor(0xFFEEEEEE),
                    textColor = SceneColor(0xFF000000),
                )
                "base" -> MermaidGitGraphTheme(
                    colors = colors(
                        0xFFFFCB5E,
                        0xFFCB5EFF,
                        0xFF77A3FF,
                        0xFFFF7A5E,
                        0xFFFF5E92,
                        0xFFFF5EE3,
                        0xFF92FF5E,
                        0xFF5EFFCB,
                    ),
                    inverseColors = colors(
                        0xFF0034A2,
                        0xFF34A200,
                        0xFF885C00,
                        0xFF0085A2,
                        0xFF00A26D,
                        0xFF00A21D,
                        0xFF6D00A2,
                        0xFFA20034,
                    ),
                    branchLabelColors = List(8) { SceneColor(0xFF333333) },
                    commitLineColor = SceneColor(0xFF0B0B0B),
                    commitLabelColor = SceneColor(0xFF0B2200),
                    commitLabelBackground = SceneColor(0xFFF4DDFF),
                    tagLabelColor = SceneColor(0xFF333333),
                    tagLabelBackground = SceneColor(0xFFFFF4DD),
                    tagLabelBorder = SceneColor(0xFFEEDEBB),
                    nodeBorder = SceneColor(0xFFEEDEBB),
                    mainBackground = SceneColor(0xFFFFF4DD),
                    primaryColor = SceneColor(0xFFFFF4DD),
                    textColor = SceneColor(0xFF333333),
                )
                "neo", "redux" -> MermaidGitGraphTheme(
                    colors = colors(
                        0xFF7373F8,
                        0xFF9B9BBF,
                        0xFFF9F986,
                        0xFF73B5F8,
                        0xFF73F8F8,
                        0xFF73F8B5,
                        0xFFF873F8,
                        0xFFF87373,
                    ),
                    inverseColors = colors(
                        0xFF8C8C07,
                        0xFF646440,
                        0xFF060679,
                        0xFF8C4A07,
                        0xFF8C0707,
                        0xFF8C074A,
                        0xFF078C07,
                        0xFF078C8C,
                    ),
                    branchLabelColors = List(8) {
                        SceneColor(if (name == "redux") 0xFF28253D else 0xFF333333)
                    },
                    commitLineColor = SceneColor(
                        if (name == "redux") 0xFFBDBCCC else 0xFF000000,
                    ),
                    commitLabelColor = SceneColor(0xFF333333),
                    commitLabelBackground = SceneColor(0xFFCCCCCC),
                    tagLabelColor = SceneColor(
                        if (name == "redux") 0xFF28253D else 0xFF333333,
                    ),
                    tagLabelBackground = SceneColor(0xFFCCCCCC),
                    tagLabelBorder = SceneColor(
                        if (name == "redux") 0xFF181818 else 0xFFB3B3B3,
                    ),
                    nodeBorder = SceneColor(
                        if (name == "redux") 0xFF28253D else 0xFF000000,
                    ),
                    mainBackground = SceneColor(0xFFFFFFFF),
                    primaryColor = SceneColor(0xFFCCCCCC),
                    textColor = SceneColor(
                        if (name == "redux") 0xFF28253D else 0xFF333333,
                    ),
                    useGradient = name == "neo",
                )
                "neo-dark", "redux-dark" -> MermaidGitGraphTheme(
                    colors = if (name == "neo-dark") {
                        colors(
                            0xFF8B0000,
                            0xFFB72682,
                            0xFF78959E,
                            0xFFAE683B,
                            0xFFE31515,
                            0xFFA300C8,
                            0xFFFEA01C,
                            0xFF38BD10,
                        )
                    } else {
                        colors(
                            0xFF000000,
                            0xFF080909,
                            0xFF000000,
                            0xFF000000,
                            0xFF000000,
                            0xFF000000,
                            0xFF000000,
                            0xFF000000,
                        )
                    },
                    inverseColors = if (name == "neo-dark") {
                        colors(
                            0xFF75FFFF,
                            0xFF48D97D,
                            0xFF876A61,
                            0xFF5197C4,
                            0xFF1CEAEA,
                            0xFF5CFF38,
                            0xFF015FE3,
                            0xFFC742EF,
                        )
                    } else {
                        colors(
                            0xFFFFFFFF,
                            0xFFF7F6F6,
                            0xFFFFFFFF,
                            0xFFFFFFFF,
                            0xFFFFFFFF,
                            0xFFFFFFFF,
                            0xFFFFFFFF,
                            0xFFFFFFFF,
                        )
                    },
                    branchLabelColors = List(8) { SceneColor(0xFFE0DFDF) },
                    commitLineColor = SceneColor(
                        if (name == "redux-dark") 0xFFBDBCCC else 0xFFCCCCCC,
                    ),
                    commitLabelColor = SceneColor(0xFFB8B6B6),
                    commitLabelBackground = SceneColor(0xFF474949),
                    tagLabelColor = SceneColor(0xFFE0DFDF),
                    tagLabelBackground = SceneColor(0xFF1F2020),
                    tagLabelBorder = SceneColor(0xFFCCCCCC),
                    nodeBorder = SceneColor(
                        if (name == "redux-dark") 0xFFFFFFFF else 0xFFCCCCCC,
                    ),
                    mainBackground = SceneColor(
                        if (name == "redux-dark") 0xFF111113 else 0xFF2A2020,
                    ),
                    primaryColor = SceneColor(0xFF1F2020),
                    textColor = SceneColor(0xFFCCCCCC),
                    useGradient = name == "neo-dark",
                )
                else -> MermaidGitGraphTheme()
            }
        }

        private fun darkRequirementTheme(): MermaidRequirementTheme =
            MermaidRequirementTheme(
                background = SceneColor(0xFF1F2020),
                borderColor = SceneColor(0xFFCCCCCC),
                textColor = SceneColor(0xFFE0DFDF),
                relationColor = SceneColor(0xFFD3D3D3),
                relationLabelBackground = SceneColor(0xFF474949),
                relationLabelColor = SceneColor(0xFFD3D3D3),
                edgeLabelBackground = SceneColor(0xFF585858),
            )

        private fun forestRequirementTheme(): MermaidRequirementTheme =
            MermaidRequirementTheme(
                background = SceneColor(0xFFCDE498),
                borderColor = SceneColor(0xFFABB594),
                textColor = SceneColor(0xFF321B67),
                relationColor = SceneColor(0xFF000000),
                relationLabelBackground = SceneColor(0xFFE8E8E8),
                relationLabelColor = SceneColor(0xFF000000),
                edgeLabelBackground = SceneColor(0xFFE8E8E8),
            )

        private fun neutralRequirementTheme(): MermaidRequirementTheme =
            MermaidRequirementTheme(
                background = SceneColor(0xFFEEEEEE),
                borderColor = SceneColor(0xFFD4D4D4),
                textColor = SceneColor(0xFF111111),
                relationColor = SceneColor(0xFF666666),
                relationLabelBackground = SceneColor(0xFFFFFFFF),
                relationLabelColor = SceneColor(0xFF333333),
                edgeLabelBackground = SceneColor(0xFFFFFFFF),
            )

        private fun baseRequirementTheme(): MermaidRequirementTheme =
            MermaidRequirementTheme(
                background = SceneColor(0xFFFFF4DD),
                borderColor = SceneColor(0xFFEEDDBB),
                textColor = SceneColor(0xFF333333),
                relationColor = SceneColor(0xFF0B0B0B),
                relationLabelBackground = SceneColor(0xFFF4DDFF),
                relationLabelColor = SceneColor(0xFF333333),
                edgeLabelBackground = SceneColor(0xFFF4DDFF),
            )

        private fun neoRequirementTheme(dark: Boolean): MermaidRequirementTheme =
            if (dark) {
                MermaidRequirementTheme(
                    background = SceneColor(0xFF1F2020),
                    borderColor = SceneColor(0xFFCCCCCC),
                    textColor = SceneColor(0xFFE0DFDF),
                    relationColor = SceneColor(0xFFCCCCCC),
                    relationLabelBackground = SceneColor(0xFF474949),
                    relationLabelColor = SceneColor(0xFFE0DFDF),
                    edgeLabelBackground = SceneColor(0xFF474949),
                )
            } else {
                MermaidRequirementTheme(
                    background = SceneColor(0xFFECECFE),
                    borderColor = SceneColor(0xFFB3B3B3),
                    textColor = SceneColor(0xFF333333),
                    relationColor = SceneColor(0xFF000000),
                    relationLabelBackground = SceneColor(0xFFCCCCCC),
                    relationLabelColor = SceneColor(0xFF333333),
                    edgeLabelBackground = SceneColor(0xFFCCCCCC),
                )
            }

        private fun reduxRequirementTheme(dark: Boolean): MermaidRequirementTheme =
            if (dark) {
                neoRequirementTheme(dark = true).copy(
                    edgeLabelBackground = SceneColor(0xFF16141F),
                )
            } else {
                MermaidRequirementTheme(
                    background = SceneColor(0xFFECECFE),
                    borderColor = SceneColor(0xFF181818),
                    textColor = SceneColor(0xFF28253D),
                    relationColor = SceneColor(0xFF000000),
                    relationLabelBackground = SceneColor(0xFFCCCCCC),
                    relationLabelColor = SceneColor(0xFF28253D),
                    edgeLabelBackground = SceneColor(0xFFFFFFFF),
                )
            }

        private fun darkGanttTheme(): MermaidGanttTheme = MermaidGanttTheme(
            sectionBackground = SceneColor(0xFFB4AC76),
            alternateSectionBackground = SceneColor(0xFF333333),
            secondSectionBackground = SceneColor(0xFFEAE8D9),
            excludedBackground = SceneColor(0xFF9F9758),
            taskFill = SceneColor(0xFF595C5C),
            taskStroke = SceneColor(0xFFFFFFFF),
            activeTaskFill = SceneColor(0xFF81B1DB),
            activeTaskStroke = SceneColor(0xFFFFFFFF),
            criticalTaskFill = SceneColor(0xFFE83737),
            criticalTaskStroke = SceneColor(0xFFE83737),
            taskText = SceneColor(0xFFE2DCD6),
            darkTaskText = SceneColor(0xFF2C2C2C),
            outsideTaskText = SceneColor(0xFFD3D3D3),
            text = SceneColor(0xFFCCCCCC),
            todayLine = SceneColor(0xFFDB5757),
            verticalLine = SceneColor(0xFF00BFFF),
            title = SceneColor(0xFFF9FFFE),
        )

        private fun forestGanttTheme(): MermaidGanttTheme = MermaidGanttTheme(
            sectionBackground = SceneColor(0xFF6EAA49),
            secondSectionBackground = SceneColor(0xFF6EAA49),
            taskFill = SceneColor(0xFF487E3A),
            taskStroke = SceneColor(0xFF13540C),
            activeTaskFill = SceneColor(0xFFCDE498),
            activeTaskStroke = SceneColor(0xFF13540C),
            text = SceneColor(0xFF000000),
            verticalLine = SceneColor(0xFF00BFFF),
        )

        private fun neutralGanttTheme(): MermaidGanttTheme = MermaidGanttTheme(
            sectionBackground = SceneColor(0xFFBDBDBD),
            secondSectionBackground = SceneColor(0xFFBDBDBD),
            taskFill = SceneColor(0xFF707070),
            taskStroke = SceneColor(0xFF575757),
            activeTaskFill = SceneColor(0xFFEEEEEE),
            activeTaskStroke = SceneColor(0xFF575757),
            doneTaskFill = SceneColor(0xFFBBBBBB),
            doneTaskStroke = SceneColor(0xFF666666),
            criticalTaskFill = SceneColor(0xFFDD4422),
            criticalTaskStroke = SceneColor(0xFFB1361B),
            darkTaskText = SceneColor(0xFF333333),
            outsideTaskText = SceneColor(0xFF333333),
            text = SceneColor(0xFF000000),
            grid = SceneColor(0xFFE6E6E6),
            todayLine = SceneColor(0xFFDD4422),
            verticalLine = SceneColor(0xFFDD4422),
        )

        private fun baseGanttTheme(): MermaidGanttTheme = MermaidGanttTheme(
            sectionBackground = SceneColor(0xFFF7F9FF),
            secondSectionBackground = SceneColor(0xFFFFF4DD),
            taskFill = SceneColor(0xFFFFF4DD),
            taskStroke = SceneColor(0xFFEEDEBB),
            activeTaskFill = SceneColor(0xFFFFFFFF),
            activeTaskStroke = SceneColor(0xFFFFF4DD),
            taskText = SceneColor(0xFF333333),
            darkTaskText = SceneColor(0xFF333333),
            outsideTaskText = SceneColor(0xFF333333),
            text = SceneColor(0xFF333333),
            title = SceneColor(0xFF090600),
        )

        private fun neoGanttTheme(): MermaidGanttTheme = MermaidGanttTheme(
            sectionBackground = SceneColor(0xFFFFFFFF),
            secondSectionBackground = SceneColor(0xFFECECFE),
            taskFill = SceneColor(0xFFECECFE),
            taskStroke = SceneColor(0xFFB3B3B3),
            activeTaskFill = SceneColor(0xFFFFFFFF),
            activeTaskStroke = SceneColor(0xFFECECFE),
            taskText = SceneColor(0xFF333333),
            darkTaskText = SceneColor(0xFF333333),
            outsideTaskText = SceneColor(0xFF333333),
            verticalLine = SceneColor(0xFFB3B3B3),
            title = SceneColor(0xFF000000),
        )

        private fun neoDarkGanttTheme(): MermaidGanttTheme = MermaidGanttTheme(
            sectionBackground = SceneColor(0xFF201F1F),
            secondSectionBackground = SceneColor(0xFF1F2020),
            taskFill = SceneColor(0xFF1F2020),
            taskStroke = SceneColor(0xFFCCCCCC),
            activeTaskFill = SceneColor(0xFF595C5C),
            activeTaskStroke = SceneColor(0xFF1F2020),
            doneTaskFill = SceneColor(0xFF584343),
            taskText = SceneColor(0xFFCCCCCC),
            darkTaskText = SceneColor(0xFFCCCCCC),
            outsideTaskText = SceneColor(0xFFCCCCCC),
            text = SceneColor(0xFFCCCCCC),
            verticalLine = SceneColor(0xFFCCCCCC),
            title = SceneColor(0xFFDFE0E0),
        )

        private fun reduxGanttTheme(): MermaidGanttTheme = neoGanttTheme().copy(
            taskStroke = SceneColor(0xFF181818),
            activeTaskStroke = SceneColor(0xFFECECFE),
            taskText = SceneColor(0xFF28253D),
            darkTaskText = SceneColor(0xFF28253D),
            outsideTaskText = SceneColor(0xFF28253D),
            text = SceneColor(0xFF28253D),
            verticalLine = SceneColor(0xFF181818),
        )

        private fun reduxColorGanttTheme(): MermaidGanttTheme = reduxGanttTheme().copy(
            sectionBackground = SceneColor(0xFFF4A8FF),
            secondSectionBackground = SceneColor(0xFF46ECD5),
        )

        private fun reduxDarkGanttTheme(): MermaidGanttTheme = neoDarkGanttTheme().copy(
            doneTaskFill = SceneColor(0xFF38383E),
        )

        private fun reduxDarkColorGanttTheme(): MermaidGanttTheme =
            reduxDarkGanttTheme().copy(
                sectionBackground = SceneColor(0xFFF4A8FF),
                alternateSectionBackground = SceneColor(0xFF333333),
                secondSectionBackground = SceneColor(0xFF46ECD5),
            )

        private fun reduxXyChartTheme(
            background: Long,
            text: Long,
        ): MermaidXyChartTheme = xyChartTheme(
            background = background,
            text = text,
            palette = listOf(
                0xFFFFF4DD,
                0xFFFFD8B1,
                0xFFFFA07A,
                0xFFECEFF1,
                0xFFD6DBDF,
                0xFFC3E0A8,
                0xFFFFB6A4,
                0xFFFFD74D,
                0xFF738FA7,
                0xFFFFFFF0,
            ),
        )

        private fun xyChartTheme(
            background: Long,
            text: Long,
            palette: List<Long>,
        ): MermaidXyChartTheme {
            val textColor = SceneColor(text)
            return MermaidXyChartTheme(
                backgroundColor = SceneColor(background),
                titleColor = textColor,
                dataLabelColor = textColor,
                legendTextColor = textColor,
                xAxisLabelColor = textColor,
                xAxisTitleColor = textColor,
                xAxisTickColor = textColor,
                xAxisLineColor = textColor,
                yAxisLabelColor = textColor,
                yAxisTitleColor = textColor,
                yAxisTickColor = textColor,
                yAxisLineColor = textColor,
                plotColorPalette = palette.map(::SceneColor),
            )
        }

        private fun reduxShadow(dark: Boolean): SceneShadow = SceneShadow(
            color = if (dark) SceneColor(0x0FFFFFFF) else SceneColor(0x0F000000),
            offsetX = 4f,
            offsetY = 4f,
        )

        private fun darkPieTheme(): MermaidPieTheme = pieTheme(
            colors = listOf(
                0xFF0B0000,
                0xFF4D1037,
                0xFF3F5258,
                0xFF4F2F1B,
                0xFF6E0A0A,
                0xFF3B0048,
                0xFF995A01,
                0xFF154706,
                0xFF161722,
                0xFF00296F,
                0xFF01629C,
                0xFF000000,
            ),
            title = 0xFFD3D3D3,
            section = 0xFFCCCCCC,
            legend = 0xFFD3D3D3,
        )

        private fun forestPieTheme(): MermaidPieTheme = pieTheme(
            colors = listOf(
                0xFFCDE498,
                0xFFCDFFB2,
                0xFFE1EFC0,
                0xFF8CB42F,
                0xFF6AFF19,
                0xFF33B42F,
                0xFF70D990,
                0xFFD99070,
                0xFF98CDE4,
                0xFF1A6330,
                0xFF63301A,
                0xFF1A4D63,
            ),
            title = 0xFF000000,
            section = 0xFF000000,
            legend = 0xFF000000,
        )

        private fun neutralPieTheme(): MermaidPieTheme = pieTheme(
            colors = listOf(
                0xFFF4F4F4,
                0xFF555555,
                0xFFBBBBBB,
                0xFF777777,
                0xFF999999,
                0xFFDDDDDD,
                0xFFFFFFFF,
                0xFFDDDDDD,
                0xFFBBBBBB,
                0xFF999999,
                0xFF777777,
                0xFF555555,
            ),
            title = 0xFF333333,
            section = 0xFF000000,
            legend = 0xFF333333,
        )

        private fun basePieTheme(): MermaidPieTheme = pieTheme(
            colors = listOf(
                0xFFFFF4DD,
                0xFFF4DDFF,
                0xFFF7F9FF,
                0xFFFFE4AA,
                0xFFE4AAFF,
                0xFFC4D7FF,
                0xFFC6FFAA,
                0xFFFFAAC6,
                0xFFDDFFF4,
                0xFFA3FF77,
                0xFFFF77A3,
                0xFFAAFFE4,
            ),
            title = 0xFF333333,
            section = 0xFF333333,
            legend = 0xFF333333,
        )

        private fun neoPieTheme(dark: Boolean): MermaidPieTheme = if (dark) {
            pieTheme(
                colors = listOf(
                    0xFF1F2020,
                    0xFF474949,
                    0xFF201F1F,
                    0xFF060606,
                    0xFF2E3030,
                    0xFF060606,
                    0xFF060606,
                    0xFF060606,
                    0xFF201F20,
                    0xFF000000,
                    0xFF000000,
                    0xFF060606,
                ),
                title = 0xFFCCCCCC,
                section = 0xFFCCCCCC,
                legend = 0xFFCCCCCC,
            )
        } else {
            pieTheme(
                colors = listOf(
                    0xFFECECFE,
                    0xFFE9E9F1,
                    0xFFFFFFFF,
                    0xFFBCBCFB,
                    0xFFCACADD,
                    0xFFFCFCCF,
                    0xFFFBBCFB,
                    0xFFBCFBFB,
                    0xFFFEECEC,
                    0xFFF98BF9,
                    0xFF8BF9F9,
                    0xFFFBBCBC,
                ),
                title = 0xFF333333,
                section = 0xFF333333,
                legend = 0xFF333333,
            )
        }

        private fun reduxPieTheme(dark: Boolean): MermaidPieTheme =
            neoPieTheme(dark).copy(
                titleTextColor = if (dark) SceneColor(0xFFCCCCCC) else SceneColor(0xFF28253D),
                sectionTextColor = if (dark) SceneColor(0xFFCCCCCC) else SceneColor(0xFF28253D),
                legendTextColor = if (dark) SceneColor(0xFFCCCCCC) else SceneColor(0xFF28253D),
            )

        private fun reduxColorPieTheme(dark: Boolean): MermaidPieTheme = pieTheme(
            colors = listOf(
                0xFFF4A8FF,
                0xFF46ECD5,
                0xFFFFB86A,
                0xFFDAB2FF,
                0xFF7BF1A8,
                0xFFC4B4FF,
                0xFFFFA2A2,
                0xFFFFDF20,
                0xFFA3B3FF,
                0xFFBBF451,
                0xFF74D4FF,
                0xFFFFA1AD,
            ),
            title = if (dark) 0xFFCCCCCC else 0xFF28253D,
            section = 0xFF28253D,
            legend = if (dark) 0xFFCCCCCC else 0xFF28253D,
        )

        private fun pieTheme(
            colors: List<Long>,
            title: Long,
            section: Long,
            legend: Long,
        ): MermaidPieTheme = MermaidPieTheme(
            colors = colors.map(::SceneColor),
            titleTextColor = SceneColor(title),
            sectionTextColor = SceneColor(section),
            legendTextColor = SceneColor(legend),
        )

        private fun parseDropShadow(
            source: String?,
            inherited: SceneShadow?,
        ): GMResult<SceneShadow?, MermaidError> {
            val value = source?.trim() ?: return GMResult.Ok(inherited)
            if (value.equals("none", ignoreCase = true)) {
                return GMResult.Ok(null)
            }
            if (value.equals("url(#drop-shadow)", ignoreCase = true)) {
                return GMResult.Ok(inherited)
            }
            val match = DROP_SHADOW_PATTERN.matchEntire(value)
                ?: return GMResult.Err(
                    MermaidError.Configuration(
                        "Mermaid theme variable 'dropShadow' uses an unsupported CSS filter '$source'",
                    ),
                )
            val offsetX = match.groupValues[1].toFloatOrNull()
            val offsetY = match.groupValues[2].toFloatOrNull()
            val blurRadius = match.groupValues[3].toFloatOrNull()
            val color = CssColorParser.parse(match.groupValues[4])
            if (offsetX == null || offsetY == null || blurRadius == null || blurRadius < 0f || color == null) {
                return GMResult.Err(
                    MermaidError.Configuration(
                        "Mermaid theme variable 'dropShadow' has invalid value '$source'",
                    ),
                )
            }
            return GMResult.Ok(
                SceneShadow(
                    color = color,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    blurRadius = blurRadius,
                ),
            )
        }

        private fun paletteColor(
            colors: List<SceneColor>,
            index: Int?,
        ): SceneColor? =
            index
                ?.takeIf { colors.isNotEmpty() }
                ?.let { value -> colors[value.mod(colors.size)] }

        private fun reduxColorBackgrounds(): List<SceneColor> = listOf(
            SceneColor(0xFFFDF4FF),
            SceneColor(0xFFF0FDFA),
            SceneColor(0xFFFFF7ED),
            SceneColor(0xFFECFEFF),
            SceneColor(0xFFF0FDF4),
            SceneColor(0xFFF5F3FF),
            SceneColor(0xFFFEF2F2),
            SceneColor(0xFFFEFCE8),
            SceneColor(0xFFEEF2FF),
            SceneColor(0xFFF7FEE7),
            SceneColor(0xFFF0F9FF),
            SceneColor(0xFFFFF1F2),
        )

        private fun reduxColorBorders(): List<SceneColor> = listOf(
            SceneColor(0xFFE879F9),
            SceneColor(0xFF2DD4BF),
            SceneColor(0xFFFB923C),
            SceneColor(0xFF22D3EE),
            SceneColor(0xFF4ADE80),
            SceneColor(0xFFA78BFA),
            SceneColor(0xFFF87171),
            SceneColor(0xFFFACC15),
            SceneColor(0xFF818CF8),
            SceneColor(0xFFA3E635),
            SceneColor(0xFF38BDF8),
            SceneColor(0xFFFB7185),
        )

        private val DROP_SHADOW_PATTERN = Regex(
            pattern = """^drop-shadow\(\s*(-?(?:\d+(?:\.\d+)?|\.\d+))px\s+""" +
                """(-?(?:\d+(?:\.\d+)?|\.\d+))px\s+""" +
                """((?:\d+(?:\.\d+)?|\.\d+))px\s+(.+)\s*\)\s*;?\s*$""",
            option = RegexOption.IGNORE_CASE,
        )
    }
}
