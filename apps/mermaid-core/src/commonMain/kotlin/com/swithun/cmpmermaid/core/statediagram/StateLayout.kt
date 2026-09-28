package com.swithun.cmpmermaid.core.statediagram

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneNodeInteraction
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
import com.swithun.cmpmermaid.core.flowchart.FlowRoutedEdge
import com.swithun.cmpmermaid.core.flowchart.FlowStyleAdapter
import com.swithun.cmpmermaid.core.flowchart.FlowSubgraph
import com.swithun.cmpmermaid.core.flowchart.FlowchartDocument
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidEdgePathPort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidHtmlEntityDecoder
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidRenderedText
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapePort
import com.swithun.cmpmermaid.core.statediagram.upstream.mermaid.StateDataFetcher
import com.swithun.cmpmermaid.core.statediagram.upstream.mermaid.StateDb
import com.swithun.cmpmermaid.core.statediagram.upstream.mermaid.StateNodeType
import com.swithun.cmpmermaid.core.statediagram.upstream.mermaid.StateRenderData
import com.swithun.cmpmermaid.core.statediagram.upstream.mermaid.StateRenderEdge
import com.swithun.cmpmermaid.core.statediagram.upstream.mermaid.StateRenderGroup
import com.swithun.cmpmermaid.core.statediagram.upstream.mermaid.StateRenderNode
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import kotlin.math.max
import kotlin.math.min

/**
 * Native translation of Mermaid 12.0.0 state dataFetcher, unified renderer,
 * and state-specific shape projection.
 */
internal class StateLayout {
    fun layout(
        db: StateDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val data = when (val fetched = StateDataFetcher.fetch(db)) {
            is GMResult.Ok -> fetched.value
            is GMResult.Err -> return fetched
        }
        if (data.edges.size > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "maxEdges",
                    actual = data.edges.size,
                    maximum = context.options.maxEdges,
                ),
            )
        }

        val visuals = linkedMapOf<String, StateNodeVisual>()
        data.nodes.values.forEach { node ->
            val visual = when (val measured = measureNode(node, data, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            visuals[node.id] = visual
        }
        val groupVisuals = linkedMapOf<String, StateGroupVisual>()
        data.groups.forEach { group ->
            val visual = when (val measured = measureGroup(group, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            groupVisuals[group.id] = visual
        }
        val edgeVisuals = linkedMapOf<Int, StateEdgeVisual>()
        data.edges.forEachIndexed { index, edge ->
            val label = edge.label?.takeIf(String::isNotBlank) ?: return@forEachIndexed
            val visual = when (val measured = measureEdge(label, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            edgeVisuals[index] = visual
        }

        val document = buildLayoutDocument(data, visuals, groupVisuals, edgeVisuals, db, context)
        val layoutName = context.options.stateLayout ?: context.options.layout
        val options = context.options.copy(
            layout = layoutName,
            nodeSpacing = context.options.stateNodeSpacing ?: context.options.nodeSpacing,
            rankSpacing = context.options.stateRankSpacing ?: context.options.rankSpacing,
            subGraphTitleTopMargin = max(
                context.options.subGraphTitleTopMargin,
                STATE_GROUP_TITLE_MARGIN,
            ),
        )
        val placement = when (layoutName) {
            "dagre" -> FlowDagreLayout.layout(
                document = document,
                nodeSizes = visuals.mapValues { (_, visual) -> visual.layout.size },
                nodeShapeLayouts = visuals.mapValues { (_, visual) -> visual.layout },
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
                nodeSizes = visuals.mapValues { (_, visual) -> visual.layout.size },
                nodeShapeLayouts = visuals.mapValues { (_, visual) -> visual.layout },
                edgeLabelSizes = edgeVisuals.mapValues { (_, visual) -> visual.size },
                subgraphLabelSizes = groupVisuals.mapValues { (_, visual) ->
                    visual.label?.metrics?.let { SceneSize(it.width, it.height) }
                        ?: SceneSize(0f, 0f)
                },
                options = options,
            )
            else -> GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "$layoutName layout",
                    message = "Native State diagram has not connected the requested layout engine",
                ),
            )
        }
        val positioned = when (placement) {
            is GMResult.Ok -> placement.value
            is GMResult.Err -> return placement
        }
        return buildScene(
            db = db,
            data = data,
            document = document,
            visuals = visuals,
            groupVisuals = groupVisuals,
            edgeVisuals = edgeVisuals,
            placement = positioned,
            context = context,
        )
    }

    private fun measureNode(
        node: StateRenderNode,
        data: StateRenderData,
        context: MermaidRenderContext,
    ): GMResult<StateNodeVisual, MermaidError> {
        val style = when (
            val parsed = FlowStyleAdapter.parse(
                styles = node.styles,
                owner = "state '${node.id}'",
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val direction = node.parentId
            ?.let { parent -> data.groups.firstOrNull { it.id == parent }?.direction }
            ?.let(::direction)
            ?: direction(data.direction)
        if (node.noteOwner != null) {
            return measureNote(node, style, context)
        }
        if (node.type in NON_TEXT_TYPES) {
            val shape = shape(node.type)
            val layout = if (node.type == StateNodeType.Choice) {
                choiceLayout()
            } else {
                when (
                    val measured = MermaidShapePort.layout(
                        node = flowNode(node, shape, context, style),
                        measuredLabel = SceneSize(0f, 0f),
                        direction = direction,
                        defaultNodeStroke = context.theme.nodeStroke,
                    )
                ) {
                    is GMResult.Ok -> measured.value
                    is GMResult.Err -> return measured
                }
            }
            return GMResult.Ok(
                StateNodeVisual(
                    source = node,
                    style = style,
                    layout = layout,
                    title = null,
                    description = null,
                ),
            )
        }

        val title = when (val rendered = renderLine(node.labels.firstOrNull().orEmpty(), style, context)) {
            is GMResult.Ok -> rendered.value
            is GMResult.Err -> return rendered
        }
        val description = if (node.labels.size > 1) {
            when (
                val rendered = renderLine(
                    node.labels.drop(1).joinToString("<br/>"),
                    style,
                    context,
                )
            ) {
                is GMResult.Ok -> rendered.value
                is GMResult.Err -> return rendered
            }
        } else {
            null
        }
        // Mermaid 12.0.0: rendering-elements/shapes/util.ts -> withMinWidth.
        val measuredTitle = if (description == null) {
            title.withMinWidth(context.options.stateMinNodeWidth)
        } else {
            title
        }
        val measuredLabel = SceneSize(
            width = max(
                context.options.stateMinNodeWidth,
                max(measuredTitle.metrics.width, description?.metrics?.width ?: 0f),
            ),
            height = measuredTitle.metrics.height +
                (description?.metrics?.height ?: 0f) +
                if (description == null) 0f else DESCRIPTION_GAP,
        )
        val baseLayout = when (
            val measured = MermaidShapePort.layout(
                node = flowNode(node, SceneShapeKind.RoundedRectangle, context, style),
                measuredLabel = measuredLabel,
                direction = direction,
                defaultNodeStroke = context.theme.nodeStroke,
            )
        ) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        val layout = if (description == null) {
            baseLayout
        } else {
            val dividerY = -baseLayout.size.height / 2f +
                context.options.statePadding +
                title.metrics.height +
                DESCRIPTION_GAP / 2f
            baseLayout.copy(
                geometry = baseLayout.geometry.copy(
                    paths = baseLayout.geometry.paths + SceneShapePath(
                        points = listOf(
                            ScenePoint(-baseLayout.size.width / 2f, dividerY),
                            ScenePoint(baseLayout.size.width / 2f, dividerY),
                        ),
                        closed = false,
                        fill = SceneShapePaint.None,
                    ),
                ),
            )
        }
        return GMResult.Ok(
            StateNodeVisual(
                source = node,
                style = style,
                layout = layout,
                title = measuredTitle,
                description = description,
            ),
        )
    }

    private fun measureNote(
        node: StateRenderNode,
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): GMResult<StateNodeVisual, MermaidError> {
        val line = when (val rendered = renderLine(node.labels.firstOrNull().orEmpty(), style, context)) {
            is GMResult.Ok -> rendered.value
            is GMResult.Err -> return rendered
        }.withMinWidth(context.options.stateMinNodeWidth)
        // Mermaid 12.0.0: state/dataFetcher.ts -> noteData.padding.
        val padding = context.options.flowchartPadding
        val size = SceneSize(
            width = line.metrics.width + padding * 2f,
            height = line.metrics.height + padding * 2f,
        )
        return GMResult.Ok(
            StateNodeVisual(
                source = node,
                style = style,
                layout = rectangleLayout(size, noteGeometry(size)),
                title = line,
                description = null,
            ),
        )
    }

    private fun measureGroup(
        group: StateRenderGroup,
        context: MermaidRenderContext,
    ): GMResult<StateGroupVisual, MermaidError> {
        val style = when (
            val parsed = FlowStyleAdapter.parse(
                styles = group.styles,
                owner = "state group '${group.id}'",
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        if (group.note || group.divider || group.label.isEmpty()) {
            return GMResult.Ok(StateGroupVisual(group, style, null))
        }
        val label = when (val rendered = renderLine(group.label, style, context)) {
            is GMResult.Ok -> rendered.value
            is GMResult.Err -> return rendered
        }
        return GMResult.Ok(StateGroupVisual(group, style, label))
    }

    private fun measureEdge(
        source: String,
        context: MermaidRenderContext,
    ): GMResult<StateEdgeVisual, MermaidError> {
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
        val metrics = measure(
            rendered = rendered,
            style = FlowNodeStyle(),
            context = context,
            maxWidth = context.options.stateWrappingWidth,
        )
        return GMResult.Ok(
            StateEdgeVisual(
                text = rendered.text,
                spans = rendered.spans,
                size = SceneSize(metrics.width, metrics.height),
            ),
        )
    }

    private fun renderLine(
        source: String,
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): GMResult<StateTextVisual, MermaidError> {
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
        return GMResult.Ok(
            StateTextVisual(
                text = rendered.text,
                spans = rendered.spans,
                metrics = measure(
                    rendered = rendered,
                    style = style,
                    context = context,
                    maxWidth = context.options.stateWrappingWidth,
                ),
            ),
        )
    }

    private fun measure(
        rendered: MermaidRenderedText,
        style: FlowNodeStyle,
        context: MermaidRenderContext,
        maxWidth: Float,
    ): TextMetrics {
        val fontSize = style.fontSize ?: context.options.fontSize ?: context.theme.fontSize
        val lineHeight = style.lineHeightMultiplier ?: DEFAULT_LINE_HEIGHT
        val measured = context.textMetrics.measure(
            TextMetricsRequest(
                text = rendered.text,
                fontSize = fontSize,
                maxWidth = maxWidth,
                lineHeight = lineHeight,
                fontFamily = style.fontFamily ?: context.options.fontFamily ?: context.theme.fontFamily,
                weight = style.fontWeight ?: SceneTextWeight.Normal,
                spans = rendered.spans,
            ),
        )
        // CSS foreignObject labels retain the complete line box; Compose trims
        // leading from TextLayoutResult.size, so restore it from the measured line count.
        return measured.lineCount?.let { lineCount ->
            measured.copy(height = lineCount * fontSize * lineHeight)
        } ?: measured
    }

    private fun buildLayoutDocument(
        data: StateRenderData,
        visuals: Map<String, StateNodeVisual>,
        groupVisuals: Map<String, StateGroupVisual>,
        edgeVisuals: Map<Int, StateEdgeVisual>,
        db: StateDb,
        context: MermaidRenderContext,
    ): FlowchartDocument {
        val nodes = data.nodes.mapValues { (_, node) ->
            val visual = visuals.getValue(node.id)
            flowNode(
                node = node,
                shape = shape(node.type, node.noteOwner != null),
                context = context,
                style = visual.style,
            )
        }
        val subgraphs = data.groups.map { group ->
            val visual = groupVisuals.getValue(group.id)
            FlowSubgraph(
                id = group.id,
                label = visual.label?.text.orEmpty(),
                labelSpans = visual.label?.spans.orEmpty(),
                nodeIds = data.nodes.values
                    .filter { node -> node.parentId == group.id }
                    .mapTo(linkedSetOf(), StateRenderNode::id),
                direction = group.direction?.let(::direction),
                parentId = group.parentId,
                padding = STATE_GROUP_PADDING,
                look = context.options.look,
                inlineStyle = visual.style,
                colorIndex = group.colorIndex,
                metadata = mapOf(
                    "algorithm" to if (group.divider) "dagre" else "inherit",
                ),
            )
        }
        return FlowchartDocument(
            direction = direction(data.direction),
            nodes = nodes,
            edges = data.edges.mapIndexed { index, edge ->
                FlowEdge(
                    id = edge.id,
                    from = edge.start,
                    to = edge.end,
                    label = edgeVisuals[index]?.text,
                    labelSpans = edgeVisuals[index]?.spans.orEmpty(),
                    pattern = edge.pattern,
                    arrowEnd = if (edge.arrow) SceneArrowHead.Triangle else SceneArrowHead.None,
                    look = context.options.look,
                )
            },
            subgraphs = subgraphs,
            title = db.diagramTitle,
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
        )
    }

    private fun flowNode(
        node: StateRenderNode,
        shape: SceneShapeKind,
        context: MermaidRenderContext,
        style: FlowNodeStyle,
    ): FlowNode = FlowNode(
        id = node.id,
        label = node.labels.joinToString("\n"),
        labelSpans = emptyList(),
        labelType = FlowLabelType.Markdown,
        shape = shape,
        padding = context.options.statePadding,
        minWidth = context.options.stateMinNodeWidth,
        look = context.options.look,
        inlineStyle = style,
        position = node.position,
    )

    private fun buildScene(
        db: StateDb,
        data: StateRenderData,
        document: FlowchartDocument,
        visuals: Map<String, StateNodeVisual>,
        groupVisuals: Map<String, StateGroupVisual>,
        edgeVisuals: Map<Int, StateEdgeVisual>,
        placement: FlowLayoutPlacement,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val elements = mutableListOf<SceneElement>()
        addGroups(data, groupVisuals, placement.subgraphBounds, context, elements)
        when (
            val result = addEdges(
                data.edges,
                edgeVisuals,
                placement.edges,
                context,
                elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        visuals.forEach { (id, visual) ->
            val bounds = placement.nodeBounds[id]
                ?: return GMResult.Err(MermaidError.Layout("State '$id' has no bounds"))
            addNode(visual, bounds, context, elements)
        }
        val layoutOnlyBounds = data.groups
            .filter(StateRenderGroup::note)
            .mapNotNull { group -> placement.subgraphBounds[group.id] }
        when (val result = addDiagramTitle(db, context, elements, layoutOnlyBounds)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        return GMResult.Ok(
            normalizeScene(
                db = db,
                document = document,
                elements = elements,
                layoutOnlyBounds = layoutOnlyBounds,
                context = context,
            ),
        )
    }

    private fun addGroups(
        data: StateRenderData,
        visuals: Map<String, StateGroupVisual>,
        bounds: Map<String, SceneRect>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val groups = data.groups.associateBy(StateRenderGroup::id)
        val depths = data.groups.associate { group ->
            group.id to generateSequence(group.parentId) { parent ->
                groups[parent]?.parentId
            }.count()
        }
        data.groups.sortedBy { depths[it.id] ?: 0 }.forEach { group ->
            // Mermaid 12.0.0: rendering-elements/clusters.js -> noteGroup.
            // The compound affects layout but paints no frame or title.
            if (group.note) return@forEach
            val groupBounds = bounds[group.id] ?: return@forEach
            val visual = visuals[group.id] ?: return@forEach
            val fill = visual.style.fill
                ?: group.colorIndex?.let(context.theme::colorFill)
                ?: context.theme.groupFill
            val stroke = visual.style.stroke
                ?: group.colorIndex?.let(context.theme::colorStroke)
                ?: context.theme.groupStroke
            if (group.divider) {
                elements += SceneShape(
                    id = "state-group-${group.id}",
                    bounds = groupBounds,
                    kind = SceneShapeKind.Rectangle,
                    fill = fill,
                    stroke = stroke,
                    strokeWidth = visual.style.strokeWidth ?: context.theme.strokeWidth,
                    strokePattern = visual.style.strokePattern ?: SceneStrokePattern.Dashed,
                    dashIntervals = visual.style.dashIntervals.ifEmpty { listOf(5f, 5f) },
                    cornerRadius = 0f,
                    zIndex = depths[group.id] ?: 0,
                )
                return@forEach
            }
            elements += SceneShape(
                id = "state-group-${group.id}",
                bounds = groupBounds,
                kind = SceneShapeKind.RoundedRectangle,
                fill = fill,
                stroke = stroke,
                strokeWidth = visual.style.strokeWidth ?: context.theme.strokeWidth,
                cornerRadius = 5f,
                zIndex = depths[group.id] ?: 0,
            )
            val label = visual.label
            if (label != null) {
                val bodyTop = groupBounds.top + label.metrics.height + GROUP_LABEL_PADDING
                elements += SceneShape(
                    id = "state-group-${group.id}-body",
                    bounds = SceneRect(
                        left = groupBounds.left,
                        top = bodyTop,
                        right = groupBounds.right,
                        bottom = groupBounds.bottom,
                    ),
                    kind = SceneShapeKind.Rectangle,
                    fill = visual.style.fill ?: if (group.alternate) {
                        context.theme.groupFill
                    } else {
                        context.theme.background
                    },
                    stroke = stroke,
                    strokeWidth = visual.style.strokeWidth ?: context.theme.strokeWidth,
                    cornerRadius = 0f,
                    zIndex = (depths[group.id] ?: 0) + 1,
                )
                elements += label.asSceneText(
                    bounds = SceneRect(
                        left = groupBounds.center.x - label.metrics.width / 2f,
                        top = groupBounds.top + GROUP_LABEL_TOP,
                        right = groupBounds.center.x + label.metrics.width / 2f,
                        bottom = groupBounds.top + GROUP_LABEL_TOP + label.metrics.height,
                    ),
                    color = visual.style.text ?: context.theme.groupText,
                    context = context,
                    alignment = SceneTextAlignment.Center,
                    zIndex = (depths[group.id] ?: 0) + 2,
                )
            }
        }
    }

    private fun addNode(
        visual: StateNodeVisual,
        bounds: SceneRect,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val note = visual.source.noteOwner != null
        val special = visual.source.type in setOf(
            StateNodeType.Start,
            StateNodeType.Fork,
            StateNodeType.Join,
        )
        val fill = when {
            note -> context.theme.noteFill
            special -> context.theme.edge
            else -> visual.style.fill ?: context.theme.nodeFill
        }
        val stroke = when {
            note -> context.theme.noteStroke
            special -> context.theme.edge
            else -> visual.style.stroke ?: context.theme.nodeStroke
        }
        elements += SceneShape(
            id = visual.source.id,
            bounds = bounds,
            kind = shape(visual.source.type, note),
            geometry = visual.layout.geometry,
            fill = fill,
            stroke = stroke,
            strokeWidth = visual.style.strokeWidth ?: context.theme.strokeWidth,
            strokePattern = visual.style.strokePattern ?: SceneStrokePattern.Solid,
            dashIntervals = visual.style.dashIntervals,
            cornerRadius = if (note) 0f else 5f,
            shadow = context.theme.dropShadow.takeIf {
                context.options.look == "neo" && !note
            },
            zIndex = 10,
        )
        val title = visual.title ?: return
        if (note) {
            val padding = context.options.flowchartPadding
            elements += title.asSceneText(
                bounds = SceneRect(
                    left = bounds.left + padding,
                    top = bounds.top + padding,
                    right = bounds.right - padding,
                    bottom = bounds.bottom - padding,
                ),
                color = visual.style.text ?: context.theme.noteText,
                context = context,
                alignment = SceneTextAlignment.Start,
            )
            return
        }
        val description = visual.description
        if (description == null) {
            elements += title.asSceneText(
                bounds = centeredBounds(bounds.center, title.metrics),
                color = visual.style.text ?: context.theme.nodeText,
                context = context,
                alignment = SceneTextAlignment.Center,
            )
            return
        }
        val titleTop = bounds.top + context.options.statePadding
        elements += title.asSceneText(
            bounds = SceneRect(
                left = bounds.center.x - title.metrics.width / 2f,
                top = titleTop,
                right = bounds.center.x + title.metrics.width / 2f,
                bottom = titleTop + title.metrics.height,
            ),
            color = visual.style.text ?: context.theme.nodeText,
            context = context,
            alignment = SceneTextAlignment.Center,
        )
        val descriptionTop = titleTop + title.metrics.height + DESCRIPTION_GAP
        elements += description.asSceneText(
            bounds = SceneRect(
                left = bounds.left + context.options.statePadding,
                top = descriptionTop,
                right = bounds.right - context.options.statePadding,
                bottom = descriptionTop + description.metrics.height,
            ),
            color = visual.style.text ?: context.theme.nodeText,
            context = context,
            alignment = SceneTextAlignment.Start,
        )
    }

    private fun addEdges(
        edges: List<StateRenderEdge>,
        visuals: Map<Int, StateEdgeVisual>,
        routed: Map<Int, FlowRoutedEdge>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        edges.forEachIndexed { index, edge ->
            val route = routed[index] ?: return@forEachIndexed
            val arrow = if (edge.arrow) SceneArrowHead.Triangle else SceneArrowHead.None
            val curve = route.curveOverride ?: context.options.curve
            val commands = when (
                val result = MermaidEdgePathPort.generate(
                    points = route.points,
                    curve = curve,
                    arrowStart = SceneArrowHead.None,
                    arrowEnd = arrow,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            elements += ScenePath(
                id = edge.id,
                points = route.points,
                commands = commands,
                color = context.theme.edge,
                strokeWidth = context.theme.strokeWidth,
                strokePattern = edge.pattern,
                arrowEnd = arrow,
                curve = curve,
                look = context.options.look,
                animated = false,
                dashIntervals = if (edge.note) listOf(5f, 5f) else emptyList(),
                zIndex = 5,
            )
            visuals[index]?.let { visual ->
                val labelAnchor = MermaidEdgePathPort.positionEdgeLabel(
                    layoutAnchor = route.labelAnchor,
                    points = route.points,
                    commands = commands,
                )
                val bounds = SceneRect(
                    left = labelAnchor.x - visual.size.width / 2f - EDGE_LABEL_PADDING,
                    top = labelAnchor.y - visual.size.height / 2f - EDGE_LABEL_PADDING,
                    right = labelAnchor.x + visual.size.width / 2f + EDGE_LABEL_PADDING,
                    bottom = labelAnchor.y + visual.size.height / 2f + EDGE_LABEL_PADDING,
                )
                elements += SceneShape(
                    id = "${edge.id}-label-background",
                    bounds = bounds,
                    kind = SceneShapeKind.Rectangle,
                    fill = context.theme.edgeLabelFill,
                    stroke = SceneColor(0x00000000),
                    strokeWidth = 0f,
                    cornerRadius = 0f,
                    zIndex = 6,
                )
                elements += SceneText(
                    text = visual.text,
                    bounds = bounds,
                    color = context.theme.nodeText,
                    fontSize = context.options.fontSize ?: context.theme.fontSize,
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

    private fun addDiagramTitle(
        db: StateDb,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
        layoutOnlyBounds: List<SceneRect>,
    ): GMResult<Unit, MermaidError> {
        val title = db.diagramTitle?.takeIf(String::isNotBlank)?.let(::decode)
            ?: return GMResult.Ok(Unit)
        val graphBounds = (elements.mapNotNull(::elementBounds) + layoutOnlyBounds)
            .reduceOrNull(SceneRect::union)
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
                MermaidError.Unexpected.from(failure, "State title measurement failed"),
            )
        }
        elements += SceneText(
            text = title,
            bounds = SceneRect(
                left = graphBounds.center.x - metrics.width / 2f,
                top = graphBounds.top - context.options.stateTitleTopMargin - metrics.height,
                right = graphBounds.center.x + metrics.width / 2f,
                bottom = graphBounds.top - context.options.stateTitleTopMargin,
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
        db: StateDb,
        document: FlowchartDocument,
        elements: List<SceneElement>,
        layoutOnlyBounds: List<SceneRect>,
        context: MermaidRenderContext,
    ): MermaidScene {
        // Mermaid 12.0.0: noteGroup is not painted, but its compound bounds
        // still participate in the root SVG bounding box.
        val bounds = (elements.mapNotNull(::elementBounds) + layoutOnlyBounds)
            .reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val padding = context.options.diagramPadding
        val dx = padding - bounds.left
        val dy = padding - bounds.top
        val translated = elements.map { element -> element.translate(dx, dy) }
        val nodeBounds = translated.filterIsInstance<SceneShape>()
            .associate { shape -> shape.id to shape.bounds }
        val interactions = db.getLinks().mapNotNull { (id, link) ->
            val targetBounds = nodeBounds[id] ?: return@mapNotNull null
            SceneNodeInteraction(
                nodeId = id,
                bounds = targetBounds,
                link = link.url,
                linkTarget = "_blank",
                tooltip = link.tooltip.takeIf(String::isNotEmpty),
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

    private fun shape(
        type: StateNodeType,
        note: Boolean = false,
    ): SceneShapeKind = when {
        note -> SceneShapeKind.Rectangle
        type == StateNodeType.Start -> SceneShapeKind.SmallCircle
        type == StateNodeType.End -> SceneShapeKind.FramedCircle
        type == StateNodeType.Fork || type == StateNodeType.Join -> SceneShapeKind.ForkJoin
        type == StateNodeType.Choice -> SceneShapeKind.Diamond
        else -> SceneShapeKind.RoundedRectangle
    }

    private fun choiceLayout(): MermaidShapeLayout {
        val half = CHOICE_SIZE / 2f
        val outline = listOf(
            ScenePoint(0f, -half),
            ScenePoint(half, 0f),
            ScenePoint(0f, half),
            ScenePoint(-half, 0f),
        )
        return MermaidShapeLayout(
            size = SceneSize(CHOICE_SIZE, CHOICE_SIZE),
            labelOffset = ScenePoint(0f, 0f),
            geometry = SceneShapeGeometry(
                paths = listOf(SceneShapePath(outline)),
                outline = outline,
            ),
            showsLabel = false,
        )
    }

    private fun rectangleLayout(
        size: SceneSize,
        geometry: SceneShapeGeometry,
    ): MermaidShapeLayout = MermaidShapeLayout(
        size = size,
        labelOffset = ScenePoint(0f, 0f),
        geometry = geometry,
        showsLabel = false,
    )

    private fun noteGeometry(size: SceneSize): SceneShapeGeometry {
        val left = -size.width / 2f
        val right = size.width / 2f
        val top = -size.height / 2f
        val bottom = size.height / 2f
        val outline = listOf(
            ScenePoint(left, top),
            ScenePoint(right, top),
            ScenePoint(right, bottom),
            ScenePoint(left, bottom),
        )
        return SceneShapeGeometry(
            paths = listOf(SceneShapePath(outline)),
            outline = outline,
        )
    }

    private fun centeredBounds(
        center: ScenePoint,
        metrics: TextMetrics,
    ): SceneRect = SceneRect(
        left = center.x - metrics.width / 2f,
        top = center.y - metrics.height / 2f,
        right = center.x + metrics.width / 2f,
        bottom = center.y + metrics.height / 2f,
    )

    private fun StateTextVisual.asSceneText(
        bounds: SceneRect,
        color: SceneColor,
        context: MermaidRenderContext,
        alignment: SceneTextAlignment,
        zIndex: Int = 20,
    ): SceneText = SceneText(
        text = text,
        bounds = bounds,
        color = color,
        fontSize = context.options.fontSize ?: context.theme.fontSize,
        lineHeight = DEFAULT_LINE_HEIGHT,
        fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
        weight = SceneTextWeight.Normal,
        spans = spans,
        horizontalAlignment = alignment,
        zIndex = zIndex,
    )

    private fun StateTextVisual.withMinWidth(minWidth: Float): StateTextVisual =
        copy(metrics = metrics.copy(width = max(metrics.width, minWidth)))

    private fun direction(value: String): FlowDirection = when (value) {
        "BT" -> FlowDirection.BottomToTop
        "LR" -> FlowDirection.LeftToRight
        "RL" -> FlowDirection.RightToLeft
        else -> FlowDirection.TopToBottom
    }

    private fun decode(source: String): String =
        MermaidHtmlEntityDecoder.decode(MermaidPreprocessor.decodeEntities(source))

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is com.swithun.cmpmermaid.core.SceneAsset -> element.bounds
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
        is com.swithun.cmpmermaid.core.SceneAsset -> copy(bounds = bounds.translate(dx, dy))
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

    private data class StateTextVisual(
        val text: String,
        val spans: List<SceneTextSpan>,
        val metrics: TextMetrics,
    )

    private data class StateNodeVisual(
        val source: StateRenderNode,
        val style: FlowNodeStyle,
        val layout: MermaidShapeLayout,
        val title: StateTextVisual?,
        val description: StateTextVisual?,
    )

    private data class StateGroupVisual(
        val source: StateRenderGroup,
        val style: FlowNodeStyle,
        val label: StateTextVisual?,
    )

    private data class StateEdgeVisual(
        val text: String,
        val spans: List<SceneTextSpan>,
        val size: SceneSize,
    )

    private companion object {
        val NON_TEXT_TYPES = setOf(
            StateNodeType.Start,
            StateNodeType.End,
            StateNodeType.Fork,
            StateNodeType.Join,
            StateNodeType.Choice,
        )
        // Mermaid 12.0.0: rendering-util/createText.ts -> addHtmlSpan.
        const val DEFAULT_LINE_HEIGHT = 1.5f
        const val DESCRIPTION_GAP = 10f
        const val CHOICE_SIZE = 28f
        const val STATE_GROUP_PADDING = 16f
        const val STATE_GROUP_TITLE_MARGIN = 22f
        const val GROUP_LABEL_PADDING = 8f
        const val GROUP_LABEL_TOP = 2f
        const val EDGE_LABEL_PADDING = 4f
        const val TITLE_FONT_SIZE = 18f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
    }
}
