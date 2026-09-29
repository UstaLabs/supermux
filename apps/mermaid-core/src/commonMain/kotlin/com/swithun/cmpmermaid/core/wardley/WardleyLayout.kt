package com.swithun.cmpmermaid.core.wardley

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidWardleyOptions
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyBuildResult
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyDocument
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyFlow
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleyNode
import com.swithun.cmpmermaid.core.wardley.upstream.mermaid.WardleySourceStrategy
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/wardley/wardleyRenderer.ts -> draw.
 */
internal class WardleyLayout {
    fun layout(
        document: WardleyDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val config = context.options.wardley
        when (val validation = validate(config)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        val theme = when (val resolved = resolveTheme(context)) {
            is GMResult.Ok -> resolved.value
            is GMResult.Err -> return resolved
        }
        val width = document.data.size?.width ?: config.width
        val height = document.data.size?.height ?: config.height
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) {
            return configurationError("diagram size must be positive and finite")
        }
        val chartWidth = width - config.padding * 2f
        val chartHeight = height - config.padding * 2f
        if (chartWidth <= 0f || chartHeight <= 0f) {
            return configurationError("padding leaves no drawable chart area")
        }

        val projection = Projection(
            width = width,
            height = height,
            padding = config.padding,
        )
        val elements = mutableListOf<SceneElement>()
        drawBackground(elements, width, height, theme)
        drawTitle(elements, document.diagramTitle, width, config, theme, context)
        drawAxes(elements, document.data, projection, config, theme, context)
        if (config.showGrid) {
            drawGrid(elements, projection, theme)
        }
        val positions = createPositions(document.data, projection)
        drawPipelines(elements, document.data, positions, config, theme)
        drawLinks(elements, document.data, positions, config, theme, context)
        drawTrends(elements, document.data, positions, projection, config, theme)
        drawNodes(elements, document.data, positions, config, theme, context)
        drawAnnotations(elements, document.data, projection, config, theme, context)
        drawNotes(elements, document.data, projection, theme, context)
        drawForces(elements, document.data, projection, theme, context)

        return GMResult.Ok(
            MermaidScene(
                width = width,
                height = height,
                background = theme.backgroundColor,
                elements = elements,
                title = document.diagramTitle,
                accessibilityTitle = document.accessibilityTitle,
                accessibilityDescription = document.accessibilityDescription,
                viewportSizing = if (config.useMaxWidth) {
                    MermaidSceneViewportSizing.ResponsiveMaxWidth
                } else {
                    MermaidSceneViewportSizing.Intrinsic
                },
            ),
        )
    }

    private fun drawBackground(
        elements: MutableList<SceneElement>,
        width: Float,
        height: Float,
        theme: WardleyTheme,
    ) {
        elements += SceneShape(
            id = "wardley-background",
            bounds = SceneRect(0f, 0f, width, height),
            kind = SceneShapeKind.Rectangle,
            fill = theme.backgroundColor,
            stroke = TRANSPARENT,
            strokeWidth = 0f,
            cornerRadius = 0f,
            zIndex = BACKGROUND_Z,
        )
    }

    private fun drawTitle(
        elements: MutableList<SceneElement>,
        title: String?,
        width: Float,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ) {
        title ?: return
        elements += text(
            id = "wardley-title",
            value = title,
            x = width / 2f,
            y = config.padding / 2f,
            color = theme.axisTextColor,
            fontSize = config.axisFontSize * 1.05f,
            weight = SceneTextWeight.Bold,
            alignment = SceneTextAlignment.Center,
            verticalAnchor = VerticalAnchor.Center,
            context = context,
            zIndex = TITLE_Z,
        )
    }

    private fun drawAxes(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        projection: Projection,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ) {
        elements += line(
            id = "wardley-axis-x",
            start = ScenePoint(projection.left, projection.bottom),
            end = ScenePoint(projection.right, projection.bottom),
            color = theme.axisColor,
            zIndex = AXIS_Z,
        )
        elements += line(
            id = "wardley-axis-y",
            start = ScenePoint(projection.left, projection.top),
            end = ScenePoint(projection.left, projection.bottom),
            color = theme.axisColor,
            zIndex = AXIS_Z,
        )
        elements += text(
            id = "wardley-axis-label-x",
            value = data.axes.xLabel ?: DEFAULT_X_LABEL,
            x = projection.left + projection.chartWidth / 2f,
            y = projection.height - config.padding / 4f,
            color = theme.axisTextColor,
            fontSize = config.axisFontSize,
            weight = SceneTextWeight.Bold,
            alignment = SceneTextAlignment.Center,
            verticalAnchor = VerticalAnchor.Center,
            context = context,
            zIndex = AXIS_LABEL_Z,
        )
        val yLabelX = config.padding / 3f
        val yLabelY = config.padding + projection.chartHeight / 2f
        elements += text(
            id = "wardley-axis-label-y",
            value = data.axes.yLabel ?: DEFAULT_Y_LABEL,
            x = yLabelX,
            y = yLabelY,
            color = theme.axisTextColor,
            fontSize = config.axisFontSize,
            weight = SceneTextWeight.Bold,
            alignment = SceneTextAlignment.Center,
            verticalAnchor = VerticalAnchor.Center,
            rotation = -90f,
            context = context,
            zIndex = AXIS_LABEL_Z,
        )
        drawStages(elements, data, projection, config, theme, context)
    }

    private fun drawStages(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        projection: Projection,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ) {
        val stages = data.axes.stages?.takeIf(List<String>::isNotEmpty) ?: DEFAULT_STAGES
        val boundaries = data.axes.stageBoundaries
        val positions = if (boundaries != null && boundaries.size == stages.size) {
            var previous = 0f
            boundaries.map { boundary ->
                StagePosition(start = previous, end = boundary).also {
                    previous = boundary
                }
            }
        } else {
            val stageWidth = 1f / stages.size
            stages.indices.map { index ->
                StagePosition(
                    start = index * stageWidth,
                    end = (index + 1) * stageWidth,
                )
            }
        }
        stages.forEachIndexed { index, stage ->
            val position = positions[index]
            val startX = projection.left + position.start * projection.chartWidth
            val endX = projection.left + position.end * projection.chartWidth
            if (index > 0) {
                elements += line(
                    id = "wardley-stage-divider-$index",
                    start = ScenePoint(startX, projection.top),
                    end = ScenePoint(startX, projection.bottom),
                    color = BLACK.withAlpha(0.8f),
                    strokePattern = SceneStrokePattern.Dashed,
                    dashIntervals = listOf(5f, 5f),
                    zIndex = STAGE_Z,
                )
            }
            elements += text(
                id = "wardley-stage-label-$index",
                value = stage,
                x = (startX + endX) / 2f,
                y = projection.height - config.padding / 1.5f,
                color = theme.axisTextColor,
                fontSize = config.axisFontSize - 2f,
                alignment = SceneTextAlignment.Center,
                verticalAnchor = VerticalAnchor.Baseline,
                context = context,
                zIndex = STAGE_LABEL_Z,
            )
        }
    }

    private fun drawGrid(
        elements: MutableList<SceneElement>,
        projection: Projection,
        theme: WardleyTheme,
    ) {
        repeat(3) { index ->
            val ratio = (index + 1) / 4f
            val x = projection.left + projection.chartWidth * ratio
            val y = projection.bottom - projection.chartHeight * ratio
            elements += line(
                id = "wardley-grid-v-${index + 1}",
                start = ScenePoint(x, projection.top),
                end = ScenePoint(x, projection.bottom),
                color = theme.gridColor,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(2f, 6f),
                zIndex = GRID_Z,
            )
            elements += line(
                id = "wardley-grid-h-${index + 1}",
                start = ScenePoint(projection.left, y),
                end = ScenePoint(projection.right, y),
                color = theme.gridColor,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(2f, 6f),
                zIndex = GRID_Z,
            )
        }
    }

    private fun createPositions(
        data: WardleyBuildResult,
        projection: Projection,
    ): LinkedHashMap<String, NodePosition> = linkedMapOf<String, NodePosition>().apply {
        data.nodes.forEach { node ->
            val x = node.x ?: return@forEach
            val y = node.y ?: return@forEach
            put(
                node.id,
                NodePosition(
                    x = projection.x(x),
                    y = projection.y(y),
                    node = node,
                ),
            )
        }
    }

    private fun drawPipelines(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        positions: MutableMap<String, NodePosition>,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
    ) {
        val squareSize = config.nodeRadius * 1.6f
        data.pipelines.forEachIndexed { pipelineIndex, pipeline ->
            val components = pipeline.componentIds.mapNotNull { id -> positions[id] }
                .sortedBy { position -> position.node.x }
            components.zipWithNext().forEachIndexed { index, (current, next) ->
                elements += line(
                    id = "wardley-pipeline-link-$pipelineIndex-$index",
                    start = ScenePoint(current.x, current.y),
                    end = ScenePoint(next.x, next.y),
                    color = theme.linkStroke,
                    strokePattern = SceneStrokePattern.Dashed,
                    dashIntervals = listOf(4f, 4f),
                    zIndex = PIPELINE_LINK_Z,
                )
            }
            if (components.isEmpty()) {
                return@forEachIndexed
            }
            val minX = components.minOf(NodePosition::x)
            val maxX = components.maxOf(NodePosition::x)
            val y = components.last().y
            val boxHeight = config.nodeRadius * 4f
            val boxTop = y - boxHeight / 2f
            positions[pipeline.nodeId]?.let { parent ->
                parent.x = (minX + maxX) / 2f
                parent.y = boxTop - squareSize / 6f
            }
            elements += SceneShape(
                id = "wardley-pipeline-box-$pipelineIndex",
                bounds = SceneRect(
                    left = minX - PIPELINE_PADDING,
                    top = boxTop,
                    right = maxX + PIPELINE_PADDING,
                    bottom = boxTop + boxHeight,
                ),
                kind = SceneShapeKind.RoundedRectangle,
                fill = TRANSPARENT,
                stroke = theme.axisColor,
                strokeWidth = 1.5f,
                cornerRadius = 4f,
                zIndex = PIPELINE_Z,
            )
        }
    }

    private fun drawLinks(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        positions: Map<String, NodePosition>,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ) {
        val pipelineMap = data.pipelines.associate { pipeline ->
            pipeline.nodeId to pipeline.componentIds.toSet()
        }
        val squareSize = config.nodeRadius * 1.6f
        data.links.forEachIndexed { index, link ->
            val source = positions[link.source] ?: return@forEachIndexed
            val target = positions[link.target] ?: return@forEachIndexed
            if (pipelineMap[link.target]?.contains(link.source) == true) {
                return@forEachIndexed
            }
            val dx = target.x - source.x
            val dy = target.y - source.y
            val distance = sqrt(dx * dx + dy * dy)
            if (!distance.isFinite() || distance <= 0f) {
                return@forEachIndexed
            }
            val sourceRadius = if (source.node.isPipelineParent) {
                squareSize / sqrt(2f)
            } else {
                config.nodeRadius
            }
            val targetRadius = if (target.node.isPipelineParent) {
                squareSize / sqrt(2f)
            } else {
                config.nodeRadius
            }
            val start = ScenePoint(
                x = source.x + dx / distance * sourceRadius,
                y = source.y + dy / distance * sourceRadius,
            )
            val end = ScenePoint(
                x = target.x - dx / distance * targetRadius,
                y = target.y - dy / distance * targetRadius,
            )
            elements += line(
                id = "wardley-link-$index",
                start = start,
                end = end,
                color = theme.linkStroke,
                strokePattern = if (link.dashed) {
                    SceneStrokePattern.Dashed
                } else {
                    SceneStrokePattern.Solid
                },
                dashIntervals = if (link.dashed) listOf(6f, 6f) else emptyList(),
                arrowStart = if (
                    link.flow == WardleyFlow.Backward ||
                    link.flow == WardleyFlow.Bidirectional
                ) {
                    SceneArrowHead.Triangle
                } else {
                    SceneArrowHead.None
                },
                arrowEnd = if (
                    link.flow == WardleyFlow.Forward ||
                    link.flow == WardleyFlow.Bidirectional
                ) {
                    SceneArrowHead.Triangle
                } else {
                    SceneArrowHead.None
                },
                markerBackground = theme.backgroundColor,
                zIndex = LINK_Z,
            )
            link.label?.let { label ->
                val midX = (source.x + target.x) / 2f
                val midY = (source.y + target.y) / 2f
                val labelX = midX + dy / distance * LINK_LABEL_OFFSET
                val labelY = midY - dx / distance * LINK_LABEL_OFFSET
                var angle = atan2(dy, dx) * 180f / PI.toFloat()
                if (angle > 90f || angle < -90f) {
                    angle += 180f
                }
                elements += text(
                    id = "wardley-link-label-$index",
                    value = label,
                    x = labelX,
                    y = labelY,
                    color = theme.axisTextColor,
                    fontSize = config.labelFontSize,
                    alignment = SceneTextAlignment.Center,
                    verticalAnchor = VerticalAnchor.Center,
                    rotation = angle,
                    context = context,
                    zIndex = LINK_LABEL_Z,
                )
            }
        }
    }

    private fun drawTrends(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        positions: Map<String, NodePosition>,
        projection: Projection,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
    ) {
        data.trends.forEachIndexed { index, trend ->
            val origin = positions[trend.nodeId] ?: return@forEachIndexed
            val target = ScenePoint(
                x = projection.x(trend.targetX),
                y = projection.y(trend.targetY),
            )
            val dx = target.x - origin.x
            val dy = target.y - origin.y
            val distance = sqrt(dx * dx + dy * dy)
            val shortenBy = config.nodeRadius + 2f
            val end = if (distance > shortenBy) {
                ScenePoint(
                    x = target.x - dx / distance * shortenBy,
                    y = target.y - dy / distance * shortenBy,
                )
            } else {
                target
            }
            elements += line(
                id = "wardley-trend-$index",
                start = ScenePoint(origin.x, origin.y),
                end = end,
                color = theme.evolutionStroke,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(4f, 4f),
                arrowEnd = SceneArrowHead.Triangle,
                markerBackground = theme.backgroundColor,
                zIndex = TREND_Z,
            )
        }
    }

    private fun drawNodes(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        positions: Map<String, NodePosition>,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ) {
        val squareSize = config.nodeRadius * 1.6f
        data.nodes.forEachIndexed { index, node ->
            val position = positions[node.id] ?: return@forEachIndexed
            drawStrategy(elements, index, node, position, config, theme)
            when {
                node.isPipelineParent -> elements += SceneShape(
                    id = "wardley-node-$index",
                    bounds = centeredRect(position.x, position.y, squareSize, squareSize),
                    kind = SceneShapeKind.Rectangle,
                    fill = theme.componentFill,
                    stroke = theme.componentStroke,
                    strokeWidth = 1f,
                    cornerRadius = 0f,
                    zIndex = NODE_Z,
                )
                node.sourceStrategy != WardleySourceStrategy.Market &&
                    node.className != "anchor" -> elements += circle(
                    id = "wardley-node-$index",
                    x = position.x,
                    y = position.y,
                    radius = config.nodeRadius,
                    fill = theme.componentFill,
                    stroke = theme.componentStroke,
                    strokeWidth = 1f,
                    zIndex = NODE_Z,
                )
            }
            if (node.inertia) {
                var offset = if (node.isPipelineParent) {
                    squareSize / 2f + 15f
                } else {
                    config.nodeRadius + 15f
                }
                if (node.sourceStrategy != null) {
                    offset += config.nodeRadius + 10f
                }
                val lineHeight = if (node.isPipelineParent) {
                    squareSize
                } else {
                    config.nodeRadius * 2f
                }
                elements += line(
                    id = "wardley-inertia-$index",
                    start = ScenePoint(position.x + offset, position.y - lineHeight / 2f),
                    end = ScenePoint(position.x + offset, position.y + lineHeight / 2f),
                    color = theme.componentStroke,
                    strokeWidth = 6f,
                    zIndex = INERTIA_Z,
                )
            }
            val isAnchor = node.className == "anchor"
            var defaultX = if (isAnchor) 0f else config.nodeLabelOffset
            var defaultY = if (isAnchor) -3f else -config.nodeLabelOffset
            if (!isAnchor && node.sourceStrategy != null) {
                defaultX += 10f
                defaultY -= 10f
            }
            elements += text(
                id = "wardley-node-label-$index",
                value = node.label,
                x = position.x + (node.labelOffsetX ?: defaultX),
                y = position.y + (node.labelOffsetY ?: defaultY),
                color = when (node.className) {
                    "evolved" -> theme.evolutionStroke
                    "anchor" -> BLACK
                    else -> theme.componentLabelColor
                },
                fontSize = config.labelFontSize,
                weight = if (isAnchor) SceneTextWeight.Bold else SceneTextWeight.Normal,
                alignment = if (isAnchor) {
                    SceneTextAlignment.Center
                } else {
                    SceneTextAlignment.Start
                },
                verticalAnchor = if (isAnchor) {
                    VerticalAnchor.Center
                } else {
                    VerticalAnchor.Baseline
                },
                context = context,
                zIndex = NODE_LABEL_Z,
            )
        }
    }

    private fun drawStrategy(
        elements: MutableList<SceneElement>,
        index: Int,
        node: WardleyNode,
        position: NodePosition,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
    ) {
        val radius = config.nodeRadius * 2f
        when (node.sourceStrategy) {
            WardleySourceStrategy.Outsource -> elements += circle(
                id = "wardley-outsource-overlay-$index",
                x = position.x,
                y = position.y,
                radius = radius,
                fill = SceneColor(0xFF666666),
                stroke = theme.componentStroke,
                strokeWidth = 1f,
                zIndex = STRATEGY_Z,
            )
            WardleySourceStrategy.Buy -> elements += circle(
                id = "wardley-buy-overlay-$index",
                x = position.x,
                y = position.y,
                radius = radius,
                fill = SceneColor(0xFFCCCCCC),
                stroke = theme.componentStroke,
                strokeWidth = 1f,
                zIndex = STRATEGY_Z,
            )
            WardleySourceStrategy.Build -> elements += circle(
                id = "wardley-build-overlay-$index",
                x = position.x,
                y = position.y,
                radius = radius,
                fill = SceneColor(0xFFEEEEEE),
                stroke = BLACK,
                strokeWidth = 1f,
                zIndex = STRATEGY_Z,
            )
            WardleySourceStrategy.Market ->
                drawMarketStrategy(elements, index, position, config, theme)
            null -> Unit
        }
    }

    private fun drawMarketStrategy(
        elements: MutableList<SceneElement>,
        index: Int,
        position: NodePosition,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
    ) {
        elements += circle(
            id = "wardley-market-overlay-$index",
            x = position.x,
            y = position.y,
            radius = config.nodeRadius * 2f,
            fill = WHITE,
            stroke = theme.componentStroke,
            strokeWidth = 1f,
            zIndex = STRATEGY_Z,
        )
        val triangleRadius = config.nodeRadius * 1.2f
        val smallRadius = config.nodeRadius * 0.7f
        val top = ScenePoint(position.x, position.y - triangleRadius)
        val bottomLeft = ScenePoint(
            position.x - triangleRadius * cos(PI.toFloat() / 6f),
            position.y + triangleRadius * sin(PI.toFloat() / 6f),
        )
        val bottomRight = ScenePoint(
            position.x + triangleRadius * cos(PI.toFloat() / 6f),
            position.y + triangleRadius * sin(PI.toFloat() / 6f),
        )
        listOf(top to bottomLeft, bottomLeft to bottomRight, bottomRight to top)
            .forEachIndexed { lineIndex, (start, end) ->
                elements += line(
                    id = "wardley-market-line-$index-$lineIndex",
                    start = start,
                    end = end,
                    color = theme.componentStroke,
                    zIndex = MARKET_LINE_Z,
                )
            }
        listOf(top, bottomLeft, bottomRight).forEachIndexed { dotIndex, point ->
            elements += circle(
                id = "wardley-market-dot-$index-$dotIndex",
                x = point.x,
                y = point.y,
                radius = smallRadius,
                fill = WHITE,
                stroke = theme.componentStroke,
                strokeWidth = 2f,
                zIndex = MARKET_DOT_Z,
            )
        }
    }

    private fun drawAnnotations(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        projection: Projection,
        config: MermaidWardleyOptions,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ) {
        data.annotations.forEachIndexed { annotationIndex, annotation ->
            val coordinates = annotation.coordinates.map { coordinate ->
                ScenePoint(projection.x(coordinate.x), projection.y(coordinate.y))
            }
            coordinates.zipWithNext().forEachIndexed { index, (start, end) ->
                elements += line(
                    id = "wardley-annotation-line-$annotationIndex-$index",
                    start = start,
                    end = end,
                    color = theme.annotationStroke,
                    strokeWidth = 1.5f,
                    strokePattern = SceneStrokePattern.Dashed,
                    dashIntervals = listOf(4f, 4f),
                    zIndex = ANNOTATION_LINE_Z,
                )
            }
            coordinates.forEachIndexed { coordinateIndex, coordinate ->
                elements += circle(
                    id = "wardley-annotation-$annotationIndex-$coordinateIndex",
                    x = coordinate.x,
                    y = coordinate.y,
                    radius = ANNOTATION_RADIUS,
                    fill = theme.annotationFill,
                    stroke = theme.annotationStroke,
                    strokeWidth = 1.5f,
                    zIndex = ANNOTATION_Z,
                )
                elements += text(
                    id = "wardley-annotation-text-$annotationIndex-$coordinateIndex",
                    value = annotation.number.toString(),
                    x = coordinate.x,
                    y = coordinate.y,
                    color = theme.annotationTextColor,
                    fontSize = 10f,
                    weight = SceneTextWeight.Bold,
                    alignment = SceneTextAlignment.Center,
                    verticalAnchor = VerticalAnchor.Center,
                    context = context,
                    zIndex = ANNOTATION_TEXT_Z,
                )
            }
        }
        val annotationsBox = data.annotationsBox ?: return
        val sorted = data.annotations.filter { annotation -> annotation.text != null }
            .sortedBy { annotation -> annotation.number }
        if (sorted.isEmpty()) {
            return
        }
        val lines = sorted.map { annotation -> "${annotation.number}. ${annotation.text}" }
        val metrics = lines.map { value ->
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = value,
                    fontSize = ANNOTATION_BOX_FONT_SIZE,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    lineHeight = TEXT_LINE_HEIGHT,
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                ),
            )
        }
        val boxWidth = metrics.maxOf { metric -> metric.width } +
            ANNOTATION_BOX_PADDING * 2f + ANNOTATION_BOX_WIDTH_BUFFER
        val maxHeight = metrics.maxOf { metric -> metric.height }
        val boxHeight = lines.size * ANNOTATION_BOX_LINE_HEIGHT +
            ANNOTATION_BOX_PADDING * 2f + maxHeight / 2f
        val requestedX = projection.x(annotationsBox.x)
        val requestedY = projection.y(annotationsBox.y)
        val boxX = requestedX.coerceIn(
            config.padding,
            max(config.padding, projection.width - config.padding - boxWidth),
        )
        val boxY = requestedY.coerceIn(
            config.padding,
            max(config.padding, projection.height - config.padding - boxHeight),
        )
        elements += SceneShape(
            id = "wardley-annotations-box",
            bounds = SceneRect(boxX, boxY, boxX + boxWidth, boxY + boxHeight),
            kind = SceneShapeKind.RoundedRectangle,
            fill = theme.annotationFill,
            stroke = theme.annotationStroke,
            strokeWidth = 1.5f,
            cornerRadius = 4f,
            zIndex = ANNOTATION_BOX_Z,
        )
        lines.forEachIndexed { index, value ->
            elements += text(
                id = "wardley-annotations-box-text-$index",
                value = value,
                x = boxX + ANNOTATION_BOX_PADDING,
                y = boxY + ANNOTATION_BOX_PADDING +
                    (index + 1) * ANNOTATION_BOX_LINE_HEIGHT,
                color = theme.annotationTextColor,
                fontSize = ANNOTATION_BOX_FONT_SIZE,
                alignment = SceneTextAlignment.Start,
                verticalAnchor = VerticalAnchor.Center,
                context = context,
                zIndex = ANNOTATION_BOX_TEXT_Z,
            )
        }
    }

    private fun drawNotes(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        projection: Projection,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ) {
        data.notes.forEachIndexed { index, note ->
            elements += text(
                id = "wardley-note-$index",
                value = note.text,
                x = projection.x(note.x),
                y = projection.y(note.y),
                color = theme.axisTextColor,
                fontSize = 11f,
                weight = SceneTextWeight.Bold,
                alignment = SceneTextAlignment.Start,
                verticalAnchor = VerticalAnchor.Baseline,
                context = context,
                zIndex = NOTE_Z,
            )
        }
    }

    private fun drawForces(
        elements: MutableList<SceneElement>,
        data: WardleyBuildResult,
        projection: Projection,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ) {
        data.accelerators.forEachIndexed { index, force ->
            val x = projection.x(force.x)
            val y = projection.y(force.y)
            elements += forceArrow(
                id = "wardley-accelerator-$index",
                x = x,
                y = y,
                pointsRight = true,
                theme = theme,
            )
            elements += forceLabel(
                id = "wardley-accelerator-label-$index",
                value = force.name,
                x = x,
                y = y,
                theme = theme,
                context = context,
            )
        }
        data.deaccelerators.forEachIndexed { index, force ->
            val x = projection.x(force.x)
            val y = projection.y(force.y)
            elements += forceArrow(
                id = "wardley-deaccelerator-$index",
                x = x,
                y = y,
                pointsRight = false,
                theme = theme,
            )
            elements += forceLabel(
                id = "wardley-deaccelerator-label-$index",
                value = force.name,
                x = x,
                y = y,
                theme = theme,
                context = context,
            )
        }
    }

    private fun forceArrow(
        id: String,
        x: Float,
        y: Float,
        pointsRight: Boolean,
        theme: WardleyTheme,
    ): ScenePath {
        val points = if (pointsRight) {
            listOf(
                ScenePoint(x, y - FORCE_ARROW_HEIGHT / 2f),
                ScenePoint(x + FORCE_ARROW_WIDTH - FORCE_ARROW_HEAD_WIDTH, y - FORCE_ARROW_HEIGHT / 2f),
                ScenePoint(x + FORCE_ARROW_WIDTH - FORCE_ARROW_HEAD_WIDTH, y - FORCE_ARROW_HEIGHT / 2f - 8f),
                ScenePoint(x + FORCE_ARROW_WIDTH, y),
                ScenePoint(x + FORCE_ARROW_WIDTH - FORCE_ARROW_HEAD_WIDTH, y + FORCE_ARROW_HEIGHT / 2f + 8f),
                ScenePoint(x + FORCE_ARROW_WIDTH - FORCE_ARROW_HEAD_WIDTH, y + FORCE_ARROW_HEIGHT / 2f),
                ScenePoint(x, y + FORCE_ARROW_HEIGHT / 2f),
            )
        } else {
            listOf(
                ScenePoint(x + FORCE_ARROW_WIDTH, y - FORCE_ARROW_HEIGHT / 2f),
                ScenePoint(x + FORCE_ARROW_HEAD_WIDTH, y - FORCE_ARROW_HEIGHT / 2f),
                ScenePoint(x + FORCE_ARROW_HEAD_WIDTH, y - FORCE_ARROW_HEIGHT / 2f - 8f),
                ScenePoint(x, y),
                ScenePoint(x + FORCE_ARROW_HEAD_WIDTH, y + FORCE_ARROW_HEIGHT / 2f + 8f),
                ScenePoint(x + FORCE_ARROW_HEAD_WIDTH, y + FORCE_ARROW_HEIGHT / 2f),
                ScenePoint(x + FORCE_ARROW_WIDTH, y + FORCE_ARROW_HEIGHT / 2f),
            )
        }
        return polygon(
            id = id,
            points = points,
            fill = WHITE,
            stroke = theme.componentStroke,
            zIndex = FORCE_Z,
        )
    }

    private fun forceLabel(
        id: String,
        value: String,
        x: Float,
        y: Float,
        theme: WardleyTheme,
        context: MermaidRenderContext,
    ): SceneText = text(
        id = id,
        value = value,
        x = x + FORCE_ARROW_WIDTH / 2f,
        y = y + FORCE_ARROW_HEIGHT / 2f + 15f,
        color = theme.axisTextColor,
        fontSize = 10f,
        weight = SceneTextWeight.Bold,
        alignment = SceneTextAlignment.Center,
        verticalAnchor = VerticalAnchor.Baseline,
        context = context,
        zIndex = FORCE_LABEL_Z,
    )

    private fun resolveTheme(
        context: MermaidRenderContext,
    ): GMResult<WardleyTheme, MermaidError> {
        val values = context.options.themeVariables
        var error: MermaidError.Configuration? = null

        fun color(
            name: String,
            fallback: SceneColor,
        ): SceneColor {
            val raw = values["wardley.$name"] ?: return fallback
            return CssColorParser.parse(raw) ?: fallback.also {
                if (error == null) {
                    error = MermaidError.Configuration(
                        "Mermaid theme variable 'wardley.$name' has invalid value '$raw'",
                    )
                }
            }
        }

        val theme = WardleyTheme(
            backgroundColor = color("backgroundColor", context.theme.background),
            axisColor = color("axisColor", BLACK),
            axisTextColor = color("axisTextColor", context.theme.nodeText),
            gridColor = color("gridColor", DEFAULT_GRID),
            componentFill = color("componentFill", WHITE),
            componentStroke = color("componentStroke", BLACK),
            componentLabelColor = color("componentLabelColor", context.theme.nodeText),
            linkStroke = color("linkStroke", BLACK),
            evolutionStroke = color("evolutionStroke", DEFAULT_EVOLUTION),
            annotationStroke = color("annotationStroke", BLACK),
            annotationTextColor = color("annotationTextColor", context.theme.nodeText),
            annotationFill = color("annotationFill", context.theme.background),
        )
        return error?.let { GMResult.Err(it) } ?: GMResult.Ok(theme)
    }

    private fun validate(
        config: MermaidWardleyOptions,
    ): GMResult<Unit, MermaidError> {
        listOf(
            "width" to config.width,
            "height" to config.height,
            "nodeRadius" to config.nodeRadius,
            "axisFontSize" to config.axisFontSize,
            "labelFontSize" to config.labelFontSize,
        ).firstOrNull { (_, value) -> !value.isFinite() || value <= 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be positive and finite")
        }
        listOf(
            "padding" to config.padding,
            "nodeLabelOffset" to config.nodeLabelOffset,
        ).firstOrNull { (_, value) -> !value.isFinite() || value < 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be non-negative and finite")
        }
        return GMResult.Ok(Unit)
    }

    private fun text(
        id: String,
        value: String,
        x: Float,
        y: Float,
        color: SceneColor,
        fontSize: Float,
        weight: SceneTextWeight = SceneTextWeight.Normal,
        alignment: SceneTextAlignment,
        verticalAnchor: VerticalAnchor,
        rotation: Float = 0f,
        context: MermaidRenderContext,
        zIndex: Int,
    ): SceneText {
        val measured = context.textMetrics.measure(
            TextMetricsRequest(
                text = value,
                fontSize = fontSize,
                maxWidth = UNWRAPPED_TEXT_WIDTH,
                lineHeight = TEXT_LINE_HEIGHT,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = weight,
            ),
        )
        val width = measured.width.coerceAtLeast(1f)
        val height = measured.height.coerceAtLeast(fontSize)
        val left = when (alignment) {
            SceneTextAlignment.Start -> x
            SceneTextAlignment.Center -> x - width / 2f
            SceneTextAlignment.End -> x - width
        }
        val top = when (verticalAnchor) {
            VerticalAnchor.Top -> y
            VerticalAnchor.Center -> y - height / 2f
            VerticalAnchor.Baseline -> y - height
        }
        return SceneText(
            text = value,
            bounds = SceneRect(left, top, left + width, top + height),
            color = color,
            fontSize = fontSize,
            lineHeight = TEXT_LINE_HEIGHT,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = weight,
            horizontalAlignment = alignment,
            rotationDegrees = rotation,
            rotationPivot = ScenePoint(x, y).takeIf { rotation != 0f },
            softWrap = false,
            zIndex = zIndex,
        )
    }

    private fun circle(
        id: String,
        x: Float,
        y: Float,
        radius: Float,
        fill: SceneColor,
        stroke: SceneColor,
        strokeWidth: Float,
        zIndex: Int,
    ): SceneShape = SceneShape(
        id = id,
        bounds = centeredRect(x, y, radius * 2f, radius * 2f),
        kind = SceneShapeKind.Circle,
        fill = fill,
        stroke = stroke,
        strokeWidth = strokeWidth,
        cornerRadius = 0f,
        zIndex = zIndex,
    )

    private fun centeredRect(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ): SceneRect = SceneRect(
        left = x - width / 2f,
        top = y - height / 2f,
        right = x + width / 2f,
        bottom = y + height / 2f,
    )

    private fun line(
        id: String,
        start: ScenePoint,
        end: ScenePoint,
        color: SceneColor,
        strokeWidth: Float = 1f,
        strokePattern: SceneStrokePattern = SceneStrokePattern.Solid,
        dashIntervals: List<Float> = emptyList(),
        arrowStart: SceneArrowHead = SceneArrowHead.None,
        arrowEnd: SceneArrowHead = SceneArrowHead.None,
        markerBackground: SceneColor? = null,
        zIndex: Int,
    ): ScenePath = ScenePath(
        id = id,
        points = listOf(start, end),
        commands = listOf(
            ScenePathCommand.MoveTo(start),
            ScenePathCommand.LineTo(end),
        ),
        color = color,
        strokeWidth = strokeWidth,
        strokePattern = strokePattern,
        arrowStart = arrowStart,
        arrowEnd = arrowEnd,
        arrowColor = color,
        curve = CLASSIC_LOOK,
        look = CLASSIC_LOOK,
        animated = false,
        dashIntervals = dashIntervals,
        markerBackground = markerBackground,
        zIndex = zIndex,
    )

    private fun polygon(
        id: String,
        points: List<ScenePoint>,
        fill: SceneColor,
        stroke: SceneColor,
        zIndex: Int,
    ): ScenePath {
        val commands = buildList {
            points.firstOrNull()?.let { first -> add(ScenePathCommand.MoveTo(first)) }
            points.drop(1).forEach { point -> add(ScenePathCommand.LineTo(point)) }
            points.firstOrNull()?.let { first -> add(ScenePathCommand.LineTo(first)) }
        }
        return ScenePath(
            id = id,
            points = points,
            commands = commands,
            color = stroke,
            strokeWidth = 1f,
            curve = CLASSIC_LOOK,
            look = CLASSIC_LOOK,
            animated = false,
            fillColor = fill,
            closed = true,
            zIndex = zIndex,
        )
    }

    private fun configurationError(message: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Configuration("Invalid Wardley configuration: $message"))

    private data class Projection(
        val width: Float,
        val height: Float,
        val padding: Float,
    ) {
        val left: Float get() = padding
        val right: Float get() = width - padding
        val top: Float get() = padding
        val bottom: Float get() = height - padding
        val chartWidth: Float get() = width - padding * 2f
        val chartHeight: Float get() = height - padding * 2f

        fun x(value: Float): Float = padding + value / 100f * chartWidth

        fun y(value: Float): Float = height - padding - value / 100f * chartHeight
    }

    private data class NodePosition(
        var x: Float,
        var y: Float,
        val node: WardleyNode,
    )

    private data class StagePosition(
        val start: Float,
        val end: Float,
    )

    private data class WardleyTheme(
        val backgroundColor: SceneColor,
        val axisColor: SceneColor,
        val axisTextColor: SceneColor,
        val gridColor: SceneColor,
        val componentFill: SceneColor,
        val componentStroke: SceneColor,
        val componentLabelColor: SceneColor,
        val linkStroke: SceneColor,
        val evolutionStroke: SceneColor,
        val annotationStroke: SceneColor,
        val annotationTextColor: SceneColor,
        val annotationFill: SceneColor,
    )

    private enum class VerticalAnchor {
        Top,
        Center,
        Baseline,
    }

    private companion object {
        val DEFAULT_STAGES = listOf("Genesis", "Custom Built", "Product", "Commodity")
        const val DEFAULT_X_LABEL = "Evolution"
        const val DEFAULT_Y_LABEL = "Visibility"
        const val TEXT_LINE_HEIGHT = 1.2f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        const val PIPELINE_PADDING = 15f
        const val LINK_LABEL_OFFSET = 8f
        const val ANNOTATION_RADIUS = 10f
        const val ANNOTATION_BOX_PADDING = 10f
        const val ANNOTATION_BOX_LINE_HEIGHT = 16f
        const val ANNOTATION_BOX_FONT_SIZE = 11f
        const val ANNOTATION_BOX_WIDTH_BUFFER = 105f
        const val FORCE_ARROW_WIDTH = 60f
        const val FORCE_ARROW_HEIGHT = 30f
        const val FORCE_ARROW_HEAD_WIDTH = 20f
        const val CLASSIC_LOOK = "classic"

        const val BACKGROUND_Z = 0
        const val GRID_Z = 1
        const val STAGE_Z = 2
        const val AXIS_Z = 3
        const val PIPELINE_LINK_Z = 4
        const val PIPELINE_Z = 5
        const val LINK_Z = 6
        const val TREND_Z = 7
        const val STRATEGY_Z = 8
        const val MARKET_LINE_Z = 9
        const val NODE_Z = 10
        const val MARKET_DOT_Z = 11
        const val INERTIA_Z = 12
        const val LINK_LABEL_Z = 14
        const val NODE_LABEL_Z = 15
        const val ANNOTATION_LINE_Z = 16
        const val ANNOTATION_Z = 17
        const val ANNOTATION_TEXT_Z = 18
        const val ANNOTATION_BOX_Z = 19
        const val ANNOTATION_BOX_TEXT_Z = 20
        const val NOTE_Z = 21
        const val FORCE_Z = 22
        const val FORCE_LABEL_Z = 23
        const val AXIS_LABEL_Z = 24
        const val STAGE_LABEL_Z = 25
        const val TITLE_Z = 26

        val TRANSPARENT = SceneColor(0x00000000)
        val BLACK = SceneColor(0xFF000000)
        val WHITE = SceneColor(0xFFFFFFFF)
        val DEFAULT_GRID = SceneColor(0x33646464)
        val DEFAULT_EVOLUTION = SceneColor(0xFFDC3545)

        fun SceneColor.withAlpha(alpha: Float): SceneColor {
            val value = (alpha.coerceIn(0f, 1f) * 255f).toLong()
            return SceneColor((argb and 0x00FFFFFF) or (value shl 24))
        }
    }
}
