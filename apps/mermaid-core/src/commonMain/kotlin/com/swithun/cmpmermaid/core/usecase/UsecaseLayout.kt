package com.swithun.cmpmermaid.core.usecase

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
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
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.flowchart.FlowDagreLayout
import com.swithun.cmpmermaid.core.flowchart.FlowDirection
import com.swithun.cmpmermaid.core.flowchart.FlowEdge
import com.swithun.cmpmermaid.core.flowchart.FlowNode
import com.swithun.cmpmermaid.core.flowchart.FlowNodeStyle
import com.swithun.cmpmermaid.core.flowchart.FlowStyleAdapter
import com.swithun.cmpmermaid.core.flowchart.FlowSubgraph
import com.swithun.cmpmermaid.core.flowchart.FlowchartDocument
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidEdgePathPort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidRenderedText
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseActor
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseActorType
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseAnimation
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseBoundaryType
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseDirection
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseDocument
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseElement
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseJsonNode
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseLabel
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseLabelType
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseRelationship
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseRelationshipType
import com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseShape
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/usecase/usecaseDb.ts -> getData,
 * usecaseRenderer.ts -> draw, and rendering-util/render.ts.
 *
 * Mermaid defaults Use Case to ELK. The native implementation deliberately uses the translated
 * Dagre pipeline until the production ELK bridge can provide the same cross-platform guarantee.
 */
internal class UsecaseLayout {
    fun layout(
        document: UsecaseDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        if (context.options.layout != "dagre") {
            return GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "${context.options.layout} layout for Use Case",
                    message = "Native Use Case currently translates Mermaid's unified renderer " +
                        "through the cross-platform Dagre layout",
                ),
            )
        }
        val options = context.options.usecase
        val theme = resolveTheme(context)
        val styles = linkedMapOf<String, FlowNodeStyle>()
        val visuals = linkedMapOf<String, UsecaseNodeVisual>()
        val nodeKinds = linkedMapOf<String, UsecaseNodeKind>()

        for (actor in document.actors.values) {
            val style = when (val result = resolveStyle(document, actor.id, actor.classes, actor.styles)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val label = when (
                val result = measureLabel(
                    actor.label,
                    style,
                    options.actorFontSize,
                    options.actorFontFamily,
                    options.actorFontWeight.toWeight(),
                    options.wrappingWidth,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val stereotype = when (
                val result = measureStereotype(
                    actor.stereotype,
                    style,
                    options.actorFontSize,
                    options.actorFontFamily,
                    options.actorFontWeight.toWeight(),
                    options.wrappingWidth,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            styles[actor.id] = style
            visuals[actor.id] = UsecaseShapePort.actor(
                label = label,
                stereotype = stereotype,
                type = actor.type,
                business = actor.business,
                icon = actor.icon,
            )
            nodeKinds[actor.id] = UsecaseNodeKind.Actor
        }

        for (usecase in document.useCases.values) {
            val style = when (
                val result = resolveStyle(document, usecase.id, usecase.classes, usecase.styles)
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val label = when (
                val result = measureLabel(
                    usecase.label,
                    style,
                    options.usecaseFontSize,
                    options.usecaseFontFamily,
                    options.usecaseFontWeight.toWeight(),
                    options.wrappingWidth,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val stereotype = when (
                val result = measureStereotype(
                    usecase.stereotype,
                    style,
                    options.usecaseFontSize,
                    options.usecaseFontFamily,
                    options.usecaseFontWeight.toWeight(),
                    options.wrappingWidth,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            styles[usecase.id] = style
            visuals[usecase.id] = UsecaseShapePort.useCase(
                label = label,
                stereotype = stereotype,
                rectangular = usecase.shape == UsecaseShape.Rectangle,
                business = usecase.business,
                minimumLabelWidth = options.minNodeWidth,
                look = context.options.look,
            )
            nodeKinds[usecase.id] = UsecaseNodeKind.Usecase
        }

        for (note in document.notes.values) {
            val style = when (val result = resolveStyle(document, note.id, emptyList(), emptyList())) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val inheritedFontSize = context.options.fontSize ?: context.theme.fontSize
            val inheritedFontFamily = context.options.fontFamily ?: context.theme.fontFamily
            val label = when (
                val result = measureLabel(
                    note.label,
                    style,
                    inheritedFontSize,
                    inheritedFontFamily,
                    SceneTextWeight.Normal,
                    options.wrappingWidth,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            styles[note.id] = style
            visuals[note.id] = UsecaseShapePort.note(label)
            nodeKinds[note.id] = UsecaseNodeKind.Note
        }

        for (json in document.jsonNodes.values) {
            val style = when (val result = resolveStyle(document, json.id, json.classes, json.styles)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val visual = when (val result = buildJsonVisual(json, style, context)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            styles[json.id] = style
            visuals[json.id] = visual
            nodeKinds[json.id] = UsecaseNodeKind.Json
        }

        val edgeModels = document.relationships + document.notes.values.map { note ->
            UsecaseRelationship(
                id = "${note.id}-edge",
                explicitId = false,
                source = note.id,
                target = note.target,
                type = UsecaseRelationshipType.Note,
                arrowType = com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseArrowType.LineSolid,
            )
        }
        if (edgeModels.size > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "maxEdges",
                    actual = edgeModels.size,
                    maximum = context.options.maxEdges,
                ),
            )
        }
        val edgeStyles = linkedMapOf<Int, FlowNodeStyle>()
        val renderedEdgeLabels = linkedMapOf<Int, UsecaseMeasuredText>()
        for ((index, edge) in edgeModels.withIndex()) {
            val style = when (val result = resolveStyle(document, edge.id, edge.classes, edge.styles)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            edgeStyles[index] = style
            val label = edge.renderedLabel() ?: continue
            val measured = when (
                val result = measureLabel(
                    label,
                    style,
                    EDGE_LABEL_FONT_SIZE,
                    context.options.fontFamily ?: context.theme.fontFamily,
                    SceneTextWeight.Normal,
                    EDGE_LABEL_MAX_WIDTH,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            renderedEdgeLabels[index] = measured
        }

        val flowDocument = createFlowDocument(document, visuals, edgeModels, edgeStyles, context)
        val nodeShapeLayouts = visuals.mapValues { (_, visual) ->
            MermaidShapeLayout(
                size = visual.size,
                labelOffset = ScenePoint(0f, 0f),
                geometry = visual.geometry,
                showsLabel = false,
            )
        }
        val layoutOptions = context.options.copy(
            layout = "dagre",
            nodeSpacing = options.nodeSpacing,
            rankSpacing = options.rankSpacing,
            diagramPadding = options.diagramPadding,
            wrappingWidth = options.wrappingWidth,
            minNodeWidth = options.minNodeWidth,
        )
        val placement = when (
            val result = FlowDagreLayout.layout(
                document = flowDocument,
                nodeSizes = visuals.mapValues { (_, visual) -> visual.size },
                nodeShapeLayouts = nodeShapeLayouts,
                edgeLabelSizes = renderedEdgeLabels.mapValues { (_, label) -> label.size },
                options = layoutOptions,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        if (placement.nodeBounds.size != visuals.size) {
            return GMResult.Err(MermaidError.Layout("Not every Use Case node was positioned"))
        }

        val elements = mutableListOf<SceneElement>()
        when (
            val result = addBoundaries(document, placement.subgraphBounds, context, theme, elements)
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        when (
            val result = addEdges(
                edgeModels,
                edgeStyles,
                renderedEdgeLabels,
                placement.edges,
                context,
                theme,
                elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        addNodes(
            document = document,
            visuals = visuals,
            styles = styles,
            kinds = nodeKinds,
            bounds = placement.nodeBounds,
            context = context,
            theme = theme,
            elements = elements,
        )
        when (val result = addTitle(document, context, elements)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        return GMResult.Ok(normalize(document, context, elements))
    }

    private fun createFlowDocument(
        document: UsecaseDocument,
        visuals: Map<String, UsecaseNodeVisual>,
        edges: List<UsecaseRelationship>,
        edgeStyles: Map<Int, FlowNodeStyle>,
        context: MermaidRenderContext,
    ): FlowchartDocument {
        val nodes = linkedMapOf<String, FlowNode>()
        visuals.forEach { (id, visual) ->
            nodes[id] = FlowNode(
                id = id,
                label = "",
                labelSpans = emptyList(),
                labelType = FlowLabelType.Text,
                shape = visual.kind,
                padding = 0f,
                minWidth = null,
                look = context.options.look,
            )
        }
        return FlowchartDocument(
            direction = document.direction.toFlowDirection(),
            nodes = nodes,
            edges = edges.mapIndexed { index, edge ->
                val markers = edge.markers()
                FlowEdge(
                    id = edge.id,
                    from = edge.source,
                    to = edge.target,
                    label = edge.renderedLabel()?.text,
                    labelSpans = emptyList(),
                    pattern = edge.pattern(),
                    arrowStart = markers.first,
                    arrowEnd = markers.second,
                    minimumLength = edge.minimumLength,
                    inlineStyle = edgeStyles[index],
                    animated = edge.animated,
                    animationDurationMillis = edge.animation.toDurationMillis(),
                    curve = "basis",
                    look = context.options.look,
                )
            },
            subgraphs = document.systemBoundaries.values.mapIndexed { index, boundary ->
                FlowSubgraph(
                    id = boundary.id,
                    label = boundary.label.text,
                    labelSpans = emptyList(),
                    nodeIds = boundary.members.toSet(),
                    padding = 20f,
                    look = context.options.look,
                    colorIndex = index,
                )
            },
            title = document.title,
            accessibilityTitle = document.accessibilityTitle,
            accessibilityDescription = document.accessibilityDescription,
        )
    }

    private fun addBoundaries(
        document: UsecaseDocument,
        bounds: Map<String, SceneRect>,
        context: MermaidRenderContext,
        theme: UsecaseTheme,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        document.systemBoundaries.values.forEachIndexed { index, boundary ->
            val layoutBounds = bounds[boundary.id] ?: return@forEachIndexed
            val style = when (
                val result = resolveStyle(document, boundary.id, boundary.classes, boundary.styles)
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val title = when (
                val result = measureLabel(
                    boundary.label,
                    style,
                    context.options.fontSize ?: context.theme.fontSize,
                    context.options.fontFamily ?: context.theme.fontFamily,
                    SceneTextWeight.Normal,
                    UNWRAPPED_LABEL_MAX_WIDTH,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val paletteFill = context.theme.bkgColorArray.palette(index)
            val paletteStroke = context.theme.borderColorArray.palette(index)
            val fill = style.fill ?: paletteFill ?: theme.boundaryFill
            val stroke = style.stroke ?: paletteStroke ?: theme.boundaryStroke
            val width = max(
                layoutBounds.width,
                title.size.width + if (boundary.type == UsecaseBoundaryType.Package) 20f else 20f,
            )
            val tabHeight = if (boundary.type == UsecaseBoundaryType.Package) {
                title.size.height + 10f
            } else {
                0f
            }
            val height = if (boundary.type == UsecaseBoundaryType.Package) {
                max(layoutBounds.height, tabHeight + 40f)
            } else {
                layoutBounds.height
            }
            val outer = SceneRect(
                left = layoutBounds.center.x - width / 2f,
                top = layoutBounds.center.y - height / 2f,
                right = layoutBounds.center.x + width / 2f,
                bottom = layoutBounds.center.y + height / 2f,
            )
            val body = if (boundary.type == UsecaseBoundaryType.Package) {
                SceneRect(outer.left, outer.top + tabHeight, outer.right, outer.bottom)
            } else {
                outer
            }
            elements += SceneShape(
                id = "subgraph_${boundary.id}",
                bounds = body,
                kind = SceneShapeKind.Rectangle,
                fill = fill,
                stroke = stroke,
                strokeWidth = style.strokeWidth ?: 1f,
                strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
                dashIntervals = style.dashIntervals,
                cornerRadius = 0f,
                zIndex = index,
            )
            val titleCenter = if (boundary.type == UsecaseBoundaryType.Package) {
                val tabWidth = min(width, max(80f, title.size.width + 20f))
                val tab = SceneRect(
                    left = outer.left,
                    top = outer.top,
                    right = outer.left + tabWidth,
                    bottom = outer.top + tabHeight,
                )
                elements += SceneShape(
                    id = "subgraph_${boundary.id}_tab",
                    bounds = tab,
                    kind = SceneShapeKind.Rectangle,
                    fill = fill,
                    stroke = stroke,
                    strokeWidth = style.strokeWidth ?: 1f,
                    strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
                    dashIntervals = style.dashIntervals,
                    cornerRadius = 0f,
                    zIndex = index + 1,
                )
                tab.center
            } else {
                ScenePoint(
                    x = outer.center.x,
                    y = outer.top + title.size.height / 2f,
                )
            }
            elements += textElement(
                id = "subgraph_${boundary.id}_title",
                visual = UsecaseTextVisual(title, titleCenter),
                origin = ScenePoint(0f, 0f),
                color = style.text ?: context.theme.groupText,
                style = style,
                defaultFontSize = context.options.fontSize ?: context.theme.fontSize,
                defaultFontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                defaultWeight = SceneTextWeight.Normal,
                zIndex = index + 2,
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun addEdges(
        edges: List<UsecaseRelationship>,
        styles: Map<Int, FlowNodeStyle>,
        labels: Map<Int, UsecaseMeasuredText>,
        routedEdges: Map<Int, com.swithun.cmpmermaid.core.flowchart.FlowRoutedEdge>,
        context: MermaidRenderContext,
        theme: UsecaseTheme,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        edges.forEachIndexed { index, edge ->
            val routed = routedEdges[index] ?: return@forEachIndexed
            val style = styles[index] ?: FlowNodeStyle()
            val markers = edge.markers()
            val commands = when (
                val result = MermaidEdgePathPort.generate(
                    points = routed.points,
                    curve = "basis",
                    arrowStart = markers.first,
                    arrowEnd = markers.second,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val defaultColor = when (edge.type) {
                UsecaseRelationshipType.Include -> theme.includeLine
                UsecaseRelationshipType.Extend -> theme.extendLine
                else -> context.theme.edge
            }
            elements += ScenePath(
                id = edge.id,
                points = routed.points,
                commands = commands,
                color = style.stroke ?: defaultColor,
                strokeWidth = style.strokeWidth ?: context.theme.strokeWidth,
                strokePattern = if (edge.animated) {
                    SceneStrokePattern.Dashed
                } else {
                    style.strokePattern ?: edge.pattern()
                },
                arrowStart = markers.first,
                arrowEnd = markers.second,
                curve = "basis",
                look = context.options.look,
                animated = edge.animated,
                animationDurationMillis = edge.animation.toDurationMillis(),
                dashIntervals = if (edge.animated) {
                    style.dashIntervals.takeIf(List<Float>::isNotEmpty) ?: listOf(9f, 5f)
                } else {
                    style.dashIntervals
                },
            )
            val measured = labels[index] ?: return@forEachIndexed
            val labelBounds = centeredBounds(routed.labelAnchor, measured.size).inflate(2f, 2f)
            elements += SceneShape(
                id = "${edge.id}_label_background",
                bounds = labelBounds,
                kind = SceneShapeKind.Rectangle,
                fill = style.labelBackground ?: context.theme.edgeLabelFill,
                stroke = TRANSPARENT,
                strokeWidth = 0f,
                cornerRadius = 0f,
                zIndex = 6,
            )
            elements += SceneText(
                text = measured.text,
                bounds = labelBounds,
                color = style.text ?: context.theme.nodeText,
                fontSize = style.fontSize ?: EDGE_LABEL_FONT_SIZE,
                lineHeight = textLineHeight(style, EDGE_LABEL_FONT_SIZE, context),
                fontFamily = style.fontFamily ?: context.options.fontFamily ?: context.theme.fontFamily,
                weight = style.fontWeight ?: SceneTextWeight.Normal,
                italic = style.italic == true,
                spans = measured.spans,
                horizontalAlignment = style.textAlignment ?: SceneTextAlignment.Center,
                zIndex = 7,
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun addNodes(
        document: UsecaseDocument,
        visuals: Map<String, UsecaseNodeVisual>,
        styles: Map<String, FlowNodeStyle>,
        kinds: Map<String, UsecaseNodeKind>,
        bounds: Map<String, SceneRect>,
        context: MermaidRenderContext,
        theme: UsecaseTheme,
        elements: MutableList<SceneElement>,
    ) {
        val rotate = context.options.usecase.colorScheme == "rotate"
        val colorIndices = buildMap {
            var index = 0
            document.actors.keys.forEach { id -> put(id, index++) }
            document.useCases.keys.forEach { id -> put(id, index++) }
        }
        visuals.forEach { (id, visual) ->
            val nodeBounds = bounds.getValue(id)
            val style = styles[id] ?: FlowNodeStyle()
            val kind = kinds.getValue(id)
            // Mermaid.js 12.0.0: diagrams/usecase/styles.ts -> genColor/getStyles.
            // The later role selector has equal specificity for ellipse/rect bodies, so the
            // browser keeps role colours on use cases while actor glyphs retain rotation.
            val rotatesWithPalette = rotate && kind == UsecaseNodeKind.Actor
            val paletteFill = colorIndices[id]
                ?.takeIf { rotatesWithPalette }
                ?.let { index -> context.theme.bkgColorArray.palette(index) }
            val paletteStroke = colorIndices[id]
                ?.takeIf { rotatesWithPalette }
                ?.let { index -> context.theme.borderColorArray.palette(index) }
            val colors = when (kind) {
                UsecaseNodeKind.Actor -> theme.actorFill to theme.actorStroke
                UsecaseNodeKind.Usecase -> theme.usecaseFill to theme.usecaseStroke
                UsecaseNodeKind.Note -> context.theme.noteFill to context.theme.noteStroke
                UsecaseNodeKind.Json -> context.theme.nodeFill to context.theme.nodeStroke
            }
            val fill = style.fill ?: paletteFill ?: colors.first
            // Mermaid.js 12.0.0: styles.ts and rendering-elements/shapes/usecaseActor.ts.
            // Actor styles are attached to the glyph group, but the non-handDrawn role rule
            // paints each child path/circle directly. That direct declaration wins over an
            // inherited user stroke; under neo, the shared path rule also owns stroke width.
            val stroke = if (kind == UsecaseNodeKind.Actor) {
                paletteStroke ?: colors.second
            } else {
                style.stroke ?: paletteStroke ?: colors.second
            }
            val strokeWidth = when {
                kind == UsecaseNodeKind.Actor && context.options.look == "neo" ->
                    context.theme.strokeWidth
                kind == UsecaseNodeKind.Actor ->
                    style.strokeWidth ?: 2f
                kind == UsecaseNodeKind.Usecase ->
                    style.strokeWidth ?: 2f
                else ->
                    style.strokeWidth ?: 1f
            }
            elements += SceneShape(
                id = id,
                bounds = nodeBounds,
                kind = visual.kind,
                geometry = visual.geometry,
                fill = fill,
                stroke = stroke,
                strokeWidth = strokeWidth,
                strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
                dashIntervals = style.dashIntervals,
                cornerRadius = 0f,
                shadow = context.theme.dropShadow.takeIf {
                    context.options.look == "neo" && kind != UsecaseNodeKind.Actor
                },
            )
            visual.asset?.let { asset ->
                elements += SceneAsset(
                    id = "${id}_asset",
                    source = asset.source,
                    bounds = centeredBounds(
                        center = nodeBounds.center.translate(asset.center.x, asset.center.y),
                        size = asset.size,
                    ),
                    kind = SceneAssetKind.Icon,
                    tint = stroke,
                )
            }
            val textColor = style.text ?: when (kind) {
                UsecaseNodeKind.Note -> context.theme.noteText
                else -> context.theme.nodeText
            }
            val defaultFontSize = when (kind) {
                UsecaseNodeKind.Actor -> context.options.usecase.actorFontSize
                UsecaseNodeKind.Usecase -> context.options.usecase.usecaseFontSize
                UsecaseNodeKind.Note,
                UsecaseNodeKind.Json,
                -> context.options.fontSize ?: context.theme.fontSize
            }
            val defaultFontFamily = when (kind) {
                UsecaseNodeKind.Actor -> context.options.usecase.actorFontFamily
                UsecaseNodeKind.Usecase -> context.options.usecase.usecaseFontFamily
                UsecaseNodeKind.Note,
                UsecaseNodeKind.Json,
                -> context.options.fontFamily ?: context.theme.fontFamily
            }
            val defaultWeight = when (kind) {
                UsecaseNodeKind.Actor -> context.options.usecase.actorFontWeight.toWeight()
                UsecaseNodeKind.Usecase -> context.options.usecase.usecaseFontWeight.toWeight()
                UsecaseNodeKind.Note,
                UsecaseNodeKind.Json,
                -> SceneTextWeight.Normal
            }
            visual.texts.forEachIndexed { index, text ->
                elements += textElement(
                    id = "${id}_label_$index",
                    visual = text,
                    origin = nodeBounds.center,
                    color = textColor,
                    style = style,
                    defaultFontSize = defaultFontSize,
                    defaultFontFamily = defaultFontFamily,
                    defaultWeight = defaultWeight,
                )
            }
        }
    }

    private fun buildJsonVisual(
        node: UsecaseJsonNode,
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): GMResult<UsecaseNodeVisual, MermaidError> {
        val inheritedFontSize = context.options.fontSize ?: context.theme.fontSize
        val inheritedFontFamily = context.options.fontFamily ?: context.theme.fontFamily
        val title = when (
            val result = measureLabel(
                UsecaseLabel(node.id, UsecaseLabelType.Text),
                style,
                inheritedFontSize,
                inheritedFontFamily,
                SceneTextWeight.Normal,
                UNWRAPPED_LABEL_MAX_WIDTH,
                context,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val rows = mutableListOf<UsecaseMeasuredJsonRow>()
        for (row in flattenJson(node.value)) {
            val key = when (
                val result = measureLabel(
                    UsecaseLabel(row.key, UsecaseLabelType.Text),
                    style,
                    inheritedFontSize,
                    inheritedFontFamily,
                    SceneTextWeight.Normal,
                    UNWRAPPED_LABEL_MAX_WIDTH,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val value = when (
                val result = measureLabel(
                    UsecaseLabel(row.value, UsecaseLabelType.Text),
                    style,
                    inheritedFontSize,
                    inheritedFontFamily,
                    SceneTextWeight.Normal,
                    UNWRAPPED_LABEL_MAX_WIDTH,
                    context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            rows += UsecaseMeasuredJsonRow(key, value)
        }
        return GMResult.Ok(
            UsecaseShapePort.jsonTable(
                title = title,
                rows = rows,
                borderWidth = style.strokeWidth ?: 1f,
            ),
        )
    }

    private fun flattenJson(root: JsonObject): List<JsonRow> {
        val rows = mutableListOf<JsonRow>()
        fun append(key: String, accessibleKey: String, value: String) {
            rows += JsonRow(key, accessibleKey, value)
        }
        fun visit(value: JsonElement, path: String) {
            when (value) {
                is JsonObject -> {
                    if (value.isEmpty()) {
                        append(path, path, "{}")
                    } else {
                        value.forEach { (key, child) ->
                            visit(child, if (path.isEmpty()) key else "$path.$key")
                        }
                    }
                }
                is JsonArray -> {
                    if (value.isEmpty()) {
                        append(path, path, "[]")
                    } else if (value.all(::isJsonScalar)) {
                        value.forEachIndexed { index, child ->
                            append(
                                key = if (index == 0) path else "",
                                accessibleKey = path,
                                value = displayJsonScalar(child),
                            )
                        }
                    } else {
                        value.forEachIndexed { index, child -> visit(child, "$path[$index]") }
                    }
                }
                else -> append(path, path, displayJsonScalar(value))
            }
        }
        visit(root, "")
        return rows
    }

    private fun measureStereotype(
        stereotype: String?,
        style: FlowNodeStyle,
        defaultFontSize: Float,
        defaultFontFamily: String,
        defaultWeight: SceneTextWeight,
        maxWidth: Float,
        context: MermaidRenderContext,
    ): GMResult<UsecaseMeasuredText?, MermaidError> {
        if (stereotype == null) return GMResult.Ok(null)
        return when (
            val result = measureLabel(
                UsecaseLabel("«$stereotype»", UsecaseLabelType.Text),
                style,
                defaultFontSize,
                defaultFontFamily,
                defaultWeight,
                maxWidth,
                context,
            )
        ) {
            is GMResult.Ok -> GMResult.Ok(result.value)
            is GMResult.Err -> result
        }
    }

    private fun measureLabel(
        label: UsecaseLabel,
        style: FlowNodeStyle,
        defaultFontSize: Float,
        defaultFontFamily: String,
        defaultWeight: SceneTextWeight,
        maxWidth: Float,
        context: MermaidRenderContext,
    ): GMResult<UsecaseMeasuredText, MermaidError> {
        val rendered = when (label.type) {
            UsecaseLabelType.Markdown -> when (
                val result = MermaidTextPort.render(
                    source = label.text,
                    labelType = FlowLabelType.Markdown,
                    config = context.options,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            UsecaseLabelType.Text -> when (val result = MermaidTextPort.sanitizeText(label.text)) {
                // Mermaid.js 12.0.0: usecaseRenderer.ts -> prepareUsecaseLayoutData.
                // Plain labels are escaped once more before insertion, so an entity in
                // the source remains visible as literal entity text.
                is GMResult.Ok -> MermaidRenderedText(text = result.value)
                is GMResult.Err -> return result
            }
        }
        val fontSize = style.fontSize ?: defaultFontSize
        val fontFamily = style.fontFamily ?: defaultFontFamily
        val weight = style.fontWeight ?: defaultWeight
        val metrics = try {
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = rendered.text,
                    fontSize = fontSize,
                    maxWidth = maxWidth,
                    lineHeight = textLineHeight(style, defaultFontSize, context),
                    fontFamily = fontFamily,
                    weight = weight,
                    spans = rendered.spans,
                ),
            )
        } catch (failure: Exception) {
            return GMResult.Err(
                MermaidError.Unexpected.from(failure, "Use Case text measurement failed"),
            )
        }
        return GMResult.Ok(
            UsecaseMeasuredText(
                text = rendered.text,
                spans = rendered.spans,
                size = SceneSize(metrics.width, metrics.height),
            ),
        )
    }

    private fun resolveStyle(
        document: UsecaseDocument,
        id: String,
        classes: List<String>,
        directStyles: List<String>,
    ): GMResult<FlowNodeStyle, MermaidError> {
        val styles = buildList {
            document.classDefinitions["default"]?.styles?.let(::addAll)
            classes.forEach { className ->
                document.classDefinitions[className]?.styles?.let(::addAll)
            }
            addAll(directStyles)
        }
        return FlowStyleAdapter.parse(styles, "Use Case '$id'")
    }

    private fun addTitle(
        document: UsecaseDocument,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val title = document.title?.takeIf(String::isNotBlank) ?: return GMResult.Ok(Unit)
        val graphBounds = elements.mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
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
                MermaidError.Unexpected.from(failure, "Use Case title measurement failed"),
            )
        }
        val centerX = graphBounds.center.x
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

    private fun normalize(
        document: UsecaseDocument,
        context: MermaidRenderContext,
        elements: List<SceneElement>,
    ): MermaidScene {
        val bounds = elements.mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val padding = context.options.usecase.diagramPadding
        val dx = padding - bounds.left
        val dy = padding - bounds.top
        return MermaidScene(
            width = bounds.width + padding * 2f,
            height = bounds.height + padding * 2f,
            background = context.theme.background,
            elements = elements.map { it.translate(dx, dy) }.sortedBy(SceneElement::zIndex),
            title = document.title,
            accessibilityTitle = document.accessibilityTitle,
            accessibilityDescription = document.accessibilityDescription,
            viewportSizing = if (context.options.usecase.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
    }

    private fun textElement(
        id: String,
        visual: UsecaseTextVisual,
        origin: ScenePoint,
        color: SceneColor,
        style: FlowNodeStyle,
        defaultFontSize: Float,
        defaultFontFamily: String,
        defaultWeight: SceneTextWeight,
        zIndex: Int = 20,
    ): SceneText {
        val center = origin.translate(visual.center.x, visual.center.y)
        return SceneText(
            text = visual.measured.text,
            bounds = centeredBounds(center, visual.measured.size),
            color = color,
            fontSize = style.fontSize ?: defaultFontSize,
            lineHeight = style.lineHeightMultiplier ?: 1.5f,
            fontFamily = style.fontFamily ?: defaultFontFamily,
            weight = style.fontWeight ?: defaultWeight,
            italic = style.italic == true,
            spans = styledSpans(visual.measured.text, visual.measured.spans, style),
            horizontalAlignment = style.textAlignment ?: SceneTextAlignment.Center,
            zIndex = zIndex,
        )
    }

    private fun styledSpans(
        text: String,
        spans: List<SceneTextSpan>,
        style: FlowNodeStyle,
    ): List<SceneTextSpan> {
        if (text.isEmpty() || (style.italic != true && !style.underline && !style.lineThrough)) {
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

    private fun textLineHeight(
        style: FlowNodeStyle,
        defaultFontSize: Float,
        context: MermaidRenderContext,
    ): Float {
        val inherited = if (context.options.htmlLabels) 1.5f else 1.1f
        val fontSize = style.fontSize ?: defaultFontSize
        return style.lineHeightPixels
            ?.div(fontSize)
            ?.takeIf { it.isFinite() && it > 0f }
            ?: style.lineHeightMultiplier
            ?: inherited
    }

    private fun resolveTheme(context: MermaidRenderContext): UsecaseTheme {
        fun color(name: String): SceneColor? =
            context.options.themeVariables[name]?.let(CssColorParser::parse)

        val colorTheme = context.options.themeName in COLOR_THEMES
        val darkColorTheme = context.options.themeName == "redux-dark-color"
        return UsecaseTheme(
            // Mermaid.js 12.0.0: themes/* -> getThemeVariables, then usecase/styles.ts.
            // Frontmatter usecase role tokens are normalized by the selected preset before
            // styles are built; generic fallback tokens remain independently configurable.
            actorFill = color("actorBkg")
                ?: color("mainBkg")
                ?: if (colorTheme && !darkColorTheme) REDUX_ACTOR_FILL else context.theme.nodeFill,
            actorStroke = color("actorBorder")
                ?: color("primaryColor")
                ?: if (colorTheme) REDUX_ACTOR_STROKE else context.theme.nodeStroke,
            usecaseFill = color("mainBkg")
                ?: if (colorTheme && !darkColorTheme) REDUX_USECASE_FILL else context.theme.nodeFill,
            usecaseStroke = color("nodeBorder")
                ?: color("primaryColor")
                ?: if (colorTheme) REDUX_USECASE_STROKE else context.theme.nodeStroke,
            boundaryFill = color("clusterBkg")
                ?: if (colorTheme && !darkColorTheme) REDUX_BOUNDARY_FILL else context.theme.groupFill,
            boundaryStroke = color("clusterBorder")
                ?: if (colorTheme) REDUX_BOUNDARY_STROKE else context.theme.groupStroke,
            includeLine = if (colorTheme) REDUX_INCLUDE_LINE else context.theme.edge,
            extendLine = if (colorTheme) REDUX_EXTEND_LINE else context.theme.edge,
        )
    }

    private fun UsecaseRelationship.renderedLabel(): UsecaseLabel? = when (type) {
        UsecaseRelationshipType.Include ->
            UsecaseLabel("include", UsecaseLabelType.Text)
        UsecaseRelationshipType.Extend ->
            UsecaseLabel("extend", UsecaseLabelType.Text)
        else -> label
    }

    private fun UsecaseRelationship.pattern(): SceneStrokePattern =
        if (
            type in setOf(
                UsecaseRelationshipType.Include,
                UsecaseRelationshipType.Extend,
                UsecaseRelationshipType.Note,
            )
        ) {
            SceneStrokePattern.Dotted
        } else {
            SceneStrokePattern.Solid
        }

    private fun UsecaseRelationship.markers(): Pair<SceneArrowHead, SceneArrowHead> =
        when (type) {
            UsecaseRelationshipType.Include,
            UsecaseRelationshipType.Extend,
            -> SceneArrowHead.None to SceneArrowHead.Triangle
            UsecaseRelationshipType.Generalization ->
                SceneArrowHead.None to SceneArrowHead.ClassExtension
            UsecaseRelationshipType.Note ->
                SceneArrowHead.None to SceneArrowHead.None
            UsecaseRelationshipType.Association -> when (arrowType) {
                com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseArrowType.SolidArrow ->
                    SceneArrowHead.None to SceneArrowHead.Triangle
                com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseArrowType.BackArrow ->
                    SceneArrowHead.Triangle to SceneArrowHead.None
                com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseArrowType.LineSolid ->
                    SceneArrowHead.None to SceneArrowHead.None
                com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseArrowType.CircleArrow ->
                    SceneArrowHead.None to SceneArrowHead.Circle
                com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseArrowType.CrossArrow ->
                    SceneArrowHead.None to SceneArrowHead.Cross
                com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseArrowType.CircleArrowReversed ->
                    SceneArrowHead.Circle to SceneArrowHead.None
                com.swithun.cmpmermaid.core.usecase.upstream.mermaid.UsecaseArrowType.CrossArrowReversed ->
                    SceneArrowHead.Cross to SceneArrowHead.None
            }
        }

    private fun UsecaseDirection.toFlowDirection(): FlowDirection = when (this) {
        UsecaseDirection.TopToBottom -> FlowDirection.TopToBottom
        UsecaseDirection.BottomToTop -> FlowDirection.BottomToTop
        UsecaseDirection.LeftToRight -> FlowDirection.LeftToRight
        UsecaseDirection.RightToLeft -> FlowDirection.RightToLeft
    }

    private fun UsecaseAnimation?.toDurationMillis(): Int? = when (this) {
        UsecaseAnimation.Fast -> 20_000
        UsecaseAnimation.Slow -> 50_000
        null -> null
    }

    private fun String.toWeight(): SceneTextWeight =
        when (lowercase()) {
            "bold", "bolder", "600", "700", "800", "900" -> SceneTextWeight.Bold
            else -> SceneTextWeight.Normal
        }

    private fun List<SceneColor>.palette(index: Int): SceneColor? =
        takeIf(List<SceneColor>::isNotEmpty)?.let { colors -> colors[index % colors.size] }

    private fun isJsonScalar(element: JsonElement): Boolean =
        element is JsonPrimitive || element is JsonNull

    private fun displayJsonScalar(element: JsonElement): String = when (element) {
        JsonNull -> "null"
        is JsonPrimitive -> element.content
        else -> element.toString()
    }

    private fun centeredBounds(center: ScenePoint, size: SceneSize): SceneRect = SceneRect(
        left = center.x - size.width / 2f,
        top = center.y - size.height / 2f,
        right = center.x + size.width / 2f,
        bottom = center.y + size.height / 2f,
    )

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneAsset -> element.bounds
        is SceneShape -> element.bounds
        is SceneText -> element.bounds
        is ScenePath -> {
            val first = element.points.firstOrNull()
            if (first == null) {
                null
            } else {
                element.points.drop(1).fold(
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
    }

    private fun SceneElement.translate(dx: Float, dy: Float): SceneElement = when (this) {
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

    private fun ScenePathCommand.translate(dx: Float, dy: Float): ScenePathCommand = when (this) {
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

    private data class UsecaseTheme(
        val actorFill: SceneColor,
        val actorStroke: SceneColor,
        val usecaseFill: SceneColor,
        val usecaseStroke: SceneColor,
        val boundaryFill: SceneColor,
        val boundaryStroke: SceneColor,
        val includeLine: SceneColor,
        val extendLine: SceneColor,
    )

    private data class JsonRow(
        val key: String,
        val accessibleKey: String,
        val value: String,
    )

    private enum class UsecaseNodeKind {
        Actor,
        Usecase,
        Note,
        Json,
    }

    private companion object {
        const val EDGE_LABEL_MAX_WIDTH = 180f
        const val EDGE_LABEL_FONT_SIZE = 10f
        const val TITLE_FONT_SIZE = 18f
        const val UNWRAPPED_LABEL_MAX_WIDTH = 100_000f
        val TRANSPARENT = SceneColor(0x00000000)
        val REDUX_ACTOR_FILL = SceneColor(0xFFF5F3FF)
        val REDUX_ACTOR_STROKE = SceneColor(0xFFA78BFA)
        val REDUX_USECASE_FILL = SceneColor(0xFFF0FDFA)
        val REDUX_USECASE_STROKE = SceneColor(0xFF2DD4BF)
        val REDUX_BOUNDARY_FILL = SceneColor(0xFFFAFAFC)
        val REDUX_BOUNDARY_STROKE = SceneColor(0xFFBDBCCC)
        val REDUX_INCLUDE_LINE = SceneColor(0xFF38BDF8)
        val REDUX_EXTEND_LINE = SceneColor(0xFFFB923C)
        val COLOR_THEMES = setOf("redux-color", "redux-dark-color")
    }
}
