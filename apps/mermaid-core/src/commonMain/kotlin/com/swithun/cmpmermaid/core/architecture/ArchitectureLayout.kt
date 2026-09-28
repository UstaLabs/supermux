package com.swithun.cmpmermaid.core.architecture

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneAssetKind
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureDb
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureDirection
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureEdge
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureGroup
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureJunction
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureNode
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.ArchitectureService
import com.swithun.cmpmermaid.core.architecture.upstream.mermaid.isHorizontal
import kotlin.math.abs
import kotlin.math.max

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/architecture/svgDraw.ts and
 * packages/mermaid/src/diagrams/architecture/architectureRenderer.ts -> draw.
 */
internal class ArchitectureLayout {
    fun layout(
        db: ArchitectureDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val placement = when (val result = ArchitectureFcoseLayout.layout(db)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val elements = mutableListOf<SceneElement>()
        db.getEdges().forEachIndexed { index, edge ->
            when (
                val result = drawEdge(
                    edge = edge,
                    index = index,
                    db = db,
                    placement = placement,
                    context = context,
                )
            ) {
                is GMResult.Ok -> elements += result.value
                is GMResult.Err -> return result
            }
        }
        db.getGroups().forEach { group ->
            val bounds = placement.groupBounds[group.id]
                ?: return layoutError("Architecture group '${group.id}' was not positioned")
            elements += drawGroup(group, bounds, context)
        }
        db.getServices().forEach { service ->
            val center = placement.nodeCenters[service.id]
                ?: return layoutError("Architecture service '${service.id}' was not positioned")
            elements += drawService(service, center, db, context)
        }
        db.getJunctions().forEach { junction ->
            if (placement.nodeCenters[junction.id] == null) {
                return layoutError("Architecture junction '${junction.id}' was not positioned")
            }
        }
        return GMResult.Ok(normalize(elements, db, context))
    }

    private fun drawEdge(
        edge: ArchitectureEdge,
        index: Int,
        db: ArchitectureDb,
        placement: ArchitectureLayoutResult,
        context: MermaidRenderContext,
    ): GMResult<List<SceneElement>, MermaidError> {
        val source = endpoint(
            id = edge.lhsId,
            direction = edge.lhsDir,
            groupModifier = edge.lhsGroup,
            db = db,
            placement = placement,
        ) ?: return layoutError("Architecture edge source '${edge.lhsId}' was not positioned")
        val target = endpoint(
            id = edge.rhsId,
            direction = edge.rhsDir,
            groupModifier = edge.rhsGroup,
            db = db,
            placement = placement,
        ) ?: return layoutError("Architecture edge target '${edge.rhsId}' was not positioned")
        val bends = if (edge.lhsDir.isHorizontal() != edge.rhsDir.isHorizontal()) {
            listOf(
                if (edge.lhsDir.isHorizontal()) {
                    ScenePoint(target.x, source.y)
                } else {
                    ScenePoint(source.x, target.y)
                },
            )
        } else {
            listOf(
                ScenePoint(
                    x = (source.x + target.x) / 2f,
                    y = (source.y + target.y) / 2f,
                ),
            )
        }
        val points = listOf(source) + bends + target
        val edgeColor = themeColor(context, "archEdgeColor") ?: context.theme.edge
        val arrowColor = themeColor(context, "archEdgeArrowColor") ?: edgeColor
        val strokeWidth = context.options.themeVariables["archEdgeWidth"]
            ?.removeSuffix("px")
            ?.toFloatOrNull()
            ?: 3f
        val result = mutableListOf<SceneElement>(
            ScenePath(
                id = "architecture-edge-$index-${edge.lhsId}-${edge.rhsId}",
                points = points,
                commands = points.toLineCommands(),
                color = edgeColor,
                strokeWidth = strokeWidth,
                curve = "linear",
                look = "classic",
                animated = false,
                zIndex = 5,
            ),
        )
        val arrowSize = db.config.iconSize / 6f
        if (edge.lhsInto) {
            result += drawArrow(
                id = "architecture-edge-$index-arrow-start",
                endpoint = source,
                direction = edge.lhsDir,
                size = arrowSize,
                color = arrowColor,
            )
        }
        if (edge.rhsInto) {
            result += drawArrow(
                id = "architecture-edge-$index-arrow-end",
                endpoint = target,
                direction = edge.rhsDir,
                size = arrowSize,
                color = arrowColor,
            )
        }
        edge.title?.let { label ->
            val midpoint = bends.first()
            val axis = edgeAxis(edge)
            val maxWidth = when (axis) {
                EdgeAxis.Horizontal -> abs(source.x - target.x)
                EdgeAxis.Vertical -> abs(source.y - target.y) / 1.5f
                EdgeAxis.Bend -> abs(source.x - target.x) / 2f
            }.coerceAtLeast(db.config.fontSize * 2f)
            val metrics = context.textMetrics.measure(
                TextMetricsRequest(
                    text = label,
                    fontSize = db.config.fontSize,
                    maxWidth = maxWidth,
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                    weight = SceneTextWeight.Medium,
                ),
            )
            val rotation = when (axis) {
                EdgeAxis.Horizontal -> 0f
                EdgeAxis.Vertical -> -90f
                EdgeAxis.Bend -> diagonalLabelRotation(edge)
            }
            result += SceneText(
                text = label,
                bounds = SceneRect(
                    left = midpoint.x - metrics.width / 2f,
                    top = midpoint.y - metrics.height / 2f,
                    right = midpoint.x + metrics.width / 2f,
                    bottom = midpoint.y + metrics.height / 2f,
                ),
                color = context.theme.textColor,
                fontSize = db.config.fontSize,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = SceneTextWeight.Medium,
                rotationDegrees = rotation,
                rotationPivot = midpoint,
                softWrap = true,
                zIndex = 20,
            )
        }
        return GMResult.Ok(result)
    }

    // Mermaid.js 12.0.0: architectureTypes.ts ->
    // ArchitectureDirectionArrow and ArchitectureDirectionArrowShift.
    private fun drawArrow(
        id: String,
        endpoint: ScenePoint,
        direction: ArchitectureDirection,
        size: Float,
        color: SceneColor,
    ): SceneShape {
        val halfSize = size / 2f
        val points = when (direction) {
            ArchitectureDirection.L -> listOf(
                ScenePoint(endpoint.x + 2f, endpoint.y),
                ScenePoint(endpoint.x - size + 2f, endpoint.y + halfSize),
                ScenePoint(endpoint.x - size + 2f, endpoint.y - halfSize),
            )
            ArchitectureDirection.R -> listOf(
                ScenePoint(endpoint.x - 2f, endpoint.y),
                ScenePoint(endpoint.x + size - 2f, endpoint.y - halfSize),
                ScenePoint(endpoint.x + size - 2f, endpoint.y + halfSize),
            )
            ArchitectureDirection.T -> listOf(
                ScenePoint(endpoint.x - halfSize, endpoint.y - size + 2f),
                ScenePoint(endpoint.x + halfSize, endpoint.y - size + 2f),
                ScenePoint(endpoint.x, endpoint.y + 2f),
            )
            ArchitectureDirection.B -> listOf(
                ScenePoint(endpoint.x, endpoint.y - 2f),
                ScenePoint(endpoint.x + halfSize, endpoint.y + size - 2f),
                ScenePoint(endpoint.x - halfSize, endpoint.y + size - 2f),
            )
        }
        val bounds = SceneRect(
            left = points.minOf(ScenePoint::x),
            top = points.minOf(ScenePoint::y),
            right = points.maxOf(ScenePoint::x),
            bottom = points.maxOf(ScenePoint::y),
        )
        val relativePoints = points.map { point ->
            ScenePoint(
                x = point.x - bounds.center.x,
                y = point.y - bounds.center.y,
            )
        }
        return SceneShape(
            id = id,
            bounds = bounds,
            kind = SceneShapeKind.Triangle,
            geometry = SceneShapeGeometry(
                paths = listOf(
                    SceneShapePath(
                        points = relativePoints,
                        fill = SceneShapePaint.Fill,
                        stroke = SceneShapePaint.None,
                    ),
                ),
                outline = relativePoints,
            ),
            fill = color,
            stroke = TRANSPARENT,
            strokeWidth = 0f,
            cornerRadius = 0f,
            zIndex = 6,
        )
    }

    private fun endpoint(
        id: String,
        direction: ArchitectureDirection,
        groupModifier: Boolean,
        db: ArchitectureDb,
        placement: ArchitectureLayoutResult,
    ): ScenePoint? {
        val node = db.getNode(id)
        val bounds = when {
            node != null -> {
                val center = placement.nodeCenters[id] ?: return null
                SceneRect(
                    left = center.x - db.config.iconSize / 2f,
                    top = center.y - db.config.iconSize / 2f,
                    right = center.x + db.config.iconSize / 2f,
                    bottom = center.y + db.config.iconSize / 2f,
                )
            }
            else -> placement.groupBounds[id] ?: return null
        }
        var point = bounds.port(direction)
        if (groupModifier) {
            val groupEdgeShift = db.config.padding + 4f
            point = point.shift(
                direction = direction,
                distance = groupEdgeShift +
                    if (direction == ArchitectureDirection.B) 18f else 0f,
            )
        } else if (node is ArchitectureJunction) {
            point = bounds.center
        }
        return point
    }

    private fun drawGroup(
        group: ArchitectureGroup,
        bounds: SceneRect,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val borderColor =
            themeColor(context, "archGroupBorderColor") ?: context.theme.nodeStroke
        val borderWidth = context.options.themeVariables["archGroupBorderWidth"]
            ?.removeSuffix("px")
            ?.toFloatOrNull()
            ?: 2f
        val elements = mutableListOf<SceneElement>(
            SceneShape(
                id = "architecture-group-${group.id}",
                bounds = bounds,
                kind = SceneShapeKind.Rectangle,
                fill = TRANSPARENT,
                stroke = borderColor,
                strokeWidth = borderWidth,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(8f, 8f),
                cornerRadius = 0f,
                zIndex = 8,
            ),
        )
        val iconSize = context.options.architecture.padding * 0.75f
        var labelLeft = bounds.left + 4f
        if (group.icon != null) {
            val iconBounds = SceneRect(
                left = bounds.left + 1f,
                top = bounds.top + 1f,
                right = bounds.left + 1f + iconSize,
                bottom = bounds.top + 1f + iconSize,
            )
            elements += drawIcon(
                id = "architecture-group-icon-${group.id}",
                icon = group.icon,
                bounds = iconBounds,
            )
            labelLeft += iconSize
        }
        group.title?.let { title ->
            val fontSize = context.options.architecture.fontSize
            val metrics = context.textMetrics.measure(
                TextMetricsRequest(
                    text = title,
                    fontSize = fontSize,
                    maxWidth = max(fontSize, bounds.right - labelLeft - 4f),
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                ),
            )
            elements += SceneText(
                text = title,
                bounds = SceneRect(
                    left = labelLeft,
                    top = bounds.top + 2f,
                    right = minOf(bounds.right - 4f, labelLeft + metrics.width),
                    bottom = bounds.top + 2f + metrics.height,
                ),
                color = context.theme.textColor,
                fontSize = fontSize,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = SceneTextWeight.Medium,
                horizontalAlignment = SceneTextAlignment.Start,
                softWrap = false,
                zIndex = 20,
            )
        }
        return elements
    }

    private fun drawService(
        service: ArchitectureService,
        center: ScenePoint,
        db: ArchitectureDb,
        context: MermaidRenderContext,
    ): List<SceneElement> {
        val iconSize = db.config.iconSize
        val bounds = SceneRect(
            left = center.x - iconSize / 2f,
            top = center.y - iconSize / 2f,
            right = center.x + iconSize / 2f,
            bottom = center.y + iconSize / 2f,
        )
        val elements = mutableListOf<SceneElement>()
        when {
            service.icon != null -> elements += drawIcon(
                id = "architecture-service-icon-${service.id}",
                icon = service.icon,
                bounds = bounds,
            )
            service.iconText != null -> {
                elements += iconBackground(
                    id = "architecture-service-icon-${service.id}",
                    bounds = bounds,
                )
                elements += SceneText(
                    text = service.iconText,
                    bounds = bounds.inflate(-2f, -2f),
                    color = WHITE,
                    fontSize = minOf(16f, db.config.fontSize),
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                    weight = SceneTextWeight.Medium,
                    softWrap = true,
                    clipToBounds = true,
                    zIndex = 15,
                )
            }
            else -> elements += SceneShape(
                id = "architecture-service-${service.id}",
                bounds = bounds,
                kind = SceneShapeKind.RoundedRectangle,
                fill = TRANSPARENT,
                stroke = themeColor(context, "archGroupBorderColor")
                    ?: context.theme.nodeStroke,
                strokeWidth = context.options.themeVariables["archGroupBorderWidth"]
                    ?.removeSuffix("px")
                    ?.toFloatOrNull()
                    ?: 2f,
                strokePattern = SceneStrokePattern.Dashed,
                dashIntervals = listOf(8f, 8f),
                cornerRadius = 5f,
                zIndex = 10,
            )
        }
        service.title?.let { title ->
            val maxWidth = iconSize * 1.5f
            val metrics = context.textMetrics.measure(
                TextMetricsRequest(
                    text = title,
                    fontSize = db.config.fontSize,
                    maxWidth = maxWidth,
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                ),
            )
            elements += SceneText(
                text = title,
                bounds = SceneRect(
                    left = center.x - metrics.width / 2f,
                    top = bounds.bottom,
                    right = center.x + metrics.width / 2f,
                    bottom = bounds.bottom + metrics.height,
                ),
                color = context.theme.textColor,
                fontSize = db.config.fontSize,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = SceneTextWeight.Medium,
                softWrap = true,
                zIndex = 20,
            )
        }
        return elements
    }

    private fun drawIcon(
        id: String,
        icon: String,
        bounds: SceneRect,
    ): List<SceneElement> {
        if (icon !in BUILT_IN_ICONS) {
            return listOf(
                SceneAsset(
                    id = id,
                    source = icon,
                    bounds = bounds,
                    kind = SceneAssetKind.Icon,
                    zIndex = 15,
                ),
            )
        }
        val elements = mutableListOf<SceneElement>(iconBackground(id, bounds))
        val left = bounds.left
        val top = bounds.top
        val width = bounds.width
        val height = bounds.height
        fun point(x: Float, y: Float): ScenePoint =
            ScenePoint(left + width * x / 80f, top + height * y / 80f)
        val iconStrokeWidth = max(1f, width / 40f)
        fun line(
            suffix: String,
            points: List<ScenePoint>,
            closed: Boolean = false,
            fill: SceneColor? = null,
            strokeWidth: Float = iconStrokeWidth,
        ) {
            elements += ScenePath(
                id = "$id-$suffix",
                points = points,
                commands = points.toLineCommands(),
                color = WHITE,
                strokeWidth = strokeWidth,
                curve = "linear",
                look = "classic",
                animated = false,
                fillColor = fill,
                closed = closed,
                zIndex = 16,
            )
        }
        fun path(
            suffix: String,
            points: List<ScenePoint>,
            commands: List<ScenePathCommand>,
            closed: Boolean = false,
            fill: SceneColor? = null,
            strokeWidth: Float = iconStrokeWidth,
        ) {
            elements += ScenePath(
                id = "$id-$suffix",
                points = points,
                commands = commands,
                color = WHITE,
                strokeWidth = strokeWidth,
                curve = "linear",
                look = "classic",
                animated = false,
                fillColor = fill,
                closed = closed,
                zIndex = 16,
            )
        }
        fun ellipse(
            suffix: String,
            centerX: Float,
            centerY: Float,
            radiusX: Float,
            radiusY: Float,
            fill: SceneColor = TRANSPARENT,
            strokeWidth: Float = iconStrokeWidth,
        ) {
            elements += SceneShape(
                id = "$id-$suffix",
                bounds = SceneRect(
                    left = point(centerX - radiusX, centerY - radiusY).x,
                    top = point(centerX - radiusX, centerY - radiusY).y,
                    right = point(centerX + radiusX, centerY + radiusY).x,
                    bottom = point(centerX + radiusX, centerY + radiusY).y,
                ),
                kind = SceneShapeKind.Ellipse,
                fill = fill,
                stroke = WHITE,
                strokeWidth = strokeWidth,
                zIndex = 16,
            )
        }
        when (icon) {
            "blank" -> Unit
            "server" -> {
                elements += SceneShape(
                    id = "$id-frame",
                    bounds = SceneRect(
                        left = point(17.5f, 17.5f).x,
                        top = point(17.5f, 17.5f).y,
                        right = point(62.5f, 62.5f).x,
                        bottom = point(62.5f, 62.5f).y,
                    ),
                    kind = SceneShapeKind.RoundedRectangle,
                    fill = TRANSPARENT,
                    stroke = WHITE,
                    strokeWidth = iconStrokeWidth,
                    cornerRadius = width / 40f,
                    zIndex = 16,
                )
                line("row-1", listOf(point(17.5f, 32.5f), point(62.5f, 32.5f)))
                line("row-2", listOf(point(17.5f, 47.5f), point(62.5f, 47.5f)))
                listOf(25f, 40f, 55f).forEachIndexed { rowIndex, y ->
                    val bar = listOf(
                        point(43.75f, y - 0.5f),
                        point(56.25f, y - 0.5f),
                        point(56.25f, y + 0.5f),
                        point(43.75f, y + 0.5f),
                    )
                    line(
                        suffix = "bar-$rowIndex-fill",
                        points = bar,
                        closed = true,
                        fill = WHITE,
                        strokeWidth = 0f,
                    )
                    line(
                        suffix = "bar-$rowIndex-stroke",
                        points = bar,
                        closed = true,
                        strokeWidth = max(0.5f, width / 80f),
                    )
                    listOf(32.5f, 27.5f, 22.5f).forEachIndexed { columnIndex, x ->
                        ellipse(
                            suffix = "led-$rowIndex-$columnIndex",
                            centerX = x,
                            centerY = y,
                            radiusX = 0.75f,
                            radiusY = 0.75f,
                            fill = WHITE,
                            strokeWidth = max(0.5f, width / 80f),
                        )
                    }
                }
            }
            "database" -> {
                listOf(57.86f, 45.95f, 34.05f).forEachIndexed { index, y ->
                    val bottom = y + 7.14f
                    val points = listOf(
                        point(20f, y),
                        point(20f, y + 3.94f),
                        point(28.95f, bottom),
                        point(40f, bottom),
                        point(51.05f, bottom),
                        point(60f, y + 3.94f),
                        point(60f, y),
                    )
                    path(
                        suffix = "layer-$index",
                        points = points,
                        commands = listOf(
                            ScenePathCommand.MoveTo(points[0]),
                            ScenePathCommand.CubicTo(points[1], points[2], points[3]),
                            ScenePathCommand.CubicTo(points[4], points[5], points[6]),
                        ),
                    )
                }
                ellipse(
                    suffix = "top",
                    centerX = 40f,
                    centerY = 22.14f,
                    radiusX = 20f,
                    radiusY = 7.14f,
                )
                line("side-left", listOf(point(20f, 57.86f), point(20f, 22.14f)))
                line("side-right", listOf(point(60f, 57.86f), point(60f, 22.14f)))
            }
            "disk" -> {
                elements += SceneShape(
                    id = "$id-frame",
                    bounds = SceneRect(
                        left = point(20f, 15f).x,
                        top = point(20f, 15f).y,
                        right = point(60f, 65f).x,
                        bottom = point(60f, 65f).y,
                    ),
                    kind = SceneShapeKind.RoundedRectangle,
                    fill = TRANSPARENT,
                    stroke = WHITE,
                    strokeWidth = iconStrokeWidth,
                    cornerRadius = width / 80f,
                    zIndex = 16,
                )
                listOf(
                    Triple(24f, 19.17f, "top-left"),
                    Triple(56f, 19.17f, "top-right"),
                    Triple(24f, 60.83f, "bottom-left"),
                    Triple(56f, 60.83f, "bottom-right"),
                ).forEach { (x, y, suffix) ->
                    ellipse(
                        suffix = "screw-$suffix",
                        centerX = x,
                        centerY = y,
                        radiusX = 0.8f,
                        radiusY = 0.83f,
                    )
                }
                ellipse("platter", 40f, 33.75f, 14f, 14.58f)
                ellipse("hub", 40f, 33.75f, 4f, 4.17f, fill = WHITE)
                val armPoints = listOf(
                    point(37.51f, 42.52f),
                    point(32.68f, 55.74f),
                    point(32.42f, 56.45f),
                    point(31.58f, 56.76f),
                    point(30.92f, 56.38f),
                    point(26.74f, 53.96f),
                    point(26.08f, 53.58f),
                    point(25.93f, 52.7f),
                    point(26.41f, 52.12f),
                    point(35.42f, 41.32f),
                    point(36.3f, 40.27f),
                    point(37.98f, 41.24f),
                )
                path(
                    suffix = "arm",
                    points = armPoints,
                    commands = listOf(
                        ScenePathCommand.MoveTo(armPoints[0]),
                        ScenePathCommand.LineTo(armPoints[1]),
                        ScenePathCommand.CubicTo(armPoints[2], armPoints[3], armPoints[4]),
                        ScenePathCommand.LineTo(armPoints[5]),
                        ScenePathCommand.CubicTo(armPoints[6], armPoints[7], armPoints[8]),
                        ScenePathCommand.LineTo(armPoints[9]),
                        ScenePathCommand.CubicTo(armPoints[10], armPoints[11], armPoints[0]),
                    ),
                    closed = true,
                    fill = WHITE,
                    strokeWidth = 0f,
                )
            }
            "internet" -> {
                elements += SceneShape(
                    id = "$id-globe",
                    bounds = SceneRect(
                        left = point(17.5f, 17.5f).x,
                        top = point(17.5f, 17.5f).y,
                        right = point(62.5f, 62.5f).x,
                        bottom = point(62.5f, 62.5f).y,
                    ),
                    kind = SceneShapeKind.Circle,
                    fill = TRANSPARENT,
                    stroke = WHITE,
                    strokeWidth = iconStrokeWidth,
                    zIndex = 16,
                )
                line("vertical", listOf(point(40f, 17.5f), point(40f, 62.5f)))
                line("horizontal", listOf(point(17.5f, 40f), point(62.5f, 40f)))
                listOf(-1f, 1f).forEachIndexed { index, direction ->
                    val points = listOf(
                        point(40f, 17.51f),
                        point(40f + 15.28f * direction, 28.61f),
                        point(40f + 15.28f * direction, 51.39f),
                        point(40f, 62.49f),
                    )
                    path(
                        suffix = "meridian-$index",
                        points = points,
                        commands = listOf(
                            ScenePathCommand.MoveTo(points[0]),
                            ScenePathCommand.CubicTo(points[1], points[2], points[3]),
                        ),
                    )
                }
                line("latitude-1", listOf(point(19.75f, 30.1f), point(60.25f, 30.1f)))
                line("latitude-2", listOf(point(19.75f, 49.9f), point(60.25f, 49.9f)))
            }
            "cloud" -> {
                val cloudPoints = listOf(
                    point(65f, 47.5f),
                    point(65f, 50.26f),
                    point(62.76f, 52.5f),
                    point(60f, 52.5f),
                    point(20f, 52.5f),
                    point(17.24f, 52.5f),
                    point(15f, 50.26f),
                    point(15f, 47.5f),
                    point(15f, 45.63f),
                    point(16.03f, 43.99f),
                    point(17.56f, 43.14f),
                    point(17.52f, 42.93f),
                    point(17.5f, 42.72f),
                    point(17.5f, 42.5f),
                    point(17.5f, 39.9f),
                    point(19.98f, 37.76f),
                    point(23.15f, 37.53f),
                    point(24.8f, 33.02f),
                    point(29.49f, 29.77f),
                    point(35f, 29.77f),
                    point(35.86f, 29.77f),
                    point(36.69f, 29.85f),
                    point(37.5f, 30f),
                    point(39.59f, 28.43f),
                    point(42.19f, 27.5f),
                    point(45f, 27.5f),
                    point(51.1f, 27.5f),
                    point(56.19f, 31.88f),
                    point(57.28f, 37.67f),
                    point(59.42f, 38.23f),
                    point(61f, 40.18f),
                    point(61f, 42.5f),
                    point(61f, 42.53f),
                    point(61f, 42.57f),
                    point(60.99f, 42.6f),
                    point(63.28f, 43.06f),
                    point(65f, 45.08f),
                )
                path(
                    suffix = "cloud",
                    points = cloudPoints,
                    commands = listOf(
                        ScenePathCommand.MoveTo(cloudPoints[0]),
                        ScenePathCommand.CubicTo(
                            cloudPoints[1],
                            cloudPoints[2],
                            cloudPoints[3],
                        ),
                        ScenePathCommand.LineTo(cloudPoints[4]),
                        ScenePathCommand.CubicTo(
                            cloudPoints[5],
                            cloudPoints[6],
                            cloudPoints[7],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[8],
                            cloudPoints[9],
                            cloudPoints[10],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[11],
                            cloudPoints[12],
                            cloudPoints[13],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[14],
                            cloudPoints[15],
                            cloudPoints[16],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[17],
                            cloudPoints[18],
                            cloudPoints[19],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[20],
                            cloudPoints[21],
                            cloudPoints[22],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[23],
                            cloudPoints[24],
                            cloudPoints[25],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[26],
                            cloudPoints[27],
                            cloudPoints[28],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[29],
                            cloudPoints[30],
                            cloudPoints[31],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[32],
                            cloudPoints[33],
                            cloudPoints[34],
                        ),
                        ScenePathCommand.CubicTo(
                            cloudPoints[35],
                            cloudPoints[36],
                            cloudPoints[0],
                        ),
                    ),
                    closed = true,
                )
            }
            else -> {
                elements += SceneText(
                    text = "?",
                    bounds = bounds,
                    color = WHITE,
                    fontSize = width * 0.42f,
                    weight = SceneTextWeight.Bold,
                    zIndex = 16,
                )
            }
        }
        return elements
    }

    private fun iconBackground(
        id: String,
        bounds: SceneRect,
    ): SceneShape = SceneShape(
        id = "$id-background",
        bounds = bounds,
        kind = SceneShapeKind.Rectangle,
        fill = ARCHITECTURE_BLUE,
        stroke = TRANSPARENT,
        strokeWidth = 0f,
        cornerRadius = 0f,
        zIndex = 10,
    )

    private fun normalize(
        elements: List<SceneElement>,
        db: ArchitectureDb,
        context: MermaidRenderContext,
    ): MermaidScene {
        val contentBounds = elements
            .mapNotNull(::elementBounds)
            .reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val padding = db.config.padding
        val dx = padding - contentBounds.left
        val dy = padding - contentBounds.top
        return MermaidScene(
            width = max(1f, contentBounds.width + padding * 2f),
            height = max(1f, contentBounds.height + padding * 2f),
            background = context.theme.background,
            elements = elements
                .map { element -> element.translate(dx, dy) }
                .sortedBy(SceneElement::zIndex),
            title = db.diagramTitle,
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
            viewportPadding = 0f,
            viewportSizing = if (db.config.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
    }

    private fun edgeAxis(edge: ArchitectureEdge): EdgeAxis = when {
        edge.lhsDir.isHorizontal() != edge.rhsDir.isHorizontal() -> EdgeAxis.Bend
        edge.lhsDir.isHorizontal() -> EdgeAxis.Horizontal
        else -> EdgeAxis.Vertical
    }

    private fun diagonalLabelRotation(edge: ArchitectureEdge): Float {
        val pair = "${edge.lhsDir.name}${edge.rhsDir.name}"
        return if (pair in setOf("LT", "TL", "BR", "RB")) -45f else 45f
    }

    private fun SceneRect.port(direction: ArchitectureDirection): ScenePoint = when (direction) {
        ArchitectureDirection.L -> ScenePoint(left, center.y)
        ArchitectureDirection.R -> ScenePoint(right, center.y)
        ArchitectureDirection.T -> ScenePoint(center.x, top)
        ArchitectureDirection.B -> ScenePoint(center.x, bottom)
    }

    private fun ScenePoint.shift(
        direction: ArchitectureDirection,
        distance: Float,
    ): ScenePoint = when (direction) {
        ArchitectureDirection.L -> copy(x = x - distance)
        ArchitectureDirection.R -> copy(x = x + distance)
        ArchitectureDirection.T -> copy(y = y - distance)
        ArchitectureDirection.B -> copy(y = y + distance)
    }

    private fun themeColor(
        context: MermaidRenderContext,
        key: String,
    ): SceneColor? = context.options.themeVariables[key]?.let(CssColorParser::parse)

    private fun List<ScenePoint>.toLineCommands(): List<ScenePathCommand> =
        mapIndexed { index, point ->
            if (index == 0) {
                ScenePathCommand.MoveTo(point)
            } else {
                ScenePathCommand.LineTo(point)
            }
        }

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneAsset -> element.bounds
        is ScenePath -> element.points.takeIf(List<ScenePoint>::isNotEmpty)?.let { points ->
            SceneRect(
                left = points.minOf(ScenePoint::x),
                top = points.minOf(ScenePoint::y),
                right = points.maxOf(ScenePoint::x),
                bottom = points.maxOf(ScenePoint::y),
            ).inflate(element.strokeWidth / 2f, element.strokeWidth / 2f)
        }
        is SceneShape -> element.bounds
        is SceneText -> element.bounds
    }

    private fun SceneElement.translate(
        dx: Float,
        dy: Float,
    ): SceneElement = when (this) {
        is SceneAsset -> copy(bounds = bounds.translate(dx, dy))
        is SceneShape -> copy(bounds = bounds.translate(dx, dy))
        is SceneText -> copy(
            bounds = bounds.translate(dx, dy),
            rotationPivot = rotationPivot?.let { point ->
                ScenePoint(point.x + dx, point.y + dy)
            },
        )
        is ScenePath -> copy(
            points = points.map { point -> ScenePoint(point.x + dx, point.y + dy) },
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

    private fun layoutError(message: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Layout(message))

    private enum class EdgeAxis {
        Horizontal,
        Vertical,
        Bend,
    }

    private companion object {
        val TRANSPARENT = SceneColor(0x00000000)
        val WHITE = SceneColor(0xFFFFFFFF)
        val ARCHITECTURE_BLUE = SceneColor(0xFF087EBF)
        val BUILT_IN_ICONS = setOf(
            "database",
            "server",
            "disk",
            "internet",
            "cloud",
            "unknown",
            "blank",
        )
    }
}
