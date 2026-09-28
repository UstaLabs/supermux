package com.swithun.cmpmermaid.core.classdiagram

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
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassDb
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassInterface
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassLineType
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassMember
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassNamespace
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassNode
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassNote
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassRelation
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.ClassRelationType
import com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid.parseGenericTypes
import com.swithun.cmpmermaid.core.flowchart.FlowDagreLayout
import com.swithun.cmpmermaid.core.flowchart.FlowDirection
import com.swithun.cmpmermaid.core.flowchart.FlowEdge
import com.swithun.cmpmermaid.core.flowchart.FlowElkLayout
import com.swithun.cmpmermaid.core.flowchart.FlowLayoutPlacement
import com.swithun.cmpmermaid.core.flowchart.FlowNode
import com.swithun.cmpmermaid.core.flowchart.FlowNodeStyle
import com.swithun.cmpmermaid.core.flowchart.FlowStyleAdapter
import com.swithun.cmpmermaid.core.flowchart.FlowSubgraph
import com.swithun.cmpmermaid.core.flowchart.FlowchartDocument
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidEdgePathPort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidHtmlEntityDecoder
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidRenderedText
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Native translation of Mermaid 12.0.0 classDb.getData(), classBox.ts,
 * note rendering, edge terminals and the unified Dagre/ELK render path.
 */
internal class ClassLayout {
    fun layout(
        db: ClassDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        if (db.getRelations().size > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "maxEdges",
                    actual = db.getRelations().size,
                    maximum = context.options.maxEdges,
                ),
            )
        }

        val classVisuals = linkedMapOf<String, ClassVisual>()
        db.getClasses().values.forEachIndexed { index, node ->
            val style = when (
                val parsed = FlowStyleAdapter.parse(
                    styles = node.styles,
                    owner = "class '${node.id}'",
                )
            ) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val visual = when (val measured = measureClass(node, index, style, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            classVisuals[node.id] = visual
        }
        val noteVisuals = linkedMapOf<String, NoteVisual>()
        db.getNotes().values.forEach { note ->
            val visual = when (val measured = measureNote(note, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            noteVisuals[note.id] = visual
        }
        val namespaceVisuals = when (val measured = measureNamespaces(db, context)) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        val interfaceVisuals = when (val measured = measureInterfaces(db, context)) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        val layoutEdges = buildLayoutEdges(db)
        val edgeLabelVisuals = when (val measured = measureEdges(layoutEdges, context)) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        val edgeLabelSizes = edgeLabelVisuals.mapValues { (_, visual) -> visual.size }
        val document = buildLayoutDocument(
            db = db,
            edges = layoutEdges,
            namespaceVisuals = namespaceVisuals,
            edgeLabelVisuals = edgeLabelVisuals,
            context = context,
        )
        val nodeSizes = buildMap {
            classVisuals.forEach { (id, visual) -> put(id, visual.size) }
            noteVisuals.forEach { (id, visual) -> put(id, visual.size) }
            interfaceVisuals.forEach { (id, visual) -> put(id, visual.size) }
        }
        if (nodeSizes.keys != document.nodes.keys) {
            return GMResult.Err(
                MermaidError.Layout("Class layout nodes and measured nodes do not match"),
            )
        }
        val nodeShapeLayouts = nodeSizes.mapValues { (_, size) -> rectangleLayout(size) }
        val layout = context.options.classLayout ?: context.options.layout
        val layoutOptions = context.options.copy(
            layout = layout,
        )
        val placement = when (layout) {
            "dagre" -> FlowDagreLayout.layout(
                document = document,
                nodeSizes = nodeSizes,
                nodeShapeLayouts = nodeShapeLayouts,
                edgeLabelSizes = edgeLabelSizes,
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
                nodeShapeLayouts = nodeShapeLayouts,
                edgeLabelSizes = edgeLabelSizes,
                subgraphLabelSizes = namespaceVisuals.mapValues { (_, visual) ->
                    SceneSize(visual.metrics.width, visual.metrics.height)
                },
                options = layoutOptions,
            )
            else -> GMResult.Err(
                MermaidError.UnsupportedFeature(
                    feature = "$layout layout",
                    message = "Native Class diagram has not connected the requested layout engine",
                ),
            )
        }
        val resolvedPlacement = when (placement) {
            is GMResult.Ok -> placement.value
            is GMResult.Err -> return placement
        }
        if (resolvedPlacement.nodeBounds.size != document.nodes.size) {
            return GMResult.Err(
                MermaidError.Layout("Not every class diagram node was positioned"),
            )
        }

        return buildScene(
            db = db,
            document = document,
            classVisuals = classVisuals,
            noteVisuals = noteVisuals,
            interfaceVisuals = interfaceVisuals,
            namespaceVisuals = namespaceVisuals,
            layoutEdges = layoutEdges,
            edgeLabelVisuals = edgeLabelVisuals,
            placement = resolvedPlacement,
            context = context,
        )
    }

    private fun measureClass(
        node: ClassNode,
        colorIndex: Int,
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): GMResult<ClassVisual, MermaidError> {
        return try {
            val fontSize = fontSize(style, context)
            val lineHeight = lineHeight(style, context)
            val fontFamily = style.fontFamily
                ?: context.options.fontFamily
                ?: context.theme.fontFamily
            val annotation = node.annotations.firstOrNull()?.let { source ->
                val rendered = when (
                    val result = MermaidTextPort.render(
                        source = "«$source»",
                        labelType = FlowLabelType.Markdown,
                        config = context.options,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                visualLine(
                    rendered = rendered,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily,
                    weight = SceneTextWeight.Normal,
                    italic = style.italic == true,
                    underline = style.underline,
                    context = context,
                )
            }
            val generic = node.type
                .takeIf(String::isNotEmpty)
                ?.let(::parseGenericTypes)
                ?.let { "&lt;$it&gt;" }
                .orEmpty()
            val renderedTitle = when (
                val result = MermaidTextPort.render(
                    source = node.label + generic,
                    labelType = FlowLabelType.Markdown,
                    config = context.options,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val title = visualLine(
                rendered = renderedTitle,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily,
                weight = style.fontWeight ?: SceneTextWeight.Bold,
                italic = style.italic == true,
                underline = style.underline,
                context = context,
            )
            val members = buildList {
                node.members.forEach { member ->
                    when (
                        val line = memberLine(
                            member,
                            style,
                            fontSize,
                            lineHeight,
                            fontFamily,
                            context,
                        )
                    ) {
                        is GMResult.Ok -> add(line.value)
                        is GMResult.Err -> return line
                    }
                }
            }
            val methods = buildList {
                node.methods.forEach { member ->
                    when (
                        val line = memberLine(
                            member,
                            style,
                            fontSize,
                            lineHeight,
                            fontFamily,
                            context,
                        )
                    ) {
                        is GMResult.Ok -> add(line.value)
                        is GMResult.Err -> return line
                    }
                }
            }
            val padding = context.options.classPadding
            val headerContentHeight = annotation?.metrics?.height.orZero() + title.metrics.height
            val headerHeight = headerContentHeight + padding * 2f
            val hasAnyRows = members.isNotEmpty() || methods.isNotEmpty()
            val renderEmptyCompartments =
                !hasAnyRows && !context.options.classHideEmptyMembersBox
            // Mermaid: rendering-elements/shapes/classBox.ts. Empty rows
            // use the measured SVG group gaps, not two full padding blocks.
            val membersHeight = when {
                members.isNotEmpty() -> members.sumOfHeight() + padding * 2f
                methods.isNotEmpty() -> padding * 2f
                renderEmptyCompartments -> padding * 1.5f
                else -> 0f
            }
            val methodsHeight = when {
                methods.isNotEmpty() ->
                    methods.sumOfHeight() +
                        if (members.isEmpty()) padding * 2.5f else padding * 2f
                members.isNotEmpty() -> padding * 2f
                renderEmptyCompartments -> padding * 1.5f
                else -> 0f
            }
            // Mermaid: diagrams/class/shapeUtil.ts -> textHelper and
            // rendering-elements/shapes/classBox.ts -> classBox. Header text
            // is centered at x=0 while member text starts at x=0, so the
            // measured group extends by the header half-width on its left.
            val headerHalfWidth = max(
                annotation?.metrics?.width.orZero(),
                title.metrics.width,
            ) / 2f
            val bodyWidth = (members + methods)
                .maxOfOrNull { it.metrics.width }
                .orZero()
            val textGroupWidth = headerHalfWidth + max(headerHalfWidth, bodyWidth)
            val size = SceneSize(
                width = textGroupWidth + padding * 2f,
                height = headerHeight + membersHeight + methodsHeight,
            )
            GMResult.Ok(
                ClassVisual(
                    source = node,
                    style = style,
                    colorIndex = colorIndex,
                    size = size,
                    annotation = annotation,
                    title = title,
                    members = members,
                    methods = methods,
                    headerHeight = headerHeight,
                    membersHeight = membersHeight,
                    methodsHeight = methodsHeight,
                ),
            )
        } catch (failure: Exception) {
            GMResult.Err(
                MermaidError.Unexpected.from(
                    failure,
                    "Text measurement failed for class '${node.id}'",
                ),
            )
        }
    }

    private fun memberLine(
        member: ClassMember,
        style: FlowNodeStyle,
        fontSize: Float,
        lineHeight: Float,
        fontFamily: String,
        context: MermaidRenderContext,
    ): GMResult<VisualLine, MermaidError> {
        val rendered = when (
            val result = MermaidTextPort.render(
                source = member.displayText.escapeAngles(),
                labelType = FlowLabelType.Markdown,
                config = context.options,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return GMResult.Ok(
            visualLine(
                rendered = rendered,
                fontSize = fontSize,
                lineHeight = lineHeight,
                fontFamily = fontFamily,
                weight = style.fontWeight ?: SceneTextWeight.Normal,
                italic = member.italic || style.italic == true,
                underline = member.underline || style.underline,
                context = context,
            ),
        )
    }

    private fun visualLine(
        rendered: MermaidRenderedText,
        fontSize: Float,
        lineHeight: Float,
        fontFamily: String,
        weight: SceneTextWeight,
        italic: Boolean,
        underline: Boolean,
        context: MermaidRenderContext,
    ): VisualLine {
        val spans = buildList {
            addAll(rendered.spans)
            if (rendered.text.isNotEmpty() && (italic || underline)) {
                add(
                    SceneTextSpan(
                        start = 0,
                        end = rendered.text.length,
                        italic = italic,
                        underline = underline,
                    ),
                )
            }
        }
        val metrics = context.textMetrics.measure(
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
        return VisualLine(
            text = rendered.text,
            metrics = metrics,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
            weight = weight,
            spans = spans,
        )
    }

    private fun measureNote(
        note: ClassNote,
        context: MermaidRenderContext,
    ): GMResult<NoteVisual, MermaidError> {
        return try {
            val rendered = when (
                val result = MermaidTextPort.render(
                    source = note.text,
                    labelType = FlowLabelType.Markdown,
                    config = context.options,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val metrics = context.textMetrics.measure(
                TextMetricsRequest(
                    text = rendered.text,
                    fontSize = context.options.fontSize ?: context.theme.fontSize,
                    // Mermaid: classDb.ts -> getData sets white-space: nowrap
                    // for note nodes. Explicit <br> lines remain intact.
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    lineHeight = DEFAULT_LINE_HEIGHT,
                    fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    spans = rendered.spans,
                ),
            )
            val padding = context.options.classNotePadding
            GMResult.Ok(
                NoteVisual(
                    source = note,
                    text = rendered.text,
                    spans = rendered.spans,
                    metrics = metrics,
                    padding = padding,
                    size = SceneSize(
                        width = metrics.width + padding * 2f,
                        height = metrics.height + padding * 2f,
                    ),
                ),
            )
        } catch (failure: Exception) {
            GMResult.Err(
                MermaidError.Unexpected.from(
                    failure,
                    "Text measurement failed for note '${note.id}'",
                ),
            )
        }
    }

    private fun measureNamespaces(
        db: ClassDb,
        context: MermaidRenderContext,
    ): GMResult<Map<String, NamespaceVisual>, MermaidError> {
        return try {
            val visuals = linkedMapOf<String, NamespaceVisual>()
            visibleNamespaces(db, context).forEach { namespace ->
                val rendered = when (
                    val result = MermaidTextPort.render(
                        source = namespaceLabel(namespace, context),
                        labelType = FlowLabelType.Markdown,
                        config = context.options,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                visuals[namespace.id] = NamespaceVisual(
                    text = rendered.text,
                    spans = rendered.spans,
                    metrics = context.textMetrics.measure(
                        TextMetricsRequest(
                            text = rendered.text,
                            fontSize = context.options.fontSize ?: context.theme.fontSize,
                            maxWidth = UNWRAPPED_TEXT_WIDTH,
                            lineHeight = DEFAULT_LINE_HEIGHT,
                            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                            weight = SceneTextWeight.Normal,
                            spans = rendered.spans,
                        ),
                    ),
                )
            }
            GMResult.Ok(visuals)
        } catch (failure: Exception) {
            GMResult.Err(
                MermaidError.Unexpected.from(failure, "Namespace text measurement failed"),
            )
        }
    }

    private fun measureInterfaces(
        db: ClassDb,
        context: MermaidRenderContext,
    ): GMResult<Map<String, InterfaceVisual>, MermaidError> {
        return try {
            val fontSize = context.options.fontSize ?: context.theme.fontSize
            val fontFamily = context.options.fontFamily ?: context.theme.fontFamily
            val horizontalPadding = if (context.options.look == "neo") 16f else 0f
            val verticalPadding = if (context.options.look == "neo") 12f else 0f
            val visuals = linkedMapOf<String, InterfaceVisual>()
            db.getInterfaces().forEach { classInterface ->
                val rendered = when (
                    val result = MermaidTextPort.render(
                        source = classInterface.label,
                        labelType = FlowLabelType.Markdown,
                        config = context.options,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                val metrics = context.textMetrics.measure(
                    TextMetricsRequest(
                        text = rendered.text,
                        fontSize = fontSize,
                        maxWidth = UNWRAPPED_TEXT_WIDTH,
                        lineHeight = INTERFACE_LINE_HEIGHT,
                        fontFamily = fontFamily,
                        weight = SceneTextWeight.Normal,
                        spans = rendered.spans,
                    ),
                )
                visuals[classInterface.id] = InterfaceVisual(
                    source = classInterface,
                    text = rendered.text,
                    spans = rendered.spans,
                    metrics = metrics,
                    size = SceneSize(
                        width = metrics.width + horizontalPadding * 2f,
                        height = metrics.height + verticalPadding * 2f,
                    ),
                )
            }
            GMResult.Ok(visuals)
        } catch (failure: Exception) {
            GMResult.Err(
                MermaidError.Unexpected.from(failure, "Interface text measurement failed"),
            )
        }
    }

    private fun measureEdges(
        edges: List<ClassLayoutEdge>,
        context: MermaidRenderContext,
    ): GMResult<Map<Int, EdgeLabelVisual>, MermaidError> {
        return try {
            val visuals = linkedMapOf<Int, EdgeLabelVisual>()
            edges.forEachIndexed { index, edge ->
                val label = edge.relation?.title?.takeIf(String::isNotBlank)
                    ?: return@forEachIndexed
                val rendered = when (
                    val result = MermaidTextPort.render(
                        source = label,
                        labelType = FlowLabelType.Markdown,
                        config = context.options,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
                val metrics = context.textMetrics.measure(
                    TextMetricsRequest(
                        text = rendered.text,
                        fontSize = EDGE_FONT_SIZE,
                        maxWidth = UNWRAPPED_TEXT_WIDTH,
                        lineHeight = DEFAULT_LINE_HEIGHT,
                        fontFamily = context.options.fontFamily
                            ?: context.theme.fontFamily,
                        weight = SceneTextWeight.Normal,
                        spans = rendered.spans,
                    ),
                )
                visuals[index] = EdgeLabelVisual(
                    text = rendered.text,
                    spans = rendered.spans,
                    size = SceneSize(
                        width = metrics.width,
                        height = metrics.height,
                    ),
                )
            }
            GMResult.Ok(visuals)
        } catch (failure: Exception) {
            GMResult.Err(
                MermaidError.Unexpected.from(failure, "Class relation text measurement failed"),
            )
        }
    }

    private fun buildLayoutDocument(
        db: ClassDb,
        edges: List<ClassLayoutEdge>,
        namespaceVisuals: Map<String, NamespaceVisual>,
        edgeLabelVisuals: Map<Int, EdgeLabelVisual>,
        context: MermaidRenderContext,
    ): FlowchartDocument {
        val nodes = linkedMapOf<String, FlowNode>()
        db.getClasses().values.forEachIndexed { index, node ->
            nodes[node.id] = layoutNode(
                id = node.id,
                look = context.options.look,
                colorIndex = index,
            )
        }
        db.getNotes().values.forEach { note ->
            nodes[note.id] = layoutNode(note.id, context.options.look)
        }
        db.getInterfaces().forEach { classInterface ->
            nodes[classInterface.id] = layoutNode(classInterface.id, context.options.look)
        }
        val subgraphs = visibleNamespaces(db, context).map { namespace ->
            val visual = namespaceVisuals[namespace.id]
            FlowSubgraph(
                id = namespace.id,
                label = visual?.text ?: namespaceLabel(namespace, context),
                labelSpans = visual?.spans.orEmpty(),
                nodeIds = nodes.keys.filterTo(linkedSetOf()) { nodeId ->
                    resolvedParent(nodeId, db, context) == namespace.id
                },
                parentId = if (context.options.classHierarchicalNamespaces) {
                    namespace.parent
                } else {
                    null
                },
                padding = context.options.classPadding,
                look = context.options.look,
            )
        }
        return FlowchartDocument(
            direction = direction(db.direction),
            nodes = nodes,
            edges = edges.mapIndexed { index, edge ->
                val label = edgeLabelVisuals[index]
                FlowEdge(
                    id = edge.id,
                    from = edge.from,
                    to = edge.to,
                    label = label?.text,
                    labelSpans = label?.spans.orEmpty(),
                    pattern = if (
                        edge.relation?.relation?.lineType == ClassLineType.DOTTED_LINE ||
                        edge.note
                    ) {
                        SceneStrokePattern.Dashed
                    } else {
                        SceneStrokePattern.Solid
                    },
                    arrowStart = edge.relation?.relation?.type1.toArrowHead(),
                    arrowEnd = edge.relation?.relation?.type2.toArrowHead(),
                    look = context.options.look,
                )
            },
            subgraphs = subgraphs,
            title = db.diagramTitle,
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
        )
    }

    private fun layoutNode(
        id: String,
        look: String,
        colorIndex: Int? = null,
    ): FlowNode = FlowNode(
        id = id,
        label = "",
        labelSpans = emptyList(),
        labelType = FlowLabelType.Text,
        shape = SceneShapeKind.Rectangle,
        padding = 0f,
        minWidth = null,
        look = look,
        colorIndex = colorIndex,
    )

    private fun buildLayoutEdges(db: ClassDb): List<ClassLayoutEdge> = buildList {
        db.getNotes().values.forEach { note ->
            val classId = note.className?.takeIf(db.getClasses()::containsKey)
            if (classId != null) {
                add(
                    ClassLayoutEdge(
                        id = "edgeNote${note.index}",
                        from = note.id,
                        to = classId,
                        note = true,
                    ),
                )
            }
        }
        db.getRelations().forEachIndexed { index, relation ->
            add(
                ClassLayoutEdge(
                    id = "id${relation.id1}-${relation.id2}-${index + 1}",
                    from = relation.id1,
                    to = relation.id2,
                    relation = relation,
                ),
            )
        }
    }

    private fun buildScene(
        db: ClassDb,
        document: FlowchartDocument,
        classVisuals: Map<String, ClassVisual>,
        noteVisuals: Map<String, NoteVisual>,
        interfaceVisuals: Map<String, InterfaceVisual>,
        namespaceVisuals: Map<String, NamespaceVisual>,
        layoutEdges: List<ClassLayoutEdge>,
        edgeLabelVisuals: Map<Int, EdgeLabelVisual>,
        placement: FlowLayoutPlacement,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val elements = mutableListOf<SceneElement>()
        addNamespaces(
            db = db,
            bounds = placement.subgraphBounds,
            visuals = namespaceVisuals,
            context = context,
            elements = elements,
        )
        when (
            val edges = addEdges(
                edges = layoutEdges,
                routedEdges = placement.edges,
                nodeBounds = placement.nodeBounds,
                edgeLabelVisuals = edgeLabelVisuals,
                context = context,
                elements = elements,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return edges
        }
        classVisuals.forEach { (id, visual) ->
            val bounds = placement.nodeBounds[id]
                ?: return GMResult.Err(MermaidError.Layout("Class '$id' has no bounds"))
            addClass(visual, bounds, context, elements)
        }
        noteVisuals.forEach { (id, visual) ->
            val bounds = placement.nodeBounds[id]
                ?: return GMResult.Err(MermaidError.Layout("Note '$id' has no bounds"))
            addNote(visual, bounds, context, elements)
        }
        interfaceVisuals.forEach { (id, visual) ->
            val bounds = placement.nodeBounds[id]
                ?: return GMResult.Err(MermaidError.Layout("Interface '$id' has no bounds"))
            addInterface(visual, bounds, context, elements)
        }
        when (val title = addDiagramTitle(db, context, elements)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return title
        }
        return GMResult.Ok(normalizeScene(db, document, elements, context))
    }

    private fun addNamespaces(
        db: ClassDb,
        bounds: Map<String, SceneRect>,
        visuals: Map<String, NamespaceVisual>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val namespaces = visibleNamespaces(db, context)
        val depth = namespaces.associate { namespace ->
            namespace.id to generateSequence(namespace.parent) { parent ->
                db.getNamespaces()[parent]?.parent
            }.count()
        }
        namespaces.sortedBy { depth[it.id] ?: 0 }.forEachIndexed { index, namespace ->
            val rawBounds = bounds[namespace.id] ?: return@forEachIndexed
            val visual = visuals[namespace.id] ?: return@forEachIndexed
            val metrics = visual.metrics
            val renderedBounds = SceneRect(
                left = min(
                    rawBounds.left,
                    rawBounds.center.x - metrics.width / 2f - context.options.classPadding,
                ),
                top = rawBounds.top,
                right = max(
                    rawBounds.right,
                    rawBounds.center.x + metrics.width / 2f + context.options.classPadding,
                ),
                bottom = rawBounds.bottom,
            )
            elements += SceneShape(
                id = "namespace-${namespace.id}",
                bounds = renderedBounds,
                kind = SceneShapeKind.Rectangle,
                fill = context.theme.groupFill,
                stroke = context.theme.groupStroke,
                strokeWidth = 1f,
                cornerRadius = 0f,
                zIndex = index,
            )
            elements += SceneText(
                text = visual.text,
                bounds = SceneRect(
                    left = renderedBounds.center.x - metrics.width / 2f,
                    top = renderedBounds.top + NAMESPACE_LABEL_TOP,
                    right = renderedBounds.center.x + metrics.width / 2f,
                    bottom = renderedBounds.top + NAMESPACE_LABEL_TOP + metrics.height,
                ),
                color = context.theme.groupText,
                fontSize = context.options.fontSize ?: context.theme.fontSize,
                lineHeight = DEFAULT_LINE_HEIGHT,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                spans = visual.spans,
                zIndex = index + 1,
            )
        }
    }

    private fun addClass(
        visual: ClassVisual,
        bounds: SceneRect,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val fill = visual.style.fill ?: paletteFill(visual.colorIndex, context)
        val stroke = visual.style.stroke ?: paletteStroke(visual.colorIndex, context)
        elements += SceneShape(
            id = visual.source.id,
            bounds = bounds,
            kind = SceneShapeKind.Rectangle,
            geometry = classGeometry(visual),
            fill = fill,
            stroke = stroke,
            strokeWidth = visual.style.strokeWidth ?: context.theme.strokeWidth,
            strokePattern = visual.style.strokePattern ?: SceneStrokePattern.Solid,
            dashIntervals = visual.style.dashIntervals,
            cornerRadius = 0f,
            shadow = context.theme.dropShadow.takeIf { context.options.look == "neo" },
            zIndex = 10,
        )

        val padding = context.options.classPadding
        var top = bounds.top + padding
        visual.annotation?.let { line ->
            elements += line.asSceneText(
                bounds = centeredLineBounds(line, bounds, top),
                color = visual.style.text ?: context.theme.nodeText,
                alignment = SceneTextAlignment.Center,
            )
            top += line.metrics.height
        }
        elements += visual.title.asSceneText(
            bounds = centeredLineBounds(visual.title, bounds, top),
            color = visual.style.text ?: context.theme.nodeText,
            alignment = SceneTextAlignment.Center,
        )

        var memberTop = bounds.top + visual.headerHeight + padding
        visual.members.forEach { line ->
            elements += line.asSceneText(
                bounds = startLineBounds(line, bounds, memberTop, padding),
                color = visual.style.text ?: context.theme.nodeText,
                alignment = SceneTextAlignment.Start,
            )
            memberTop += line.metrics.height
        }
        var methodTop = bounds.top + visual.headerHeight + visual.membersHeight + padding
        visual.methods.forEach { line ->
            elements += line.asSceneText(
                bounds = startLineBounds(line, bounds, methodTop, padding),
                color = visual.style.text ?: context.theme.nodeText,
                alignment = SceneTextAlignment.Start,
            )
            methodTop += line.metrics.height
        }
    }

    private fun addNote(
        visual: NoteVisual,
        bounds: SceneRect,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        elements += SceneShape(
            id = visual.source.id,
            bounds = bounds,
            kind = SceneShapeKind.Rectangle,
            geometry = noteGeometry(visual.size),
            fill = context.theme.noteFill,
            stroke = context.theme.noteStroke,
            strokeWidth = 1f,
            cornerRadius = 0f,
            zIndex = 10,
        )
        elements += SceneText(
            text = visual.text,
            bounds = SceneRect(
                left = bounds.left + visual.padding,
                top = bounds.top + visual.padding,
                right = bounds.right - visual.padding,
                bottom = bounds.bottom - visual.padding,
            ),
            color = context.theme.noteText,
            fontSize = context.options.fontSize ?: context.theme.fontSize,
            lineHeight = DEFAULT_LINE_HEIGHT,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            spans = visual.spans,
            horizontalAlignment = SceneTextAlignment.Start,
            zIndex = 20,
        )
    }

    private fun addInterface(
        visual: InterfaceVisual,
        bounds: SceneRect,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        elements += SceneText(
            text = visual.text,
            bounds = SceneRect(
                left = bounds.center.x - visual.metrics.width / 2f,
                top = bounds.center.y - visual.metrics.height / 2f,
                right = bounds.center.x + visual.metrics.width / 2f,
                bottom = bounds.center.y + visual.metrics.height / 2f,
            ),
            color = context.theme.nodeText,
            fontSize = context.options.fontSize ?: context.theme.fontSize,
            lineHeight = INTERFACE_LINE_HEIGHT,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            spans = visual.spans,
            zIndex = 20,
        )
    }

    private fun addEdges(
        edges: List<ClassLayoutEdge>,
        routedEdges: Map<Int, com.swithun.cmpmermaid.core.flowchart.FlowRoutedEdge>,
        nodeBounds: Map<String, SceneRect>,
        edgeLabelVisuals: Map<Int, EdgeLabelVisual>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        edges.forEachIndexed { index, edge ->
            val routed = routedEdges[index] ?: return@forEachIndexed
            val relation = edge.relation
            val arrowStart = relation?.relation?.type1.toArrowHead()
            val arrowEnd = relation?.relation?.type2.toArrowHead()
            val commands = when (
                val generated = MermaidEdgePathPort.generate(
                    points = routed.points,
                    curve = context.options.curve,
                    arrowStart = arrowStart,
                    arrowEnd = arrowEnd,
                )
            ) {
                is GMResult.Ok -> generated.value
                is GMResult.Err -> return generated
            }
            val edgeStyle = when (
                val parsed = FlowStyleAdapter.parse(
                    styles = relation?.styles.orEmpty(),
                    owner = "class relation '${edge.id}'",
                )
            ) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            elements += ScenePath(
                id = edge.id,
                points = routed.points,
                commands = commands,
                color = edgeStyle.stroke ?: context.theme.edge,
                strokeWidth = edgeStyle.strokeWidth ?: context.theme.strokeWidth,
                strokePattern = edgeStyle.strokePattern ?: if (
                    edge.note ||
                    relation?.relation?.lineType == ClassLineType.DOTTED_LINE
                ) {
                    SceneStrokePattern.Dashed
                } else {
                    SceneStrokePattern.Solid
                },
                arrowStart = arrowStart,
                arrowEnd = arrowEnd,
                curve = context.options.curve,
                look = context.options.look,
                animated = false,
                dashIntervals = edgeStyle.dashIntervals,
                zIndex = 5,
            )
            if (relation != null) {
                addRelationLabels(
                    relation = relation,
                    edgeId = edge.id,
                    points = routed.points,
                    labelAnchor = routed.labelAnchor,
                    labelVisual = edgeLabelVisuals[index],
                    nodeBounds = nodeBounds,
                    context = context,
                    elements = elements,
                )
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun addRelationLabels(
        relation: ClassRelation,
        edgeId: String,
        points: List<ScenePoint>,
        labelAnchor: ScenePoint,
        labelVisual: EdgeLabelVisual?,
        nodeBounds: Map<String, SceneRect>,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        relation.title?.takeIf(String::isNotBlank)?.let {
            val visual = labelVisual ?: return@let
            val size = visual.size
            val bounds = SceneRect(
                left = labelAnchor.x - size.width / 2f - EDGE_LABEL_PADDING_X,
                top = labelAnchor.y - size.height / 2f - EDGE_LABEL_PADDING_Y,
                right = labelAnchor.x + size.width / 2f + EDGE_LABEL_PADDING_X,
                bottom = labelAnchor.y + size.height / 2f + EDGE_LABEL_PADDING_Y,
            )
            elements += SceneShape(
                id = "$edgeId-label-background",
                bounds = bounds,
                kind = SceneShapeKind.Rectangle,
                fill = context.theme.nodeFill,
                stroke = SceneColor(0x00000000),
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
        relation.relationTitle1
            .takeUnless { it == "none" || it.isBlank() }
            ?.let { cardinality ->
                addTerminalLabel(
                    id = "$edgeId-start-cardinality",
                    text = decode(cardinality),
                    position = terminalLabelPosition(
                        points = points,
                        marker = relation.relation.type1 != ClassRelationType.NONE,
                        start = true,
                    ),
                    centered = true,
                    avoidBounds = nodeBounds[relation.id1],
                    context = context,
                    elements = elements,
                )
            }
        relation.relationTitle2
            .takeUnless { it == "none" || it.isBlank() }
            ?.let { cardinality ->
                addTerminalLabel(
                    id = "$edgeId-end-cardinality",
                    text = decode(cardinality),
                    position = terminalLabelPosition(
                        points = points,
                        marker = relation.relation.type2 != ClassRelationType.NONE,
                        start = false,
                    ),
                    centered = false,
                    avoidBounds = nodeBounds[relation.id2],
                    context = context,
                    elements = elements,
                )
            }
    }

    private fun addTerminalLabel(
        id: String,
        text: String,
        position: ScenePoint?,
        centered: Boolean,
        avoidBounds: SceneRect?,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        if (position == null) return
        val metrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = text,
                fontSize = TERMINAL_FONT_SIZE,
                maxWidth = UNWRAPPED_TEXT_WIDTH,
                lineHeight = DEFAULT_LINE_HEIGHT,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
            ),
        )
        val rawBounds = if (centered) {
            SceneRect(
                left = position.x - metrics.width / 2f,
                top = position.y - metrics.height / 2f,
                right = position.x + metrics.width / 2f,
                bottom = position.y + metrics.height / 2f,
            )
        } else {
            // Mermaid: rendering-util/rendering-elements/edges.js ->
            // insertEdgeLabel. endLabelLeft is inserted outside its empty
            // inner group, so computeLabelTransform does not affect it.
            SceneRect(
                left = position.x,
                top = position.y,
                right = position.x + metrics.width,
                bottom = position.y + metrics.height,
            )
        }
        val bounds = avoidBounds
            ?.let { obstacle -> rawBounds.translatedOutside(obstacle) }
            ?: rawBounds
        elements += SceneText(
            text = text,
            bounds = bounds,
            color = context.theme.nodeText,
            fontSize = TERMINAL_FONT_SIZE,
            lineHeight = DEFAULT_LINE_HEIGHT,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            zIndex = 8,
        )
    }

    private fun SceneRect.translatedOutside(
        obstacle: SceneRect,
    ): SceneRect {
        if (!overlaps(obstacle)) return this
        val candidates = listOf(
            ScenePoint(obstacle.left - right - TERMINAL_LABEL_GAP, 0f),
            ScenePoint(obstacle.right - left + TERMINAL_LABEL_GAP, 0f),
            ScenePoint(0f, obstacle.top - bottom - TERMINAL_LABEL_GAP),
            ScenePoint(0f, obstacle.bottom - top + TERMINAL_LABEL_GAP),
        )
        val translation = candidates.minBy { point ->
            kotlin.math.abs(point.x) + kotlin.math.abs(point.y)
        }
        return translate(translation.x, translation.y)
    }

    private fun SceneRect.overlaps(other: SceneRect): Boolean =
        left < other.right &&
            right > other.left &&
            top < other.bottom &&
            bottom > other.top

    private fun terminalLabelPosition(
        points: List<ScenePoint>,
        marker: Boolean,
        start: Boolean,
    ): ScenePoint? {
        if (points.size < 2) return null
        val oriented = if (start) points else points.asReversed()
        val markerSize = if (marker) 10f else 0f
        val center = pointAtDistance(oriented, 25f + markerSize) ?: return null
        val first = oriented.first()
        val offset = 10f + markerSize * 0.5f
        val angle = atan2(first.y - center.y, first.x - center.x)
        return if (start) {
            ScenePoint(
                x = sin(angle) * offset + (first.x + center.x) / 2f,
                y = -cos(angle) * offset + (first.y + center.y) / 2f,
            )
        } else {
            ScenePoint(
                x = sin(angle) * offset + (first.x + center.x) / 2f - 5f,
                y = -cos(angle) * offset + (first.y + center.y) / 2f - 5f,
            )
        }
    }

    private fun pointAtDistance(
        points: List<ScenePoint>,
        distance: Float,
    ): ScenePoint? {
        var remaining = distance
        points.zipWithNext().forEach { (start, end) ->
            val length = hypot(end.x - start.x, end.y - start.y)
            if (length <= 0f) return@forEach
            if (length < remaining) {
                remaining -= length
            } else {
                val ratio = (remaining / length).coerceIn(0f, 1f)
                return ScenePoint(
                    x = start.x + (end.x - start.x) * ratio,
                    y = start.y + (end.y - start.y) * ratio,
                )
            }
        }
        return points.lastOrNull()
    }

    private fun addDiagramTitle(
        db: ClassDb,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val title = db.diagramTitle?.takeIf(String::isNotBlank)?.let(::decode)
            ?: return GMResult.Ok(Unit)
        val graphBounds = elements.mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
            ?: return GMResult.Ok(Unit)
        val measured = try {
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
                MermaidError.Unexpected.from(failure, "Class title measurement failed"),
            )
        }
        elements += SceneText(
            text = title,
            bounds = SceneRect(
                left = graphBounds.center.x - measured.width / 2f,
                top = graphBounds.top - context.options.titleTopMargin - measured.height,
                right = graphBounds.center.x + measured.width / 2f,
                bottom = graphBounds.top - context.options.titleTopMargin,
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
        db: ClassDb,
        document: FlowchartDocument,
        elements: List<SceneElement>,
        context: MermaidRenderContext,
    ): MermaidScene {
        val bounds = elements.mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val padding = context.options.diagramPadding
        val dx = padding - bounds.left
        val dy = padding - bounds.top
        val translated = elements.map { element -> element.translate(dx, dy) }
        val classBounds = translated
            .filterIsInstance<SceneShape>()
            .associate { shape -> shape.id to shape.bounds }
        val interactions = db.getClasses().values.mapNotNull { node ->
            if (node.link == null && node.tooltip == null && node.callbackName == null) {
                return@mapNotNull null
            }
            val nodeBounds = classBounds[node.id] ?: return@mapNotNull null
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

    private fun visibleNamespaces(
        db: ClassDb,
        context: MermaidRenderContext,
    ): List<ClassNamespace> = db.getNamespaces().values.filter { namespace ->
        context.options.classHierarchicalNamespaces || namespace.explicit
    }

    private fun namespaceLabel(
        namespace: ClassNamespace,
        context: MermaidRenderContext,
    ): String = decode(
        if (context.options.classHierarchicalNamespaces) namespace.label else namespace.id,
    )

    private fun resolvedParent(
        nodeId: String,
        db: ClassDb,
        context: MermaidRenderContext,
    ): String? {
        val parent = db.getClasses()[nodeId]?.parent ?: db.getNotes()[nodeId]?.parent
        if (context.options.classHierarchicalNamespaces) {
            return parent
        }
        var candidate = parent
        while (candidate != null) {
            val namespace = db.getNamespaces()[candidate] ?: return null
            if (namespace.explicit) return candidate
            candidate = namespace.parent
        }
        return null
    }

    private fun direction(value: String): FlowDirection = when (value) {
        "BT" -> FlowDirection.BottomToTop
        "LR" -> FlowDirection.LeftToRight
        "RL" -> FlowDirection.RightToLeft
        else -> FlowDirection.TopToBottom
    }

    private fun classGeometry(visual: ClassVisual): SceneShapeGeometry {
        val width = visual.size.width
        val height = visual.size.height
        val outer = rectanglePoints(width, height)
        val paths = mutableListOf(
            SceneShapePath(points = outer),
        )
        if (visual.membersHeight > 0f || visual.methodsHeight > 0f) {
            val firstDivider = -height / 2f + visual.headerHeight
            paths += divider(width, firstDivider)
        }
        if (visual.methodsHeight > 0f) {
            val secondDivider =
                -height / 2f + visual.headerHeight + visual.membersHeight
            paths += divider(width, secondDivider)
        }
        return SceneShapeGeometry(paths = paths, outline = outer)
    }

    private fun noteGeometry(size: SceneSize): SceneShapeGeometry {
        val outer = rectanglePoints(size.width, size.height)
        return SceneShapeGeometry(
            paths = listOf(SceneShapePath(outer)),
            outline = outer,
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

    private fun divider(
        width: Float,
        y: Float,
    ): SceneShapePath = SceneShapePath(
        points = listOf(
            ScenePoint(-width / 2f, y),
            ScenePoint(width / 2f, y + 0.001f),
        ),
        closed = false,
        fill = SceneShapePaint.None,
        stroke = SceneShapePaint.Stroke,
        strokeWidth = 1f,
    )

    private fun centeredLineBounds(
        line: VisualLine,
        container: SceneRect,
        top: Float,
    ): SceneRect = SceneRect(
        left = container.center.x - line.metrics.width / 2f,
        top = top,
        right = container.center.x + line.metrics.width / 2f,
        bottom = top + line.metrics.height,
    )

    private fun startLineBounds(
        line: VisualLine,
        container: SceneRect,
        top: Float,
        padding: Float,
    ): SceneRect {
        val left = container.left + padding
        return SceneRect(
            left = left,
            top = top,
            right = min(left + line.metrics.width, container.right - padding),
            bottom = top + line.metrics.height,
        )
    }

    private fun VisualLine.asSceneText(
        bounds: SceneRect,
        color: SceneColor,
        alignment: SceneTextAlignment,
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
        zIndex = 20,
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

    private fun fontSize(
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): Float = style.fontSize ?: context.options.fontSize ?: context.theme.fontSize

    private fun lineHeight(
        style: FlowNodeStyle,
        context: MermaidRenderContext,
    ): Float {
        val size = fontSize(style, context)
        return style.lineHeightPixels
            ?.div(size)
            ?.takeIf { it.isFinite() && it > 0f }
            ?: style.lineHeightMultiplier
            ?: DEFAULT_LINE_HEIGHT
    }

    private fun Int?.toArrowHead(): SceneArrowHead = when (this) {
        ClassRelationType.AGGREGATION -> SceneArrowHead.ClassAggregation
        ClassRelationType.EXTENSION -> SceneArrowHead.ClassExtension
        ClassRelationType.COMPOSITION -> SceneArrowHead.ClassComposition
        ClassRelationType.DEPENDENCY -> SceneArrowHead.ClassDependency
        ClassRelationType.LOLLIPOP -> SceneArrowHead.ClassLollipop
        else -> SceneArrowHead.None
    }

    private fun decode(source: String): String =
        MermaidHtmlEntityDecoder.decode(MermaidPreprocessor.decodeEntities(source))
            .replace(HTML_BREAK, "\n")

    private fun String.escapeAngles(): String =
        replace("<", "&lt;").replace(">", "&gt;")

    private fun List<VisualLine>.sumOfHeight(): Float =
        sumOf { line -> line.metrics.height.toDouble() }.toFloat()

    private fun Float?.orZero(): Float = this ?: 0f

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

    private data class VisualLine(
        val text: String,
        val metrics: TextMetrics,
        val fontSize: Float,
        val lineHeight: Float,
        val fontFamily: String,
        val weight: SceneTextWeight,
        val spans: List<SceneTextSpan>,
    )

    private data class ClassVisual(
        val source: ClassNode,
        val style: FlowNodeStyle,
        val colorIndex: Int,
        val size: SceneSize,
        val annotation: VisualLine?,
        val title: VisualLine,
        val members: List<VisualLine>,
        val methods: List<VisualLine>,
        val headerHeight: Float,
        val membersHeight: Float,
        val methodsHeight: Float,
    )

    private data class NoteVisual(
        val source: ClassNote,
        val text: String,
        val spans: List<SceneTextSpan>,
        val metrics: TextMetrics,
        val padding: Float,
        val size: SceneSize,
    )

    private data class InterfaceVisual(
        val source: ClassInterface,
        val text: String,
        val spans: List<SceneTextSpan>,
        val metrics: TextMetrics,
        val size: SceneSize,
    )

    private data class NamespaceVisual(
        val text: String,
        val spans: List<SceneTextSpan>,
        val metrics: TextMetrics,
    )

    private data class EdgeLabelVisual(
        val text: String,
        val spans: List<SceneTextSpan>,
        val size: SceneSize,
    )

    private data class ClassLayoutEdge(
        val id: String,
        val from: String,
        val to: String,
        val note: Boolean = false,
        val relation: ClassRelation? = null,
    )

    private companion object {
        val HTML_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        const val EDGE_FONT_SIZE = 14f
        const val TERMINAL_FONT_SIZE = 11f
        const val TERMINAL_LABEL_GAP = 1f
        const val TITLE_FONT_SIZE = 18f
        const val DEFAULT_LINE_HEIGHT = 1.2f
        const val INTERFACE_LINE_HEIGHT = 1.5f
        const val EDGE_LABEL_PADDING_X = 6f
        const val EDGE_LABEL_PADDING_Y = 3f
        const val NAMESPACE_LABEL_TOP = 4f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
    }
}
