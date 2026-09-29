package com.swithun.cmpmermaid.core.requirement

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneAsset
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
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.flowchart.FlowDagreLayout
import com.swithun.cmpmermaid.core.flowchart.FlowDirection
import com.swithun.cmpmermaid.core.flowchart.FlowEdge
import com.swithun.cmpmermaid.core.flowchart.FlowElkLayout
import com.swithun.cmpmermaid.core.flowchart.FlowLayoutPlacement
import com.swithun.cmpmermaid.core.flowchart.FlowNode
import com.swithun.cmpmermaid.core.flowchart.FlowNodeStyle
import com.swithun.cmpmermaid.core.flowchart.FlowStyleAdapter
import com.swithun.cmpmermaid.core.flowchart.FlowchartDocument
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidEdgePathPort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidRenderedText
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import com.swithun.cmpmermaid.core.requirement.upstream.mermaid.RequirementDb
import com.swithun.cmpmermaid.core.requirement.upstream.mermaid.RequirementElement
import com.swithun.cmpmermaid.core.requirement.upstream.mermaid.RequirementNode
import com.swithun.cmpmermaid.core.requirement.upstream.mermaid.RequirementRelation
import com.swithun.cmpmermaid.core.requirement.upstream.mermaid.RequirementRelationshipType
import kotlin.math.max
import kotlin.math.min

/**
 * Native translation of Mermaid 12.0.0 requirementDb.getData(),
 * requirementBox.ts, requirementRenderer.ts, and requirement markers.
 */
internal class RequirementLayout {
    fun layout(
        db: RequirementDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        if (db.getRelationships().size > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "maxEdges",
                    actual = db.getRelationships().size,
                    maximum = context.options.maxEdges,
                ),
            )
        }

        val visuals = linkedMapOf<String, RequirementNodeVisual>()
        val allNodes = buildList<RequirementSource> {
            db.getRequirements().values.forEach { add(RequirementSource.Requirement(it)) }
            db.getElements().values.forEach { add(RequirementSource.Element(it)) }
        }
        allNodes.forEachIndexed { colorIndex, source ->
            val visual = when (val measured = measureNode(source, colorIndex, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            visuals[source.name] = visual
        }
        val labels = linkedMapOf<Int, RequirementTextVisual>()
        db.getRelationships().forEachIndexed { index, relation ->
            val visual = when (val measured = measureRelationship(relation, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            labels[index] = visual
        }

        val missingNode = db.getRelationships().firstOrNull { relation ->
            relation.source !in visuals || relation.destination !in visuals
        }
        if (missingNode != null) {
            val missing = if (missingNode.source !in visuals) {
                missingNode.source
            } else {
                missingNode.destination
            }
            return GMResult.Err(
                MermaidError.Layout(
                    "Requirement relationship references undefined node '$missing'",
                ),
            )
        }

        val document = buildLayoutDocument(db, visuals, labels, context)
        val nodeSizes = visuals.mapValues { (_, visual) -> visual.size }
        val nodeLayouts = nodeSizes.mapValues { (_, size) -> rectangleLayout(size) }
        val layoutOptions = context.options.copy(
            nodeSpacing = context.options.stateNodeSpacing ?: context.options.nodeSpacing,
            rankSpacing = context.options.stateRankSpacing ?: context.options.rankSpacing,
        )
        val placement = when (val layout = context.options.layout) {
            "dagre" -> FlowDagreLayout.layout(
                document = document,
                nodeSizes = nodeSizes,
                nodeShapeLayouts = nodeLayouts,
                edgeLabelSizes = labels.mapValues { (_, visual) ->
                    SceneSize(visual.metrics.width, visual.metrics.height)
                },
                options = layoutOptions,
            )
            "elk",
            "elk.layered",
            "elk.stress",
            "elk.force",
            "elk.mrtree",
            "elk.sporeOverlap",
            "elk.box",
            "elk.rectpacking",
            -> FlowElkLayout.layout(
                document = document,
                nodeSizes = nodeSizes,
                nodeShapeLayouts = nodeLayouts,
                edgeLabelSizes = labels.mapValues { (_, visual) ->
                    SceneSize(visual.metrics.width, visual.metrics.height)
                },
                subgraphLabelSizes = emptyMap(),
                options = layoutOptions,
            )
            else -> GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "$layout layout",
                    message = "Native Requirement diagram has not connected the requested layout engine",
                ),
            )
        }
        val positioned = when (placement) {
            is GMResult.Ok -> placement.value
            is GMResult.Err -> return placement
        }
        return buildScene(
            db = db,
            document = document,
            visuals = visuals,
            labels = labels,
            placement = positioned,
            context = context,
        )
    }

    private fun measureNode(
        source: RequirementSource,
        colorIndex: Int,
        context: MermaidRenderContext,
    ): GMResult<RequirementNodeVisual, MermaidError> {
        val style = when (
            val parsed = FlowStyleAdapter.parse(
                styles = source.cssStyles,
                owner = "Requirement node '${source.name}'",
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val type = when (
            val measured = measureText(
                source = source.typeLabel,
                style = style,
                forceBold = false,
                context = context,
            )
        ) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        val name = when (
            val measured = measureText(
                source = source.name,
                style = style,
                forceBold = true,
                context = context,
            )
        ) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        val body = mutableListOf<RequirementTextVisual>()
        source.bodyLines.forEach { line ->
            if (line.isEmpty()) {
                return@forEach
            }
            when (
                val measured = measureText(
                    source = line,
                    style = style,
                    forceBold = false,
                    context = context,
                )
            ) {
                is GMResult.Ok -> body += measured.value
                is GMResult.Err -> return measured
            }
        }

        val positionedLines = mutableListOf<RequirementPositionedText>()
        var yOffset = 0f
        positionedLines += RequirementPositionedText(type, yOffset, isBody = false)
        yOffset += type.metrics.height
        positionedLines += RequirementPositionedText(name, yOffset, isBody = false)
        yOffset += name.metrics.height + BODY_GAP
        body.forEach { line ->
            positionedLines += RequirementPositionedText(line, yOffset, isBody = true)
            yOffset += line.metrics.height
        }

        val minimumTop = positionedLines.minOf { positioned ->
            positioned.centerY - positioned.text.metrics.height / 2f
        }
        val maximumBottom = positionedLines.maxOf { positioned ->
            positioned.centerY + positioned.text.metrics.height / 2f
        }
        val totalWidth = positionedLines.maxOf { it.text.metrics.width } + BOX_PADDING
        val totalHeight = maximumBottom - minimumTop + BOX_PADDING
        return GMResult.Ok(
            RequirementNodeVisual(
                source = source,
                style = style,
                colorIndex = colorIndex,
                size = SceneSize(totalWidth, totalHeight),
                lines = positionedLines,
                dividerY = if (body.isEmpty()) {
                    null
                } else {
                    -totalHeight / 2f + type.metrics.height + name.metrics.height + BODY_GAP
                },
            ),
        )
    }

    private fun measureRelationship(
        relation: RequirementRelation,
        context: MermaidRenderContext,
    ): GMResult<RequirementTextVisual, MermaidError> = measureText(
        source = "&lt;&lt;${relation.type.sourceName}&gt;&gt;",
        style = FlowNodeStyle(),
        forceBold = false,
        context = context,
    )

    private fun measureText(
        source: String,
        style: FlowNodeStyle,
        forceBold: Boolean,
        context: MermaidRenderContext,
    ): GMResult<RequirementTextVisual, MermaidError> {
        val rendered = when (
            val result = MermaidTextPort.render(
                source = source,
                labelType = FlowLabelType.Markdown,
                config = context.options,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val fontSize = style.fontSize ?: context.options.fontSize ?: context.theme.fontSize
        val lineHeight = style.lineHeightPixels
            ?.div(fontSize)
            ?.takeIf { value -> value.isFinite() && value > 0f }
            ?: style.lineHeightMultiplier
            ?: DEFAULT_LINE_HEIGHT
        val fontFamily = style.fontFamily ?: context.options.fontFamily ?: context.theme.fontFamily
        val weight = if (forceBold) {
            SceneTextWeight.Bold
        } else {
            style.fontWeight ?: SceneTextWeight.Normal
        }
        val spans = buildList {
            addAll(rendered.spans)
            if (
                rendered.text.isNotEmpty() &&
                (style.italic == true || style.underline || style.lineThrough)
            ) {
                add(
                    SceneTextSpan(
                        start = 0,
                        end = rendered.text.length,
                        italic = style.italic == true,
                        underline = style.underline,
                        lineThrough = style.lineThrough,
                    ),
                )
            }
        }
        return try {
            val measured = context.textMetrics.measure(
                TextMetricsRequest(
                    text = rendered.text,
                    fontSize = fontSize,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily,
                    weight = weight,
                    spans = spans,
                ),
            )
            // Mermaid: requirementBox.ts -> addText() uses the HTML label's line box
            // height. Compose may report only glyph height for an isolated line.
            val visibleLineCount = if (rendered.text.isEmpty()) {
                0
            } else {
                rendered.text.count { character -> character == '\n' } + 1
            }
            val lineBoxHeight = visibleLineCount * fontSize * lineHeight
            GMResult.Ok(
                RequirementTextVisual(
                    text = rendered.text,
                    spans = spans,
                    metrics = TextMetrics(
                        width = measured.width,
                        height = max(measured.height, lineBoxHeight),
                    ),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily,
                    weight = weight,
                ),
            )
        } catch (failure: Exception) {
            GMResult.Err(
                MermaidError.Unexpected.from(failure, "Requirement text measurement failed"),
            )
        }
    }

    private fun buildLayoutDocument(
        db: RequirementDb,
        visuals: Map<String, RequirementNodeVisual>,
        labels: Map<Int, RequirementTextVisual>,
        context: MermaidRenderContext,
    ): FlowchartDocument = FlowchartDocument(
        direction = direction(db.getDirection()),
        nodes = visuals.mapValues { (id, visual) ->
            FlowNode(
                id = id,
                label = "",
                labelSpans = emptyList(),
                labelType = FlowLabelType.Text,
                shape = SceneShapeKind.Rectangle,
                padding = 0f,
                minWidth = null,
                look = context.options.look,
                inlineStyle = visual.style,
                colorIndex = visual.colorIndex,
            )
        },
        edges = db.getRelationships().mapIndexed { index, relation ->
            val isContains = relation.type == RequirementRelationshipType.Contains
            FlowEdge(
                id = relation.id(index),
                from = relation.source,
                to = relation.destination,
                label = labels[index]?.text,
                labelSpans = labels[index]?.spans.orEmpty(),
                pattern = if (isContains) {
                    SceneStrokePattern.Solid
                } else {
                    SceneStrokePattern.Dashed
                },
                arrowStart = if (isContains) {
                    SceneArrowHead.RequirementContains
                } else {
                    SceneArrowHead.None
                },
                arrowEnd = if (isContains) {
                    SceneArrowHead.None
                } else {
                    SceneArrowHead.RequirementArrow
                },
                curve = context.options.curve,
                look = context.options.look,
            )
        },
        subgraphs = emptyList(),
        title = db.diagramTitle,
        accessibilityTitle = db.accessibilityTitle,
        accessibilityDescription = db.accessibilityDescription,
    )

    private fun buildScene(
        db: RequirementDb,
        document: FlowchartDocument,
        visuals: Map<String, RequirementNodeVisual>,
        labels: Map<Int, RequirementTextVisual>,
        placement: FlowLayoutPlacement,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val elements = mutableListOf<SceneElement>()
        when (
            val added = addRelationships(
                relationships = db.getRelationships(),
                routedEdges = placement.edges,
                labels = labels,
                context = context,
                elements = elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return added
        }
        visuals.forEach { (id, visual) ->
            val bounds = placement.nodeBounds[id]
                ?: return GMResult.Err(
                    MermaidError.Layout("Requirement node '$id' has no bounds"),
                )
            addNode(visual, bounds, context, elements)
        }
        when (val title = addDiagramTitle(db, context, elements)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return title
        }
        return GMResult.Ok(normalizeScene(document, elements, context))
    }

    private fun addRelationships(
        relationships: List<RequirementRelation>,
        routedEdges: Map<Int, com.swithun.cmpmermaid.core.flowchart.FlowRoutedEdge>,
        labels: Map<Int, RequirementTextVisual>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        relationships.forEachIndexed { index, relation ->
            val routed = routedEdges[index]
                ?: return GMResult.Err(
                    MermaidError.Layout("Requirement relation ${relation.id(index)} was not routed"),
                )
            val contains = relation.type == RequirementRelationshipType.Contains
            val arrowStart = if (contains) {
                SceneArrowHead.RequirementContains
            } else {
                SceneArrowHead.None
            }
            val arrowEnd = if (contains) {
                SceneArrowHead.None
            } else {
                SceneArrowHead.RequirementArrow
            }
            val curve = routed.curveOverride ?: context.options.curve
            val commands = when (
                val generated = MermaidEdgePathPort.generate(
                    points = routed.points,
                    curve = curve,
                    arrowStart = arrowStart,
                    arrowEnd = arrowEnd,
                )
            ) {
                is GMResult.Ok -> generated.value
                is GMResult.Err -> return generated
            }
            val edgeId = relation.id(index)
            val strokeWidth = if (context.options.look == "neo") {
                context.theme.strokeWidth
            } else {
                1f
            }
            elements += ScenePath(
                id = edgeId,
                points = routed.points,
                commands = commands,
                color = context.theme.requirement.relationColor,
                strokeWidth = strokeWidth,
                strokePattern = if (contains) {
                    SceneStrokePattern.Solid
                } else {
                    SceneStrokePattern.Dashed
                },
                arrowStart = arrowStart,
                arrowEnd = arrowEnd,
                curve = curve,
                look = context.options.look,
                animated = false,
                dashIntervals = if (contains) emptyList() else RELATION_DASH_INTERVALS,
                markerBackground = context.theme.requirement.background,
                zIndex = 5,
            )
            val label = labels[index]
                ?: return GMResult.Err(
                    MermaidError.Layout("Requirement relation $edgeId has no label"),
                )
            val labelAnchor = MermaidEdgePathPort.positionEdgeLabel(
                layoutAnchor = routed.labelAnchor,
                points = routed.points,
                commands = commands,
            )
            val labelBounds = SceneRect(
                left = labelAnchor.x - label.metrics.width / 2f - EDGE_LABEL_PADDING_X,
                top = labelAnchor.y - label.metrics.height / 2f - EDGE_LABEL_PADDING_Y,
                right = labelAnchor.x + label.metrics.width / 2f + EDGE_LABEL_PADDING_X,
                bottom = labelAnchor.y + label.metrics.height / 2f + EDGE_LABEL_PADDING_Y,
            )
            elements += SceneShape(
                id = "$edgeId-label-background",
                bounds = labelBounds,
                kind = SceneShapeKind.Rectangle,
                fill = context.theme.requirement.edgeLabelBackground,
                stroke = TRANSPARENT,
                strokeWidth = 0f,
                cornerRadius = 0f,
                zIndex = 6,
            )
            elements += label.asSceneText(
                bounds = labelBounds,
                color = context.theme.requirement.relationLabelColor,
                alignment = SceneTextAlignment.Center,
                zIndex = 7,
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun addNode(
        visual: RequirementNodeVisual,
        bounds: SceneRect,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val fill = visual.style.fill ?: paletteFill(visual.colorIndex, context)
        val stroke = visual.style.stroke ?: paletteStroke(visual.colorIndex, context)
        val strokeWidth = visual.style.strokeWidth ?: context.theme.requirement.borderSize
        elements += SceneShape(
            id = visual.source.name,
            bounds = bounds,
            kind = SceneShapeKind.Rectangle,
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            strokePattern = visual.style.strokePattern ?: SceneStrokePattern.Solid,
            dashIntervals = visual.style.dashIntervals,
            cornerRadius = 0f,
            shadow = context.theme.dropShadow.takeIf { context.options.look == "neo" },
            zIndex = 10,
        )
        visual.dividerY?.let { dividerY ->
            val y = bounds.center.y + dividerY
            elements += ScenePath(
                id = "${visual.source.name}-divider",
                points = listOf(
                    ScenePoint(bounds.left, y),
                    ScenePoint(bounds.right, y),
                ),
                commands = listOf(
                    ScenePathCommand.MoveTo(ScenePoint(bounds.left, y)),
                    ScenePathCommand.LineTo(ScenePoint(bounds.right, y)),
                ),
                color = stroke,
                strokeWidth = strokeWidth,
                strokePattern = visual.style.strokePattern ?: SceneStrokePattern.Solid,
                look = context.options.look,
                animated = false,
                dashIntervals = visual.style.dashIntervals,
                zIndex = 11,
            )
        }

        // Mermaid: requirementBox.ts -> requirementBox() re-translates every body label
        // to x + padding / 2 after addText() applies the layout-specific text alignment.
        val bodyAlignment = SceneTextAlignment.Start
        visual.lines.forEach { positioned ->
            val line = positioned.text
            val top = bounds.top +
                positioned.centerY -
                line.metrics.height / 2f +
                BOX_PADDING
            val textBounds = if (positioned.isBody && bodyAlignment == SceneTextAlignment.Start) {
                val left = bounds.left + BOX_PADDING / 2f - START_ALIGNMENT_INSET
                SceneRect(
                    left = left,
                    top = top,
                    right = left + line.metrics.width,
                    bottom = top + line.metrics.height,
                )
            } else {
                SceneRect(
                    left = bounds.center.x - line.metrics.width / 2f,
                    top = top,
                    right = bounds.center.x + line.metrics.width / 2f,
                    bottom = top + line.metrics.height,
                )
            }
            elements += line.asSceneText(
                bounds = textBounds,
                color = visual.style.text ?: context.theme.requirement.textColor,
                alignment = if (positioned.isBody) {
                    bodyAlignment
                } else {
                    SceneTextAlignment.Center
                },
            )
        }
    }

    private fun addDiagramTitle(
        db: RequirementDb,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val title = db.diagramTitle?.takeIf(String::isNotBlank) ?: return GMResult.Ok(Unit)
        val bounds = elements.mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
            ?: return GMResult.Ok(Unit)
        val fontFamily = context.options.fontFamily ?: context.theme.fontFamily
        val metrics = try {
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = title,
                    fontSize = TITLE_FONT_SIZE,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    lineHeight = 1f,
                    fontFamily = fontFamily,
                    weight = SceneTextWeight.Normal,
                ),
            )
        } catch (failure: Exception) {
            return GMResult.Err(
                MermaidError.Unexpected.from(failure, "Requirement title measurement failed"),
            )
        }
        elements += SceneText(
            text = title,
            bounds = SceneRect(
                left = bounds.center.x - metrics.width / 2f,
                top = bounds.top - context.options.stateTitleTopMargin - metrics.height,
                right = bounds.center.x + metrics.width / 2f,
                bottom = bounds.top - context.options.stateTitleTopMargin,
            ),
            color = context.theme.requirement.textColor,
            fontSize = TITLE_FONT_SIZE,
            lineHeight = 1f,
            fontFamily = fontFamily,
            weight = SceneTextWeight.Normal,
            zIndex = 30,
        )
        return GMResult.Ok(Unit)
    }

    private fun normalizeScene(
        document: FlowchartDocument,
        elements: List<SceneElement>,
        context: MermaidRenderContext,
    ): MermaidScene {
        val bounds = elements.mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val padding = context.options.diagramPadding
        val translated = elements.map { element ->
            element.translate(padding - bounds.left, padding - bounds.top)
        }
        return MermaidScene(
            width = bounds.width + padding * 2f,
            height = bounds.height + padding * 2f,
            background = context.theme.background,
            elements = translated.sortedBy(SceneElement::zIndex),
            title = document.title,
            accessibilityTitle = document.accessibilityTitle,
            accessibilityDescription = document.accessibilityDescription,
        )
    }

    private fun paletteFill(
        index: Int,
        context: MermaidRenderContext,
    ): SceneColor = if (context.theme.bkgColorArray.isEmpty()) {
        context.theme.requirement.background
    } else {
        context.theme.colorFill(index)
    }

    private fun paletteStroke(
        index: Int,
        context: MermaidRenderContext,
    ): SceneColor = if (context.theme.borderColorArray.isEmpty()) {
        context.theme.requirement.borderColor
    } else {
        context.theme.colorStroke(index)
    }

    private fun direction(source: String): FlowDirection = when (source.uppercase()) {
        "BT" -> FlowDirection.BottomToTop
        "LR" -> FlowDirection.LeftToRight
        "RL" -> FlowDirection.RightToLeft
        else -> FlowDirection.TopToBottom
    }

    private fun RequirementRelation.id(index: Int): String =
        "$source-$destination-$index"

    private fun rectangleLayout(size: SceneSize): MermaidShapeLayout {
        val outline = rectanglePoints(size.width, size.height)
        return MermaidShapeLayout(
            size = size,
            labelOffset = ScenePoint(0f, 0f),
            geometry = SceneShapeGeometry(
                paths = listOf(
                    SceneShapePath(
                        points = outline,
                        fill = SceneShapePaint.Fill,
                        stroke = SceneShapePaint.Stroke,
                    ),
                ),
                outline = outline,
            ),
            showsLabel = false,
        )
    }

    private fun rectanglePoints(
        width: Float,
        height: Float,
    ): List<ScenePoint> = listOf(
        ScenePoint(-width / 2f, -height / 2f),
        ScenePoint(width / 2f, -height / 2f),
        ScenePoint(width / 2f, height / 2f),
        ScenePoint(-width / 2f, height / 2f),
    )

    private fun RequirementTextVisual.asSceneText(
        bounds: SceneRect,
        color: SceneColor,
        alignment: SceneTextAlignment,
        zIndex: Int = 20,
    ): SceneText = SceneText(
        text = text,
        bounds = bounds,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
        weight = weight,
        spans = spans,
        horizontalAlignment = alignment,
        zIndex = zIndex,
        softWrap = false,
    )

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneAsset -> element.bounds
        is SceneShape -> element.bounds
        is SceneText -> element.bounds
        is ScenePath -> {
            val first = element.points.firstOrNull()
            if (first == null) null else element.points.drop(1).fold(
                SceneRect(first.x, first.y, first.x, first.y),
            ) { bounds, point ->
                SceneRect(
                    left = min(bounds.left, point.x),
                    top = min(bounds.top, point.y),
                    right = max(bounds.right, point.x),
                    bottom = max(bounds.bottom, point.y),
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
        is SceneText -> copy(bounds = bounds.translate(dx, dy))
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

    private sealed interface RequirementSource {
        val name: String
        val cssStyles: List<String>
        val typeLabel: String
        val bodyLines: List<String>

        data class Requirement(
            val value: RequirementNode,
        ) : RequirementSource {
            override val name: String = value.name
            override val cssStyles: List<String> = value.cssStyles
            override val typeLabel: String = "&lt;&lt;${value.type.sourceName}&gt;&gt;"
            override val bodyLines: List<String> = listOf(
                value.requirementId.takeIf(String::isNotEmpty)?.let { "ID: $it" }.orEmpty(),
                value.text.takeIf(String::isNotEmpty)?.let { "Text: $it" }.orEmpty(),
                value.risk?.sourceName?.let { "Risk: $it" }.orEmpty(),
                value.verifyMethod?.sourceName?.let { "Verification: $it" }.orEmpty(),
            )
        }

        data class Element(
            val value: RequirementElement,
        ) : RequirementSource {
            override val name: String = value.name
            override val cssStyles: List<String> = value.cssStyles
            override val typeLabel: String = "&lt;&lt;Element&gt;&gt;"
            override val bodyLines: List<String> = listOf(
                value.type.takeIf(String::isNotEmpty)?.let { "Type: $it" }.orEmpty(),
                value.docRef.takeIf(String::isNotEmpty)?.let { "Doc Ref: $it" }.orEmpty(),
            )
        }
    }

    private data class RequirementTextVisual(
        val text: String,
        val spans: List<SceneTextSpan>,
        val metrics: TextMetrics,
        val fontSize: Float,
        val lineHeight: Float,
        val fontFamily: String,
        val weight: SceneTextWeight,
    )

    private data class RequirementPositionedText(
        val text: RequirementTextVisual,
        val centerY: Float,
        val isBody: Boolean,
    )

    private data class RequirementNodeVisual(
        val source: RequirementSource,
        val style: FlowNodeStyle,
        val colorIndex: Int,
        val size: SceneSize,
        val lines: List<RequirementPositionedText>,
        val dividerY: Float?,
    )

    private companion object {
        const val BOX_PADDING = 20f
        const val BODY_GAP = 20f
        // Mermaid requirementBox.ts HTML labels resolve 14px text to a 21px line box.
        const val DEFAULT_LINE_HEIGHT = 1.5f
        const val TITLE_FONT_SIZE = 18f
        const val EDGE_LABEL_PADDING_X = 6f
        const val EDGE_LABEL_PADDING_Y = 3f
        // SceneTextAlignment.Start paints at bounds.left + 4.
        const val START_ALIGNMENT_INSET = 4f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        val RELATION_DASH_INTERVALS = listOf(10f, 7f)
        val TRANSPARENT = SceneColor(0x00000000)
    }
}
