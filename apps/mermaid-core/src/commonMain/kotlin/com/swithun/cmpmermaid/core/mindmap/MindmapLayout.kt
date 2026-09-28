package com.swithun.cmpmermaid.core.mindmap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneLinearGradient
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.D3CurvePort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidRenderedText
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapePort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import com.swithun.cmpmermaid.core.mindmap.upstream.dagre.MindmapDagreLayout
import com.swithun.cmpmermaid.core.mindmap.upstream.cose.CoseBilkentLayout
import com.swithun.cmpmermaid.core.mindmap.upstream.cose.CoseEdgeInput
import com.swithun.cmpmermaid.core.mindmap.upstream.cose.CoseNodeInput
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapDocument
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapEdge
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapNode
import com.swithun.cmpmermaid.core.mindmap.upstream.mermaid.MindmapNodeType
import com.swithun.cmpmermaid.core.mindmap.upstream.tidytree.MindmapTidyTreeLayout
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Native translation of Mermaid 12.0.0 mindmapRenderer.ts and the common
 * rendering path used by the cose-bilkent layout.
 */
internal class MindmapLayout {
    fun layout(
        document: MindmapDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        if (document.edges.size > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "Mindmap edges",
                    actual = document.edges.size,
                    maximum = context.options.maxEdges,
                ),
            )
        }
        document.nodes.firstOrNull { node -> !node.icon.isNullOrBlank() }?.let { node ->
            return GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "Mindmap icon",
                    message = "Native Mindmap cannot resolve icon '${node.icon}' without " +
                        "Mermaid's registered icon-pack provider",
                ),
            )
        }
        document.nodes.firstOrNull { node -> !node.classNames.isNullOrBlank() }?.let { node ->
            return GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "Mindmap CSS class",
                    message = "Native Mindmap cannot apply CSS class '${node.classNames}'",
                ),
            )
        }

        val visuals = linkedMapOf<Int, MindmapNodeVisual>()
        document.nodes.forEach { node ->
            val rendered = when (
                val result = MermaidTextPort.render(
                    source = node.description,
                    labelType = FlowLabelType.Markdown,
                    config = context.options,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val metrics = when (val result = measure(node, rendered, context)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val kind = node.sceneShapeKind(context)
            val bottomStroke = node.sectionInverseColor(context)
            val shapeLayout = when (
                val result = MermaidShapePort.mindmapShape(
                    kind = kind,
                    isDefaultNode = node.type == MindmapNodeType.Default &&
                        !context.usesReduxMindmapShape(),
                    measuredLabel = SceneSize(metrics.width, metrics.height),
                    padding = node.padding,
                    look = context.options.look,
                    bottomStroke = bottomStroke,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            visuals[node.id] = MindmapNodeVisual(
                renderedText = rendered,
                textMetrics = metrics,
                kind = kind,
                shapeLayout = shapeLayout,
            )
        }

        val placement = when (val result = position(document, visuals, context)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val elements = mutableListOf<SceneElement>()
        document.edges.forEach { edge ->
            val points = placement.edgePoints[edge.id]
                ?: return layoutError("Mindmap edge '${edge.id}' has no routed points")
            elements += ScenePath(
                id = edge.id,
                points = points,
                commands = D3CurvePort.generate(points, "basis"),
                color = edge.color(context),
                strokeWidth = edge.strokeWidth(context),
                strokePattern = SceneStrokePattern.Solid,
                curve = "basis",
                look = context.options.look,
                animated = false,
                zIndex = 5,
            )
        }
        document.nodes.forEach { node ->
            val visual = visuals[node.id]
                ?: return layoutError("Mindmap node '${node.nodeId}' was not measured")
            val center = placement.nodeCenters[node.id]
                ?: return layoutError("Mindmap node '${node.nodeId}' was not positioned")
            val shapeBounds = visual.shapeLayout.size.centeredAt(center)
            val style = node.style(context)
            elements += SceneShape(
                id = "node-${node.id}",
                bounds = shapeBounds,
                kind = visual.kind,
                geometry = visual.shapeLayout.geometry,
                fill = style.fill,
                stroke = style.stroke,
                strokeWidth = style.strokeWidth,
                shadow = if (context.options.look == NEO_LOOK) {
                    context.theme.dropShadow
                } else {
                    null
                },
                strokeGradient = style.strokeGradient,
                zIndex = 10,
            )
            val labelCenter = center.translate(
                visual.shapeLayout.labelOffset.x,
                visual.shapeLayout.labelOffset.y,
            )
            elements += SceneText(
                text = visual.renderedText.text,
                bounds = SceneRect(
                    left = labelCenter.x - visual.textMetrics.width / 2f,
                    top = labelCenter.y - visual.textMetrics.height / 2f,
                    right = labelCenter.x + visual.textMetrics.width / 2f,
                    bottom = labelCenter.y + visual.textMetrics.height / 2f,
                ),
                color = style.text,
                fontSize = context.options.fontSize ?: context.theme.fontSize,
                lineHeight = TEXT_LINE_HEIGHT,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                spans = visual.renderedText.spans,
                horizontalAlignment = SceneTextAlignment.Center,
                softWrap = true,
                zIndex = 20,
            )
        }

        return GMResult.Ok(normalize(elements, document, context))
    }

    private fun measure(
        node: MindmapNode,
        rendered: MermaidRenderedText,
        context: MermaidRenderContext,
    ): GMResult<TextMetrics, MermaidError> = try {
        GMResult.Ok(
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = rendered.text,
                    fontSize = context.options.fontSize ?: context.theme.fontSize,
                    maxWidth = node.labelMaxWidth(context),
                    lineHeight = TEXT_LINE_HEIGHT,
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    spans = rendered.spans,
                ),
            ),
        )
    } catch (failure: Exception) {
        GMResult.Err(
            MermaidError.Unexpected.from(
                failure,
                "Text measurement failed for Mindmap node '${node.nodeId}'",
            ),
        )
    }

    // Mermaid 12.0.0: mindmapRenderer.ts -> draw and shapes/util.ts -> labelHelper.
    // These shapes clear node.width before label measurement and therefore fall
    // back to flowchart.wrappingWidth; the remaining shapes keep maxNodeWidth.
    private fun MindmapNode.labelMaxWidth(context: MermaidRenderContext): Float =
        when (type) {
            MindmapNodeType.RoundedRectangle,
            MindmapNodeType.Rectangle,
            MindmapNodeType.Hexagon,
            -> context.options.wrappingWidth
            MindmapNodeType.Default -> if (context.usesReduxMindmapShape()) {
                context.options.wrappingWidth
            } else {
                context.options.mindmap.maxNodeWidth
            }
            MindmapNodeType.Circle,
            MindmapNodeType.Cloud,
            MindmapNodeType.Bang,
            -> context.options.mindmap.maxNodeWidth
        }

    private fun position(
        document: MindmapDocument,
        visuals: Map<Int, MindmapNodeVisual>,
        context: MermaidRenderContext,
    ): GMResult<MindmapPlacement, MermaidError> =
        when (context.options.mindmap.layoutAlgorithm) {
            "cose-bilkent" -> positionWithCose(document, visuals)
            "dagre" -> MindmapDagreLayout.layout(
                document = document,
                shapeLayouts = visuals.mapValues { (_, visual) -> visual.shapeLayout },
            )
            "tidy-tree" -> MindmapTidyTreeLayout.layout(
                document = document,
                nodeSizes = visuals.mapValues { (_, visual) -> visual.shapeLayout.size },
            )
            "elk",
            "elk.layered",
            "elk.stress",
            "elk.force",
            "elk.mrtree",
            "elk.sporeOverlap",
            "elk.box",
            "elk.rectpacking",
            -> unsupportedElkLayout(context.options.mindmap.layoutAlgorithm)
            else -> GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "${context.options.mindmap.layoutAlgorithm} Mindmap layout",
                    message = "Native Mindmap has not connected the requested layout engine",
                ),
            )
        }

    private fun unsupportedElkLayout(
        layout: String,
    ): GMResult<MindmapPlacement, MermaidError> = GMResult.Err(
        MermaidError.UnsupportedFeature(
            feature = "$layout Mindmap layout",
            message = "Mermaid.js 12.0.0 fails while rendering Mindmap with '$layout'; " +
                "Native Mindmap does not substitute another layout or execute elkjs in a " +
                "JavaScript engine",
        ),
    )

    private fun positionWithCose(
        document: MindmapDocument,
        visuals: Map<Int, MindmapNodeVisual>,
    ): GMResult<MindmapPlacement, MermaidError> {
        val result = CoseBilkentLayout.layout(
            nodeInputs = document.nodes.map { node ->
                CoseNodeInput(
                    id = node.id.toString(),
                    size = visuals.getValue(node.id).shapeLayout.size,
                )
            },
            edgeInputs = document.edges.map { edge ->
                CoseEdgeInput(
                    id = edge.id,
                    source = edge.start.toString(),
                    target = edge.end.toString(),
                )
            },
        )
        return when (result) {
            is GMResult.Ok -> {
                val centers = result.value.nodeCenters.mapNotNull { (id, center) ->
                    id.toIntOrNull()?.let { parsedId -> parsedId to center }
                }.toMap()
                val edgePoints = linkedMapOf<String, List<ScenePoint>>()
                document.edges.forEach { edge ->
                    val source = centers[edge.start]
                        ?: return layoutError(
                            "Mindmap edge '${edge.id}' has no CoSE source position",
                        )
                    val target = centers[edge.end]
                        ?: return layoutError(
                            "Mindmap edge '${edge.id}' has no CoSE target position",
                        )
                    edgePoints[edge.id] = coseEdgePoints(source, target)
                }
                GMResult.Ok(
                    MindmapPlacement(
                        nodeCenters = centers,
                        edgePoints = edgePoints,
                    ),
                )
            }
            is GMResult.Err -> result
        }
    }

    private fun coseEdgePoints(
        source: ScenePoint,
        target: ScenePoint,
    ): List<ScenePoint> {
        // Cytoscape 3.33.1 computes rscratch endpoints from its default 30 px
        // node style even though CoSE receives the measured layout dimensions.
        val start = source.toward(target, CYTOSCAPE_EDGE_RADIUS)
        val end = target.toward(source, CYTOSCAPE_EDGE_RADIUS)
        return listOf(
            start,
            ScenePoint(
                x = (source.x + target.x) / 2f,
                y = (source.y + target.y) / 2f,
            ),
            end,
        )
    }

    private fun normalize(
        elements: List<SceneElement>,
        document: MindmapDocument,
        context: MermaidRenderContext,
    ): MermaidScene {
        val bounds = elements
            .mapNotNull(::elementBounds)
            .reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val padding = context.options.mindmap.padding
        val dx = padding - bounds.left
        val dy = padding - bounds.top
        return MermaidScene(
            width = max(1f, bounds.width + padding * 2f),
            height = max(1f, bounds.height + padding * 2f),
            background = context.theme.background,
            elements = elements
                .map { element -> element.translate(dx, dy) }
                .sortedBy(SceneElement::zIndex),
            title = document.title,
            viewportPadding = 0f,
            viewportSizing = if (context.options.mindmap.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
    }

    private fun MindmapNode.sceneShapeKind(context: MermaidRenderContext): SceneShapeKind =
        when (type) {
            MindmapNodeType.Default -> SceneShapeKind.RoundedRectangle
            MindmapNodeType.RoundedRectangle -> SceneShapeKind.RoundedRectangle
            MindmapNodeType.Rectangle -> SceneShapeKind.Rectangle
            MindmapNodeType.Circle -> SceneShapeKind.Circle
            MindmapNodeType.Cloud -> SceneShapeKind.Cloud
            MindmapNodeType.Bang -> SceneShapeKind.Bang
            MindmapNodeType.Hexagon -> SceneShapeKind.Hexagon
        }

    private fun MindmapNode.style(context: MermaidRenderContext): MindmapNodeStyle {
        val paletteIndex = paletteIndex()
        val theme = context.theme.mindmap
        val neo = context.options.look == NEO_LOOK
        val gradient = neo && theme.useGradient
        val reduxFamily = context.usesReduxMindmapShape()
        val monochromeRedux = context.usesMonochromeReduxMindmapStyle()
        val neutral = context.options.themeName?.lowercase() == "neutral"
        val fill = when {
            gradient -> theme.mainBackground
            isRoot && neo && reduxFamily -> theme.mainBackground
            isRoot -> theme.rootFill
            neo && (monochromeRedux || neutral) -> theme.mainBackground
            else -> theme.sectionFills.colorAt(paletteIndex, theme.mainBackground)
        }
        val stroke = when {
            !neo -> TRANSPARENT
            monochromeRedux -> theme.nodeBorder
            else -> theme.sectionFills.colorAt(paletteIndex, theme.nodeBorder)
        }
        val text = when {
            isRoot && neo && reduxFamily -> theme.nodeBorder
            isRoot -> theme.rootText
            neo && monochromeRedux -> theme.nodeBorder
            neo && neutral && !context.options.htmlLabels ->
                theme.sectionLabelColors.colorAt(1, context.theme.nodeText)
            else -> theme.sectionLabelColors.colorAt(paletteIndex, context.theme.nodeText)
        }
        return MindmapNodeStyle(
            fill = fill,
            stroke = stroke,
            text = text,
            strokeWidth = if (neo) context.theme.strokeWidth else 0f,
            strokeGradient = if (gradient) {
                SceneLinearGradient(theme.gradientStart, theme.gradientStop)
            } else {
                null
            },
        )
    }

    private fun MindmapNode.sectionInverseColor(context: MermaidRenderContext): SceneColor {
        if (context.options.look == NEO_LOOK && context.theme.mindmap.useGradient) {
            return TRANSPARENT
        }
        return context.theme.mindmap.sectionInverseColors.colorAt(
            paletteIndex(),
            context.theme.mindmap.nodeBorder,
        )
    }

    private fun MindmapEdge.color(context: MermaidRenderContext): SceneColor {
        val paletteIndex = ((section ?: 0) + 1).mod(MINDMAP_COLOR_LIMIT)
        val themeName = context.options.themeName?.lowercase()
        return if (
            context.options.look == NEO_LOOK &&
            (context.usesReduxMindmapShape() || themeName == "neo-dark")
        ) {
            context.theme.mindmap.nodeBorder
        } else {
            context.theme.mindmap.sectionFills.colorAt(paletteIndex, context.theme.edge)
        }
    }

    private fun MindmapEdge.strokeWidth(context: MermaidRenderContext): Float {
        val edgeDepth = depth + 1
        return if (context.options.look == NEO_LOOK) {
            max(10f - edgeDepth * 2f, 2f)
        } else {
            (14f - edgeDepth * 3f).takeIf { width -> width > 0f } ?: 3f
        }
    }

    private fun MindmapNode.paletteIndex(): Int =
        if (isRoot) 0 else ((section ?: 0) + 1).mod(MINDMAP_COLOR_LIMIT)

    private fun MermaidRenderContext.usesReduxMindmapShape(): Boolean =
        options.themeName?.lowercase()?.contains("redux") == true ||
            (options.themeName == null && theme == MermaidTheme.FlowchartDefault)

    // Mermaid 12.0.0: diagrams/mindmap/styles.ts -> genSections.
    // Redux Color remains in the Redux shape family, but only the two exact
    // monochrome themes replace section colors with mainBkg/nodeBorder.
    private fun MermaidRenderContext.usesMonochromeReduxMindmapStyle(): Boolean =
        options.themeName?.lowercase() in MONOCHROME_REDUX_THEMES

    private fun List<SceneColor>.colorAt(
        index: Int,
        fallback: SceneColor,
    ): SceneColor = getOrNull(index.mod(MINDMAP_COLOR_LIMIT)) ?: fallback

    private fun SceneSize.centeredAt(center: ScenePoint): SceneRect = SceneRect(
        left = center.x - width / 2f,
        top = center.y - height / 2f,
        right = center.x + width / 2f,
        bottom = center.y + height / 2f,
    )

    private fun ScenePoint.toward(
        target: ScenePoint,
        distance: Float,
    ): ScenePoint {
        val dx = target.x - x
        val dy = target.y - y
        val length = hypot(dx, dy)
        if (length <= 0.0001f) return this
        return ScenePoint(
            x = x + dx / length * min(distance, length / 2f),
            y = y + dy / length * min(distance, length / 2f),
        )
    }

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneAsset -> element.bounds
        is SceneShape -> element.bounds
        is SceneText -> element.bounds
        is ScenePath -> {
            val points = buildList {
                element.commands.forEach { command ->
                    when (command) {
                        is ScenePathCommand.MoveTo -> add(command.point)
                        is ScenePathCommand.LineTo -> add(command.point)
                        is ScenePathCommand.QuadraticTo -> {
                            add(command.control)
                            add(command.end)
                        }
                        is ScenePathCommand.CubicTo -> {
                            add(command.control1)
                            add(command.control2)
                            add(command.end)
                        }
                        is ScenePathCommand.ArcTo -> add(command.end)
                    }
                }
            }
            val first = points.firstOrNull()
            if (first == null) null else points.drop(1).fold(
                SceneRect(first.x, first.y, first.x, first.y),
            ) { current, point ->
                SceneRect(
                    left = min(current.left, point.x),
                    top = min(current.top, point.y),
                    right = max(current.right, point.x),
                    bottom = max(current.bottom, point.y),
                )
            }
        }
    }

    private fun SceneElement.translate(
        dx: Float,
        dy: Float,
    ): SceneElement = when (this) {
        is SceneAsset -> copy(bounds = bounds.translate(dx, dy))
        is SceneShape -> copy(bounds = bounds.translate(dx, dy))
        is SceneText -> copy(
            bounds = bounds.translate(dx, dy),
            rotationPivot = rotationPivot?.translate(dx, dy),
        )
        is ScenePath -> copy(
            points = points.map { point -> point.translate(dx, dy) },
            commands = commands.map { command -> command.translate(dx, dy) },
        )
    }

    private fun ScenePathCommand.translate(
        dx: Float,
        dy: Float,
    ): ScenePathCommand = when (this) {
        is ScenePathCommand.MoveTo -> copy(point = point.translate(dx, dy))
        is ScenePathCommand.LineTo -> copy(point = point.translate(dx, dy))
        is ScenePathCommand.QuadraticTo -> copy(
            control = control.translate(dx, dy),
            end = end.translate(dx, dy),
        )
        is ScenePathCommand.CubicTo -> copy(
            control1 = control1.translate(dx, dy),
            control2 = control2.translate(dx, dy),
            end = end.translate(dx, dy),
        )
        is ScenePathCommand.ArcTo -> copy(end = end.translate(dx, dy))
    }

    private fun ScenePoint.translate(
        dx: Float,
        dy: Float,
    ): ScenePoint = ScenePoint(x + dx, y + dy)

    private fun <T> layoutError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Layout(message))

    private data class MindmapNodeVisual(
        val renderedText: MermaidRenderedText,
        val textMetrics: TextMetrics,
        val kind: SceneShapeKind,
        val shapeLayout: MermaidShapeLayout,
    )

    private data class MindmapNodeStyle(
        val fill: SceneColor,
        val stroke: SceneColor,
        val text: SceneColor,
        val strokeWidth: Float,
        val strokeGradient: SceneLinearGradient?,
    )

    private companion object {
        const val NEO_LOOK = "neo"
        const val TEXT_LINE_HEIGHT = 1.2f
        const val MINDMAP_COLOR_LIMIT = 12
        const val CYTOSCAPE_EDGE_RADIUS = 15f
        val TRANSPARENT = SceneColor(0x00000000)
        val MONOCHROME_REDUX_THEMES = setOf("redux", "redux-dark")
    }
}
