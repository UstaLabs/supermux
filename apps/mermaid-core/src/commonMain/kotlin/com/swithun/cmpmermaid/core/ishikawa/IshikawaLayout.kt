package com.swithun.cmpmermaid.core.ishikawa

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneAsset
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
import com.swithun.cmpmermaid.core.ishikawa.upstream.mermaid.IshikawaDb
import com.swithun.cmpmermaid.core.ishikawa.upstream.mermaid.IshikawaNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/ishikawa/ishikawaRenderer.ts -> draw.
 */
internal class IshikawaLayout {
    fun layout(
        db: IshikawaDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val padding = db.config.diagramPadding
        if (!padding.isFinite() || padding < 0f) {
            return configurationError("diagramPadding must be non-negative")
        }
        val fontSize = context.options.fontSize ?: context.theme.fontSize
        if (!fontSize.isFinite() || fontSize <= 0f) {
            return configurationError("fontSize must be positive")
        }
        val root = db.getRoot()
            ?: return GMResult.Err(MermaidError.Layout("Ishikawa diagram has no effect node"))
        val causes = root.children
        val upperStats = sideStats(causes.filterIndexed { index, _ -> index % 2 == 0 })
        val lowerStats = sideStats(causes.filterIndexed { index, _ -> index % 2 == 1 })
        val descendantTotal = upperStats.total + lowerStats.total
        var upperLength = SPINE_BASE_LENGTH
        var lowerLength = SPINE_BASE_LENGTH
        if (descendantTotal > 0) {
            val pool = SPINE_BASE_LENGTH * 2f
            val minimumLength = SPINE_BASE_LENGTH * 0.3f
            upperLength = max(minimumLength, pool * upperStats.total / descendantTotal)
            lowerLength = max(minimumLength, pool * lowerStats.total / descendantTotal)
        }
        val minimumSpacing = fontSize * 2f
        upperLength = max(upperLength, upperStats.maximum * minimumSpacing)
        lowerLength = max(lowerLength, lowerStats.maximum * minimumSpacing)
        val spineY = max(upperLength, SPINE_BASE_LENGTH)

        val state = DrawState()
        when (val head = drawHead(root.text, spineY, fontSize, context, state)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return head
        }
        var spineX = 0f
        if (causes.isNotEmpty()) {
            spineX -= 20f
            val pairCount = (causes.size + 1) / 2
            repeat(pairCount) { pairIndex ->
                var pairMinimumTextX = Float.POSITIVE_INFINITY
                val upperCause = causes.getOrNull(pairIndex * 2)
                if (upperCause != null) {
                    when (
                        val drawn = drawBranch(
                            node = upperCause,
                            startX = spineX,
                            startY = spineY,
                            direction = -1,
                            length = upperLength,
                            fontSize = fontSize,
                            branchIndex = pairIndex * 2,
                            context = context,
                            state = state,
                        )
                    ) {
                        is GMResult.Ok -> pairMinimumTextX =
                            min(pairMinimumTextX, drawn.value)
                        is GMResult.Err -> return drawn
                    }
                }
                val lowerCause = causes.getOrNull(pairIndex * 2 + 1)
                if (lowerCause != null) {
                    when (
                        val drawn = drawBranch(
                            node = lowerCause,
                            startX = spineX,
                            startY = spineY,
                            direction = 1,
                            length = lowerLength,
                            fontSize = fontSize,
                            branchIndex = pairIndex * 2 + 1,
                            context = context,
                            state = state,
                        )
                    ) {
                        is GMResult.Ok -> pairMinimumTextX =
                            min(pairMinimumTextX, drawn.value)
                        is GMResult.Err -> return drawn
                    }
                }
                if (pairMinimumTextX.isFinite()) {
                    spineX = pairMinimumTextX
                }
            }
        }
        state.elements.add(
            index = 0,
            element = line(
                id = "ishikawa-spine",
                startX = spineX,
                startY = spineY,
                endX = 0f,
                endY = spineY,
                strokeWidth = 2f,
                arrowStart = false,
                context = context,
            ),
        )
        return GMResult.Ok(normalize(state.elements, db, context, padding))
    }

    // Mermaid.js 12.0.0: ishikawaRenderer.ts -> sideStats.
    private fun sideStats(nodes: List<IshikawaNode>): SideStats =
        nodes.fold(SideStats()) { stats, node ->
            val descendants = countDescendants(node)
            SideStats(
                total = stats.total + descendants,
                maximum = max(stats.maximum, descendants),
            )
        }

    private fun countDescendants(node: IshikawaNode): Int =
        node.children.sumOf { child -> 1 + countDescendants(child) }

    // Mermaid.js 12.0.0: ishikawaRenderer.ts -> drawHead.
    private fun drawHead(
        label: String,
        spineY: Float,
        fontSize: Float,
        context: MermaidRenderContext,
        state: DrawState,
    ): GMResult<Unit, MermaidError> {
        val maximumCharacters = max(
            6,
            (HEAD_WRAP_WIDTH / (fontSize * 0.6f)).toInt(),
        )
        val wrapped = wrapText(label, maximumCharacters)
        val measured = when (
            val result = measureText(
                source = wrapped,
                fontSize = HEAD_FONT_SIZE,
                lineSpacing = fontSize * TEXT_LINE_HEIGHT,
                weight = SceneTextWeight.Bold,
                context = context,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val width = max(60f, measured.width + 6f)
        val height = max(40f, measured.height * 2f + 40f)
        val top = ScenePoint(0f, spineY - height / 2f)
        val bottom = ScenePoint(0f, spineY + height / 2f)
        val control = ScenePoint(width * 2.4f, spineY)
        val end = top
        state.elements += ScenePath(
            id = "ishikawa-head",
            points = listOf(
                top,
                bottom,
                ScenePoint(width * 1.2f, spineY),
                top,
            ),
            commands = listOf(
                ScenePathCommand.MoveTo(top),
                ScenePathCommand.LineTo(bottom),
                ScenePathCommand.QuadraticTo(control = control, end = end),
            ),
            color = context.theme.edge,
            strokeWidth = 2f,
            strokePattern = SceneStrokePattern.Solid,
            curve = CLASSIC_LOOK,
            look = CLASSIC_LOOK,
            animated = false,
            fillColor = context.theme.nodeFill,
            closed = true,
            zIndex = 10,
        )
        val centerX = width / 2f + 3f
        state.elements += textElement(
            measured = measured,
            anchorX = centerX,
            anchorY = spineY,
            horizontalAnchor = HorizontalAnchor.Middle,
            verticalAnchor = VerticalAnchor.Middle,
            fontSize = HEAD_FONT_SIZE,
            lineHeight = fontSize * TEXT_LINE_HEIGHT / HEAD_FONT_SIZE,
            weight = SceneTextWeight.Bold,
            context = context,
        )
        return GMResult.Ok(Unit)
    }

    // Mermaid.js 12.0.0: ishikawaRenderer.ts -> drawBranch.
    private fun drawBranch(
        node: IshikawaNode,
        startX: Float,
        startY: Float,
        direction: Int,
        length: Float,
        fontSize: Float,
        branchIndex: Int,
        context: MermaidRenderContext,
        state: DrawState,
    ): GMResult<Float, MermaidError> {
        val lineLength = length * if (node.children.isNotEmpty()) 1f else 0.2f
        val deltaX = -COS_ANGLE * lineLength
        val deltaY = SIN_ANGLE * lineLength * direction
        val endX = startX + deltaX
        val endY = startY + deltaY
        state.elements += line(
            id = "ishikawa-branch-$branchIndex",
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            strokeWidth = 2f,
            arrowStart = true,
            context = context,
        )
        val causeText = when (
            val result = measureText(
                source = node.text,
                fontSize = fontSize,
                weight = SceneTextWeight.Normal,
                context = context,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val causeElement = textElement(
            measured = causeText,
            anchorX = endX,
            anchorY = endY + 11f * direction,
            horizontalAnchor = HorizontalAnchor.Middle,
            verticalAnchor = VerticalAnchor.Middle,
            fontSize = fontSize,
            weight = SceneTextWeight.Normal,
            context = context,
        )
        state.elements += SceneShape(
            id = "ishikawa-label-box-$branchIndex",
            bounds = causeElement.bounds.inflate(horizontal = 20f, vertical = 2f),
            kind = SceneShapeKind.Rectangle,
            fill = context.theme.nodeFill,
            stroke = context.theme.edge,
            strokeWidth = 2f,
            cornerRadius = 0f,
            zIndex = 10,
        )
        state.elements += causeElement
        var minimumTextX = causeElement.bounds.left
        if (node.children.isEmpty()) {
            return GMResult.Ok(minimumTextX)
        }

        val flattened = flattenTree(node.children, direction)
        val entryCount = flattened.entries.size
        val verticalPositions = MutableList(entryCount) { 0f }
        flattened.yOrder.forEachIndexed { slot, entryIndex ->
            verticalPositions[entryIndex] =
                startY + deltaY * ((slot + 1f) / (entryCount + 1f))
        }
        val bones = mutableMapOf(
            -1 to BoneInfo(
                x0 = startX,
                y0 = startY,
                x1 = endX,
                y1 = endY,
                childCount = node.children.size,
            ),
        )
        val diagonalX = -COS_ANGLE
        val diagonalY = SIN_ANGLE * direction
        flattened.entries.forEachIndexed { index, entry ->
            val y = verticalPositions[index]
            val parent = bones[entry.parentIndex]
                ?: return GMResult.Err(
                    MermaidError.Layout(
                        "Ishikawa child '${entry.text}' has no parent bone",
                    ),
                )
            val x0: Float
            val y0: Float
            val x1: Float
            val verticalAnchor: VerticalAnchor
            if (entry.depth % 2 == 0) {
                val parentDeltaY = parent.y1 - parent.y0
                x0 = lerp(
                    parent.x0,
                    parent.x1,
                    if (parentDeltaY != 0f) (y - parent.y0) / parentDeltaY else 0.5f,
                )
                y0 = y
                x1 = x0 - if (entry.childCount > 0) {
                    BONE_BASE + entry.childCount * BONE_PER_CHILD
                } else {
                    BONE_STUB
                }
                verticalAnchor = VerticalAnchor.Middle
            } else {
                val childIndex = parent.childrenDrawn
                parent.childrenDrawn += 1
                x0 = lerp(
                    parent.x0,
                    parent.x1,
                    (parent.childCount - childIndex).toFloat() / (parent.childCount + 1f),
                )
                y0 = parent.y0
                x1 = x0 + diagonalX * ((y - y0) / diagonalY)
                verticalAnchor = if (direction < 0) {
                    VerticalAnchor.Baseline
                } else {
                    VerticalAnchor.Hanging
                }
            }
            val subBranchIndex = state.subBranchIndex
            state.subBranchIndex += 1
            state.elements += line(
                id = "ishikawa-sub-branch-$subBranchIndex",
                startX = x0,
                startY = y0,
                endX = x1,
                endY = y,
                strokeWidth = 1f,
                arrowStart = true,
                context = context,
            )
            val measured = when (
                val result = measureText(
                    source = entry.text,
                    fontSize = fontSize,
                    weight = SceneTextWeight.Normal,
                    context = context,
                )
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val childText = textElement(
                measured = measured,
                anchorX = x1,
                anchorY = y,
                horizontalAnchor = HorizontalAnchor.End,
                verticalAnchor = verticalAnchor,
                fontSize = fontSize,
                weight = SceneTextWeight.Normal,
                context = context,
            )
            state.elements += childText
            minimumTextX = min(minimumTextX, childText.bounds.left)
            if (entry.childCount > 0) {
                bones[index] = BoneInfo(
                    x0 = x0,
                    y0 = y0,
                    x1 = x1,
                    y1 = y,
                    childCount = entry.childCount,
                )
            }
        }
        return GMResult.Ok(minimumTextX)
    }

    // Mermaid.js 12.0.0: ishikawaRenderer.ts -> flattenTree.
    private fun flattenTree(
        children: List<IshikawaNode>,
        direction: Int,
    ): FlattenedTree {
        val entries = mutableListOf<LabelEntry>()
        val yOrder = mutableListOf<Int>()

        fun walk(
            nodes: List<IshikawaNode>,
            parentIndex: Int,
            depth: Int,
        ) {
            val ordered = if (direction == -1) nodes.reversed() else nodes
            ordered.forEach { child ->
                val index = entries.size
                entries += LabelEntry(
                    text = wrapText(child.text, CHILD_WRAP_CHARACTERS),
                    depth = depth,
                    parentIndex = parentIndex,
                    childCount = child.children.size,
                )
                if (depth % 2 == 0) {
                    yOrder += index
                    if (child.children.isNotEmpty()) {
                        walk(child.children, index, depth + 1)
                    }
                } else {
                    if (child.children.isNotEmpty()) {
                        walk(child.children, index, depth + 1)
                    }
                    yOrder += index
                }
            }
        }
        walk(children, parentIndex = -1, depth = 2)
        return FlattenedTree(entries = entries, yOrder = yOrder)
    }

    private fun measureText(
        source: String,
        fontSize: Float,
        lineSpacing: Float = fontSize * TEXT_LINE_HEIGHT,
        weight: SceneTextWeight,
        context: MermaidRenderContext,
    ): GMResult<MeasuredText, MermaidError> {
        val lines = splitLines(source)
        val lineMetrics = try {
            lines.map { line ->
                context.textMetrics.measure(
                    TextMetricsRequest(
                        text = line,
                        fontSize = fontSize,
                        maxWidth = MAXIMUM_TEXT_WIDTH,
                        lineHeight = TEXT_LINE_HEIGHT,
                        fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
                        weight = weight,
                    ),
                )
            }
        } catch (failure: Exception) {
            return GMResult.Err(
                MermaidError.Unexpected.from(
                    failure,
                    "Text measurement failed for Ishikawa label '$source'",
                ),
            )
        }
        val width = lineMetrics.maxOfOrNull { metrics -> metrics.width } ?: 0f
        val lineBoxHeight = lineMetrics.maxOfOrNull { metrics -> metrics.height } ?: fontSize
        val height = lineBoxHeight + (lines.size - 1).coerceAtLeast(0) * lineSpacing
        if (
            !width.isFinite() ||
            !height.isFinite() ||
            width < 0f ||
            height < 0f
        ) {
            return GMResult.Err(
                MermaidError.Layout("Invalid text metrics for Ishikawa label '$source'"),
            )
        }
        return GMResult.Ok(
            MeasuredText(
                text = lines.joinToString("\n"),
                width = width,
                height = height,
            ),
        )
    }

    private fun textElement(
        measured: MeasuredText,
        anchorX: Float,
        anchorY: Float,
        horizontalAnchor: HorizontalAnchor,
        verticalAnchor: VerticalAnchor,
        fontSize: Float,
        lineHeight: Float = TEXT_LINE_HEIGHT,
        weight: SceneTextWeight,
        context: MermaidRenderContext,
    ): SceneText {
        val left = when (horizontalAnchor) {
            HorizontalAnchor.Start -> anchorX
            HorizontalAnchor.Middle -> anchorX - measured.width / 2f
            HorizontalAnchor.End -> anchorX - measured.width
        }
        val top = when (verticalAnchor) {
            VerticalAnchor.Middle -> anchorY - measured.height / 2f
            VerticalAnchor.Baseline -> anchorY - measured.height * BASELINE_ASCENT_RATIO
            VerticalAnchor.Hanging -> anchorY
        }
        return SceneText(
            text = measured.text,
            bounds = SceneRect(
                left = left,
                top = top,
                right = left + measured.width,
                bottom = top + measured.height,
            ),
            color = context.theme.textColor,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = weight,
            horizontalAlignment = SceneTextAlignment.Center,
            softWrap = false,
            zIndex = 20,
        )
    }

    private fun line(
        id: String,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        strokeWidth: Float,
        arrowStart: Boolean,
        context: MermaidRenderContext,
    ): ScenePath {
        val start = ScenePoint(startX, startY)
        val end = ScenePoint(endX, endY)
        return ScenePath(
            id = id,
            points = listOf(start, end),
            commands = listOf(
                ScenePathCommand.MoveTo(start),
                ScenePathCommand.LineTo(end),
            ),
            color = context.theme.edge,
            strokeWidth = strokeWidth,
            strokePattern = SceneStrokePattern.Solid,
            arrowStart = if (arrowStart) SceneArrowHead.Triangle else SceneArrowHead.None,
            curve = "linear",
            look = CLASSIC_LOOK,
            animated = false,
            zIndex = 5,
        )
    }

    // Mermaid.js 12.0.0: ishikawaRenderer.ts -> applyPaddedViewBox.
    private fun normalize(
        elements: List<SceneElement>,
        db: IshikawaDb,
        context: MermaidRenderContext,
        padding: Float,
    ): MermaidScene {
        val contentBounds = elements
            .mapNotNull(::elementBounds)
            .reduceOrNull(SceneRect::union)
            ?: SceneRect(0f, 0f, 1f, 1f)
        val width = (contentBounds.width + padding * 2f).coerceAtLeast(1f)
        val height = (contentBounds.height + padding * 2f).coerceAtLeast(1f)
        val deltaX = padding - contentBounds.left
        val deltaY = padding - contentBounds.top
        return MermaidScene(
            width = width,
            height = height,
            background = context.theme.background,
            elements = elements.map { element -> element.translate(deltaX, deltaY) },
            title = db.diagramTitle,
            viewportSizing = if (db.config.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
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
                bounds.union(SceneRect(point.x, point.y, point.x, point.y))
            }
        }
    }

    private fun SceneElement.translate(
        deltaX: Float,
        deltaY: Float,
    ): SceneElement = when (this) {
        is SceneAsset -> copy(bounds = bounds.translate(deltaX, deltaY))
        is SceneShape -> copy(bounds = bounds.translate(deltaX, deltaY))
        is SceneText -> copy(
            bounds = bounds.translate(deltaX, deltaY),
            rotationPivot = rotationPivot?.translate(deltaX, deltaY),
        )
        is ScenePath -> copy(
            points = points.map { point -> point.translate(deltaX, deltaY) },
            commands = commands.map { command -> command.translate(deltaX, deltaY) },
        )
    }

    private fun ScenePathCommand.translate(
        deltaX: Float,
        deltaY: Float,
    ): ScenePathCommand = when (this) {
        is ScenePathCommand.MoveTo -> copy(point = point.translate(deltaX, deltaY))
        is ScenePathCommand.LineTo -> copy(point = point.translate(deltaX, deltaY))
        is ScenePathCommand.QuadraticTo -> copy(
            control = control.translate(deltaX, deltaY),
            end = end.translate(deltaX, deltaY),
        )
        is ScenePathCommand.CubicTo -> copy(
            control1 = control1.translate(deltaX, deltaY),
            control2 = control2.translate(deltaX, deltaY),
            end = end.translate(deltaX, deltaY),
        )
        is ScenePathCommand.ArcTo -> copy(end = end.translate(deltaX, deltaY))
    }

    private fun ScenePoint.translate(
        deltaX: Float,
        deltaY: Float,
    ): ScenePoint = ScenePoint(x + deltaX, y + deltaY)

    private fun splitLines(text: String): List<String> =
        text.replace(HTML_BREAK, "\n").split('\n')

    private fun wrapText(
        text: String,
        maximumCharacters: Int,
    ): String {
        if (text.length <= maximumCharacters) {
            return text
        }
        val lines = mutableListOf<String>()
        text.split(WHITESPACE).filter(String::isNotEmpty).forEach { word ->
            val lastIndex = lines.lastIndex
            if (
                lastIndex >= 0 &&
                lines[lastIndex].length + 1 + word.length <= maximumCharacters
            ) {
                lines[lastIndex] = "${lines[lastIndex]} $word"
            } else {
                lines += word
            }
        }
        return lines.joinToString("\n")
    }

    private fun lerp(
        start: Float,
        end: Float,
        progress: Float,
    ): Float = start + (end - start) * progress

    private fun configurationError(detail: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid Ishikawa $detail"))

    private data class SideStats(
        val total: Int = 0,
        val maximum: Int = 0,
    )

    private data class LabelEntry(
        val text: String,
        val depth: Int,
        val parentIndex: Int,
        val childCount: Int,
    )

    private data class FlattenedTree(
        val entries: List<LabelEntry>,
        val yOrder: List<Int>,
    )

    private data class BoneInfo(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val childCount: Int,
        var childrenDrawn: Int = 0,
    )

    private data class MeasuredText(
        val text: String,
        val width: Float,
        val height: Float,
    )

    private class DrawState {
        val elements = mutableListOf<SceneElement>()
        var subBranchIndex: Int = 0
    }

    private enum class HorizontalAnchor {
        Start,
        Middle,
        End,
    }

    private enum class VerticalAnchor {
        Middle,
        Baseline,
        Hanging,
    }

    private companion object {
        const val HEAD_FONT_SIZE = 14f
        const val HEAD_WRAP_WIDTH = 110f
        const val SPINE_BASE_LENGTH = 250f
        const val BONE_STUB = 30f
        const val BONE_BASE = 60f
        const val BONE_PER_CHILD = 5f
        const val CHILD_WRAP_CHARACTERS = 15
        const val TEXT_LINE_HEIGHT = 1.05f
        const val BASELINE_ASCENT_RATIO = 0.8f
        const val MAXIMUM_TEXT_WIDTH = 100_000f
        const val CLASSIC_LOOK = "classic"
        const val ANGLE = 82f * PI.toFloat() / 180f
        val COS_ANGLE = cos(ANGLE)
        val SIN_ANGLE = sin(ANGLE)
        val HTML_BREAK = Regex("<br\\s*/?>")
        val WHITESPACE = Regex("\\s+")
    }
}
