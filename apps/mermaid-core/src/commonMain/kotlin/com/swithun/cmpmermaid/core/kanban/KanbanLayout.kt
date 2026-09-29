package com.swithun.cmpmermaid.core.kanban

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneNodeInteraction
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidRenderedText
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import com.swithun.cmpmermaid.core.mermaidDarken
import com.swithun.cmpmermaid.core.mermaidLighten
import kotlin.math.max
import kotlin.math.min

/**
 * Native translation of Mermaid 12.0.0 kanbanRenderer.ts, styles.ts,
 * clusters.js -> kanbanSection, and shapes/kanbanItem.ts.
 */
internal class KanbanLayout {
    fun layout(
        document: KanbanDocument,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val sectionWidth = context.options.kanban.sectionWidth
        if (!sectionWidth.isFinite() || sectionWidth <= 0f) {
            return configurationError("sectionWidth must be a positive finite number")
        }
        if (
            !context.options.kanban.padding.isFinite() ||
            context.options.kanban.padding < 0f
        ) {
            return configurationError("padding must be a non-negative finite number")
        }
        val nodeCount = document.sections.size + document.sections.sumOf { it.items.size }
        if (nodeCount > context.options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "Kanban nodes",
                    actual = nodeCount,
                    maximum = context.options.maxEdges,
                ),
            )
        }

        val factory = KanbanNodeFactory(context)
        val sections = mutableListOf<KanbanSectionVisual>()
        var maxLabelHeight = MIN_SECTION_LABEL_HEIGHT
        document.sections.forEachIndexed { index, section ->
            val label = when (
                val measured = factory.measure(section.node.label, sectionWidth)
            ) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            maxLabelHeight = max(maxLabelHeight, label.metrics.height)
            val centerX = sectionWidth * (index + 1) +
                index * SECTION_PADDING / 2f
            sections += KanbanSectionVisual(
                section = section,
                index = index,
                centerX = centerX,
                label = label,
            )
        }

        val elements = mutableListOf<SceneElement>()
        val itemInteractions = mutableListOf<KanbanInteraction>()
        val itemVisuals = linkedMapOf<KanbanSectionVisual, List<KanbanItemVisual>>()
        sections.forEach { section ->
            val top = -sectionWidth * 1.5f + maxLabelHeight
            var y = top
            val visuals = mutableListOf<KanbanItemVisual>()
            section.section.items.forEach { item ->
                val visual = when (
                    val measured = factory.measureItem(
                        node = item,
                        width = sectionWidth - ITEM_WIDTH_PADDING,
                    )
                ) {
                    is GMResult.Ok -> measured.value
                    is GMResult.Err -> return measured
                }
                val centerY = y + visual.height / 2f
                visuals += visual.copy(
                    center = ScenePoint(section.centerX, centerY),
                )
                y = centerY + visual.height / 2f + ITEM_VERTICAL_GAP
            }
            section.height = max(y - top + SECTION_BOTTOM_PADDING, MIN_SECTION_HEIGHT) +
                (maxLabelHeight - MIN_SECTION_LABEL_HEIGHT)
            itemVisuals[section] = visuals
        }

        // Mermaid appends all section groups before the separate item layer.
        sections.forEach { section ->
            val rect = SceneRect(
                left = section.centerX - sectionWidth / 2f,
                top = -sectionWidth * 1.5f,
                right = section.centerX + sectionWidth / 2f,
                bottom = -sectionWidth * 1.5f + section.height,
            )
            val colors = factory.sectionColors(section.index)
            elements += SceneShape(
                id = "kanban-section-${section.index}-${section.section.node.id}",
                bounds = rect,
                kind = SceneShapeKind.RoundedRectangle,
                fill = colors.fill,
                stroke = colors.stroke,
                strokeWidth = if (context.options.look == NEO_LOOK) {
                    context.theme.strokeWidth
                } else {
                    1f
                },
                cornerRadius = SECTION_CORNER_RADIUS,
                shadow = if (context.options.look == NEO_LOOK) {
                    context.theme.dropShadow
                } else {
                    null
                },
                zIndex = SECTION_Z_START + section.index * SECTION_Z_STRIDE,
            )
            elements += SceneText(
                text = section.label.rendered.text,
                bounds = SceneRect(
                    left = section.centerX - section.label.metrics.width / 2f,
                    top = rect.top + context.options.subGraphTitleTopMargin,
                    right = section.centerX + section.label.metrics.width / 2f,
                    bottom = rect.top + context.options.subGraphTitleTopMargin +
                        section.label.metrics.height,
                ),
                color = colors.text,
                fontSize = factory.fontSize,
                lineHeight = factory.lineHeight,
                fontFamily = factory.fontFamily,
                weight = SceneTextWeight.Normal,
                spans = section.label.rendered.spans,
                horizontalAlignment = SceneTextAlignment.Center,
                softWrap = true,
                zIndex = SECTION_Z_START + section.index * SECTION_Z_STRIDE + 1,
            )
        }

        var itemIndex = 0
        sections.forEach { section ->
            itemVisuals.getValue(section).forEach { visual ->
                val paintBase = ITEM_Z_START + itemIndex * ITEM_Z_STRIDE
                val bounds = SceneRect(
                    left = visual.center.x - visual.width / 2f,
                    top = visual.center.y - visual.height / 2f,
                    right = visual.center.x + visual.width / 2f,
                    bottom = visual.center.y + visual.height / 2f,
                )
                elements += SceneShape(
                    id = visual.node.id,
                    bounds = bounds,
                    kind = SceneShapeKind.RoundedRectangle,
                    fill = context.theme.background,
                    stroke = context.theme.nodeStroke,
                    strokeWidth = 1f,
                    cornerRadius = ITEM_CORNER_RADIUS,
                    shadow = if (context.options.look == NEO_LOOK) {
                        context.theme.dropShadow
                    } else {
                        null
                    },
                    zIndex = paintBase,
                )
                elements += visual.title.toSceneText(
                    left = bounds.left + ITEM_TITLE_LEFT_PADDING,
                    top = visual.center.y -
                        visual.metadataHeight / 2f -
                        visual.title.metrics.height / 2f,
                    color = context.theme.nodeText,
                    fontSize = factory.fontSize,
                    lineHeight = factory.lineHeight,
                    fontFamily = factory.fontFamily,
                    alignment = SceneTextAlignment.Start,
                    zIndex = paintBase + 1,
                )
                visual.ticket?.let { ticket ->
                    val top = visual.center.y -
                        visual.metadataHeight / 2f +
                        visual.title.metrics.height / 2f
                    val ticketBounds = SceneRect(
                        left = bounds.left + ITEM_TITLE_LEFT_PADDING,
                        top = top,
                        right = bounds.left + ITEM_TITLE_LEFT_PADDING + ticket.metrics.width,
                        bottom = top + ticket.metrics.height,
                    )
                    val linked = context.options.kanban.ticketBaseUrl.isNotEmpty()
                    elements += ticket.toSceneText(
                        bounds = ticketBounds,
                        color = context.theme.nodeText,
                        fontSize = factory.fontSize,
                        lineHeight = factory.lineHeight,
                        fontFamily = factory.fontFamily,
                        alignment = SceneTextAlignment.Start,
                        zIndex = paintBase + 2,
                        underline = linked,
                    )
                    if (linked) {
                        itemInteractions += KanbanInteraction(
                            nodeId = visual.node.id,
                            bounds = ticketBounds,
                            link = context.options.kanban.ticketBaseUrl.replaceFirst(
                                "#TICKET#",
                                visual.node.ticket.orEmpty(),
                            ),
                        )
                    }
                }
                visual.assigned?.let { assigned ->
                    val top = visual.center.y -
                        visual.metadataHeight / 2f +
                        visual.title.metrics.height / 2f
                    elements += assigned.toSceneText(
                        left = bounds.right -
                            assigned.metrics.width -
                            ITEM_METADATA_RIGHT_PADDING,
                        top = top,
                        color = context.theme.nodeText,
                        fontSize = factory.fontSize,
                        lineHeight = factory.lineHeight,
                        fontFamily = factory.fontFamily,
                        alignment = SceneTextAlignment.Start,
                        zIndex = paintBase + 3,
                    )
                }
                visual.node.priority?.let { priority ->
                    val start = ScenePoint(
                        x = bounds.left + PRIORITY_X_OFFSET,
                        y = bounds.top + PRIORITY_CORNER_OFFSET,
                    )
                    val end = ScenePoint(
                        x = start.x,
                        y = bounds.bottom - PRIORITY_CORNER_OFFSET,
                    )
                    elements += ScenePath(
                        id = "${visual.node.id}-priority",
                        points = listOf(start, end),
                        commands = listOf(
                            ScenePathCommand.MoveTo(start),
                            ScenePathCommand.LineTo(end),
                        ),
                        color = priorityColor(priority),
                        strokeWidth = PRIORITY_STROKE_WIDTH,
                        strokePattern = SceneStrokePattern.Solid,
                        curve = "linear",
                        look = context.options.look,
                        animated = false,
                        zIndex = paintBase + 4,
                    )
                }
                itemIndex++
            }
        }

        return GMResult.Ok(normalize(elements, itemInteractions, context))
    }

    private fun normalize(
        elements: List<SceneElement>,
        itemInteractions: List<KanbanInteraction>,
        context: MermaidRenderContext,
    ): MermaidScene {
        val bounds = elements.mapNotNull(::elementBounds).reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        // Mermaid 12.0.0 kanbanRenderer.ts accidentally reads Mindmap padding
        // and useMaxWidth. Preserve that observable behavior for source parity.
        val padding = context.options.mindmap.padding
        val dx = padding - bounds.left
        val dy = padding - bounds.top
        return MermaidScene(
            width = max(1f, bounds.width + padding * 2f),
            height = max(1f, bounds.height + padding * 2f),
            background = context.theme.background,
            elements = elements.map { element -> element.translate(dx, dy) },
            interactions = itemInteractions.map { interaction ->
                SceneNodeInteraction(
                    nodeId = interaction.nodeId,
                    bounds = interaction.bounds.translate(dx, dy),
                    link = interaction.link,
                    linkTarget = "_blank",
                )
            },
            viewportPadding = 0f,
            viewportSizing = if (context.options.mindmap.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
    }

    private fun priorityColor(priority: String): SceneColor = when (priority) {
        "Very High" -> SceneColor(0xFFFF0000)
        "High" -> SceneColor(0xFFFFA500)
        "Low" -> SceneColor(0xFF0000FF)
        "Very Low" -> SceneColor(0xFFADD8E6)
        else -> TRANSPARENT
    }

    private fun configurationError(message: String): GMResult<MermaidScene, MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid Kanban $message"))

    private companion object {
        const val SECTION_PADDING = 10f
        const val ITEM_WIDTH_PADDING = 15f
        const val ITEM_VERTICAL_GAP = 5f
        const val SECTION_BOTTOM_PADDING = 30f
        const val MIN_SECTION_HEIGHT = 50f
        const val MIN_SECTION_LABEL_HEIGHT = 25f
        const val SECTION_CORNER_RADIUS = 5f
        const val ITEM_CORNER_RADIUS = 5f
        const val ITEM_TITLE_LEFT_PADDING = 10f
        const val ITEM_METADATA_RIGHT_PADDING = 10f
        const val PRIORITY_X_OFFSET = 2f
        const val PRIORITY_CORNER_OFFSET = 2f
        const val PRIORITY_STROKE_WIDTH = 4f
        const val SECTION_Z_START = 10
        const val SECTION_Z_STRIDE = 2
        const val ITEM_Z_START = 100
        const val ITEM_Z_STRIDE = 5
        const val NEO_LOOK = "neo"
        val TRANSPARENT = SceneColor(0x00000000)
    }
}

private class KanbanNodeFactory(
    private val context: MermaidRenderContext,
) {
    val fontSize: Float = context.options.fontSize ?: context.theme.fontSize
    val fontFamily: String = context.options.fontFamily ?: context.theme.fontFamily
    val lineHeight: Float = if (context.options.htmlLabels) {
        HTML_LINE_HEIGHT
    } else {
        SVG_LINE_HEIGHT
    }

    fun measure(
        source: String,
        maxWidth: Float,
    ): GMResult<KanbanTextVisual, MermaidError> {
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
        val metrics = try {
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = rendered.text,
                    fontSize = fontSize,
                    maxWidth = maxWidth,
                    lineHeight = lineHeight,
                    fontFamily = fontFamily,
                    weight = SceneTextWeight.Normal,
                    spans = rendered.spans,
                ),
            )
        } catch (failure: Exception) {
            return GMResult.Err(
                MermaidError.Unexpected.from(failure, "Kanban text measurement failed"),
            )
        }
        return GMResult.Ok(
            KanbanTextVisual(
                rendered = rendered,
                metrics = metrics.copy(
                    width = metrics.width.coerceAtMost(maxWidth),
                    height = metrics.height.coerceAtLeast(fontSize * lineHeight),
                ),
            ),
        )
    }

    fun measureItem(
        node: KanbanNode,
        width: Float,
    ): GMResult<KanbanItemVisual, MermaidError> {
        val title = when (
            val measured = measure(
                node.label,
                width - UPSTREAM_TITLE_WIDTH_REDUCTION - BUNDLED_FONT_WRAP_COMPENSATION,
            )
        ) {
            is GMResult.Ok -> measured.value
            is GMResult.Err -> return measured
        }
        val ticket = node.ticket?.takeIf(String::isNotEmpty)?.let { source ->
            when (val measured = measure(source, width - UPSTREAM_TITLE_WIDTH_REDUCTION)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
        }
        val assigned = node.assigned?.takeIf(String::isNotEmpty)?.let { source ->
            when (val measured = measure(source, width - UPSTREAM_TITLE_WIDTH_REDUCTION)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
        }
        val metadataHeight = max(
            ticket?.metrics?.height ?: 0f,
            assigned?.metrics?.height ?: 0f,
        )
        return GMResult.Ok(
            KanbanItemVisual(
                node = node,
                title = title,
                ticket = ticket,
                assigned = assigned,
                width = width,
                height = title.metrics.height + ITEM_VERTICAL_PADDING + metadataHeight / 2f,
                metadataHeight = metadataHeight,
            ),
        )
    }

    fun sectionColors(index: Int): KanbanSectionColors {
        // Mermaid styles.ts emits section-(i - 1), while the renderer starts
        // section numbering at one. The first visible section therefore uses
        // cScale2 rather than cScale0.
        val paletteIndex = (index + 2).mod(THEME_COLOR_LIMIT)
        val base = context.theme.mindmap.sectionFills.getOrElse(paletteIndex) {
            context.theme.groupFill
        }
        val fill = if (isDark(context.theme.background)) {
            base.mermaidDarken(10.0)
        } else {
            base.mermaidLighten(10.0)
        }
        return KanbanSectionColors(
            fill = fill,
            stroke = if (context.options.look == NEO_LOOK) {
                context.theme.nodeStroke
            } else {
                fill
            },
            text = context.theme.mindmap.sectionLabelColors.getOrElse(paletteIndex) {
                context.theme.nodeText
            },
        )
    }

    private fun isDark(color: SceneColor): Boolean {
        val red = (color.argb shr 16) and 0xFF
        val green = (color.argb shr 8) and 0xFF
        val blue = color.argb and 0xFF
        return red * 299L + green * 587L + blue * 114L < 128_000L
    }

    private companion object {
        // Mermaid passes width - 10 to browser Trebuchet. Bundled Arimo is
        // narrower for some word runs, so reserve 5 units to retain its wraps.
        const val UPSTREAM_TITLE_WIDTH_REDUCTION = 10f
        const val BUNDLED_FONT_WRAP_COMPENSATION = 5f
        const val ITEM_VERTICAL_PADDING = 20f
        const val HTML_LINE_HEIGHT = 1.5f
        const val SVG_LINE_HEIGHT = 1.1f
        const val THEME_COLOR_LIMIT = 12
        const val NEO_LOOK = "neo"
    }
}

private data class KanbanSectionVisual(
    val section: KanbanSection,
    val index: Int,
    val centerX: Float,
    val label: KanbanTextVisual,
    var height: Float = 0f,
)

private data class KanbanItemVisual(
    val node: KanbanNode,
    val title: KanbanTextVisual,
    val ticket: KanbanTextVisual?,
    val assigned: KanbanTextVisual?,
    val width: Float,
    val height: Float,
    val metadataHeight: Float,
    val center: ScenePoint = ScenePoint(0f, 0f),
)

private data class KanbanTextVisual(
    val rendered: MermaidRenderedText,
    val metrics: TextMetrics,
)

private data class KanbanSectionColors(
    val fill: SceneColor,
    val stroke: SceneColor,
    val text: SceneColor,
)

private data class KanbanInteraction(
    val nodeId: String,
    val bounds: SceneRect,
    val link: String,
)

private fun KanbanTextVisual.toSceneText(
    left: Float,
    top: Float,
    color: SceneColor,
    fontSize: Float,
    lineHeight: Float,
    fontFamily: String,
    alignment: SceneTextAlignment,
    zIndex: Int,
    underline: Boolean = false,
): SceneText = toSceneText(
    bounds = SceneRect(
        left = left,
        top = top,
        right = left + metrics.width,
        bottom = top + metrics.height,
    ),
    color = color,
    fontSize = fontSize,
    lineHeight = lineHeight,
    fontFamily = fontFamily,
    alignment = alignment,
    zIndex = zIndex,
    underline = underline,
)

private fun KanbanTextVisual.toSceneText(
    bounds: SceneRect,
    color: SceneColor,
    fontSize: Float,
    lineHeight: Float,
    fontFamily: String,
    alignment: SceneTextAlignment,
    zIndex: Int,
    underline: Boolean = false,
): SceneText {
    val spans = if (underline && rendered.text.isNotEmpty()) {
        rendered.spans + SceneTextSpan(
            start = 0,
            end = rendered.text.length,
            underline = true,
        )
    } else {
        rendered.spans
    }
    return SceneText(
        text = rendered.text,
        bounds = bounds,
        color = color,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
        weight = SceneTextWeight.Normal,
        spans = spans,
        horizontalAlignment = alignment,
        softWrap = true,
        zIndex = zIndex,
    )
}

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
