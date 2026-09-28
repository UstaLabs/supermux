package com.swithun.cmpmermaid.core.c4

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidC4ElementOptions
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.c4.upstream.mermaid.C4Boundary
import com.swithun.cmpmermaid.core.c4.upstream.mermaid.C4BoundaryOrShape
import com.swithun.cmpmermaid.core.c4.upstream.mermaid.C4Db
import com.swithun.cmpmermaid.core.c4.upstream.mermaid.C4DiagramType
import com.swithun.cmpmermaid.core.c4.upstream.mermaid.C4ElementType
import com.swithun.cmpmermaid.core.c4.upstream.mermaid.C4Relation
import com.swithun.cmpmermaid.core.c4.upstream.mermaid.C4Shape
import com.swithun.cmpmermaid.core.flowchart.FlowDirection
import com.swithun.cmpmermaid.core.flowchart.FlowNode
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidHtmlEntityDecoder
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapePort
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/c4/c4Renderer.ts,
 * c4ShapeAdapter.ts, svgDraw.ts, and
 * rendering-elements/shapes/c4LabelHelper.ts.
 */
internal class C4Layout {
    private var globalBoundaryMaxX = 0f
    private var globalBoundaryMaxY = 0f

    fun layout(
        db: C4Db,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val elements = mutableListOf<SceneElement>()
        val screenBounds = Bounds(widthLimit = SCREEN_AVAILABLE_WIDTH)
        screenBounds.setData(
            context.options.c4.diagramMarginX,
            context.options.c4.diagramMarginX,
            context.options.c4.diagramMarginY,
            context.options.c4.diagramMarginY,
        )
        globalBoundaryMaxX = context.options.c4.diagramMarginX
        globalBoundaryMaxY = context.options.c4.diagramMarginY

        when (
            val result = drawInsideBoundary(
                parentBounds = screenBounds,
                currentBoundaries = db.getBoundaries(""),
                db = db,
                context = context,
                elements = elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        when (val result = drawRelations(db, context, elements)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        return GMResult.Ok(buildScene(elements, db, context))
    }

    private fun drawInsideBoundary(
        parentBounds: Bounds,
        currentBoundaries: List<C4Boundary>,
        db: C4Db,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        if (currentBoundaries.isEmpty()) return GMResult.Ok(Unit)
        val currentBounds = Bounds(
            widthLimit = parentBounds.widthLimit /
                min(db.c4BoundaryInRow, currentBoundaries.size).coerceAtLeast(1),
        )
        currentBoundaries.forEachIndexed { index, boundary ->
            val header = measureBoundaryHeader(boundary, currentBounds.widthLimit, context)
            val x: Float
            val y: Float
            if (index == 0 || index % db.c4BoundaryInRow == 0) {
                x = parentBounds.startX + context.options.c4.diagramMarginX
                y = parentBounds.stopY + context.options.c4.diagramMarginY + header.totalHeight
            } else {
                x = if (currentBounds.stopX != currentBounds.startX) {
                    currentBounds.stopX + context.options.c4.diagramMarginX
                } else {
                    currentBounds.startX
                }
                y = currentBounds.startY
            }
            currentBounds.setData(x, x, y, y)

            when (
                val result = drawShapeArray(
                    currentBounds = currentBounds,
                    shapes = db.getC4ShapeArray(boundary.alias),
                    shapesInRow = db.c4ShapeInRow,
                    context = context,
                    elements = elements,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }

            val nested = db.getBoundaries(boundary.alias)
            if (nested.isNotEmpty()) {
                when (
                    val result = drawInsideBoundary(
                        parentBounds = currentBounds,
                        currentBoundaries = nested,
                        db = db,
                        context = context,
                        elements = elements,
                    )
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
            }
            if (boundary.alias != GLOBAL_BOUNDARY) {
                drawBoundary(boundary, currentBounds, header, context, elements)
            }
            parentBounds.stopY = max(
                currentBounds.stopY + context.options.c4.c4ShapeMargin,
                parentBounds.stopY,
            )
            parentBounds.stopX = max(
                currentBounds.stopX + context.options.c4.c4ShapeMargin,
                parentBounds.stopX,
            )
            globalBoundaryMaxX = max(globalBoundaryMaxX, parentBounds.stopX)
            globalBoundaryMaxY = max(globalBoundaryMaxY, parentBounds.stopY)
        }
        return GMResult.Ok(Unit)
    }

    private fun drawShapeArray(
        currentBounds: Bounds,
        shapes: List<C4Shape>,
        shapesInRow: Int,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val visuals = mutableListOf<C4ShapeVisual>()
        shapes.forEach { shape ->
            when (val result = measureShape(shape, context)) {
                is GMResult.Ok -> visuals += result.value
                is GMResult.Err -> return result
            }
        }
        visuals.forEach { visual ->
            currentBounds.insert(
                width = visual.layout.size.width,
                height = visual.layout.size.height,
                margin = context.options.c4.c4ShapeMargin,
                shapesInRow = shapesInRow,
                nextLinePaddingX = context.options.c4.nextLinePaddingX,
            ).also { bounds ->
                visual.shape.bounds = bounds
                visual.shape.outline = visual.layout.geometry.outline.map { point ->
                    ScenePoint(
                        x = point.x + bounds.center.x,
                        y = point.y + bounds.center.y,
                    )
                }
                drawShape(visual, bounds, context, elements)
            }
        }
        if (visuals.isNotEmpty()) {
            currentBounds.stopX += context.options.c4.c4ShapeMargin
            currentBounds.stopY += context.options.c4.c4ShapeMargin
        }
        return GMResult.Ok(Unit)
    }

    private fun measureShape(
        shape: C4Shape,
        context: MermaidRenderContext,
    ): GMResult<C4ShapeVisual, MermaidError> {
        val style = context.options.c4.elementStyles[shape.typeC4Shape.sourceName]
            ?: MermaidC4ElementOptions()
        val fontFamily = context.options.fontFamily
            ?: style.fontFamily
            .removeSurrounding("\"")
        val name = normalizeText(shape.label.text)
        val stereotype = buildString {
            append('[')
            append(shape.typeC4Shape.stereotype)
            if (shape.techn.text.isNotEmpty()) {
                append(": ")
                append(normalizeText(shape.techn.text))
            }
            append(']')
        }
        val sectionSources = buildList {
            if (name.isNotEmpty()) {
                add(C4SectionSource(name, style.fontSize, SceneTextWeight.Bold))
            }
            if (stereotype.isNotEmpty()) {
                add(
                    C4SectionSource(
                        stereotype,
                        style.fontSize * TYPE_FONT_SCALE,
                        fontWeight(style.fontWeight),
                    ),
                )
            }
            normalizeText(shape.descr.text).takeIf(String::isNotEmpty)?.let { description ->
                add(
                    C4SectionSource(
                        description,
                        style.fontSize * DESCRIPTION_FONT_SCALE,
                        fontWeight(style.fontWeight),
                    ),
                )
            }
        }
        val wrapWidth =
            (context.options.c4.width - 2f * context.options.c4.c4ShapePadding)
                .coerceAtLeast(MIN_WRAP_WIDTH)
        val sections = sectionSources.map { section ->
            val metrics = measure(
                text = section.text,
                fontSize = section.fontSize,
                maxWidth = if (shape.wrap) wrapWidth else UNBOUNDED_TEXT_WIDTH,
                weight = section.weight,
                fontFamily = fontFamily,
                context = context,
            )
            C4MeasuredSection(section, metrics)
        }
        val labelSize = SceneSize(
            width = sections.maxOfOrNull { section -> section.metrics.width } ?: 0f,
            height = sections.sumOf { section -> section.metrics.height.toDouble() }.toFloat() +
                SECTION_GAP * (sections.size - 1).coerceAtLeast(0),
        )
        val kind = resolveShapeKind(shape)
        val node = FlowNode(
            id = shape.alias,
            label = name,
            labelSpans = emptyList(),
            labelType = FlowLabelType.String,
            shape = kind,
            padding = context.options.c4.c4ShapePadding,
            minWidth = null,
            look = context.options.look,
        )
        val natural = when (
            val result = MermaidShapePort.layout(
                node = node,
                measuredLabel = labelSize,
                direction = FlowDirection.TopToBottom,
                defaultNodeStroke = shape.typeC4Shape.defaultStroke,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val layout = natural.ensureMinimumWidth(context.options.c4.width)
        return GMResult.Ok(
            C4ShapeVisual(
                shape = shape,
                kind = kind,
                layout = layout,
                sections = sections,
                labelSize = labelSize,
                style = style,
                fontFamily = fontFamily,
            ),
        )
    }

    private fun drawShape(
        visual: C4ShapeVisual,
        bounds: SceneRect,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val fill = visual.shape.bgColor?.let(CssColorParser::parse)
            ?: visual.style.background
            ?: visual.shape.typeC4Shape.defaultFill
        val stroke = visual.shape.borderColor?.let(CssColorParser::parse)
            ?: visual.style.border
            ?: visual.shape.typeC4Shape.defaultStroke
        elements += SceneShape(
            id = "c4-shape-${visual.shape.alias}",
            bounds = bounds,
            kind = visual.kind,
            geometry = visual.layout.geometry,
            fill = fill,
            stroke = stroke,
            strokeWidth = 2f,
            cornerRadius = if (
                visual.kind == SceneShapeKind.RoundedRectangle ||
                visual.kind == SceneShapeKind.Subroutine
            ) {
                12f
            } else {
                0f
            },
            zIndex = 10,
        )

        val textColor = visual.shape.fontColor?.let(CssColorParser::parse) ?: WHITE
        val labelCenter = ScenePoint(
            x = bounds.center.x + visual.layout.labelOffset.x,
            y = bounds.center.y + visual.layout.labelOffset.y,
        )
        var top = labelCenter.y - visual.labelSize.height / 2f
        visual.sections.forEach { section ->
            val height = section.metrics.height
            elements += SceneText(
                text = section.source.text,
                bounds = SceneRect(
                    left = labelCenter.x - visual.labelSize.width / 2f,
                    top = top,
                    right = labelCenter.x + visual.labelSize.width / 2f,
                    bottom = top + height,
                ),
                color = textColor,
                fontSize = section.source.fontSize,
                fontFamily = visual.fontFamily,
                weight = section.source.weight,
                softWrap = visual.shape.wrap,
                zIndex = 20,
            )
            top += height + SECTION_GAP
        }
    }

    private fun measureBoundaryHeader(
        boundary: C4Boundary,
        widthLimit: Float,
        context: MermaidRenderContext,
    ): C4BoundaryHeader {
        val fontFamily = context.options.fontFamily
            ?: context.options.c4.boundaryFontFamily.removeSurrounding("\"")
        var y = if (boundary.sprite != null) 48f else 0f
        val label = measure(
            text = normalizeText(boundary.label.text),
            fontSize = context.options.c4.boundaryFontSize + 2f,
            maxWidth = if (boundary.wrap) widthLimit else UNBOUNDED_TEXT_WIDTH,
            weight = SceneTextWeight.Bold,
            fontFamily = fontFamily,
            context = context,
        )
        val labelY = y + 8f
        y = labelY + label.height

        val typeText = boundary.type.text
            .takeIf(String::isNotEmpty)
            ?.let { value -> "[${normalizeText(value)}]" }
        val type = typeText?.let { value ->
            measure(
                text = value,
                fontSize = context.options.c4.boundaryFontSize,
                maxWidth = if (boundary.wrap) widthLimit else UNBOUNDED_TEXT_WIDTH,
                weight = fontWeight(context.options.c4.boundaryFontWeight),
                fontFamily = fontFamily,
                context = context,
            )
        }
        val typeY = type?.let {
            val value = y + 5f
            y = value + it.height
            value
        }
        val descriptionText = normalizeText(boundary.descr.text).takeIf(String::isNotEmpty)
        val description = descriptionText?.let { value ->
            measure(
                text = value,
                fontSize = context.options.c4.boundaryFontSize - 2f,
                maxWidth = if (boundary.wrap) widthLimit else UNBOUNDED_TEXT_WIDTH,
                weight = fontWeight(context.options.c4.boundaryFontWeight),
                fontFamily = fontFamily,
                context = context,
            )
        }
        val descriptionY = description?.let {
            val value = y + 20f
            y = value + it.height
            value
        }
        return C4BoundaryHeader(
            label = normalizeText(boundary.label.text),
            labelMetrics = label,
            labelY = labelY,
            type = typeText,
            typeMetrics = type,
            typeY = typeY,
            description = descriptionText,
            descriptionMetrics = description,
            descriptionY = descriptionY,
            totalHeight = y,
            fontFamily = fontFamily,
        )
    }

    private fun drawBoundary(
        boundary: C4Boundary,
        boundsData: Bounds,
        header: C4BoundaryHeader,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val bounds = SceneRect(
            left = boundsData.startX,
            top = boundsData.startY,
            right = boundsData.stopX,
            bottom = boundsData.stopY,
        )
        boundary.bounds = bounds
        elements += SceneShape(
            id = "c4-boundary-${boundary.alias}",
            bounds = bounds,
            kind = SceneShapeKind.RoundedRectangle,
            fill = boundary.bgColor?.let(CssColorParser::parse) ?: TRANSPARENT,
            stroke = boundary.borderColor?.let(CssColorParser::parse) ?: C4_DARK,
            strokeWidth = 1f,
            strokePattern = if (boundary.nodeType == null) {
                SceneStrokePattern.Dashed
            } else {
                SceneStrokePattern.Solid
            },
            dashIntervals = if (boundary.nodeType == null) listOf(7f, 7f) else emptyList(),
            cornerRadius = 2.5f,
            zIndex = 5,
        )
        addBoundaryText(
            text = header.label,
            metrics = header.labelMetrics,
            centerY = bounds.top + header.labelY,
            bounds = bounds,
            fontSize = context.options.c4.boundaryFontSize + 2f,
            weight = SceneTextWeight.Bold,
            fontFamily = header.fontFamily,
            elements = elements,
        )
        if (header.type != null && header.typeMetrics != null && header.typeY != null) {
            addBoundaryText(
                text = header.type,
                metrics = header.typeMetrics,
                centerY = bounds.top + header.typeY,
                bounds = bounds,
                fontSize = context.options.c4.boundaryFontSize,
                weight = fontWeight(context.options.c4.boundaryFontWeight),
                fontFamily = header.fontFamily,
                elements = elements,
            )
        }
        if (
            header.description != null &&
            header.descriptionMetrics != null &&
            header.descriptionY != null
        ) {
            addBoundaryText(
                text = header.description,
                metrics = header.descriptionMetrics,
                centerY = bounds.top + header.descriptionY,
                bounds = bounds,
                fontSize = context.options.c4.boundaryFontSize - 2f,
                weight = fontWeight(context.options.c4.boundaryFontWeight),
                fontFamily = header.fontFamily,
                elements = elements,
            )
        }
    }

    private fun addBoundaryText(
        text: String,
        metrics: TextMetrics,
        centerY: Float,
        bounds: SceneRect,
        fontSize: Float,
        weight: SceneTextWeight,
        fontFamily: String,
        elements: MutableList<SceneElement>,
    ) {
        elements += SceneText(
            text = text,
            bounds = SceneRect(
                left = bounds.center.x - metrics.width / 2f,
                top = centerY - metrics.height / 2f,
                right = bounds.center.x + metrics.width / 2f,
                bottom = centerY + metrics.height / 2f,
            ),
            color = C4_DARK,
            fontSize = fontSize,
            fontFamily = fontFamily,
            weight = weight,
            zIndex = 20,
        )
    }

    private fun drawRelations(
        db: C4Db,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        db.getRelations().forEachIndexed { index, relation ->
            val from = db.getElementBounds(relation.from)
                ?: return layoutError(
                    "C4 rel '${relation.from}' -> '${relation.to}' references an unknown element",
                )
            val to = db.getElementBounds(relation.to)
                ?: return layoutError(
                    "C4 rel '${relation.from}' -> '${relation.to}' references an unknown element",
                )
            val fromBounds = from.bounds
                ?: return layoutError("C4 element '${relation.from}' was not positioned")
            val toBounds = to.bounds
                ?: return layoutError("C4 element '${relation.to}' was not positioned")
            val start = intersect(from, toBounds.center)
            val end = intersect(to, fromBounds.center)
            val color = relation.lineColor?.let(CssColorParser::parse) ?: C4_DARK
            val arrowStart = if (relation.type == "birel" || relation.type == "rel_b") {
                SceneArrowHead.Triangle
            } else {
                SceneArrowHead.None
            }
            val arrowEnd = if (relation.type == "rel_b") {
                SceneArrowHead.None
            } else {
                SceneArrowHead.Triangle
            }
            val commands: List<ScenePathCommand>
            val points: List<ScenePoint>
            if (index == 0) {
                points = listOf(start, end)
                commands = listOf(
                    ScenePathCommand.MoveTo(start),
                    ScenePathCommand.LineTo(end),
                )
            } else {
                val control = ScenePoint(
                    x = start.x + (end.x - start.x) / 4f,
                    y = start.y + (end.y - start.y) / 2f,
                )
                points = listOf(start, control, end)
                commands = listOf(
                    ScenePathCommand.MoveTo(start),
                    ScenePathCommand.QuadraticTo(control, end),
                )
            }
            elements += ScenePath(
                id = "c4-relation-${index + 1}-${relation.from}-${relation.to}",
                points = points,
                commands = commands,
                color = color,
                strokeWidth = 1f,
                arrowStart = arrowStart,
                arrowEnd = arrowEnd,
                arrowColor = color,
                curve = "linear",
                look = "classic",
                animated = false,
                zIndex = 30,
            )
            drawRelationText(
                relation = relation,
                index = index,
                start = start,
                end = end,
                dynamic = db.diagramType == C4DiagramType.Dynamic,
                context = context,
                elements = elements,
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun drawRelationText(
        relation: C4Relation,
        index: Int,
        start: ScenePoint,
        end: ScenePoint,
        dynamic: Boolean,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val fontSize = context.options.c4.messageFontSize
        val fontFamily = context.options.fontFamily
            ?: context.options.c4.messageFontFamily.removeSurrounding("\"")
        val weight = fontWeight(context.options.c4.messageFontWeight)
        val label = normalizeText(
            if (dynamic) "${index + 1}: ${relation.label.text}" else relation.label.text,
        )
        val labelMetrics = measure(
            text = label,
            fontSize = fontSize,
            maxWidth = UNBOUNDED_TEXT_WIDTH,
            weight = weight,
            fontFamily = fontFamily,
            context = context,
        )
        val middleX = min(start.x, end.x) + abs(end.x - start.x) / 2f + relation.offsetX
        val middleY = min(start.y, end.y) + abs(end.y - start.y) / 2f + relation.offsetY
        val textColor = relation.textColor?.let(CssColorParser::parse) ?: C4_DARK
        elements += SceneText(
            text = label,
            bounds = SceneRect(
                left = middleX,
                top = middleY - labelMetrics.height / 2f,
                right = middleX + labelMetrics.width,
                bottom = middleY + labelMetrics.height / 2f,
            ),
            color = textColor,
            fontSize = fontSize,
            fontFamily = fontFamily,
            weight = weight,
            zIndex = 31,
        )
        normalizeText(relation.techn.text).takeIf(String::isNotEmpty)?.let { technology ->
            val value = "[$technology]"
            val metrics = measure(
                text = value,
                fontSize = fontSize,
                maxWidth = UNBOUNDED_TEXT_WIDTH,
                weight = weight,
                fontFamily = fontFamily,
                context = context,
            )
            val width = max(labelMetrics.width, metrics.width)
            val centerY = middleY + fontSize + 5f
            elements += SceneText(
                text = value,
                bounds = SceneRect(
                    left = middleX,
                    top = centerY - metrics.height / 2f,
                    right = middleX + width,
                    bottom = centerY + metrics.height / 2f,
                ),
                color = textColor,
                fontSize = fontSize,
                fontFamily = fontFamily,
                weight = weight,
                italic = true,
                zIndex = 31,
            )
        }
    }

    private fun intersect(
        element: C4BoundaryOrShape,
        toward: ScenePoint,
    ): ScenePoint {
        val bounds = element.bounds ?: return toward
        val outline = when (element) {
            is C4BoundaryOrShape.Boundary -> rectangleOutline(bounds)
            is C4BoundaryOrShape.Shape -> element.value.outline
        }
        if (outline.size < 3) return bounds.center
        val center = bounds.center
        val directionX = toward.x - center.x
        val directionY = toward.y - center.y
        var bestDistance = Float.POSITIVE_INFINITY
        var best = center
        (outline + outline.first()).zipWithNext().forEach { (first, second) ->
            val segmentX = second.x - first.x
            val segmentY = second.y - first.y
            val denominator = directionX * segmentY - directionY * segmentX
            if (abs(denominator) < 0.0001f) return@forEach
            val offsetX = first.x - center.x
            val offsetY = first.y - center.y
            val ray = (offsetX * segmentY - offsetY * segmentX) / denominator
            val segment = (offsetX * directionY - offsetY * directionX) / denominator
            if (ray >= 0f && segment in 0f..1f && ray < bestDistance) {
                bestDistance = ray
                best = ScenePoint(
                    x = center.x + directionX * ray,
                    y = center.y + directionY * ray,
                )
            }
        }
        return best
    }

    private fun resolveShapeKind(shape: C4Shape): SceneShapeKind {
        val explicit = sequenceOf(shape.shape, shape.sprite)
            .filterNotNull()
            .mapNotNull(::keywordShape)
            .firstOrNull()
        if (explicit != null) return explicit
        shape.tags
            ?.split(',')
            ?.firstNotNullOfOrNull { tag -> keywordShape(tag.trim()) }
            ?.let { return it }
        return when {
            shape.typeC4Shape.isPerson -> SceneShapeKind.Person
            shape.typeC4Shape.isDatabase -> SceneShapeKind.Cylinder
            shape.typeC4Shape.isQueue -> SceneShapeKind.DirectAccessStorage
            else -> SceneShapeKind.RoundedRectangle
        }
    }

    private fun keywordShape(value: String): SceneShapeKind? = when (value.lowercase()) {
        "person" -> SceneShapeKind.Person
        "box", "rounded" -> SceneShapeKind.RoundedRectangle
        "cylinder", "database", "db" -> SceneShapeKind.Cylinder
        "queue", "pipe" -> SceneShapeKind.DirectAccessStorage
        "component" -> SceneShapeKind.Subroutine
        else -> null
    }

    private fun MermaidShapeLayout.ensureMinimumWidth(minimumWidth: Float): MermaidShapeLayout {
        if (size.width >= minimumWidth || size.width <= 0f) return this
        val scale = minimumWidth / size.width
        fun ScenePoint.scaleX(): ScenePoint = copy(x = x * scale)
        return copy(
            size = size.copy(width = minimumWidth),
            labelOffset = labelOffset.copy(x = labelOffset.x * scale),
            geometry = SceneShapeGeometry(
                paths = geometry.paths.map { path ->
                    path.copy(points = path.points.map(ScenePoint::scaleX))
                },
                outline = geometry.outline.map(ScenePoint::scaleX),
            ),
        )
    }

    private fun buildScene(
        elements: List<SceneElement>,
        db: C4Db,
        context: MermaidRenderContext,
    ): MermaidScene {
        val marginX = context.options.c4.diagramMarginX
        val marginY = context.options.c4.diagramMarginY
        val boxStartX = marginX
        val boxStartY = marginY
        val boxWidth = globalBoundaryMaxX - boxStartX
        val boxHeight = globalBoundaryMaxY - boxStartY
        val title = db.diagramTitle?.takeIf(String::isNotBlank)
        val extraVertForTitle = if (title != null) TITLE_VIEWBOX_HEIGHT else 0f
        val viewBoxLeft = boxStartX - marginX
        val viewBoxTop = -(marginY + extraVertForTitle)
        val sceneElements = elements.toMutableList()
        title?.let { text ->
            val metrics = measure(
                text = text,
                fontSize = TITLE_FONT_SIZE,
                maxWidth = UNBOUNDED_TEXT_WIDTH,
                weight = SceneTextWeight.Normal,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                context = context,
            )
            val titleX = boxWidth / 2f - 4f * marginX
            val titleBaselineY = boxStartY + marginY
            sceneElements += SceneText(
                text = normalizeText(text),
                bounds = SceneRect(
                    left = titleX,
                    top = titleBaselineY - metrics.height,
                    right = titleX + metrics.width,
                    bottom = titleBaselineY,
                ),
                color = context.theme.textColor,
                fontSize = TITLE_FONT_SIZE,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                horizontalAlignment = SceneTextAlignment.Start,
                softWrap = false,
                zIndex = 40,
            )
        }
        return MermaidScene(
            width = max(1f, boxWidth + 2f * marginX),
            height = max(1f, boxHeight + 2f * marginY + extraVertForTitle),
            background = context.theme.background,
            elements = sceneElements
                .map { element -> element.translate(-viewBoxLeft, -viewBoxTop) }
                .sortedBy(SceneElement::zIndex),
            title = db.diagramTitle,
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
            viewportPadding = 0f,
            viewportSizing = if (context.options.c4.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
    }

    private fun measure(
        text: String,
        fontSize: Float,
        maxWidth: Float,
        weight: SceneTextWeight,
        fontFamily: String,
        context: MermaidRenderContext,
    ): TextMetrics = context.textMetrics.measure(
        TextMetricsRequest(
            text = normalizeText(text),
            fontSize = fontSize,
            maxWidth = maxWidth,
            fontFamily = fontFamily,
            weight = weight,
        ),
    )

    private fun normalizeText(value: String): String =
        MermaidHtmlEntityDecoder.decode(value)
            .replace(HTML_BREAK, "\n")

    private fun fontWeight(value: String): SceneTextWeight = when {
        value.equals("bold", ignoreCase = true) -> SceneTextWeight.Bold
        value.toIntOrNull()?.let { weight -> weight >= 600 } == true -> SceneTextWeight.Bold
        value.equals("normal", ignoreCase = true) -> SceneTextWeight.Normal
        else -> SceneTextWeight.Medium
    }

    private fun rectangleOutline(bounds: SceneRect): List<ScenePoint> = listOf(
        ScenePoint(bounds.left, bounds.top),
        ScenePoint(bounds.right, bounds.top),
        ScenePoint(bounds.right, bounds.bottom),
        ScenePoint(bounds.left, bounds.bottom),
    )

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneShape -> element.bounds
        is SceneText -> element.bounds
        is ScenePath -> element.points.takeIf(List<ScenePoint>::isNotEmpty)?.let { points ->
            SceneRect(
                left = points.minOf(ScenePoint::x),
                top = points.minOf(ScenePoint::y),
                right = points.maxOf(ScenePoint::x),
                bottom = points.maxOf(ScenePoint::y),
            ).inflate(element.strokeWidth / 2f, element.strokeWidth / 2f)
        }
        else -> null
    }

    private fun SceneElement.translate(
        dx: Float,
        dy: Float,
    ): SceneElement = when (this) {
        is SceneShape -> copy(bounds = bounds.translate(dx, dy))
        is SceneText -> copy(
            bounds = bounds.translate(dx, dy),
            rotationPivot = rotationPivot?.translate(dx, dy),
        )
        is ScenePath -> copy(
            points = points.map { point -> point.translate(dx, dy) },
            commands = commands.map { command -> command.translate(dx, dy) },
        )
        else -> this
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

    private fun layoutError(message: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Layout(message))

    private data class C4SectionSource(
        val text: String,
        val fontSize: Float,
        val weight: SceneTextWeight,
    )

    private data class C4MeasuredSection(
        val source: C4SectionSource,
        val metrics: TextMetrics,
    )

    private data class C4ShapeVisual(
        val shape: C4Shape,
        val kind: SceneShapeKind,
        val layout: MermaidShapeLayout,
        val sections: List<C4MeasuredSection>,
        val labelSize: SceneSize,
        val style: MermaidC4ElementOptions,
        val fontFamily: String,
    )

    private data class C4BoundaryHeader(
        val label: String,
        val labelMetrics: TextMetrics,
        val labelY: Float,
        val type: String?,
        val typeMetrics: TextMetrics?,
        val typeY: Float?,
        val description: String?,
        val descriptionMetrics: TextMetrics?,
        val descriptionY: Float?,
        val totalHeight: Float,
        val fontFamily: String,
    )

    private class Bounds(
        var widthLimit: Float,
    ) {
        var startX: Float = 0f
        var stopX: Float = 0f
        var startY: Float = 0f
        var stopY: Float = 0f
        private var nextStartX: Float = 0f
        private var nextStopX: Float = 0f
        private var nextStartY: Float = 0f
        private var nextStopY: Float = 0f
        private var count: Int = 0

        fun setData(
            startX: Float,
            stopX: Float,
            startY: Float,
            stopY: Float,
        ) {
            this.startX = startX
            this.stopX = stopX
            this.startY = startY
            this.stopY = stopY
            nextStartX = startX
            nextStopX = stopX
            nextStartY = startY
            nextStopY = stopY
            count = 0
        }

        fun insert(
            width: Float,
            height: Float,
            margin: Float,
            shapesInRow: Int,
            nextLinePaddingX: Float,
        ): SceneRect {
            count += 1
            var itemStartX = if (nextStartX == nextStopX) {
                nextStopX + margin
            } else {
                nextStopX + margin * 2f
            }
            var itemStopX = itemStartX + width
            var itemStartY = nextStartY + margin * 2f
            var itemStopY = itemStartY + height
            if (
                itemStartX >= widthLimit ||
                itemStopX >= widthLimit ||
                count > shapesInRow
            ) {
                itemStartX = nextStartX + margin + nextLinePaddingX
                itemStartY = nextStopY + margin * 2f
                itemStopX = itemStartX + width
                itemStopY = itemStartY + height
                nextStopX = itemStopX
                nextStartY = nextStopY
                nextStopY = itemStopY
                count = 1
            }
            startX = min(startX, itemStartX)
            startY = min(startY, itemStartY)
            stopX = max(stopX, itemStopX)
            stopY = max(stopY, itemStopY)
            nextStartX = min(nextStartX, itemStartX)
            nextStartY = min(nextStartY, itemStartY)
            nextStopX = max(nextStopX, itemStopX)
            nextStopY = max(nextStopY, itemStopY)
            return SceneRect(itemStartX, itemStartY, itemStopX, itemStopY)
        }
    }

    private companion object {
        const val GLOBAL_BOUNDARY = "global"
        const val SCREEN_AVAILABLE_WIDTH = 900f
        const val SECTION_GAP = 3f
        const val MIN_WRAP_WIDTH = 32f
        const val TYPE_FONT_SCALE = 0.75f
        const val DESCRIPTION_FONT_SCALE = 0.82f
        const val UNBOUNDED_TEXT_WIDTH = 100_000f
        const val TITLE_FONT_SIZE = 16f
        const val TITLE_VIEWBOX_HEIGHT = 60f
        val HTML_BREAK = Regex("(?i)<br\\s*/?>")
        val TRANSPARENT = SceneColor(0x00000000)
        val WHITE = SceneColor(0xFFFFFFFF)
        val C4_DARK = SceneColor(0xFF444444)
    }
}
