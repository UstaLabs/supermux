package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneAssetKind
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneNodeInteraction
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
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.subGraphTitleTotalMargin
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidEdgePathPort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidLineJumpPort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapePort
import com.swithun.cmpmermaid.core.swimlane.SwimlaneLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

internal class FlowchartLayout {
    fun layout(
        document: FlowchartDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val resolvedDocument = resolveImageMetrics(document, context)
        val nodeSizes = linkedMapOf<String, SceneSize>()
        val nodeTextSizes = linkedMapOf<String, SceneSize>()
        val nodeShapeLayouts = linkedMapOf<String, MermaidShapeLayout>()
        for ((id, node) in resolvedDocument.nodes) {
            val style = resolveStyle(node)
            val measured = try {
                context.textMetrics.measure(
                    TextMetricsRequest(
                        text = node.label,
                        fontSize = textFontSize(style, context),
                        maxWidth = context.options.wrappingWidth,
                        lineHeight = textLineHeight(style, context),
                        fontFamily = textFontFamily(style, context),
                        weight = style.fontWeight ?: SceneTextWeight.Normal,
                        spans = textSpans(node.label, node.labelSpans, style),
                    ),
                )
            } catch (failure: Exception) {
                return GMResult.Err(
                    MermaidError.Unexpected.from(failure, "Text measurement failed for '$id'"),
                )
            }
            val textSize = SceneSize(measured.width, measured.height)
            nodeTextSizes[id] = textSize
            val shapeLayout = when (
                val shapeResult = MermaidShapePort.layout(
                    node = node,
                    measuredLabel = textSize,
                    direction = resolvedDocument.direction,
                    defaultNodeStroke = context.theme.nodeStroke,
                    defaultFlowContainerStroke = context.theme.flowContainerStroke,
                )
            ) {
                is GMResult.Ok -> shapeResult.value
                is GMResult.Err -> return shapeResult
            }
            nodeShapeLayouts[id] = shapeLayout
            nodeSizes[id] = shapeLayout.size
        }
        val edgeLabelSizes = linkedMapOf<Int, SceneSize>()
        for ((index, edge) in resolvedDocument.edges.withIndex()) {
            val label = edge.label ?: continue
            val edgeStyle = resolveEdgeStyle(edge)
            val measured = try {
                context.textMetrics.measure(
                    TextMetricsRequest(
                        text = label,
                        fontSize = textFontSize(edgeStyle, context),
                        maxWidth = 180f,
                        lineHeight = textLineHeight(edgeStyle, context),
                        fontFamily = textFontFamily(edgeStyle, context),
                        weight = edgeStyle.fontWeight ?: SceneTextWeight.Normal,
                        spans = textSpans(label, edge.labelSpans, edgeStyle),
                    ),
                )
            } catch (failure: Exception) {
                return GMResult.Err(
                    MermaidError.Unexpected.from(
                        failure,
                        "Edge label measurement failed for '${edge.id}'",
                    ),
                )
            }
            edgeLabelSizes[index] = SceneSize(measured.width, measured.height)
        }
        val subgraphLabelSizes = linkedMapOf<String, SceneSize>()
        for (subgraph in resolvedDocument.subgraphs) {
            val style = subgraph.inlineStyle ?: FlowNodeStyle()
            val measured = try {
                context.textMetrics.measure(
                    TextMetricsRequest(
                        text = subgraph.label,
                        fontSize = textFontSize(style, context),
                        maxWidth = UNWRAPPED_LABEL_MAX_WIDTH,
                        lineHeight = textLineHeight(style, context),
                        fontFamily = textFontFamily(style, context),
                        weight = style.fontWeight ?: SceneTextWeight.Normal,
                        spans = textSpans(subgraph.label, subgraph.labelSpans, style),
                    ),
                )
            } catch (failure: Exception) {
                return GMResult.Err(
                    MermaidError.Unexpected.from(
                        failure,
                        "Subgraph label measurement failed for '${subgraph.id}'",
                    ),
                )
            }
            subgraphLabelSizes[subgraph.id] = SceneSize(measured.width, measured.height)
        }

        val placement = when (context.options.layout) {
            "dagre" -> when (
                val dagre = FlowDagreLayout.layout(
                    document = resolvedDocument,
                    nodeSizes = nodeSizes,
                    nodeShapeLayouts = nodeShapeLayouts,
                    edgeLabelSizes = edgeLabelSizes,
                    options = context.options,
                )
            ) {
                is GMResult.Ok -> dagre.value
                is GMResult.Err -> return dagre
            }
            "elk",
            "elk.layered",
            "elk.stress",
            "elk.force",
            "elk.mrtree",
            "elk.sporeOverlap",
            "elk.box",
            "elk.rectpacking",
            -> when (
                val elk = FlowElkLayout.layout(
                    document = resolvedDocument,
                    nodeSizes = nodeSizes,
                    nodeShapeLayouts = nodeShapeLayouts,
                    edgeLabelSizes = edgeLabelSizes,
                    subgraphLabelSizes = subgraphLabelSizes,
                    options = context.options,
                )
            ) {
                is GMResult.Ok -> elk.value
                is GMResult.Err -> return elk
            }
            "swimlane" -> when (
                val swimlane = SwimlaneLayout.layout(
                    document = resolvedDocument,
                    nodeSizes = nodeSizes,
                    nodeShapeLayouts = nodeShapeLayouts,
                    edgeLabelSizes = edgeLabelSizes,
                    options = context.options,
                )
            ) {
                is GMResult.Ok -> swimlane.value
                is GMResult.Err -> return swimlane
            }
            else -> return GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "${context.options.layout} layout",
                    message = "Native Flowchart has not connected the requested layout engine",
                ),
            )
        }
        val nodeBounds = placement.nodeBounds
        if (nodeBounds.size != resolvedDocument.nodes.size) {
            return GMResult.Err(MermaidError.Layout("Not every flowchart node was positioned"))
        }
        val subgraphBounds = placement.subgraphBounds

        val elements = mutableListOf<SceneElement>()
        when (
            val subgraphs = addSubgraphs(
                document = resolvedDocument,
                subgraphBounds = subgraphBounds,
                subgraphLabelSizes = subgraphLabelSizes,
                context = context,
                elements = elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return subgraphs
        }
        when (
            val edges = addEdges(
                document = resolvedDocument,
                routedEdges = placement.edges,
                edgeLabelSizes = edgeLabelSizes,
                context = context,
                elements = elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return edges
        }
        addNodes(resolvedDocument, nodeBounds, nodeTextSizes, nodeShapeLayouts, context, elements)
        when (val title = addDiagramTitle(resolvedDocument, context, elements)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return title
        }

        return GMResult.Ok(normalizeScene(elements, resolvedDocument, context))
    }

    private fun resolveImageMetrics(
        document: FlowchartDocument,
        context: MermaidRenderContext,
    ): FlowchartDocument = document.copy(
        nodes = document.nodes.mapValues { (_, node) ->
            val source = node.image ?: return@mapValues node
            val naturalSize = context.assetMetrics[source]
                ?.takeIf { size -> size.width > 0f && size.height > 0f }
                ?: return@mapValues node
            val aspectRatio = naturalSize.width / naturalSize.height
            val minimumImageWidth = context.options.wrappingWidth.takeIf {
                node.label.isNotEmpty()
            } ?: 0f
            val rawWidth = max(
                minimumImageWidth,
                node.assetWidth ?: naturalSize.width,
            )
            val constrained = node.constraint.equals("on", ignoreCase = true)
            val imageWidth = if (constrained && node.assetHeight != null) {
                node.assetHeight * aspectRatio
            } else {
                rawWidth
            }
            val imageHeight = if (constrained) {
                imageWidth / aspectRatio
            } else {
                node.assetHeight ?: naturalSize.height
            }
            node.copy(
                assetWidth = imageWidth,
                assetHeight = imageHeight,
            )
        },
    )

    private fun addSubgraphs(
        document: FlowchartDocument,
        subgraphBounds: Map<String, SceneRect>,
        subgraphLabelSizes: Map<String, SceneSize>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val subgraphsById = document.subgraphs.associateBy(FlowSubgraph::id)
        fun depth(subgraph: FlowSubgraph): Int {
            var current = subgraph.parentId
            var result = 0
            val visited = mutableSetOf<String>()
            while (current != null && visited.add(current)) {
                result += 1
                current = subgraphsById[current]?.parentId
            }
            return result
        }

        document.subgraphs
            .sortedBy(::depth)
            .forEachIndexed { index, subgraph ->
                val bounds = subgraphBounds[subgraph.id]
                    ?: return@forEachIndexed
                val style = subgraph.inlineStyle ?: FlowNodeStyle()
                val measured = subgraphLabelSizes[subgraph.id]
                    ?: return GMResult.Err(
                        MermaidError.Layout(
                            "Subgraph '${subgraph.id}' has no measured label",
                        ),
                    )
                if (context.options.layout == "swimlane" && subgraph.parentId == null) {
                    addSwimlane(
                        subgraph = subgraph,
                        bounds = bounds,
                        measured = measured,
                        style = style,
                        direction = document.direction,
                        context = context,
                        zIndex = index,
                        elements = elements,
                    )
                    return@forEachIndexed
                }
                val renderedWidth = max(bounds.width, measured.width + subgraph.padding)
                val renderedBounds = SceneRect(
                    left = bounds.center.x - renderedWidth / 2f,
                    top = bounds.top,
                    right = bounds.center.x + renderedWidth / 2f,
                    bottom = bounds.bottom,
                )
                elements += SceneShape(
                    id = "subgraph_${subgraph.id}",
                    bounds = renderedBounds,
                    kind = if (subgraph.cornerRadius > 0f) {
                        SceneShapeKind.RoundedRectangle
                    } else {
                        SceneShapeKind.Rectangle
                    },
                    fill = style.fill ?: context.theme.colorFill(subgraph.colorIndex),
                    stroke = style.stroke ?: context.theme.colorStroke(subgraph.colorIndex),
                    strokeWidth = style.strokeWidth ?: 1f,
                    strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
                    dashIntervals = style.dashIntervals,
                    cornerRadius = subgraph.cornerRadius,
                    shadow = context.theme.dropShadow.takeIf { subgraph.look == "neo" },
                    zIndex = index,
                )
                elements += SceneText(
                    text = subgraph.label,
                    bounds = SceneRect(
                        left = renderedBounds.center.x - measured.width / 2f,
                        top = renderedBounds.top + context.options.subGraphTitleTopMargin,
                        right = renderedBounds.center.x + measured.width / 2f,
                        bottom = renderedBounds.top +
                            context.options.subGraphTitleTopMargin +
                            measured.height,
                    ),
                    color = style.text ?: context.theme.groupText,
                    fontSize = textFontSize(style, context),
                    lineHeight = textLineHeight(style, context),
                    fontFamily = textFontFamily(style, context),
                    weight = style.fontWeight ?: SceneTextWeight.Normal,
                    spans = textSpans(subgraph.label, subgraph.labelSpans, style),
                    horizontalAlignment = style.textAlignment ?: SceneTextAlignment.Center,
                    zIndex = index + 1,
                )
            }
        return GMResult.Ok(Unit)
    }

    /**
     * Mermaid.js 12.0.0:
     * rendering-util/rendering-elements/clusters/swimlane.js -> swimlane.
     */
    private fun addSwimlane(
        subgraph: FlowSubgraph,
        bounds: SceneRect,
        measured: SceneSize,
        style: FlowNodeStyle,
        direction: FlowDirection,
        context: MermaidRenderContext,
        zIndex: Int,
        elements: MutableList<SceneElement>,
    ) {
        val width = max(bounds.width, measured.width + subgraph.padding)
        val renderedBounds = SceneRect(
            left = bounds.center.x - width / 2f,
            top = bounds.top,
            right = bounds.center.x + width / 2f,
            bottom = bounds.bottom,
        )
        val paletteEnabled = context.options.themeName in COLOR_THEMES
        val paletteFill = context.theme.colorFill(subgraph.colorIndex)
        val fill = style.fill ?: if (paletteEnabled) paletteFill else context.theme.groupFill
        val bodyFill = style.fill ?: if (paletteEnabled) paletteFill else Transparent
        val stroke = style.stroke ?: context.theme.colorStroke(subgraph.colorIndex)
        val leftTitle = direction == FlowDirection.LeftToRight
        val titleSize = if (leftTitle) {
            min(renderedBounds.width, max(measured.height, 0f))
        } else {
            min(renderedBounds.height, max(measured.height, 0f))
        }
        val titleBounds = if (leftTitle) {
            SceneRect(
                left = renderedBounds.left,
                top = renderedBounds.top,
                right = renderedBounds.left + titleSize,
                bottom = renderedBounds.bottom,
            )
        } else {
            SceneRect(
                left = renderedBounds.left,
                top = renderedBounds.top,
                right = renderedBounds.right,
                bottom = renderedBounds.top + titleSize,
            )
        }
        val bodyBounds = if (leftTitle) {
            SceneRect(
                left = titleBounds.right,
                top = renderedBounds.top,
                right = renderedBounds.right,
                bottom = renderedBounds.bottom,
            )
        } else {
            SceneRect(
                left = renderedBounds.left,
                top = titleBounds.bottom,
                right = renderedBounds.right,
                bottom = renderedBounds.bottom,
            )
        }
        elements += SceneShape(
            id = "subgraph_${subgraph.id}_body",
            bounds = bodyBounds,
            kind = SceneShapeKind.Rectangle,
            fill = bodyFill,
            stroke = stroke,
            strokeWidth = style.strokeWidth ?: 1f,
            strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
            dashIntervals = style.dashIntervals,
            zIndex = zIndex,
        )
        elements += SceneShape(
            id = "subgraph_${subgraph.id}",
            bounds = titleBounds,
            kind = SceneShapeKind.Rectangle,
            fill = fill,
            stroke = stroke,
            strokeWidth = style.strokeWidth ?: 1f,
            strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
            dashIntervals = style.dashIntervals,
            zIndex = zIndex + 1,
        )
        val labelCenter = titleBounds.center
        val labelBounds = SceneRect(
            left = labelCenter.x - measured.width / 2f,
            top = labelCenter.y - measured.height / 2f,
            right = labelCenter.x + measured.width / 2f,
            bottom = labelCenter.y + measured.height / 2f,
        )
        elements += SceneText(
            text = subgraph.label,
            bounds = labelBounds,
            color = style.text ?: context.theme.groupText,
            fontSize = textFontSize(style, context),
            lineHeight = textLineHeight(style, context),
            fontFamily = textFontFamily(style, context),
            weight = style.fontWeight ?: SceneTextWeight.Normal,
            spans = textSpans(subgraph.label, subgraph.labelSpans, style),
            horizontalAlignment = style.textAlignment ?: SceneTextAlignment.Center,
            rotationDegrees = if (leftTitle) -90f else 0f,
            rotationPivot = labelCenter.takeIf { leftTitle },
            zIndex = zIndex + 2,
        )
    }

    private fun addEdges(
        document: FlowchartDocument,
        routedEdges: Map<Int, FlowRoutedEdge>,
        edgeLabelSizes: Map<Int, SceneSize>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val paths = mutableListOf<ScenePath>()
        val labels = mutableListOf<SceneElement>()

        document.edges.forEachIndexed { index, edge ->
            if (edge.invisible) return@forEachIndexed
            val routed = routedEdges[index] ?: return@forEachIndexed
            val edgeStyle = resolveEdgeStyle(edge)
            val curve = routed.curveOverride ?: edge.curve ?: context.options.curve
            val commands = when (
                val generatedPath = MermaidEdgePathPort.generate(
                    points = routed.points,
                    curve = curve,
                    arrowStart = edge.arrowStart,
                    arrowEnd = edge.arrowEnd,
                )
            ) {
                is GMResult.Ok -> generatedPath.value
                is GMResult.Err -> return generatedPath
            }
            paths += ScenePath(
                id = edge.id,
                points = routed.points,
                commands = commands,
                color = edgeStyle.stroke ?: context.theme.edge,
                strokeWidth = edgeStyle.strokeWidth ?: edge.thickness ?: context.theme.strokeWidth,
                strokePattern = if (edge.animated) SceneStrokePattern.Dashed else edgeStyle.strokePattern ?: edge.pattern,
                arrowStart = edge.arrowStart,
                arrowEnd = edge.arrowEnd,
                curve = curve,
                look = edge.look,
                animated = edge.animated,
                animationDurationMillis = edge.animationDurationMillis,
                dashIntervals = if (edge.animated) {
                    edgeStyle.dashIntervals.takeIf(List<Float>::isNotEmpty)
                        ?: listOf(9f, 5f)
                } else {
                    edgeStyle.dashIntervals
                },
            )

            val label = edge.label ?: return@forEachIndexed
            val labelSize = edgeLabelSizes[index] ?: return@forEachIndexed
            val center = routed.labelAnchor.translate(
                dx = 0f,
                dy = context.options.subGraphTitleTotalMargin / 2f,
            )
            val backgroundPadding = 2f
            val labelBounds = SceneRect(
                left = center.x - labelSize.width / 2f - backgroundPadding,
                top = center.y - labelSize.height / 2f - backgroundPadding,
                right = center.x + labelSize.width / 2f + backgroundPadding,
                bottom = center.y + labelSize.height / 2f + backgroundPadding,
            )
            labels += SceneShape(
                id = "${edge.id}_label_background",
                bounds = labelBounds,
                kind = SceneShapeKind.Rectangle,
                fill = edgeStyle.labelBackground ?: context.theme.edgeLabelFill,
                stroke = SceneColor(0x00000000),
                strokeWidth = 0f,
                cornerRadius = 0f,
                zIndex = 6,
            )
            labels += SceneText(
                text = label,
                bounds = labelBounds,
                color = edgeStyle.text ?: context.theme.nodeText,
                fontSize = textFontSize(edgeStyle, context),
                lineHeight = textLineHeight(edgeStyle, context),
                fontFamily = textFontFamily(edgeStyle, context),
                weight = edgeStyle.fontWeight ?: SceneTextWeight.Normal,
                spans = textSpans(label, edge.labelSpans, edgeStyle),
                horizontalAlignment = edgeStyle.textAlignment ?: SceneTextAlignment.Center,
                zIndex = 7,
            )
        }
        elements += when {
            context.options.layout == "elk" || context.options.layout.startsWith("elk.") ->
                MermaidLineJumpPort.apply(paths, context.options.elk.lineHops)
            context.options.layout == "swimlane" ->
                MermaidLineJumpPort.apply(paths, context.options.swimlane.lineHops)
            else -> paths
        }
        elements += labels
        return GMResult.Ok(Unit)
    }

    private fun addNodes(
        document: FlowchartDocument,
        nodeBounds: Map<String, SceneRect>,
        nodeTextSizes: Map<String, SceneSize>,
        nodeShapeLayouts: Map<String, MermaidShapeLayout>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        document.nodes.forEach { (id, node) ->
            val bounds = nodeBounds.getValue(id)
            val style = resolveStyle(node)
            val shapeLayout = nodeShapeLayouts.getValue(id)
            val isIconShape = node.shape in ICON_SHAPES
            val fill = style.fill
                ?: node.colorIndex?.let(context.theme::colorFill)
                ?: context.theme.nodeFill
            val stroke = when {
                node.shape == SceneShapeKind.Icon -> SceneColor(0x00000000)
                isIconShape -> fill
                else -> style.stroke
                    ?: node.colorIndex?.let(context.theme::colorStroke)
                    ?: context.theme.nodeStroke
            }
            elements += SceneShape(
                id = id,
                bounds = bounds,
                kind = node.shape,
                geometry = shapeLayout.geometry,
                fill = if (node.shape == SceneShapeKind.Icon) {
                    SceneColor(0x00000000)
                } else {
                    fill
                },
                stroke = stroke,
                strokeWidth = style.strokeWidth ?: context.theme.strokeWidth,
                strokePattern = style.strokePattern ?: com.swithun.cmpmermaid.core.SceneStrokePattern.Solid,
                dashIntervals = style.dashIntervals,
                cornerRadius = 9f,
                shadow = context.theme.dropShadow.takeIf {
                    node.look == "neo" && !isIconShape
                },
            )
            shapeLayout.assetOffset?.let { offset ->
                val isIcon = node.image == null && node.icon != null
                val source = if (isIcon) node.icon else node.image
                if (source != null) {
                    val assetWidth = if (isIcon) {
                        max(node.assetWidth ?: 48f, node.assetHeight ?: 48f)
                    } else {
                        node.assetWidth ?: 48f
                    }
                    val assetHeight = if (isIcon) {
                        assetWidth
                    } else {
                        node.assetHeight ?: 48f
                    }
                    val assetCenter = ScenePoint(
                        x = bounds.center.x + offset.x,
                        y = bounds.center.y + offset.y,
                    )
                    elements += SceneAsset(
                        id = "${id}_asset",
                        source = source,
                        bounds = SceneRect(
                            left = assetCenter.x - assetWidth / 2f,
                            top = assetCenter.y - assetHeight / 2f,
                            right = assetCenter.x + assetWidth / 2f,
                            bottom = assetCenter.y + assetHeight / 2f,
                        ),
                        kind = if (isIcon) SceneAssetKind.Icon else SceneAssetKind.Image,
                        tint = if (isIcon) {
                            style.stroke ?: context.theme.nodeStroke
                        } else {
                            null
                        },
                    )
                }
            }
            if (shapeLayout.showsLabel) {
                val textSize = nodeTextSizes.getValue(id)
                val center = bounds.center
                val labelCenter = ScenePoint(
                    x = center.x + shapeLayout.labelOffset.x,
                    y = center.y + shapeLayout.labelOffset.y,
                )
                val labelBounds = SceneRect(
                    left = labelCenter.x - textSize.width / 2f,
                    top = labelCenter.y - textSize.height / 2f,
                    right = labelCenter.x + textSize.width / 2f,
                    bottom = labelCenter.y + textSize.height / 2f,
                )
                if (node.label.isNotEmpty() && (node.icon != null || node.image != null)) {
                    elements += SceneShape(
                        id = "${id}_asset_label_background",
                        bounds = labelBounds,
                        kind = SceneShapeKind.Rectangle,
                        fill = style.labelBackground
                            ?: context.theme.edgeLabelFill.withAlpha(0x80),
                        stroke = SceneColor(0x00000000),
                        strokeWidth = 0f,
                        cornerRadius = 0f,
                        zIndex = 19,
                    )
                }
                elements += SceneText(
                    text = node.label,
                    bounds = labelBounds,
                    color = style.text ?: context.theme.nodeText,
                    fontSize = textFontSize(style, context),
                    lineHeight = textLineHeight(style, context),
                    fontFamily = textFontFamily(style, context),
                    weight = style.fontWeight ?: SceneTextWeight.Normal,
                    spans = textSpans(node.label, node.labelSpans, style),
                    horizontalAlignment = style.textAlignment ?: SceneTextAlignment.Center,
                )
            }
        }
    }

    private fun resolveStyle(node: FlowNode): FlowNodeStyle =
        node.inlineStyle ?: FlowNodeStyle()

    private fun resolveEdgeStyle(edge: FlowEdge): FlowNodeStyle =
        edge.inlineStyle ?: FlowNodeStyle()

    private fun SceneColor.withAlpha(alpha: Int): SceneColor =
        SceneColor((argb and 0x00FFFFFFL) or (alpha.toLong() shl 24))

    private fun textFontSize(
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): Float {
        val inherited = context.options.fontSize ?: context.theme.fontSize
        style.fontSize?.let { return it }
        val scale = style.fontSizeScale ?: return inherited
        var resolved = inherited
        repeat(if (context.options.htmlLabels) 3 else 2) {
            resolved *= scale
        }
        return resolved
    }

    private fun textLineHeight(
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): Float {
        val inherited = if (context.options.htmlLabels) 1.5f else 1.1f
        val fontSize = textFontSize(style, context)
        return style.lineHeightPixels
            ?.div(fontSize)
            ?.takeIf { it.isFinite() && it > 0f }
            ?: style.lineHeightMultiplier
            ?: inherited
    }

    private fun textFontFamily(
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): String = style.fontFamily ?: context.theme.fontFamily

    private fun textSpans(
        text: String,
        spans: List<SceneTextSpan>,
        style: FlowNodeStyle,
    ): List<SceneTextSpan> {
        if (
            text.isEmpty() ||
            (style.italic != true && !style.underline && !style.lineThrough)
        ) {
            return spans
        }
        return listOf(
            SceneTextSpan(
                start = 0,
                end = text.length,
                italic = style.italic == true,
                underline = style.underline,
                lineThrough = style.lineThrough,
            ),
        ) + spans
    }

    /**
     * Native equivalent of utils.insertTitle() in Mermaid's unified Flowchart
     * renderer. Compose uses top-left text bounds rather than an SVG baseline,
     * so the measured text height is applied above the same top margin.
     */
    private fun addDiagramTitle(
        document: FlowchartDocument,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val title = document.title?.takeIf(String::isNotBlank)
            ?: return GMResult.Ok(Unit)
        val graphBounds = elements
            .mapNotNull(::elementBounds)
            .reduceOrNull(SceneRect::union)
            ?: return GMResult.Ok(Unit)
        val measured = try {
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = title,
                    fontSize = TITLE_FONT_SIZE,
                    maxWidth = graphBounds.width.coerceAtLeast(1f),
                    lineHeight = 1f,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                ),
            )
        } catch (failure: Exception) {
            return GMResult.Err(
                MermaidError.Unexpected.from(failure, "Title measurement failed"),
            )
        }
        val centerX = graphBounds.left + graphBounds.width / 2f
        val bottom = graphBounds.top - context.options.titleTopMargin
        elements += SceneText(
            text = title,
            bounds = SceneRect(
                left = centerX - measured.width / 2f,
                top = bottom - measured.height,
                right = centerX + measured.width / 2f,
                bottom = bottom,
            ),
            color = context.theme.nodeText,
            fontSize = TITLE_FONT_SIZE,
            lineHeight = 1f,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            zIndex = 30,
        )
        return GMResult.Ok(Unit)
    }

    private fun normalizeScene(
        elements: List<SceneElement>,
        document: FlowchartDocument,
        context: MermaidRenderContext,
    ): MermaidScene {
        val bounds = elements
            .mapNotNull(::elementBounds)
            .reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val padding = context.options.diagramPadding
        val dx = padding - bounds.left
        val dy = padding - bounds.top
        val translated = elements.map { it.translate(dx, dy) }
        val translatedNodeBounds = translated
            .filterIsInstance<SceneShape>()
            .associate { shape -> shape.id to shape.bounds }
        val interactions = document.nodes.values.mapNotNull { node ->
            if (
                node.link == null &&
                node.tooltip == null &&
                node.callbackName == null
            ) {
                return@mapNotNull null
            }
            val nodeBounds = translatedNodeBounds[node.id] ?: return@mapNotNull null
            SceneNodeInteraction(
                nodeId = node.id,
                bounds = nodeBounds,
                link = node.link,
                linkTarget = node.linkTarget,
                tooltip = node.tooltip,
                callbackName = node.callbackName,
                callbackArgs = node.callbackArgs,
            )
        }
        return MermaidScene(
            width = bounds.width + padding * 2f,
            height = bounds.height + padding * 2f,
            background = context.theme.background,
            elements = translated.sortedBy(SceneElement::zIndex),
            title = document.title,
            accessibilityTitle = document.accessibilityTitle,
            accessibilityDescription = document.accessibilityDescription,
            interactions = interactions,
        )
    }

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneAsset -> element.bounds
        is SceneShape -> element.bounds
        is SceneText -> rotatedTextBounds(element)
        is ScenePath -> {
            val first = element.points.firstOrNull()
            if (first == null) null else element.points.drop(1).fold(SceneRect(first.x, first.y, first.x, first.y)) { bounds, point ->
                SceneRect(
                    min(bounds.left, point.x),
                    min(bounds.top, point.y),
                    max(bounds.right, point.x),
                    max(bounds.bottom, point.y),
                )
            }
        }
    }

    private fun rotatedTextBounds(element: SceneText): SceneRect {
        if (element.rotationDegrees == 0f) return element.bounds
        val pivot = element.rotationPivot ?: element.bounds.center
        val radians = element.rotationDegrees * PI.toFloat() / 180f
        val cosine = cos(radians)
        val sine = sin(radians)
        val corners = listOf(
            ScenePoint(element.bounds.left, element.bounds.top),
            ScenePoint(element.bounds.right, element.bounds.top),
            ScenePoint(element.bounds.right, element.bounds.bottom),
            ScenePoint(element.bounds.left, element.bounds.bottom),
        ).map { point ->
            val x = point.x - pivot.x
            val y = point.y - pivot.y
            ScenePoint(
                x = pivot.x + x * cosine - y * sine,
                y = pivot.y + x * sine + y * cosine,
            )
        }
        return SceneRect(
            left = corners.minOf(ScenePoint::x),
            top = corners.minOf(ScenePoint::y),
            right = corners.maxOf(ScenePoint::x),
            bottom = corners.maxOf(ScenePoint::y),
        )
    }

    private fun SceneElement.translate(dx: Float, dy: Float): SceneElement = when (this) {
        is SceneAsset -> copy(bounds = bounds.translate(dx, dy))
        is SceneShape -> copy(bounds = bounds.translate(dx, dy))
        is SceneText -> copy(
            bounds = bounds.translate(dx, dy),
            rotationPivot = rotationPivot?.translate(dx, dy),
        )
        is ScenePath -> copy(
            points = points.map { ScenePoint(it.x + dx, it.y + dy) },
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

    private fun ScenePoint.translate(dx: Float, dy: Float): ScenePoint =
        ScenePoint(x + dx, y + dy)

    private companion object {
        val ICON_SHAPES = setOf(
            SceneShapeKind.Icon,
            SceneShapeKind.IconCircle,
            SceneShapeKind.IconSquare,
            SceneShapeKind.IconRounded,
        )
        const val TITLE_FONT_SIZE = 18f
        const val UNWRAPPED_LABEL_MAX_WIDTH = 100_000f
        val COLOR_THEMES = setOf("redux-color", "redux-dark-color")
        val Transparent = SceneColor(0x00000000)
    }
}
