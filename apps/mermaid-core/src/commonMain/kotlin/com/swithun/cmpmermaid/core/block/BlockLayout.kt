package com.swithun.cmpmermaid.core.block

import com.swithun.cmpmermaid.core.GMResult
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
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.block.upstream.mermaid.Block
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockDb
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockEdgeGeometry
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockSize
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockText
import com.swithun.cmpmermaid.core.block.upstream.mermaid.BlockType
import com.swithun.cmpmermaid.core.flowchart.FlowDirection
import com.swithun.cmpmermaid.core.flowchart.FlowNode
import com.swithun.cmpmermaid.core.flowchart.FlowNodeStyle
import com.swithun.cmpmermaid.core.flowchart.FlowStyleAdapter
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.FlowLabelType
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapeLayout
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidShapePort
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidTextPort
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * block/renderHelpers.ts, block/layout.ts, block/blockRenderer.ts, and
 * rendering-elements/shapes/blockArrow.ts.
 */
internal class BlockLayout {
    fun layout(
        db: BlockDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val padding = context.options.block.padding
        if (!padding.isFinite() || padding < 0f) {
            return GMResult.Err(
                MermaidError.Configuration(
                    "Mermaid Block configuration padding must be non-negative",
                ),
            )
        }
        for (block in db.getBlocks()) {
            when (val measured = measureBlocks(block, db, context)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return measured
            }
        }
        val root = db.getBlock(BlockDb.ROOT_ID)
            ?: return GMResult.Err(MermaidError.Layout("Block root is missing"))
        setBlockSizes(root, padding = padding)
        when (val positioned = layoutBlocks(root, padding)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return positioned
        }

        val elements = mutableListOf<SceneElement>()
        for (block in db.getBlocks()) {
            when (val inserted = insertBlock(block, db, context, elements)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return inserted
            }
        }
        db.getEdges().forEachIndexed { index, edge ->
            when (val inserted = insertEdge(edge, index, db, context, elements)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return inserted
            }
        }
        return GMResult.Ok(normalize(elements, db, context))
    }

    private fun measureBlocks(
        block: Block,
        db: BlockDb,
        context: MermaidRenderContext,
    ): GMResult<Unit, MermaidError> {
        if (block.type != BlockType.Space) {
            val visual = when (val measured = measureBlock(block, db, context)) {
                is GMResult.Ok -> measured.value
                is GMResult.Err -> return measured
            }
            block.renderedLabel = visual.text
            block.style = visual.style
            block.shapeLayout = visual.shape
            block.size = BlockSize(
                width = visual.shape.size.width,
                height = visual.shape.size.height,
            )
            db.setBlock(block)
        }
        block.children.forEach { child ->
            when (val measured = measureBlocks(child, db, context)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return measured
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun measureBlock(
        block: Block,
        db: BlockDb,
        context: MermaidRenderContext,
    ): GMResult<BlockVisual, MermaidError> {
        val rendered = when (
            val text = MermaidTextPort.render(
                source = block.label.orEmpty(),
                labelType = FlowLabelType.Text,
                config = context.options,
            )
        ) {
            is GMResult.Ok -> text.value
            is GMResult.Err -> return text
        }
        val styleSource = buildList {
            block.classes.forEach { className ->
                addAll(db.getClasses()[className]?.styles.orEmpty())
            }
            addAll(block.styles)
        }
        val style = when (
            val parsed = FlowStyleAdapter.parse(
                styles = styleSource,
                owner = "Block '${block.id}'",
            )
        ) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val fontSize = textFontSize(style, context)
        val lineHeight = textLineHeight(style, context)
        val spans = textSpans(rendered.text, rendered.spans, style)
        val measured = try {
            context.textMetrics.measure(
                TextMetricsRequest(
                    text = rendered.text,
                    fontSize = fontSize,
                    maxWidth = UNWRAPPED_LABEL_MAX_WIDTH,
                    lineHeight = lineHeight,
                    fontFamily = style.fontFamily ?: context.theme.fontFamily,
                    weight = style.fontWeight ?: SceneTextWeight.Normal,
                    spans = spans,
                ),
            )
        } catch (failure: Exception) {
            return GMResult.Err(
                MermaidError.Unexpected.from(
                    failure,
                    "Text measurement failed for Block '${block.id}'",
                ),
            )
        }
        val textSize = SceneSize(measured.width, measured.height)
        val shape = when (block.type) {
            BlockType.Composite -> rectangleLayout(textSize)
            BlockType.BlockArrow -> blockArrowLayout(
                directions = block.directions,
                label = textSize,
                padding = context.options.block.padding,
            )
            else -> {
                val kind = shapeKind(block.type)
                when (
                    val result = MermaidShapePort.layout(
                        node = FlowNode(
                            id = block.id,
                            label = rendered.text,
                            labelSpans = rendered.spans,
                            labelType = FlowLabelType.Text,
                            shape = kind,
                            padding = context.options.block.padding,
                            minWidth = null,
                            look = context.options.look,
                        ),
                        measuredLabel = textSize,
                        direction = FlowDirection.TopToBottom,
                        defaultNodeStroke = context.theme.nodeStroke,
                        defaultFlowContainerStroke = context.theme.flowContainerStroke,
                    )
                ) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
            }
        }
        return GMResult.Ok(
            BlockVisual(
                text = BlockText(rendered.text, rendered.spans),
                style = style,
                shape = shape,
            ),
        )
    }

    private fun setBlockSizes(
        block: Block,
        siblingWidth: Float = 0f,
        siblingHeight: Float = 0f,
        padding: Float,
    ) {
        if (block.size?.width == null || block.size?.width == 0f) {
            block.size = BlockSize(siblingWidth, siblingHeight)
        }
        if (block.children.isEmpty()) {
            return
        }
        block.children.forEach { child ->
            setBlockSizes(child, padding = padding)
        }
        val childSize = getMaxChildSize(block)
        val maxWidth = childSize.width
        val maxHeight = childSize.height
        block.children.forEach { child ->
            child.size?.let { size ->
                val span = child.widthInColumns ?: 1
                size.width = maxWidth * span + padding * (span - 1)
                size.height = maxHeight
                size.x = 0f
                size.y = 0f
            }
        }
        block.children.forEach { child ->
            setBlockSizes(child, maxWidth, maxHeight, padding)
        }

        val columns = block.columns ?: -1
        val itemCount = block.children.sumOf { child -> child.widthInColumns ?: 1 }
        var xSize = block.children.size
        if (columns > 0 && columns < itemCount) {
            xSize = columns
        }
        if (xSize <= 0) {
            return
        }
        val ySize = ceil(itemCount.toFloat() / xSize.toFloat()).toInt()
        var width = xSize * (maxWidth + padding) + padding
        var height = ySize * (maxHeight + padding) + padding
        if (width < siblingWidth) {
            width = siblingWidth
            height = siblingHeight
            val childWidth = (siblingWidth - xSize * padding - padding) / xSize
            val childHeight = (siblingHeight - ySize * padding - padding) / ySize
            block.children.forEach { child ->
                child.size?.let { size ->
                    size.width = childWidth
                    size.height = childHeight
                    size.x = 0f
                    size.y = 0f
                }
            }
        }
        val existingWidth = block.size?.width ?: 0f
        if (width < existingWidth) {
            width = existingWidth
            val count = if (columns > 0) {
                minOf(block.children.size, columns)
            } else {
                block.children.size
            }
            if (count > 0) {
                val childWidth = (width - count * padding - padding) / count
                block.children.forEach { child ->
                    child.size?.width = childWidth
                }
            }
        }
        block.size = BlockSize(width = width, height = height)
    }

    private fun getMaxChildSize(block: Block): SceneSize {
        var maxWidth = 0f
        var maxHeight = 0f
        block.children.forEach { child ->
            val size = child.size ?: BlockSize(0f, 0f)
            if (child.type == BlockType.Space) {
                return@forEach
            }
            maxWidth = max(maxWidth, size.width / (child.widthInColumns ?: 1))
            maxHeight = max(maxHeight, size.height)
        }
        return SceneSize(maxWidth, maxHeight)
    }

    private fun layoutBlocks(
        block: Block,
        padding: Float,
    ): GMResult<Unit, MermaidError> {
        if (block.children.isEmpty()) {
            return GMResult.Ok(Unit)
        }
        val columns = block.columns ?: -1
        if (columns == 0) {
            return GMResult.Err(
                MermaidError.Layout("Block columns must be an integer other than zero"),
            )
        }
        val rowHeights = linkedMapOf<Int, Float>()
        var columnPosition = 0
        block.children.forEach { child ->
            val size = child.size ?: return@forEach
            val position = when (val calculated = calculateBlockPosition(columns, columnPosition)) {
                is GMResult.Ok -> calculated.value
                is GMResult.Err -> return calculated
            }
            rowHeights[position.second] = max(
                rowHeights[position.second] ?: 0f,
                size.height,
            )
            var filled = child.widthInColumns ?: 1
            if (columns > 0) {
                filled = minOf(filled, columns - columnPosition % columns)
            }
            columnPosition += filled
        }
        val rowYOffsets = linkedMapOf<Int, Float>()
        var offset = 0f
        rowHeights.keys.sorted().forEach { row ->
            rowYOffsets[row] = offset
            offset += (rowHeights[row] ?: 0f) + padding
        }

        columnPosition = 0
        var rowPosition = 0
        val blockSize = block.size ?: return GMResult.Ok(Unit)
        var startingX = if (blockSize.x != 0f) {
            blockSize.x - blockSize.width / 2f
        } else {
            -padding
        }
        block.children.forEach { child ->
            val size = child.size ?: return@forEach
            val position = when (val calculated = calculateBlockPosition(columns, columnPosition)) {
                is GMResult.Ok -> calculated.value
                is GMResult.Err -> return calculated
            }
            val py = position.second
            if (py != rowPosition) {
                rowPosition = py
                startingX = if (blockSize.x != 0f) {
                    blockSize.x - blockSize.width / 2f
                } else {
                    -padding
                }
            }
            val halfWidth = size.width / 2f
            size.x = startingX + padding + halfWidth
            startingX = size.x + halfWidth
            val rowOffset = rowYOffsets[py] ?: 0f
            val rowHeight = rowHeights[py] ?: size.height
            size.y = blockSize.y - blockSize.height / 2f + rowOffset + rowHeight / 2f + padding
            when (val nested = layoutBlocks(child, padding)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return nested
            }
            var filled = child.widthInColumns ?: 1
            if (columns > 0) {
                filled = minOf(filled, columns - columnPosition % columns)
            }
            columnPosition += filled
        }
        return GMResult.Ok(Unit)
    }

    private fun calculateBlockPosition(
        columns: Int,
        position: Int,
    ): GMResult<Pair<Int, Int>, MermaidError> {
        if (columns == 0) {
            return GMResult.Err(
                MermaidError.Layout("Columns must be an integer other than zero"),
            )
        }
        if (position < 0) {
            return GMResult.Err(
                MermaidError.Layout("Position must be a non-negative integer: $position"),
            )
        }
        return GMResult.Ok(
            when {
                columns < 0 -> position to 0
                columns == 1 -> 0 to position
                else -> position % columns to position / columns
            },
        )
    }

    private fun insertBlock(
        block: Block,
        db: BlockDb,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        if (block.type != BlockType.Space) {
            val size = block.size
                ?: return GMResult.Err(MermaidError.Layout("Block '${block.id}' has no size"))
            val natural = block.shapeLayout
                ?: return GMResult.Err(MermaidError.Layout("Block '${block.id}' has no shape"))
            val style = block.style ?: FlowNodeStyle()
            val kind = if (block.type == BlockType.Composite) {
                SceneShapeKind.Rectangle
            } else {
                shapeKind(block.type)
            }
            val label = block.renderedLabel
            val labelMetrics = if (label != null && label.text.isNotEmpty()) {
                val fontSize = textFontSize(style, context)
                val lineHeight = textLineHeight(style, context)
                try {
                    context.textMetrics.measure(
                        TextMetricsRequest(
                            text = label.text,
                            fontSize = fontSize,
                            maxWidth = size.width.coerceAtLeast(1f),
                            lineHeight = lineHeight,
                            fontFamily = style.fontFamily ?: context.theme.fontFamily,
                            weight = style.fontWeight ?: SceneTextWeight.Normal,
                            spans = textSpans(label.text, label.spans, style),
                        ),
                    )
                } catch (failure: Exception) {
                    return GMResult.Err(
                        MermaidError.Unexpected.from(
                            failure,
                            "Text measurement failed for Block '${block.id}'",
                        ),
                    )
                }
            } else {
                null
            }
            val positioned = positionedShapeLayout(
                block = block,
                natural = natural,
                allocated = size,
                label = labelMetrics?.let { SceneSize(it.width, it.height) } ?: SceneSize(0f, 0f),
                padding = context.options.block.padding,
                look = context.options.look,
            )
            val renderedSize = BlockSize(
                width = positioned.size.width,
                height = positioned.size.height,
                x = size.x,
                y = size.y,
            )
            block.renderedSize = renderedSize
            block.shapeLayout = positioned
            val isComposite = block.type == BlockType.Composite
            val paletteEnabled = context.options.themeName?.lowercase() in COLOR_THEMES &&
                context.theme.borderColorArray.isNotEmpty()
            val fill = style.fill ?: when {
                isComposite && paletteEnabled -> context.theme.colorFill(block.colorIndex)
                isComposite -> context.theme.groupFill.withAlpha(COMPOSITE_FILL_ALPHA)
                else -> context.theme.nodeFill
            }
            val stroke = style.stroke ?: when {
                isComposite && paletteEnabled -> context.theme.colorStroke(block.colorIndex)
                isComposite -> context.theme.groupStroke.withAlpha(COMPOSITE_STROKE_ALPHA)
                else -> context.theme.nodeStroke
            }
            val bounds = SceneRect(
                left = renderedSize.x - renderedSize.width / 2f,
                top = renderedSize.y - renderedSize.height / 2f,
                right = renderedSize.x + renderedSize.width / 2f,
                bottom = renderedSize.y + renderedSize.height / 2f,
            )
            val paint = elements.size + 1
            elements += SceneShape(
                id = block.id,
                bounds = bounds,
                kind = kind,
                geometry = positioned.geometry,
                fill = fill,
                stroke = stroke,
                strokeWidth = style.strokeWidth ?: context.theme.strokeWidth,
                strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
                dashIntervals = style.dashIntervals,
                cornerRadius = if (block.type == BlockType.Round) 5f else 0f,
                shadow = context.theme.dropShadow.takeIf {
                    context.options.look == NEO_LOOK
                },
                zIndex = paint,
            )
            if (label != null && labelMetrics != null) {
                val fontSize = textFontSize(style, context)
                val lineHeight = textLineHeight(style, context)
                val center = bounds.center
                elements += SceneText(
                    text = label.text,
                    bounds = SceneRect(
                        left = center.x - labelMetrics.width / 2f,
                        top = center.y - labelMetrics.height / 2f,
                        right = center.x + labelMetrics.width / 2f,
                        bottom = center.y + labelMetrics.height / 2f,
                    ),
                    color = style.text ?: context.theme.nodeText,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontFamily = style.fontFamily ?: context.theme.fontFamily,
                    weight = style.fontWeight ?: SceneTextWeight.Normal,
                    italic = style.italic == true,
                    spans = textSpans(label.text, label.spans, style),
                    horizontalAlignment = style.textAlignment ?: SceneTextAlignment.Center,
                    softWrap = true,
                    zIndex = paint + 1,
                )
            }
        }
        block.children.forEach { child ->
            when (val inserted = insertBlock(child, db, context, elements)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return inserted
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun insertEdge(
        edge: Block,
        index: Int,
        db: BlockDb,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ): GMResult<Unit, MermaidError> {
        val start = edge.start?.let(db::getBlock) ?: return GMResult.Ok(Unit)
        val end = edge.end?.let(db::getBlock) ?: return GMResult.Ok(Unit)
        val startSize = start.renderedSize ?: start.size ?: return GMResult.Ok(Unit)
        val endSize = end.renderedSize ?: end.size ?: return GMResult.Ok(Unit)
        val geometry = edgeGeometry(start, startSize, end, endSize)
        val z = elements.size + 1
        elements += ScenePath(
            id = edge.id,
            points = geometry.points,
            commands = geometry.points.mapIndexed { pointIndex, point ->
                if (pointIndex == 0) {
                    ScenePathCommand.MoveTo(point)
                } else {
                    ScenePathCommand.LineTo(point)
                }
            },
            color = context.theme.edge,
            strokeWidth = if (edge.thickness == "thick") 3.5f else 2f,
            strokePattern = if (edge.pattern == "dotted") {
                SceneStrokePattern.Dotted
            } else {
                SceneStrokePattern.Solid
            },
            arrowStart = arrowHead(edge.arrowTypeStart),
            arrowEnd = arrowHead(edge.arrowTypeEnd),
            curve = "linear",
            look = context.options.look,
            animated = false,
            markerBackground = context.theme.background,
            zIndex = z,
        )
        val label = edge.label.orEmpty()
        if (label.isNotEmpty()) {
            val rendered = when (
                val result = MermaidTextPort.render(
                    label,
                    FlowLabelType.Text,
                    context.options,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val measured = try {
                context.textMetrics.measure(
                    TextMetricsRequest(
                        text = rendered.text,
                        fontSize = context.options.fontSize ?: context.theme.fontSize,
                        maxWidth = EDGE_LABEL_MAX_WIDTH,
                        lineHeight = DEFAULT_LINE_HEIGHT,
                        fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                        weight = SceneTextWeight.Normal,
                        spans = rendered.spans,
                    ),
                )
            } catch (failure: Exception) {
                return GMResult.Err(
                    MermaidError.Unexpected.from(
                        failure,
                        "Edge label measurement failed for Block edge '$index'",
                    ),
                )
            }
            val labelBounds = SceneRect(
                left = geometry.labelAnchor.x - measured.width / 2f - EDGE_LABEL_PADDING,
                top = geometry.labelAnchor.y - measured.height / 2f - EDGE_LABEL_PADDING,
                right = geometry.labelAnchor.x + measured.width / 2f + EDGE_LABEL_PADDING,
                bottom = geometry.labelAnchor.y + measured.height / 2f + EDGE_LABEL_PADDING,
            )
            elements += SceneShape(
                id = "${edge.id}-label-background",
                bounds = labelBounds,
                kind = SceneShapeKind.Rectangle,
                fill = context.theme.edgeLabelFill,
                stroke = TRANSPARENT,
                strokeWidth = 0f,
                cornerRadius = 0f,
                zIndex = z + 1,
            )
            elements += SceneText(
                text = rendered.text,
                bounds = labelBounds,
                color = context.theme.nodeText,
                fontSize = context.options.fontSize ?: context.theme.fontSize,
                lineHeight = DEFAULT_LINE_HEIGHT,
                fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                spans = rendered.spans,
                zIndex = z + 2,
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun edgeGeometry(
        start: Block,
        startSize: BlockSize,
        end: Block,
        endSize: BlockSize,
    ): BlockEdgeGeometry {
        val startCenter = ScenePoint(startSize.x, startSize.y)
        val endCenter = ScenePoint(endSize.x, endSize.y)
        val middle = ScenePoint(
            x = startCenter.x + (endCenter.x - startCenter.x) / 2f,
            y = startCenter.y + (endCenter.y - startCenter.y) / 2f,
        )
        return BlockEdgeGeometry(
            points = listOf(
                intersect(start.shapeLayout, startSize, middle),
                middle,
                intersect(end.shapeLayout, endSize, middle),
            ),
            labelAnchor = middle,
        )
    }

    private fun intersect(
        shapeLayout: MermaidShapeLayout?,
        size: BlockSize,
        toward: ScenePoint,
    ): ScenePoint {
        val center = ScenePoint(size.x, size.y)
        val natural = shapeLayout?.size
        val outline = shapeLayout?.geometry?.outline?.map { point ->
            ScenePoint(
                x = center.x + point.x * size.width / (natural?.width ?: size.width),
                y = center.y + point.y * size.height / (natural?.height ?: size.height),
            )
        }.orEmpty().ifEmpty {
            rectanglePoints(size.width, size.height).map { point ->
                ScenePoint(center.x + point.x, center.y + point.y)
            }
        }
        val directionX = toward.x - center.x
        val directionY = toward.y - center.y
        var best: Pair<Float, ScenePoint>? = null
        (outline + outline.first()).zipWithNext().forEach { (first, second) ->
            val segmentX = second.x - first.x
            val segmentY = second.y - first.y
            val denominator = directionX * segmentY - directionY * segmentX
            if (abs(denominator) < 0.0001f) return@forEach
            val offsetX = first.x - center.x
            val offsetY = first.y - center.y
            val ray = (offsetX * segmentY - offsetY * segmentX) / denominator
            val segment = (offsetX * directionY - offsetY * directionX) / denominator
            val currentBest = best
            if (
                ray in 0f..1f &&
                segment in 0f..1f &&
                (currentBest == null || ray > currentBest.first)
            ) {
                best = ray to ScenePoint(
                    x = center.x + directionX * ray,
                    y = center.y + directionY * ray,
                )
            }
        }
        return best?.second ?: center
    }

    private fun normalize(
        elements: List<SceneElement>,
        db: BlockDb,
        context: MermaidRenderContext,
    ): MermaidScene {
        val bounds = elements
            .mapNotNull(::elementBounds)
            .reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val dx = VIEWBOX_PADDING - bounds.left
        val dy = VIEWBOX_PADDING - bounds.top
        return MermaidScene(
            width = max(1f, bounds.width + VIEWBOX_PADDING * 2f),
            height = max(1f, bounds.height + VIEWBOX_PADDING * 2f),
            background = context.theme.background,
            elements = elements
                .map { element -> element.translate(dx, dy) }
                .sortedBy(SceneElement::zIndex),
            title = db.diagramTitle,
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
            viewportPadding = 0f,
            viewportSizing = if (context.options.block.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
    }

    /**
     * Mermaid.js 12.0.0:
     * rendering-elements/shapes/{blockArrow,circle,doubleCircle,stadium,
     * question,cylinder}.ts.
     *
     * Block layout assigns a common cell size, but the positioned shape handlers
     * do not all consume it in the same way.
     */
    private fun positionedShapeLayout(
        block: Block,
        natural: MermaidShapeLayout,
        allocated: BlockSize,
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout = when (block.type) {
        BlockType.BlockArrow -> {
            val spansColumns = (block.widthInColumns ?: 1) > 1
            if (spansColumns && allocated.width > natural.size.width) {
                blockArrowLayout(
                    directions = block.directions,
                    label = label,
                    padding = padding,
                    totalWidth = allocated.width,
                )
            } else {
                natural
            }
        }
        BlockType.Circle,
        BlockType.Diamond,
        BlockType.Ellipse,
        BlockType.Stadium,
        -> natural
        BlockType.DoubleCircle -> positionedDoubleCircleLayout(
            allocatedWidth = allocated.width,
            padding = padding,
            look = look,
        )
        BlockType.Cylinder -> scaleLayout(
            natural = natural,
            target = SceneSize(
                width = allocated.width,
                height = positionedCylinderHeight(
                    allocated = allocated,
                    label = label,
                    padding = padding,
                    look = look,
                ),
            ),
        )
        else -> scaleLayout(
            natural = natural,
            target = SceneSize(allocated.width, allocated.height),
        )
    }

    private fun positionedDoubleCircleLayout(
        allocatedWidth: Float,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val labelPadding = if (look == NEO_LOOK) 16f else padding
        val gap = if (look == NEO_LOOK) 12f else 5f
        val innerRadius = allocatedWidth / 2f + labelPadding
        val outerRadius = innerRadius + gap
        val outer = ellipsePoints(outerRadius)
        val inner = ellipsePoints(innerRadius)
        return MermaidShapeLayout(
            size = SceneSize(outerRadius * 2f, outerRadius * 2f),
            labelOffset = ScenePoint(0f, 0f),
            geometry = SceneShapeGeometry(
                paths = listOf(
                    SceneShapePath(points = outer),
                    SceneShapePath(points = inner, fill = SceneShapePaint.None),
                ),
                outline = outer,
            ),
            showsLabel = true,
        )
    }

    private fun positionedCylinderHeight(
        allocated: BlockSize,
        label: SceneSize,
        padding: Float,
        look: String,
    ): Float {
        val labelPaddingX = if (look == NEO_LOOK) 24f else padding
        val labelPaddingY = if (look == NEO_LOOK) 24f else padding
        val originalRadiusX = allocated.width / 2f
        val originalRadiusY = originalRadiusX / (2.5f + allocated.width / 50f)
        val contentWidth = max(8f, allocated.width - labelPaddingY)
        val width = max(contentWidth, label.width) + labelPaddingY
        val radiusX = width / 2f
        val radiusY = radiusX / (2.5f + width / 50f)
        val contentHeight = max(
            8f,
            allocated.height - labelPaddingX - originalRadiusY * 3f,
        )
        val bodyHeight = max(contentHeight, label.height) + labelPaddingX + radiusY
        return bodyHeight + radiusY * 2f
    }

    private fun scaleLayout(
        natural: MermaidShapeLayout,
        target: SceneSize,
    ): MermaidShapeLayout = natural.copy(
        size = target,
        geometry = scaleGeometry(
            geometry = natural.geometry,
            natural = natural.size,
            target = BlockSize(target.width, target.height),
        ),
    )

    private fun ellipsePoints(
        radius: Float,
        count: Int = 50,
    ): List<ScenePoint> = (0 until count).map { index ->
        val angle = 2f * PI.toFloat() * index / count
        ScenePoint(
            x = radius * cos(angle),
            y = radius * sin(angle),
        )
    }

    private fun scaleGeometry(
        geometry: SceneShapeGeometry,
        natural: SceneSize,
        target: BlockSize,
    ): SceneShapeGeometry {
        val scaleX = target.width / natural.width.coerceAtLeast(0.1f)
        val scaleY = target.height / natural.height.coerceAtLeast(0.1f)
        fun ScenePoint.scale() = ScenePoint(x * scaleX, y * scaleY)
        return SceneShapeGeometry(
            paths = geometry.paths.map { path ->
                path.copy(points = path.points.map(ScenePoint::scale))
            },
            outline = geometry.outline.map(ScenePoint::scale),
        )
    }

    private fun blockArrowLayout(
        directions: List<String>,
        label: SceneSize,
        padding: Float,
        totalWidth: Float? = null,
    ): MermaidShapeLayout {
        val halfPadding = padding / 2f
        val height = label.height + 4f * halfPadding
        val midpoint = height / 2f
        val width = totalWidth ?: (label.width + 2f * midpoint + 2f * halfPadding)
        val expanded = linkedSetOf<String>()
        directions.forEach { direction ->
            when (direction.trim()) {
                "x" -> {
                    expanded += "right"
                    expanded += "left"
                }
                "y" -> {
                    expanded += "up"
                    expanded += "down"
                }
                "right", "left", "up", "down" -> expanded += direction.trim()
            }
        }
        val key = DIRECTION_ORDER.filter(expanded::contains).joinToString("|")
        val points = arrowPoints(
            key = key,
            width = width,
            height = height,
            midpoint = midpoint,
            padding = halfPadding,
        )
        val minX = points.minOfOrNull(ScenePoint::x) ?: 0f
        val maxX = points.maxOfOrNull(ScenePoint::x) ?: 0.1f
        val minY = points.minOfOrNull(ScenePoint::y) ?: 0f
        val maxY = points.maxOfOrNull(ScenePoint::y) ?: 0.1f
        val center = ScenePoint((minX + maxX) / 2f, (minY + maxY) / 2f)
        val normalized = points.map { point ->
            ScenePoint(point.x - center.x, point.y - center.y)
        }
        return MermaidShapeLayout(
            size = SceneSize(maxX - minX, maxY - minY),
            labelOffset = ScenePoint(0f, 0f),
            geometry = SceneShapeGeometry(
                paths = listOf(SceneShapePath(normalized)),
                outline = normalized,
            ),
            showsLabel = true,
        )
    }

    private fun arrowPoints(
        key: String,
        width: Float,
        height: Float,
        midpoint: Float,
        padding: Float,
    ): List<ScenePoint> = when (key) {
        "right|left|up|down" -> points(
            0f to 0f,
            midpoint to 0f,
            width / 2f to 2f * padding,
            width - midpoint to 0f,
            width to 0f,
            width to -height / 3f,
            width + 2f * padding to -height / 2f,
            width to -2f * height / 3f,
            width to -height,
            width - midpoint to -height,
            width / 2f to -height - 2f * padding,
            midpoint to -height,
            0f to -height,
            0f to -2f * height / 3f,
            -2f * padding to -height / 2f,
            0f to -height / 3f,
        )
        "right|left|up" -> points(
            midpoint to 0f,
            width - midpoint to 0f,
            width to -height / 2f,
            width - midpoint to -height,
            midpoint to -height,
            0f to -height / 2f,
        )
        "right|left|down" -> points(
            0f to 0f,
            midpoint to -height,
            width - midpoint to -height,
            width to 0f,
        )
        "right|up|down" -> points(
            0f to 0f,
            width to -midpoint,
            width to -height + midpoint,
            0f to -height,
        )
        "left|up|down" -> points(
            width to 0f,
            0f to -midpoint,
            0f to -height + midpoint,
            width to -height,
        )
        "right|left" -> points(
            midpoint to 0f,
            midpoint to -padding,
            width - midpoint to -padding,
            width - midpoint to 0f,
            width to -height / 2f,
            width - midpoint to -height,
            width - midpoint to -height + padding,
            midpoint to -height + padding,
            midpoint to -height,
            0f to -height / 2f,
        )
        "up|down" -> points(
            width / 2f to 0f,
            0f to -padding,
            midpoint to -padding,
            midpoint to -height + padding,
            0f to -height + padding,
            width / 2f to -height,
            width to -height + padding,
            width - midpoint to -height + padding,
            width - midpoint to -padding,
            width to -padding,
        )
        "right|up" -> points(
            0f to 0f,
            width to -midpoint,
            0f to -height,
        )
        "right|down" -> points(
            0f to 0f,
            width to 0f,
            0f to -height,
        )
        "left|up" -> points(
            width to 0f,
            0f to -midpoint,
            width to -height,
        )
        "left|down" -> points(
            width to 0f,
            0f to 0f,
            width to -height,
        )
        "right" -> points(
            midpoint to -padding,
            midpoint to -padding,
            width - midpoint to -padding,
            width - midpoint to 0f,
            width to -height / 2f,
            width - midpoint to -height,
            width - midpoint to -height + padding,
            midpoint to -height + padding,
            midpoint to -height + padding,
        )
        "left" -> points(
            midpoint to 0f,
            midpoint to -padding,
            width - midpoint to -padding,
            width - midpoint to -height + padding,
            midpoint to -height + padding,
            midpoint to -height,
            0f to -height / 2f,
        )
        "up" -> points(
            midpoint to -padding,
            midpoint to -height + padding,
            0f to -height + padding,
            width / 2f to -height,
            width to -height + padding,
            width - midpoint to -height + padding,
            width - midpoint to -padding,
        )
        "down" -> points(
            width / 2f to 0f,
            0f to -padding,
            midpoint to -padding,
            midpoint to -height + padding,
            width - midpoint to -height + padding,
            width - midpoint to -padding,
            width to -padding,
        )
        else -> points(0f to 0f)
    }

    private fun rectangleLayout(label: SceneSize): MermaidShapeLayout {
        val width = label.width.coerceAtLeast(0.1f)
        val height = label.height.coerceAtLeast(0.1f)
        val outline = rectanglePoints(width, height)
        return MermaidShapeLayout(
            size = SceneSize(width, height),
            labelOffset = ScenePoint(0f, 0f),
            geometry = SceneShapeGeometry(
                paths = listOf(SceneShapePath(outline)),
                outline = outline,
            ),
            showsLabel = true,
        )
    }

    private fun shapeKind(type: BlockType): SceneShapeKind = when (type) {
        BlockType.Round -> SceneShapeKind.RoundedRectangle
        BlockType.Diamond -> SceneShapeKind.Diamond
        BlockType.Hexagon -> SceneShapeKind.Hexagon
        BlockType.LeanRight -> SceneShapeKind.Parallelogram
        BlockType.LeanLeft -> SceneShapeKind.ParallelogramAlt
        BlockType.Trapezoid -> SceneShapeKind.Trapezoid
        BlockType.InverseTrapezoid -> SceneShapeKind.TrapezoidAlt
        BlockType.RectangleLeftInverseArrow,
        BlockType.Odd,
        -> SceneShapeKind.Asymmetric
        BlockType.Circle -> SceneShapeKind.Circle
        BlockType.Ellipse -> SceneShapeKind.Ellipse
        BlockType.Stadium -> SceneShapeKind.Stadium
        BlockType.Subroutine -> SceneShapeKind.Subroutine
        BlockType.Cylinder -> SceneShapeKind.Cylinder
        BlockType.DoubleCircle -> SceneShapeKind.DoubleCircle
        else -> SceneShapeKind.Rectangle
    }

    private fun arrowHead(value: String): SceneArrowHead = when (value) {
        "arrow_point" -> SceneArrowHead.Triangle
        "arrow_circle" -> SceneArrowHead.Circle
        "arrow_cross" -> SceneArrowHead.Cross
        else -> SceneArrowHead.None
    }

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
        val inherited = if (context.options.htmlLabels) DEFAULT_LINE_HEIGHT else SVG_LINE_HEIGHT
        val fontSize = textFontSize(style, context)
        return style.lineHeightPixels
            ?.div(fontSize)
            ?.takeIf { it.isFinite() && it > 0f }
            ?: style.lineHeightMultiplier
            ?: inherited
    }

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

    private fun elementBounds(element: SceneElement): SceneRect? = when (element) {
        is SceneShape -> element.bounds
        is SceneText -> element.bounds
        is ScenePath -> element.points.takeIf(List<ScenePoint>::isNotEmpty)?.let { points ->
            SceneRect(
                left = points.minOf(ScenePoint::x),
                top = points.minOf(ScenePoint::y),
                right = points.maxOf(ScenePoint::x),
                bottom = points.maxOf(ScenePoint::y),
            )
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

    private fun SceneColor.withAlpha(alpha: Int): SceneColor =
        SceneColor((argb and 0x00FFFFFFL) or (alpha.toLong() shl 24))

    private fun points(vararg points: Pair<Float, Float>): List<ScenePoint> =
        points.map { (x, y) -> ScenePoint(x, y) }

    private fun rectanglePoints(width: Float, height: Float): List<ScenePoint> =
        points(
            -width / 2f to -height / 2f,
            width / 2f to -height / 2f,
            width / 2f to height / 2f,
            -width / 2f to height / 2f,
        )

    private data class BlockVisual(
        val text: BlockText,
        val style: FlowNodeStyle,
        val shape: MermaidShapeLayout,
    )

    private companion object {
        const val NEO_LOOK = "neo"
        const val DEFAULT_LINE_HEIGHT = 1.5f
        const val SVG_LINE_HEIGHT = 1.1f
        const val UNWRAPPED_LABEL_MAX_WIDTH = 100_000f
        const val EDGE_LABEL_MAX_WIDTH = 180f
        const val EDGE_LABEL_PADDING = 2f
        const val VIEWBOX_PADDING = 5f
        const val COMPOSITE_FILL_ALPHA = 0x80
        const val COMPOSITE_STROKE_ALPHA = 0x33
        val TRANSPARENT = SceneColor(0x00000000)
        val COLOR_THEMES = setOf("redux-color", "redux-dark-color")
        val DIRECTION_ORDER = listOf("right", "left", "up", "down")
    }
}
