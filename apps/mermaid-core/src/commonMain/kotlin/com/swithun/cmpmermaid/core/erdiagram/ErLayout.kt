package com.swithun.cmpmermaid.core.erdiagram

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.mermaidDarken
import com.swithun.cmpmermaid.core.mermaidLighten
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
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.parseGenericTypes
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErAttribute
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErAttributeKey
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErCardinality
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErDb
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErEntity
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErIdentification
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErRelationship
import com.swithun.cmpmermaid.core.erdiagram.upstream.mermaid.ErSubGraph
import com.swithun.cmpmermaid.core.flowchart.FlowDagreLayout
import com.swithun.cmpmermaid.core.flowchart.FlowDirection
import com.swithun.cmpmermaid.core.flowchart.FlowEdge
import com.swithun.cmpmermaid.core.flowchart.FlowElkLayout
import com.swithun.cmpmermaid.core.flowchart.FlowLayoutPlacement
import com.swithun.cmpmermaid.core.flowchart.FlowNode
import com.swithun.cmpmermaid.core.flowchart.FlowNodeStyle
import com.swithun.cmpmermaid.core.flowchart.FlowRoutedEdge
import com.swithun.cmpmermaid.core.flowchart.FlowStyleAdapter
import com.swithun.cmpmermaid.core.flowchart.FlowSubgraph
import com.swithun.cmpmermaid.core.flowchart.FlowchartDocument
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidEdgePathPort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidHtmlEntityDecoder
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidRenderedText
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import kotlin.math.max
import kotlin.math.min

/**
 * Native translation of Mermaid 12.0.0's ErDB.getData(), erBox.ts and unified renderer.
 */
internal class ErLayout {
    fun layout(
        db: ErDb,
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

        val subGraphIds = db.getSubGraphs().mapTo(linkedSetOf(), ErSubGraph::id)
        val entities = db.getEntities().values.filter { entity ->
            entity.sourceName !in subGraphIds
        }
        val entityVisuals = linkedMapOf<String, ErEntityVisual>()
        entities.forEachIndexed { colorIndex, entity ->
            val visual = when (val measured = measureEntity(db, entity, colorIndex, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            entityVisuals[entity.id] = visual
        }
        val subGraphVisuals = linkedMapOf<String, ErSubGraphVisual>()
        db.getSubGraphs().forEach { subGraph ->
            val visual = when (val measured = measureSubGraph(db, subGraph, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            subGraphVisuals[subGraph.id] = visual
        }
        val edgeVisuals = linkedMapOf<Int, ErEdgeVisual>()
        db.getRelationships().forEachIndexed { index, relationship ->
            val visual = when (val measured = measureEdge(relationship, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            if (visual != null) {
                edgeVisuals[index] = visual
            }
        }

        val document = buildLayoutDocument(
            db = db,
            entities = entities,
            subGraphVisuals = subGraphVisuals,
            edgeVisuals = edgeVisuals,
            context = context,
        )
        val nodeSizes = entityVisuals.mapValues { (_, visual) -> visual.size }
        val nodeLayouts = nodeSizes.mapValues { (_, size) -> rectangleLayout(size) }
        val options = context.options.copy(
            nodeSpacing = context.options.erNodeSpacing,
            rankSpacing = context.options.erRankSpacing,
            subGraphTitleTopMargin = max(
                context.options.subGraphTitleTopMargin,
                SUBGRAPH_TITLE_MARGIN,
            ),
        )
        val placement = when (val layoutName = context.options.layout) {
            "dagre" -> FlowDagreLayout.layout(
                document = document,
                nodeSizes = nodeSizes,
                nodeShapeLayouts = nodeLayouts,
                edgeLabelSizes = edgeVisuals.mapValues { (_, visual) -> visual.size },
                options = options,
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
                edgeLabelSizes = edgeVisuals.mapValues { (_, visual) -> visual.size },
                subgraphLabelSizes = subGraphVisuals.mapValues { (_, visual) ->
                    SceneSize(visual.label.metrics.width, visual.label.metrics.height)
                },
                options = options,
            )
            else -> GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "$layoutName layout",
                    message = "Native ER diagram has not connected the requested layout engine",
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
            entityVisuals = entityVisuals,
            subGraphVisuals = subGraphVisuals,
            edgeVisuals = edgeVisuals,
            placement = positioned,
            context = context,
        )
    }

    private fun measureEntity(
        db: ErDb,
        entity: ErEntity,
        colorIndex: Int,
        context: MermaidRenderContext,
    ): GMResult<ErEntityVisual, MermaidError> {
        val style = when (
            val parsed = FlowStyleAdapter.parse(
                styles = db.compiledStyles(entity.classes) + entity.styles,
                owner = "ER entity '${entity.sourceName}'",
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val title = when (
            val measured = measureText(
                source = entity.alias.ifEmpty { entity.label },
                style = style,
                context = context,
                // Mermaid: rendering-elements/shapes/erBox.ts -> addText/createText.
                maxWidth = context.options.wrappingWidth,
            )
        ) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        if (entity.attributes.isEmpty()) {
            return GMResult.Ok(
                ErEntityVisual(
                    source = entity,
                    style = style,
                    colorIndex = colorIndex,
                    size = SceneSize(
                        width = max(
                            context.options.erMinEntityWidth,
                            title.metrics.width + context.options.erDiagramPadding * 2f,
                        ),
                        height = max(
                            context.options.erMinEntityHeight,
                            title.metrics.height + context.options.erDiagramPadding * 3f,
                        ),
                    ),
                    title = title,
                ),
            )
        }

        val rows = mutableListOf<ErAttributeRow>()
        entity.attributes.forEach { attribute ->
            val row = when (val measured = measureAttribute(attribute, style, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            rows += row
        }
        val columnPadding = context.options.erDiagramPadding
        val textPadding = context.options.erEntityPadding *
            if (context.options.htmlLabels) 1f else 1.25f
        var typeWidth = rows.maxOfOrNull { it.type.metrics.width }?.plus(columnPadding) ?: 0f
        var nameWidth = rows.maxOfOrNull { it.name.metrics.width }?.plus(columnPadding) ?: 0f
        var keysWidth = rows
            .filter { it.keys.text.isNotEmpty() }
            .maxOfOrNull { it.keys.metrics.width }
            ?.plus(columnPadding)
            ?: 0f
        var commentWidth = rows
            .filter { it.comment.text.isNotEmpty() }
            .maxOfOrNull { it.comment.metrics.width }
            ?.plus(columnPadding)
            ?: 0f
        val activeColumns = 2 + if (keysWidth > 0f) 1 else 0 + if (commentWidth > 0f) 1 else 0
        val titleMinimum = title.metrics.width + columnPadding * 2f
        val columnTotal = typeWidth + nameWidth + keysWidth + commentWidth
        if (titleMinimum > columnTotal) {
            val addition = (titleMinimum - columnTotal) / activeColumns
            typeWidth += addition
            nameWidth += addition
            if (keysWidth > 0f) keysWidth += addition
            if (commentWidth > 0f) commentWidth += addition
        }
        val headerHeight = title.metrics.height + textPadding
        val measuredRows = rows.map { row ->
            row.copy(
                height = maxOf(
                    row.type.metrics.height,
                    row.name.metrics.height,
                    row.keys.metrics.height,
                    row.comment.metrics.height,
                ) + textPadding,
            )
        }
        val size = SceneSize(
            width = max(
                context.options.erMinEntityWidth,
                typeWidth + nameWidth + keysWidth + commentWidth,
            ),
            height = max(
                context.options.erMinEntityHeight,
                headerHeight + measuredRows.sumOf { row -> row.height.toDouble() }.toFloat(),
            ),
        )
        return GMResult.Ok(
            ErEntityVisual(
                source = entity,
                style = style,
                colorIndex = colorIndex,
                size = size,
                title = title,
                rows = measuredRows,
                headerHeight = headerHeight,
                typeWidth = typeWidth,
                nameWidth = nameWidth,
                keysWidth = keysWidth,
                commentWidth = commentWidth,
            ),
        )
    }

    private fun measureAttribute(
        attribute: ErAttribute,
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): GMResult<ErAttributeRow, MermaidError> {
        val values = listOf(
            parseGenericTypes(attribute.type).escapeAngles(),
            attribute.name,
            attribute.keys.joinToString(",") { key -> key.sourceName },
            attribute.comment,
        )
        val lines = mutableListOf<ErTextVisual>()
        values.forEach { value ->
            when (val measured = measureText(value, style, context)) {
                is GMResult.Ok -> lines += measured.value
                is GMResult.Err -> return measured
            }
        }
        return GMResult.Ok(
            ErAttributeRow(
                type = lines[0],
                name = lines[1],
                keys = lines[2],
                comment = lines[3],
            ),
        )
    }

    private fun measureSubGraph(
        db: ErDb,
        subGraph: ErSubGraph,
        context: MermaidRenderContext,
    ): GMResult<ErSubGraphVisual, MermaidError> {
        val style = when (
            val parsed = FlowStyleAdapter.parse(
                styles = db.compiledStyles(subGraph.classes) + subGraph.styles,
                owner = "ER subgraph '${subGraph.id}'",
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val label = when (val measured = measureText(subGraph.title, style, context)) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        return GMResult.Ok(ErSubGraphVisual(subGraph, style, label))
    }

    private fun measureEdge(
        relationship: ErRelationship,
        context: MermaidRenderContext,
    ): GMResult<ErEdgeVisual?, MermaidError> {
        if (relationship.roleA.isBlank()) {
            return GMResult.Ok(null)
        }
        val label = when (
            val measured = measureText(
                source = relationship.roleA,
                style = FlowNodeStyle(),
                context = context,
                fontSize = EDGE_FONT_SIZE,
            )
        ) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        return GMResult.Ok(
            ErEdgeVisual(
                text = label.text,
                spans = label.spans,
                size = SceneSize(label.metrics.width, label.metrics.height),
            ),
        )
    }

    private fun measureText(
        source: String,
        style: FlowNodeStyle,
        context: MermaidRenderContext,
        fontSize: Float = style.fontSize ?: context.options.fontSize ?: context.theme.fontSize,
        maxWidth: Float = UNWRAPPED_TEXT_WIDTH,
    ): GMResult<ErTextVisual, MermaidError> {
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
        val family = style.fontFamily ?: context.options.fontFamily ?: context.theme.fontFamily
        val lineHeight = style.lineHeightPixels
            ?.div(fontSize)
            ?.takeIf { value -> value.isFinite() && value > 0f }
            ?: style.lineHeightMultiplier
            ?: DEFAULT_LINE_HEIGHT
        val weight = style.fontWeight ?: SceneTextWeight.Normal
        val softWrap = maxWidth < UNWRAPPED_TEXT_WIDTH &&
            context.options.markdownAutoWrap &&
            rendered.text.any(Char::isWhitespace)
        val measuredMaxWidth = if (softWrap) maxWidth else UNWRAPPED_TEXT_WIDTH
        return try {
            GMResult.Ok(
                ErTextVisual(
                    text = rendered.text,
                    spans = rendered.spans,
                    metrics = context.textMetrics.measure(
                        TextMetricsRequest(
                            text = rendered.text,
                            fontSize = fontSize,
                            maxWidth = measuredMaxWidth,
                            lineHeight = lineHeight,
                            fontFamily = family,
                            weight = weight,
                            spans = rendered.spans,
                        ),
                    ),
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontFamily = family,
                    weight = weight,
                    softWrap = softWrap,
                ),
            )
        } catch (failure: Exception) {
            GMResult.Err(
                MermaidError.Unexpected.from(failure, "ER text measurement failed"),
            )
        }
    }

    private fun buildLayoutDocument(
        db: ErDb,
        entities: List<ErEntity>,
        subGraphVisuals: Map<String, ErSubGraphVisual>,
        edgeVisuals: Map<Int, ErEdgeVisual>,
        context: MermaidRenderContext,
    ): FlowchartDocument {
        val parentBySourceId = linkedMapOf<String, String>()
        db.getSubGraphs().asReversed().forEach { subGraph ->
            subGraph.nodes.forEach { id -> parentBySourceId[id] = subGraph.id }
        }
        val entityIdBySourceName = entities.associate { entity ->
            entity.sourceName to entity.id
        }
        val nodes = linkedMapOf<String, FlowNode>()
        entities.forEachIndexed { index, entity ->
            nodes[entity.id] = FlowNode(
                id = entity.id,
                label = "",
                labelSpans = emptyList(),
                labelType = FlowLabelType.Text,
                shape = SceneShapeKind.Rectangle,
                padding = 0f,
                minWidth = null,
                look = context.options.look,
                colorIndex = index,
            )
        }
        val subgraphs = db.getSubGraphs().asReversed().map { subGraph ->
            val visual = subGraphVisuals.getValue(subGraph.id)
            FlowSubgraph(
                id = subGraph.id,
                label = visual.label.text,
                labelSpans = visual.label.spans,
                nodeIds = subGraph.nodes.mapNotNullTo(linkedSetOf()) { id ->
                    entityIdBySourceName[id] ?: id.takeIf(subGraphVisuals::containsKey)
                },
                direction = subGraph.direction?.let(::direction),
                parentId = parentBySourceId[subGraph.id],
                padding = SUBGRAPH_PADDING,
                look = context.options.look,
                inlineStyle = visual.style,
            )
        }
        return FlowchartDocument(
            direction = direction(db.getDirection()),
            nodes = nodes,
            edges = db.getRelationships().mapIndexed { index, relationship ->
                FlowEdge(
                    id = relationship.id(index),
                    from = relationship.entityA,
                    to = relationship.entityB,
                    label = edgeVisuals[index]?.text,
                    labelSpans = edgeVisuals[index]?.spans.orEmpty(),
                    pattern = relationship.strokePattern,
                    arrowStart = relationship.specification.cardB.arrowHead,
                    arrowEnd = relationship.specification.cardA.arrowHead,
                    curve = ER_CURVE,
                    look = context.options.look,
                )
            },
            subgraphs = subgraphs,
            title = db.diagramTitle,
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
        )
    }

    private fun buildScene(
        db: ErDb,
        document: FlowchartDocument,
        entityVisuals: Map<String, ErEntityVisual>,
        subGraphVisuals: Map<String, ErSubGraphVisual>,
        edgeVisuals: Map<Int, ErEdgeVisual>,
        placement: FlowLayoutPlacement,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val elements = mutableListOf<SceneElement>()
        addSubGraphs(
            subgraphs = document.subgraphs,
            visuals = subGraphVisuals,
            bounds = placement.subgraphBounds,
            context = context,
            elements = elements,
        )
        when (
            val added = addEdges(
                relationships = db.getRelationships(),
                routedEdges = placement.edges,
                edgeVisuals = edgeVisuals,
                context = context,
                elements = elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return added
        }
        entityVisuals.forEach { (id, visual) ->
            val bounds = placement.nodeBounds[id]
                ?: return GMResult.Err(MermaidError.Layout("ER entity '$id' has no bounds"))
            addEntity(visual, bounds, context, elements)
        }
        when (val title = addDiagramTitle(db, context, elements)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return title
        }
        return GMResult.Ok(normalizeScene(document, elements, context))
    }

    private fun addSubGraphs(
        subgraphs: List<FlowSubgraph>,
        visuals: Map<String, ErSubGraphVisual>,
        bounds: Map<String, SceneRect>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        subgraphs.forEachIndexed { index, subgraph ->
            val visual = visuals[subgraph.id] ?: return@forEachIndexed
            val frame = bounds[visual.source.id] ?: return@forEachIndexed
            elements += SceneShape(
                id = "er-subgraph-${visual.source.id}",
                bounds = frame,
                kind = SceneShapeKind.Rectangle,
                fill = visual.style.fill ?: context.theme.groupFill,
                stroke = visual.style.stroke ?: context.theme.groupStroke,
                strokeWidth = visual.style.strokeWidth ?: 1f,
                strokePattern = visual.style.strokePattern ?: SceneStrokePattern.Solid,
                dashIntervals = visual.style.dashIntervals,
                cornerRadius = 0f,
                zIndex = index,
            )
            elements += visual.label.asSceneText(
                bounds = SceneRect(
                    left = frame.center.x - visual.label.metrics.width / 2f,
                    top = frame.top + SUBGRAPH_LABEL_TOP,
                    right = frame.center.x + visual.label.metrics.width / 2f,
                    bottom = frame.top + SUBGRAPH_LABEL_TOP + visual.label.metrics.height,
                ),
                color = visual.style.text ?: context.theme.groupText,
                alignment = SceneTextAlignment.Center,
                zIndex = index + 1,
            )
        }
    }

    private fun addEdges(
        relationships: List<ErRelationship>,
        routedEdges: Map<Int, FlowRoutedEdge>,
        edgeVisuals: Map<Int, ErEdgeVisual>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        relationships.forEachIndexed { index, relationship ->
            val routed = routedEdges[index]
                ?: return GMResult.Err(
                    MermaidError.Layout("ER relationship ${relationship.id(index)} was not routed"),
                )
            val arrowStart = relationship.specification.cardB.arrowHead
            val arrowEnd = relationship.specification.cardA.arrowHead
            val commands = when (
                val generated = MermaidEdgePathPort.generate(
                    points = routed.points,
                    curve = ER_CURVE,
                    arrowStart = arrowStart,
                    arrowEnd = arrowEnd,
                )
            ) {
                is GMResult.Ok -> generated.value
                is GMResult.Err -> return generated
            }
            val edgeId = relationship.id(index)
            elements += ScenePath(
                id = edgeId,
                points = routed.points,
                commands = commands,
                color = context.theme.edge,
                strokeWidth = context.theme.strokeWidth,
                strokePattern = relationship.strokePattern,
                arrowStart = arrowStart,
                arrowEnd = arrowEnd,
                curve = ER_CURVE,
                look = context.options.look,
                animated = false,
                dashIntervals = if (
                    relationship.specification.relationType ==
                    ErIdentification.NonIdentifying
                ) {
                    ER_DASH_INTERVALS
                } else {
                    emptyList()
                },
                markerBackground = context.theme.nodeFill,
                zIndex = 5,
            )
            edgeVisuals[index]?.let { visual ->
                val bounds = SceneRect(
                    left = routed.labelAnchor.x - visual.size.width / 2f - EDGE_LABEL_PADDING_X,
                    top = routed.labelAnchor.y - visual.size.height / 2f - EDGE_LABEL_PADDING_Y,
                    right = routed.labelAnchor.x + visual.size.width / 2f + EDGE_LABEL_PADDING_X,
                    bottom = routed.labelAnchor.y + visual.size.height / 2f + EDGE_LABEL_PADDING_Y,
                )
                elements += SceneShape(
                    id = "$edgeId-label-background",
                    bounds = bounds,
                    kind = SceneShapeKind.Rectangle,
                    fill = context.theme.edgeLabelFill,
                    stroke = TRANSPARENT,
                    strokeWidth = 0f,
                    cornerRadius = 0f,
                    zIndex = 6,
                )
                elements += SceneText(
                    text = visual.text,
                    bounds = bounds,
                    color = context.theme.nodeText,
                    fontSize = EDGE_FONT_SIZE,
                    lineHeight = DEFAULT_LINE_HEIGHT,
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    spans = visual.spans,
                    zIndex = 7,
                )
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun addEntity(
        visual: ErEntityVisual,
        bounds: SceneRect,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val fill = visual.style.fill ?: paletteFill(visual.colorIndex, context)
        val stroke = visual.style.stroke ?: paletteStroke(visual.colorIndex, context)
        val strokeWidth = visual.style.strokeWidth ?: context.theme.strokeWidth
        elements += SceneShape(
            id = visual.source.id,
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
        if (visual.rows.isNotEmpty() && visual.style.fill == null) {
            var top = bounds.top + visual.headerHeight
            visual.rows.forEachIndexed { index, row ->
                val rowBounds = SceneRect(bounds.left, top, bounds.right, top + row.height)
                elements += SceneShape(
                    id = "${visual.source.id}-row-$index",
                    bounds = rowBounds,
                    kind = SceneShapeKind.Rectangle,
                    fill = if (index % 2 == 0) context.theme.erRowOdd() else context.theme.erRowEven(),
                    stroke = TRANSPARENT,
                    strokeWidth = 0f,
                    cornerRadius = 0f,
                    zIndex = 11,
                )
                top += row.height
            }
        }
        if (visual.rows.isNotEmpty()) {
            elements += SceneShape(
                id = "${visual.source.id}-grid",
                bounds = bounds,
                kind = SceneShapeKind.Rectangle,
                geometry = entityGridGeometry(visual),
                fill = TRANSPARENT,
                stroke = stroke,
                strokeWidth = strokeWidth,
                cornerRadius = 0f,
                zIndex = 12,
            )
        }

        val titleBounds = SceneRect(
            left = bounds.center.x - visual.title.metrics.width / 2f,
            top = bounds.top + (visual.headerHeight - visual.title.metrics.height) / 2f,
            right = bounds.center.x + visual.title.metrics.width / 2f,
            bottom = bounds.top +
                (visual.headerHeight - visual.title.metrics.height) / 2f +
                visual.title.metrics.height,
        )
        elements += visual.title.asSceneText(
            bounds = titleBounds,
            color = visual.style.text ?: context.theme.nodeText,
            alignment = SceneTextAlignment.Center,
        )
        var rowTop = bounds.top + visual.headerHeight
        visual.rows.forEach { row ->
            val columns = listOf(
                row.type to visual.typeWidth,
                row.name to visual.nameWidth,
                row.keys to visual.keysWidth,
                row.comment to visual.commentWidth,
            ).filter { (_, width) -> width > 0f }
            var left = bounds.left
            columns.forEach { (line, width) ->
                val top = rowTop + (row.height - line.metrics.height) / 2f
                elements += line.asSceneText(
                    bounds = SceneRect(
                        left = left + context.options.erDiagramPadding / 2f,
                        top = top,
                        right = left + width - context.options.erDiagramPadding / 2f,
                        bottom = top + line.metrics.height,
                    ),
                    color = visual.style.text ?: context.theme.nodeText,
                    alignment = SceneTextAlignment.Start,
                )
                left += width
            }
            rowTop += row.height
        }
    }

    private fun entityGridGeometry(visual: ErEntityVisual): SceneShapeGeometry {
        val width = visual.size.width
        val height = visual.size.height
        val paths = mutableListOf<SceneShapePath>()
        paths += horizontalDivider(width, -height / 2f + visual.headerHeight)
        var columnX = -width / 2f
        listOf(
            visual.typeWidth,
            visual.nameWidth,
            visual.keysWidth,
        ).forEach { columnWidth ->
            if (columnWidth <= 0f) return@forEach
            columnX += columnWidth
            if (columnX < width / 2f - 0.001f) {
                paths += verticalDivider(
                    x = columnX,
                    top = -height / 2f + visual.headerHeight,
                    bottom = height / 2f,
                )
            }
        }
        var rowY = -height / 2f + visual.headerHeight
        visual.rows.dropLast(1).forEach { row ->
            rowY += row.height
            paths += horizontalDivider(width, rowY)
        }
        return SceneShapeGeometry(
            paths = paths,
            outline = rectanglePoints(width, height),
        )
    }

    private fun addDiagramTitle(
        db: ErDb,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val title = db.diagramTitle
            ?.takeIf(String::isNotBlank)
            ?.let(::decode)
            ?: return GMResult.Ok(Unit)
        val bounds = elements.mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
            ?: return GMResult.Ok(Unit)
        val metrics = try {
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = title,
                    fontSize = TITLE_FONT_SIZE,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    lineHeight = 1f,
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                ),
            )
        } catch (failure: Exception) {
            return GMResult.Err(
                MermaidError.Unexpected.from(failure, "ER title measurement failed"),
            )
        }
        elements += SceneText(
            text = title,
            bounds = SceneRect(
                left = bounds.center.x - metrics.width / 2f,
                top = bounds.top - context.options.erTitleTopMargin - metrics.height,
                right = bounds.center.x + metrics.width / 2f,
                bottom = bounds.top - context.options.erTitleTopMargin,
            ),
            color = context.theme.nodeText,
            fontSize = TITLE_FONT_SIZE,
            lineHeight = 1f,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
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

    private fun rectangleLayout(size: SceneSize): MermaidShapeLayout {
        val outline = rectanglePoints(size.width, size.height)
        return MermaidShapeLayout(
            size = size,
            labelOffset = ScenePoint(0f, 0f),
            geometry = SceneShapeGeometry(
                paths = listOf(SceneShapePath(outline)),
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

    private fun horizontalDivider(
        width: Float,
        y: Float,
    ): SceneShapePath = SceneShapePath(
        points = listOf(ScenePoint(-width / 2f, y), ScenePoint(width / 2f, y)),
        closed = false,
        fill = SceneShapePaint.None,
    )

    private fun verticalDivider(
        x: Float,
        top: Float,
        bottom: Float,
    ): SceneShapePath = SceneShapePath(
        points = listOf(ScenePoint(x, top), ScenePoint(x, bottom)),
        closed = false,
        fill = SceneShapePaint.None,
    )

    private fun paletteFill(
        index: Int,
        context: MermaidRenderContext,
    ): SceneColor = if (context.theme.bkgColorArray.isEmpty()) {
        context.theme.nodeFill
    } else {
        context.theme.colorFill(index)
    }

    private fun paletteStroke(
        index: Int,
        context: MermaidRenderContext,
    ): SceneColor = if (context.theme.borderColorArray.isEmpty()) {
        context.theme.nodeStroke
    } else {
        context.theme.colorStroke(index)
    }

    private fun direction(source: String): FlowDirection = when (source.uppercase()) {
        "BT" -> FlowDirection.BottomToTop
        "LR" -> FlowDirection.LeftToRight
        "RL" -> FlowDirection.RightToLeft
        else -> FlowDirection.TopToBottom
    }

    private val ErRelationship.strokePattern: SceneStrokePattern
        get() = if (specification.relationType == ErIdentification.Identifying) {
            SceneStrokePattern.Solid
        } else {
            SceneStrokePattern.Dashed
        }

    private val ErCardinality.arrowHead: SceneArrowHead
        get() = when (this) {
            ErCardinality.OnlyOne -> SceneArrowHead.ErOnlyOne
            ErCardinality.ZeroOrOne -> SceneArrowHead.ErZeroOrOne
            ErCardinality.OneOrMore -> SceneArrowHead.ErOneOrMore
            ErCardinality.ZeroOrMore -> SceneArrowHead.ErZeroOrMore
            // Mermaid 12's unified ER renderer parses MD_PARENT but does not
            // register the legacy diamond marker.
            ErCardinality.MdParent -> SceneArrowHead.None
        }

    private val ErAttributeKey.sourceName: String
        get() = when (this) {
            ErAttributeKey.Primary -> "PK"
            ErAttributeKey.Foreign -> "FK"
            ErAttributeKey.Unique -> "UK"
        }

    private fun ErRelationship.id(index: Int): String =
        "id$entityA-$entityB-${index + 1}"

    private fun ErTextVisual.asSceneText(
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
        softWrap = softWrap,
    )

    private fun decode(source: String): String =
        MermaidHtmlEntityDecoder.decode(MermaidPreprocessor.decodeEntities(source))
            .replace(HTML_BREAK, "\n")

    private fun String.escapeAngles(): String =
        replace("<", "&lt;").replace(">", "&gt;")

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

    private data class ErTextVisual(
        val text: String,
        val spans: List<SceneTextSpan>,
        val metrics: TextMetrics,
        val fontSize: Float,
        val lineHeight: Float,
        val fontFamily: String,
        val weight: SceneTextWeight,
        val softWrap: Boolean,
    )

    private data class ErAttributeRow(
        val type: ErTextVisual,
        val name: ErTextVisual,
        val keys: ErTextVisual,
        val comment: ErTextVisual,
        val height: Float = 0f,
    )

    private data class ErEntityVisual(
        val source: ErEntity,
        val style: FlowNodeStyle,
        val colorIndex: Int,
        val size: SceneSize,
        val title: ErTextVisual,
        val rows: List<ErAttributeRow> = emptyList(),
        val headerHeight: Float = size.height,
        val typeWidth: Float = 0f,
        val nameWidth: Float = 0f,
        val keysWidth: Float = 0f,
        val commentWidth: Float = 0f,
    )

    private data class ErSubGraphVisual(
        val source: ErSubGraph,
        val style: FlowNodeStyle,
        val label: ErTextVisual,
    )

    private data class ErEdgeVisual(
        val text: String,
        val spans: List<SceneTextSpan>,
        val size: SceneSize,
    )

    private companion object {
        val TRANSPARENT = SceneColor(0x00000000)
        val ER_DASH_INTERVALS = listOf(8f, 8f)
        val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        const val DEFAULT_LINE_HEIGHT = 1.5f
        const val EDGE_FONT_SIZE = 14f
        const val TITLE_FONT_SIZE = 18f
        const val EDGE_LABEL_PADDING_X = 6f
        const val EDGE_LABEL_PADDING_Y = 3f
        const val SUBGRAPH_PADDING = 8f
        const val SUBGRAPH_TITLE_MARGIN = 24f
        const val SUBGRAPH_LABEL_TOP = 4f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        const val ER_CURVE = "basis"
    }
}

// Mermaid.js 12.0.0: packages/mermaid/src/themes/theme-*.js -> updateColors (rowOdd/rowEven)
// Light themes resolve to near-white rows (lighten(mainBkg, 75) / '#ffffff'); the dark theme uses
// lighten(mainBkg, 5) / darken(mainBkg, 10). A hard-coded white made dark-theme attributes unreadable.
private fun MermaidTheme.isDarkNodeFill(): Boolean {
    val c = nodeFill.argb
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val b = c and 0xFF
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) < 128
}

internal fun MermaidTheme.erRowOdd(): SceneColor =
    rowOdd ?: if (isDarkNodeFill()) nodeFill.mermaidLighten(5.0) else ErLayoutRowDefaults.ODD

internal fun MermaidTheme.erRowEven(): SceneColor =
    rowEven ?: if (isDarkNodeFill()) nodeFill.mermaidDarken(10.0) else ErLayoutRowDefaults.EVEN

private object ErLayoutRowDefaults {
    val ODD = SceneColor(0xFFFFFFFF)
    val EVEN = SceneColor(0xFFF7F7F7)
}
