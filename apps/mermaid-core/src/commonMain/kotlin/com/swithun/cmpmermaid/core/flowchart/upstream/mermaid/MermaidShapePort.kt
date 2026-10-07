package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneShapeGeometry
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.flowchart.FlowDirection
import com.swithun.cmpmermaid.core.flowchart.FlowNode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry port of Mermaid 12.0.0 rendering-elements/shapes.
 *
 * The returned paths are centered around the node origin. Compose only paints
 * these primitives; it does not make shape sizing or geometry decisions.
 */
internal object MermaidShapePort {
    /**
     * Mermaid 12.0.0:
     * rendering-elements/shapes/defaultMindmapNode.ts.
     */
    fun mindmapDefault(
        measuredLabel: SceneSize,
        padding: Float,
        look: String,
        bottomStroke: SceneColor,
    ): MermaidShapeLayout {
        val width = measuredLabel.width + padding * 4f
        val height = measuredLabel.height + padding
        val radius = 5f
        val outline = if (look == NEO) {
            val top = -height / 2f
            val bottom = height / 2f
            listOf(ScenePoint(-width / 2f, bottom)) +
                arcPoints(
                    centerX = -width / 2f + radius,
                    centerY = top + radius,
                    radiusX = radius,
                    radiusY = radius,
                    startAngle = 180f,
                    endAngle = 270f,
                    count = 8,
                ).drop(1) +
                listOf(ScenePoint(width / 2f - radius, top)) +
                arcPoints(
                    centerX = width / 2f - radius,
                    centerY = top + radius,
                    radiusX = radius,
                    radiusY = radius,
                    startAngle = 270f,
                    endAngle = 360f,
                    count = 8,
                ).drop(1) +
                listOf(ScenePoint(width / 2f, bottom))
        } else {
            roundedRectanglePoints(width, height, radius)
        }
        return centeredShape(
            paths = listOf(
                closedPath(outline),
                openStroke(
                    -width / 2f to height / 2f,
                    width / 2f to height / 2f,
                    strokeWidth = 3f,
                    strokeColor = bottomStroke,
                ),
            ),
            outline = outline,
        )
    }

    /**
     * Mermaid 12.0.0:
     * rendering-elements/shapes/mindmapCircle.ts -> circle.ts.
     */
    fun mindmapCircle(
        measuredLabel: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val labelRadius = hypot(measuredLabel.width, measuredLabel.height) / 2f
        val radius = if (look == NEO) labelRadius + 32f else labelRadius + padding
        val outline = ellipsePoints(0f, 0f, radius, radius)
        return centeredShape(listOf(closedPath(outline)), outline)
    }

    fun mindmapShape(
        kind: SceneShapeKind,
        isDefaultNode: Boolean,
        measuredLabel: SceneSize,
        padding: Float,
        look: String,
        bottomStroke: SceneColor,
    ): GMResult<MermaidShapeLayout, MermaidError> = when {
        isDefaultNode -> GMResult.Ok(
            mindmapDefault(measuredLabel, padding, look, bottomStroke),
        )
        kind == SceneShapeKind.Rectangle -> GMResult.Ok(
            rectangle(measuredLabel, padding = 10f, look = look),
        )
        kind == SceneShapeKind.RoundedRectangle -> GMResult.Ok(
            roundedRectangle(measuredLabel, padding = 15f),
        )
        kind == SceneShapeKind.Circle -> GMResult.Ok(
            mindmapCircle(measuredLabel, padding = 10f, look = look),
        )
        kind == SceneShapeKind.Cloud -> GMResult.Ok(cloud(measuredLabel, padding))
        kind == SceneShapeKind.Bang -> GMResult.Ok(bang(measuredLabel, padding))
        kind == SceneShapeKind.Hexagon -> GMResult.Ok(hexagon(measuredLabel, padding, look))
        else -> GMResult.Err(
            MermaidError.UnsupportedFeature(
                feature = "Mindmap ${kind.name} shape",
                message = "Mermaid 12 Mindmap does not map this node type to a shape",
            ),
        )
    }

    fun layout(
        node: FlowNode,
        measuredLabel: SceneSize,
        direction: FlowDirection,
        defaultNodeStroke: SceneColor,
        defaultFlowContainerStroke: SceneColor = defaultNodeStroke,
    ): GMResult<MermaidShapeLayout, MermaidError> {
        val label = measuredLabel.copy(
            width = if (node.label.isNotEmpty()) {
                max(measuredLabel.width, node.minWidth ?: 0f)
            } else {
                measuredLabel.width
            },
        )
        return GMResult.Ok(
            when (node.shape) {
                SceneShapeKind.Rectangle -> rectangle(label, node.padding, node.look)
                SceneShapeKind.RoundedRectangle -> roundedRectangle(label, node.padding)
                SceneShapeKind.CollapsedGroup ->
                    collapsedGroup(
                        label = label,
                        padding = node.padding,
                        defaultNodeStroke = defaultNodeStroke,
                        flowContainerStroke = defaultFlowContainerStroke,
                        isFlowContainer = node.metadata["containerType"] == "flow",
                    )
                SceneShapeKind.Stadium -> stadium(label, node.padding, node.look)
                SceneShapeKind.Subroutine -> subroutine(label, node.padding, node.look)
                SceneShapeKind.Cylinder -> cylinder(label, node.padding, node.look, lined = false)
                SceneShapeKind.Datastore -> datastore(label, node.padding)
                SceneShapeKind.Folder -> folder(label, node.padding)
                SceneShapeKind.Bucket -> bucket(label, node.padding)
                SceneShapeKind.Console -> console(label, node.padding)
                SceneShapeKind.Browser -> browser(label, node.padding)
                SceneShapeKind.Person -> person(label, node.padding)
                SceneShapeKind.Circle -> circle(label, node.padding, node.look)
                SceneShapeKind.Bang -> bang(label, node.padding)
                SceneShapeKind.Cloud -> cloud(label, node.padding)
                SceneShapeKind.DoubleCircle -> doubleCircle(label, node.padding, node.look)
                SceneShapeKind.Diamond -> diamond(label, node.padding)
                SceneShapeKind.Hexagon -> hexagon(label, node.padding, node.look)
                SceneShapeKind.Parallelogram ->
                    lean(label, node.padding, node.look, LeanDirection.Right)
                SceneShapeKind.ParallelogramAlt ->
                    lean(label, node.padding, node.look, LeanDirection.Left)
                SceneShapeKind.Trapezoid ->
                    lean(label, node.padding, node.look, LeanDirection.TrapezoidBottom)
                SceneShapeKind.TrapezoidAlt -> invertedTrapezoid(label, node.padding, node.look)
                SceneShapeKind.Asymmetric -> asymmetric(label, node.padding, node.look)
                SceneShapeKind.Ellipse -> ellipse(label, node.padding)
                SceneShapeKind.TextBlock -> text(label)
                SceneShapeKind.NotchedRectangle -> card(label, node.padding, node.look)
                SceneShapeKind.LinedRectangle -> shadedProcess(label, node.padding, node.look)
                SceneShapeKind.SmallCircle -> stateStart()
                SceneShapeKind.FramedCircle -> stateEnd()
                SceneShapeKind.ForkJoin -> forkJoin(direction)
                SceneShapeKind.Hourglass -> hourglass()
                SceneShapeKind.BraceLeft -> curlyBrace(label, node.padding, node.look, BraceKind.Left)
                SceneShapeKind.BraceRight -> curlyBrace(label, node.padding, node.look, BraceKind.Right)
                SceneShapeKind.Braces -> curlyBrace(label, node.padding, node.look, BraceKind.Both)
                SceneShapeKind.Bolt -> lightningBolt()
                SceneShapeKind.Document -> waveDocument(label, node.padding, node.look, DocumentKind.Single)
                SceneShapeKind.Delay -> delay(label, node.padding, node.look)
                SceneShapeKind.DirectAccessStorage ->
                    tiltedCylinder(label, node.padding, node.look)
                SceneShapeKind.LinedCylinder -> cylinder(label, node.padding, node.look, lined = true)
                SceneShapeKind.CurvedTrapezoid ->
                    curvedTrapezoid(label, node.padding, node.look)
                SceneShapeKind.DividedRectangle ->
                    dividedRectangle(label, node.padding, node.look)
                SceneShapeKind.Triangle -> triangle(label, node.padding, node.look, flipped = false)
                SceneShapeKind.WindowPane -> windowPane(label, node.padding, node.look)
                SceneShapeKind.FilledCircle -> filledCircle()
                SceneShapeKind.LinedDocument ->
                    waveDocument(label, node.padding, node.look, DocumentKind.Lined)
                SceneShapeKind.NotchedPentagon ->
                    trapezoidalPentagon(label, node.padding, node.look)
                SceneShapeKind.FlippedTriangle ->
                    triangle(label, node.padding, node.look, flipped = true)
                SceneShapeKind.SlopedRectangle -> slopedRectangle(label, node.padding, node.look)
                SceneShapeKind.MultiDocument ->
                    waveDocument(label, node.padding, node.look, DocumentKind.Multi)
                SceneShapeKind.MultiProcess -> multiRectangle(label, node.padding, node.look)
                SceneShapeKind.PaperTape -> waveRectangle(label, node.padding, node.look)
                SceneShapeKind.BowTieRectangle -> bowTie(label, node.padding, node.look)
                SceneShapeKind.CrossedCircle -> crossedCircle()
                SceneShapeKind.TaggedDocument ->
                    waveDocument(label, node.padding, node.look, DocumentKind.Tagged)
                SceneShapeKind.TaggedRectangle -> taggedRectangle(label, node.padding, node.look)
                SceneShapeKind.Icon -> icon(node, label, IconForm.Plain)
                SceneShapeKind.IconCircle -> icon(node, label, IconForm.Circle)
                SceneShapeKind.IconSquare -> icon(node, label, IconForm.Square)
                SceneShapeKind.IconRounded -> icon(node, label, IconForm.Rounded)
                SceneShapeKind.Image -> image(node, label)
            },
        )
    }

    private fun rectangle(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val width = label.width + if (look == NEO) 32f else padding * 4f
        val height = label.height + if (look == NEO) 24f else padding * 2f
        return centeredShape(
            paths = listOf(closedPath(rectanglePoints(width, height))),
            outline = rectanglePoints(width, height),
        )
    }

    private fun roundedRectangle(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = label.width + padding * 2f
        val height = label.height + padding * 2f
        val outline = roundedRectanglePoints(width, height, radius = 5f)
        return centeredShape(listOf(closedPath(outline)), rectanglePoints(width, height))
    }

    private fun collapsedGroup(
        label: SceneSize,
        padding: Float,
        defaultNodeStroke: SceneColor,
        flowContainerStroke: SceneColor,
        isFlowContainer: Boolean,
    ): MermaidShapeLayout {
        val width = max(label.width + padding * 2f, 80f)
        val height = label.height + 8f + 20f + padding * 2f
        val outline = roundedRectanglePoints(width, height, radius = 8f)
        val separatorY = -height / 2f + padding + label.height + 8f
        val paths = buildList {
            add(closedPath(outline))
            add(
                openStroke(
                    -width / 2f + 8f to separatorY,
                    width / 2f - 8f to separatorY,
                    strokeWidth = 0.75f,
                    dashIntervals = listOf(3f, 3f),
                    // Mermaid.js 12.0.0: rendering-elements/shapes/collapsedGroup.ts
                    // -> getCollapsedStyle. Agentflow explicitly selects the flow style.
                    strokeColor = flowContainerStroke.takeIf { isFlowContainer },
                ),
            )
            for (index in -1..1) {
                add(
                    closedPath(
                        ellipsePoints(
                            centerX = index * 10f,
                            centerY = separatorY + 10f,
                            radiusX = 2.5f,
                            radiusY = 2.5f,
                        ),
                        fill = SceneShapePaint.Stroke,
                        stroke = if (isFlowContainer) {
                            SceneShapePaint.None
                        } else {
                            SceneShapePaint.Stroke
                        },
                        strokeWidth = 2f.takeUnless { isFlowContainer },
                        opacity = if (isFlowContainer) 0.5f else 0.6f,
                        strokeColor = defaultNodeStroke.takeUnless { isFlowContainer },
                    ),
                )
            }
        }
        return centeredShape(paths, rectanglePoints(width, height), ScenePoint(0f, -14f))
    }

    private fun stadium(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val paddingX = if (look == NEO) 40f else padding
        val paddingY = if (look == NEO) 24f else padding
        val height = label.height + paddingY
        val inscribedDiameter = height * cos(PI.toFloat() / (2f * 49f))
        val capWidthAtLabel = sqrt(max(0f, inscribedDiameter * inscribedDiameter - label.height * label.height))
        val width = maxOf(
            label.width + height / 4f + paddingX,
            1.5f * height + (height - inscribedDiameter),
            label.width + paddingX + height - capWidthAtLabel,
        )
        val radius = height / 2f
        val outline =
            listOf(ScenePoint(-width / 2f + radius, -height / 2f)) +
                listOf(ScenePoint(width / 2f - radius, -height / 2f)) +
                mermaidCirclePoints(
                    -width / 2f + radius,
                    0f,
                    radius,
                    50,
                    90f,
                    270f,
                ) +
                listOf(ScenePoint(width / 2f - radius, height / 2f)) +
                mermaidCirclePoints(
                    width / 2f - radius,
                    0f,
                    radius,
                    50,
                    270f,
                    450f,
                )
        return centeredShape(listOf(closedPath(outline)), outline)
    }

    private fun subroutine(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val width = label.width + 16f + if (look == NEO) 28f else padding
        val height = label.height + if (look == NEO) 12f else padding
        val outline = rectanglePoints(width, height)
        return centeredShape(
            paths = listOf(
                closedPath(outline),
                openStroke(-width / 2f + 8f to -height / 2f, -width / 2f + 8f to height / 2f),
                openStroke(width / 2f - 8f to -height / 2f, width / 2f - 8f to height / 2f),
            ),
            outline = outline,
        )
    }

    private fun cylinder(
        label: SceneSize,
        padding: Float,
        look: String,
        lined: Boolean,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 24f else padding
        val width = if (lined) {
            label.width + paddingX * 2f
        } else {
            label.width + paddingY
        }
        val radiusX = width / 2f
        val radiusY = radiusX / (2.5f + width / 50f)
        val bodyHeight = if (lined) {
            label.height + radiusY + paddingY * 2f
        } else {
            label.height + paddingX + radiusY
        }
        val totalHeight = bodyHeight + radiusY * 2f
        val topCenterY = -totalHeight / 2f + radiusY
        val bottomCenterY = totalHeight / 2f - radiusY
        val outline =
            arcPoints(0f, topCenterY, radiusX, radiusY, 180f, 360f, 50) +
                listOf(ScenePoint(radiusX, bottomCenterY)) +
                arcPoints(0f, bottomCenterY, radiusX, radiusY, 0f, 180f, 50) +
                listOf(ScenePoint(-radiusX, topCenterY))
        val paths = buildList {
            add(closedPath(outline))
            add(openStroke(*ellipsePoints(0f, topCenterY, radiusX, radiusY).toPairs()))
            if (lined) {
                val offsetY = topCenterY + bodyHeight * 0.1f
                add(openStroke(*arcPoints(0f, offsetY, radiusX, radiusY, 180f, 0f, 50).toPairs()))
            }
        }
        val labelOffset = if (lined) ScenePoint(0f, radiusY) else ScenePoint(0f, padding / 1.5f)
        return centeredShape(paths, outline, labelOffset)
    }

    private fun datastore(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = label.width + padding * 4f
        val height = label.height + padding * 2f
        val outline = rectanglePoints(width, height)
        return centeredShape(
            listOf(
                closedPath(
                    points = outline,
                    dashIntervals = listOf(width, height),
                ),
            ),
            outline,
        )
    }

    private fun folder(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = max(label.width + padding * 2f, 90f)
        val contentHeight = label.height + padding * 2f
        val tabHeight = (contentHeight * 0.16f).coerceIn(8f, 14f)
        val totalHeight = contentHeight + tabHeight
        val bodyHeight = totalHeight - tabHeight
        val tabWidth = max(width * 0.38f, 28f)
        val top = -totalHeight / 2f
        val points = points(
            -width / 2f to top,
            -width / 2f + tabWidth to top,
            -width / 2f + tabWidth to top + tabHeight,
            width / 2f to top + tabHeight,
            width / 2f to totalHeight / 2f,
            -width / 2f to totalHeight / 2f,
        )
        return centeredShape(
            listOf(closedPath(points)),
            points,
            ScenePoint(0f, tabHeight / 2f),
        )
    }

    private fun bucket(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = max(label.width + padding * 2f, 80f)
        val rimRadiusY = (width * 0.08f).coerceIn(5f, 12f)
        val totalHeight = label.height + padding * 2f + rimRadiusY
        val topY = -totalHeight / 2f + rimRadiusY
        val bottomY = totalHeight / 2f
        val bottomWidth = width * 0.72f
        val topUpper = arcPoints(0f, topY, width / 2f, rimRadiusY, 180f, 360f, 25)
        val bottomLower = arcPoints(0f, bottomY, bottomWidth / 2f, rimRadiusY, 0f, 180f, 25)
        val outline = topUpper + bottomLower
        val body = listOf(ScenePoint(-width / 2f, topY), ScenePoint(-bottomWidth / 2f, bottomY)) +
            bottomLower.drop(1) +
            listOf(ScenePoint(width / 2f, topY)) +
            arcPoints(0f, topY, width / 2f, rimRadiusY, 0f, -180f, 25).drop(1)
        return centeredShape(
            paths = listOf(
                closedPath(body),
                openStroke(*ellipsePoints(0f, topY, width / 2f, rimRadiusY).toPairs()),
            ),
            outline = outline,
            labelCenter = ScenePoint(0f, rimRadiusY / 2f),
        )
    }

    private fun console(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = max(label.width + padding * 2f, 90f)
        val glyphBand = 20f
        val height = label.height + padding * 2f + glyphBand
        val outline = roundedRectanglePoints(width, height, 12f)
        val top = -height / 2f
        return centeredShape(
            paths = listOf(
                closedPath(outline),
                openStroke(-width / 2f + 12f to top + 11f, -width / 2f + 17f to top + 16f),
                openStroke(-width / 2f + 17f to top + 16f, -width / 2f + 12f to top + 21f),
                openStroke(-width / 2f + 20f to top + 21f, -width / 2f + 27f to top + 21f),
            ),
            outline = rectanglePoints(width, height),
            labelCenter = ScenePoint(0f, glyphBand / 2f),
        )
    }

    private fun browser(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = max(label.width + padding * 2f, 90f)
        val barHeight = 18f
        val height = label.height + padding * 2f + barHeight
        val top = -height / 2f
        val outline = roundedRectanglePoints(width, height, 12f)
        val paths = buildList {
            add(closedPath(outline))
            add(openStroke(-width / 2f to top + barHeight, width / 2f to top + barHeight))
            for (index in 0..2) {
                add(
                    closedPath(
                        ellipsePoints(
                            -width / 2f + 12f + index * 9f,
                            top + barHeight / 2f,
                            2.5f,
                            2.5f,
                        ),
                        fill = SceneShapePaint.Stroke,
                        stroke = SceneShapePaint.None,
                    ),
                )
            }
            add(
                closedPath(
                    roundedRectanglePoints(
                        width = max(width - 56f, 10f),
                        height = barHeight - 8f,
                        radius = 3f,
                        centerX = 16f,
                        centerY = top + barHeight / 2f,
                    ),
                    fill = SceneShapePaint.None,
                    opacity = 0.6f,
                ),
            )
        }
        return centeredShape(
            paths,
            rectanglePoints(width, height),
            ScenePoint(0f, barHeight / 2f),
        )
    }

    private fun person(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = max(label.width + padding * 2f, 100f)
        val headRadius = (width * 0.23f).coerceIn(16f, 56f)
        val overlap = headRadius * 0.27f
        val bodyHeight = label.height + padding * 2f
        val bodyRadius = min(width * 0.177f, bodyHeight * 0.45f)
        val totalHeight = bodyHeight + headRadius * 2f - overlap
        val top = -totalHeight / 2f
        val bodyTop = top + headRadius * 2f - overlap
        val bodyCenterY = bodyTop + bodyHeight / 2f
        val headCenterY = top + headRadius
        val body = roundedRectanglePoints(
            width = width,
            height = bodyHeight,
            radius = bodyRadius,
            centerY = bodyCenterY,
        )
        val head = ellipsePoints(0f, headCenterY, headRadius, headRadius)
        val phi = asin(((bodyTop - headCenterY) / headRadius).coerceIn(-1f, 1f)) * 180f / PI.toFloat()
        val outline =
            arcPoints(0f, headCenterY, headRadius, headRadius, 180f + phi, 360f - phi, 24) +
                arcPoints(-width / 2f + bodyRadius, bodyTop + bodyRadius, bodyRadius, bodyRadius, 180f, 270f, 12) +
                arcPoints(-width / 2f + bodyRadius, totalHeight / 2f - bodyRadius, bodyRadius, bodyRadius, 90f, 180f, 12) +
                arcPoints(width / 2f - bodyRadius, totalHeight / 2f - bodyRadius, bodyRadius, bodyRadius, 0f, 90f, 12) +
                arcPoints(width / 2f - bodyRadius, bodyTop + bodyRadius, bodyRadius, bodyRadius, -90f, 0f, 12)
        return centeredShape(
            paths = listOf(closedPath(body), closedPath(head)),
            outline = outline,
            labelCenter = ScenePoint(0f, bodyCenterY),
        )
    }

    private fun circle(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val labelRadius = hypot(label.width, label.height) / 2f
        val radius = if (look == NEO) labelRadius + 32f else labelRadius + padding / 2f
        val outline = ellipsePoints(0f, 0f, radius, radius)
        return centeredShape(listOf(closedPath(outline)), outline)
    }

    private fun bang(label: SceneSize, padding: Float): MermaidShapeLayout {
        val halfPadding = padding / 2f
        val nominalWidth = max(label.width + 10f * halfPadding, label.width + 20f)
        val nominalHeight = max(label.height + 8f * halfPadding, label.height + 20f)
        val radius = nominalWidth * 0.15f
        val builder = SvgPointBuilder(ScenePoint(0f, 0f))
        builder.arcBy(radius, radius, 1f, false, false, nominalWidth * 0.25f, -nominalHeight * 0.1f)
        builder.arcBy(radius, radius, 1f, false, false, nominalWidth * 0.25f, 0f)
        builder.arcBy(radius, radius, 1f, false, false, nominalWidth * 0.25f, 0f)
        builder.arcBy(radius, radius, 1f, false, false, nominalWidth * 0.25f, nominalHeight * 0.1f)
        builder.arcBy(radius, radius, 1f, false, false, nominalWidth * 0.15f, nominalHeight * 0.33f)
        builder.arcBy(radius * 0.8f, radius * 0.8f, 1f, false, false, 0f, nominalHeight * 0.34f)
        builder.arcBy(radius, radius, 1f, false, false, -nominalWidth * 0.15f, nominalHeight * 0.33f)
        builder.arcBy(radius, radius, 1f, false, false, -nominalWidth * 0.25f, nominalHeight * 0.15f)
        builder.arcBy(radius, radius, 1f, false, false, -nominalWidth * 0.25f, 0f)
        builder.arcBy(radius, radius, 1f, false, false, -nominalWidth * 0.25f, 0f)
        builder.arcBy(radius, radius, 1f, false, false, -nominalWidth * 0.25f, -nominalHeight * 0.15f)
        builder.arcBy(radius, radius, 1f, false, false, -nominalWidth * 0.1f, -nominalHeight * 0.33f)
        builder.arcBy(radius * 0.8f, radius * 0.8f, 1f, false, false, 0f, -nominalHeight * 0.34f)
        builder.arcBy(radius, radius, 1f, false, false, nominalWidth * 0.1f, -nominalHeight * 0.33f)
        val shifted = builder.points.map {
            ScenePoint(it.x - nominalWidth / 2f, it.y - nominalHeight / 2f)
        }
        return centeredShape(
            listOf(closedPath(shifted)),
            boundingRectangle(shifted),
        )
    }

    private fun cloud(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = label.width + padding
        val height = label.height + padding
        val r1 = 0.15f * width
        val r2 = 0.25f * width
        val r3 = 0.35f * width
        val r4 = 0.2f * width
        val builder = SvgPointBuilder(ScenePoint(0f, 0f))
        builder.arcBy(r1, r1, 0f, false, true, width * 0.25f, -width * 0.1f)
        builder.arcBy(r3, r3, 1f, false, true, width * 0.4f, -width * 0.1f)
        builder.arcBy(r2, r2, 1f, false, true, width * 0.35f, width * 0.2f)
        builder.arcBy(r1, r1, 1f, false, true, width * 0.15f, height * 0.35f)
        builder.arcBy(r4, r4, 1f, false, true, -width * 0.15f, height * 0.65f)
        builder.arcBy(r2, r1, 1f, false, true, -width * 0.25f, width * 0.15f)
        builder.arcBy(r3, r3, 1f, false, true, -width * 0.5f, 0f)
        builder.arcBy(r1, r1, 1f, false, true, -width * 0.25f, -width * 0.15f)
        builder.arcBy(r1, r1, 1f, false, true, -width * 0.1f, -height * 0.35f)
        builder.arcBy(r4, r4, 1f, false, true, width * 0.1f, -height * 0.65f)
        val shifted = builder.points.map {
            ScenePoint(it.x - width / 2f, it.y - height / 2f)
        }
        return centeredShape(
            listOf(closedPath(shifted)),
            boundingRectangle(shifted),
        )
    }

    private fun doubleCircle(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val labelRadius = hypot(label.width, label.height) / 2f
        val innerRadius = labelRadius + if (look == NEO) 16f else padding
        val outerRadius = innerRadius + if (look == NEO) 12f else 5f
        val outer = ellipsePoints(0f, 0f, outerRadius, outerRadius)
        val inner = ellipsePoints(0f, 0f, innerRadius, innerRadius)
        return centeredShape(
            paths = listOf(closedPath(outer), closedPath(inner, fill = SceneShapePaint.None)),
            outline = outer,
        )
    }

    private fun diamond(label: SceneSize, padding: Float): MermaidShapeLayout {
        val side = label.width + label.height + padding * 2f
        val points = points(0f to -side / 2f, side / 2f to 0f, 0f to side / 2f, -side / 2f to 0f)
        return centeredShape(listOf(closedPath(points)), points)
    }

    private fun hexagon(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val height = label.height + if (look == NEO) 70f else padding
        val inset = height / if (look == NEO) 3.5f else 4f
        val width = label.width + inset * 2f + if (look == NEO) 32f else padding
        val points = points(
            -width / 2f + inset to -height / 2f,
            width / 2f - inset to -height / 2f,
            width / 2f to 0f,
            width / 2f - inset to height / 2f,
            -width / 2f + inset to height / 2f,
            -width / 2f to 0f,
        )
        return centeredShape(listOf(closedPath(points)), points)
    }

    private fun lean(
        label: SceneSize,
        padding: Float,
        look: String,
        direction: LeanDirection,
    ): MermaidShapeLayout {
        val height = label.height + padding
        val width = label.width + if (look == NEO) padding * 2f else padding
        val halfSlant = height / 2f
        val points = when (direction) {
            LeanDirection.Right -> points(
                -width / 2f - halfSlant to height / 2f,
                width / 2f to height / 2f,
                width / 2f + halfSlant to -height / 2f,
                -width / 2f to -height / 2f,
            )
            LeanDirection.Left -> points(
                -width / 2f to height / 2f,
                width / 2f + halfSlant to height / 2f,
                width / 2f to -height / 2f,
                -width / 2f - halfSlant to -height / 2f,
            )
            LeanDirection.TrapezoidBottom -> points(
                -width / 2f - halfSlant to height / 2f,
                width / 2f + halfSlant to height / 2f,
                width / 2f to -height / 2f,
                -width / 2f to -height / 2f,
            )
        }
        return centeredShape(listOf(closedPath(points)), points)
    }

    private fun invertedTrapezoid(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val height = label.height + padding * 2f
        val width = label.width + if (look == NEO) padding * 4f else padding * 2f
        val halfSlant = height / 2f
        val points = points(
            -width / 2f to height / 2f,
            width / 2f to height / 2f,
            width / 2f + halfSlant to -height / 2f,
            -width / 2f - halfSlant to -height / 2f,
        )
        return centeredShape(listOf(closedPath(points)), points)
    }

    private fun asymmetric(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val paddingX = if (look == NEO) 21f else padding
        val paddingY = if (look == NEO) 12f else padding
        val labelWidth = label.width + if (look == NEO) paddingX * 2f else paddingX
        val height = label.height + if (look == NEO) paddingY * 2f else paddingY
        val width = labelWidth
        val x = -width / 2f
        val y = -height / 2f
        val notch = y / 2f
        val points = points(
            x + notch to y,
            x to 0f,
            x + notch to -y,
            -x to -y,
            -x to y,
        )
        return centeredShape(listOf(closedPath(points)), points)
    }

    private fun ellipse(label: SceneSize, padding: Float): MermaidShapeLayout {
        val width = label.width + padding * 2f
        val height = label.height + padding * 2f
        val outline = ellipsePoints(0f, 0f, width / 2f, height / 2f)
        return centeredShape(listOf(closedPath(outline)), outline)
    }

    private fun text(label: SceneSize): MermaidShapeLayout {
        val outline = rectanglePoints(max(label.width, 0.1f), max(label.height, 0.1f))
        return centeredShape(
            listOf(
                closedPath(
                    outline,
                    fill = SceneShapePaint.None,
                    stroke = SceneShapePaint.None,
                ),
            ),
            outline,
        )
    }

    private fun card(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val width = label.width + if (look == NEO) 56f else padding + 12f
        val height = label.height + if (look == NEO) 48f else padding
        val points = points(
            -width / 2f + 12f to -height / 2f,
            width / 2f to -height / 2f,
            width / 2f to height / 2f,
            -width / 2f to height / 2f,
            -width / 2f to -height / 2f + 12f,
        )
        return centeredShape(listOf(closedPath(points)), points)
    }

    private fun shadedProcess(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val totalWidth = label.width + paddingX * 2f + if (look == NEO) 8f else 16f
        val totalHeight = label.height + paddingY * 2f
        val outline = rectanglePoints(totalWidth, totalHeight)
        return centeredShape(
            paths = listOf(
                closedPath(outline),
                openStroke(-totalWidth / 2f + 8f to -totalHeight / 2f, -totalWidth / 2f + 8f to totalHeight / 2f),
            ),
            outline = rectanglePoints(totalWidth, totalHeight),
            labelCenter = ScenePoint(4f, 0f),
        )
    }

    private fun stateStart(): MermaidShapeLayout {
        val outline = ellipsePoints(0f, 0f, 7f, 7f)
        return centeredShape(
            listOf(closedPath(outline)),
            outline,
            showsLabel = false,
        )
    }

    private fun stateEnd(): MermaidShapeLayout {
        val outer = ellipsePoints(0f, 0f, 7f, 7f)
        val inner = ellipsePoints(0f, 0f, 2.5f, 2.5f)
        return centeredShape(
            listOf(
                closedPath(outer),
                closedPath(inner, fill = SceneShapePaint.Stroke),
            ),
            outer,
            showsLabel = false,
        )
    }

    private fun forkJoin(direction: FlowDirection): MermaidShapeLayout {
        val width = if (direction == FlowDirection.LeftToRight ||
            direction == FlowDirection.RightToLeft
        ) {
            10f
        } else {
            70f
        }
        val height = if (width == 10f) 70f else 10f
        val outline = rectanglePoints(width, height)
        return centeredShape(
            listOf(closedPath(outline)),
            outline,
            showsLabel = false,
        )
    }

    private fun hourglass(): MermaidShapeLayout {
        val points = points(-15f to -15f, 15f to -15f, -15f to 15f, 15f to 15f)
        return centeredShape(listOf(closedPath(points)), points, showsLabel = false)
    }

    private fun curlyBrace(
        label: SceneSize,
        padding: Float,
        look: String,
        kind: BraceKind,
    ): MermaidShapeLayout {
        val width = label.width + if (look == NEO) 36f else padding
        val height = label.height + if (look == NEO) 24f else padding
        val radius = max(5f, height * 0.1f)
        val boundsOutline = rectanglePoints(width + radius * 2f, height + radius * 2f)

        fun leftBrace(): List<ScenePoint> =
            mermaidCirclePoints(width / 2f, -height / 2f, radius, 30, -90f, 0f) +
                ScenePoint(-width / 2f - radius, radius) +
                mermaidCirclePoints(width / 2f + radius * 2f, -radius, radius, 20, -180f, -270f) +
                mermaidCirclePoints(width / 2f + radius * 2f, radius, radius, 20, -90f, -180f) +
                ScenePoint(-width / 2f - radius, -height / 2f) +
                mermaidCirclePoints(width / 2f, height / 2f, radius, 20, 0f, 90f)

        fun rightBrace(): List<ScenePoint> =
            circlePoints(width / 2f, -height / 2f, radius, 20, -90f, 0f) +
                ScenePoint(width / 2f + radius, -radius) +
                circlePoints(width / 2f + radius * 2f, -radius, radius, 20, -180f, -270f) +
                circlePoints(width / 2f + radius * 2f, radius, radius, 20, -90f, -180f) +
                ScenePoint(width / 2f + radius, height / 2f) +
                circlePoints(width / 2f, height / 2f, radius, 20, 0f, 90f)

        val paths = when (kind) {
            BraceKind.Left -> listOf(
                openPath(leftBrace().map { ScenePoint(it.x + radius, it.y) }),
                invisiblePath(boundsOutline),
            )
            BraceKind.Right -> listOf(
                openPath(rightBrace().map { ScenePoint(it.x - radius, it.y) }),
                invisiblePath(boundsOutline),
            )
            BraceKind.Both -> {
                val left = leftBrace().map { ScenePoint(it.x + radius - radius / 4f, it.y) }
                val right = rightBrace().map { ScenePoint(it.x + radius - radius / 4f, it.y) }
                listOf(openPath(left), openPath(right), invisiblePath(boundsOutline))
            }
        }
        val labelCenter = when (kind) {
            BraceKind.Left -> ScenePoint(radius - 18f, -4.5f)
            BraceKind.Right, BraceKind.Both -> ScenePoint(-10.5f, -4.5f)
        }
        return centeredShape(paths, boundsOutline, labelCenter)
    }

    private fun lightningBolt(): MermaidShapeLayout {
        val width = 35f
        val height = 35f
        val gap = 7f
        val points = points(
            width / 2f to -height,
            -width / 2f to gap / 2f,
            width / 2f - gap * 2f to gap / 2f,
            -width / 2f to height,
            width / 2f to -gap / 2f,
            -width / 2f + gap * 2f to -gap / 2f,
        )
        return centeredShape(listOf(closedPath(points)), points, showsLabel = false)
    }

    private fun waveDocument(
        label: SceneSize,
        padding: Float,
        look: String,
        kind: DocumentKind,
    ): MermaidShapeLayout = when (kind) {
        DocumentKind.Multi -> multiDocument(label, padding, look)
        else -> singleDocument(label, padding, look, kind)
    }

    private fun singleDocument(
        label: SceneSize,
        padding: Float,
        look: String,
        kind: DocumentKind,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val width = label.width + paddingX * 2f
        val height = label.height + paddingY * 2f
        val amplitude = if (kind == DocumentKind.Tagged) height / 8f else {
            if (look == NEO) height / 4f else height / 8f
        }
        val finalHeight = height + amplitude
        val sideFactor = if (kind == DocumentKind.Single) 0f else 0.1f
        val left = -width / 2f - width / 2f * sideFactor
        val right = width / 2f + width / 2f * sideFactor
        val raw = listOf(ScenePoint(left, finalHeight / 2f)) +
            sineWave(left, finalHeight / 2f, right, finalHeight / 2f, amplitude, 0.8f) +
            listOf(
                ScenePoint(right, -finalHeight / 2f),
                ScenePoint(left, -finalHeight / 2f),
            )
        val shifted = raw.map { ScenePoint(it.x, it.y - amplitude / 2f) }
        val paths = buildList {
            add(closedPath(shifted))
            if (kind == DocumentKind.Lined) {
                val lineX = -width / 2f
                add(
                    openStroke(
                        lineX to -finalHeight / 2f - amplitude / 2f,
                        lineX to finalHeight * 0.55f - amplitude / 2f,
                    ),
                )
            }
            if (kind == DocumentKind.Tagged) {
                val tagWidth = 0.2f * width
                val tagHeight = 0.2f * height
                val x = -width / 2f + width * 0.05f
                val y = -finalHeight / 2f - tagHeight * 0.4f
                val tag = listOf(
                    ScenePoint(x + width - tagWidth, (y + height) * 1.3f),
                    ScenePoint(x + width, y + height - tagHeight),
                    ScenePoint(x + width, (y + height) * 0.9f),
                ) + sineWave(
                    x + width,
                    (y + height) * 1.25f,
                    x + width - tagWidth,
                    (y + height) * 1.3f,
                    -height * 0.02f,
                    0.5f,
                )
                add(closedPath(tag.map { ScenePoint(it.x, it.y - amplitude / 2f) }))
            }
        }
        val labelOffset = when (kind) {
            DocumentKind.Lined -> ScenePoint(width * 0.025f, -amplitude)
            DocumentKind.Tagged -> ScenePoint(0f, -amplitude / 2f)
            else -> ScenePoint(0f, -amplitude)
        }
        return centeredShape(paths, shifted, labelOffset)
    }

    private fun multiDocument(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val width = label.width + paddingX * 2f
        val height = label.height + paddingY * 3f
        val amplitude = if (look == NEO) height / 4f else height / 8f
        val finalHeight = height + amplitude / 2f
        val offset = 10f
        val x = -width / 2f
        val y = -finalHeight / 2f
        val wave = sineWave(
            x - offset,
            y + finalHeight + offset,
            x + width - offset,
            y + finalHeight + offset,
            amplitude,
            0.8f,
        )
        val waveEnd = wave.last()
        val outer = points(
            x - offset to y + offset,
            x - offset to y + finalHeight + offset,
        ) + wave + points(
            x + width - offset to waveEnd.y - offset,
            x + width to waveEnd.y - offset,
            x + width to waveEnd.y - 2f * offset,
            x + width + offset to waveEnd.y - 2f * offset,
            x + width + offset to y - offset,
            x + offset to y - offset,
            x + offset to y,
            x to y,
            x to y + offset,
        )
        val inner = points(
            x to y + offset,
            x + width - offset to y + offset,
            x + width - offset to waveEnd.y - offset,
            x + width to waveEnd.y - offset,
            x + width to y,
            x to y,
        )
        val shiftY = -amplitude / 2f
        val shiftedOuter = outer.map { ScenePoint(it.x, it.y + shiftY) }
        val shiftedInner = inner.map { ScenePoint(it.x, it.y + shiftY) }
        return centeredShape(
            paths = listOf(closedPath(shiftedOuter), closedPath(shiftedInner)),
            outline = shiftedOuter,
            labelCenter = ScenePoint(-offset, offset - amplitude),
        )
    }

    private fun delay(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val height = max(10f, label.height) + paddingY * 2f
        val radius = height / 2f
        val clearance = radius - sqrt(max(0f, radius * radius - (label.height / 2f).let { it * it }))
        val width = max(15f, label.width) + clearance * 2f + paddingX * 2f
        val outline =
            points(-width / 2f to -height / 2f, width / 2f - radius to -height / 2f) +
                arcPoints(width / 2f - radius, 0f, radius, radius, -90f, 90f, 50) +
                points(-width / 2f to height / 2f)
        return centeredShape(listOf(closedPath(outline)), outline)
    }

    private fun tiltedCylinder(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val labelPadding = if (look == NEO) 12f else padding / 2f
        val height = label.height + labelPadding
        val radiusY = height / 2f
        val radiusX = radiusY / (2.5f + height / 50f)
        val bodyWidth = label.width + radiusX + labelPadding
        val totalWidth = bodyWidth + radiusX * 2f
        val leftCenterX = -totalWidth / 2f + radiusX
        val rightCenterX = totalWidth / 2f - radiusX
        val outline =
            arcPoints(leftCenterX, 0f, radiusX, radiusY, 90f, 270f, 50) +
                listOf(ScenePoint(rightCenterX, -radiusY)) +
                arcPoints(rightCenterX, 0f, radiusX, radiusY, -90f, 90f, 50) +
                listOf(ScenePoint(leftCenterX, radiusY))
        return centeredShape(
            paths = listOf(
                closedPath(outline),
                openStroke(*ellipsePoints(rightCenterX, 0f, radiusX, radiusY).toPairs()),
            ),
            outline = outline,
            labelCenter = ScenePoint(-radiusX, 0f),
        )
    }

    private fun curvedTrapezoid(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val height = max(5f, label.height + paddingY * 2f)
        val radius = height / 2f
        val capClearance = radius - sqrt(max(0f, radius * radius - (label.height / 2f).let { it * it }))
        val sideClearance = max(height / 4f, capClearance)
        val paddedLabelWidth = label.width + paddingX * 2f
        val width = maxOf(20f, paddedLabelWidth + sideClearance * 2f, paddedLabelWidth * 1.25f)
        val rightWall = width - radius
        val topInset = height / 4f
        val raw = points(
            rightWall to 0f,
            topInset to 0f,
            0f to height / 2f,
            topInset to height,
            rightWall to height,
        ) + arcPoints(rightWall, height / 2f, radius, radius, 90f, -90f, 50)
        val outline = raw.map { ScenePoint(it.x - width / 2f, it.y - height / 2f) }
        return centeredShape(listOf(closedPath(outline)), outline)
    }

    private fun dividedRectangle(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 16f else padding
        val width = label.width + paddingX
        val contentHeight = label.height + paddingY
        val offset = contentHeight * 0.2f
        val totalHeight = contentHeight + offset
        val outline = rectanglePoints(width, totalHeight)
        val separatorY = -totalHeight / 2f + offset
        return centeredShape(
            paths = listOf(
                closedPath(outline),
                openStroke(-width / 2f to separatorY, width / 2f to separatorY),
            ),
            outline = rectanglePoints(width, totalHeight),
            labelCenter = ScenePoint(0f, offset / 2f),
        )
    }

    private fun triangle(
        label: SceneSize,
        padding: Float,
        look: String,
        flipped: Boolean,
    ): MermaidShapeLayout {
        val labelPaddingX = if (look == NEO) padding * 2f else padding
        val width = label.width + labelPaddingX
        val height = width + label.height
        val points = if (flipped) {
            points(-height / 2f to -height / 2f, height / 2f to -height / 2f, 0f to height / 2f)
        } else {
            points(-height / 2f to height / 2f, height / 2f to height / 2f, 0f to -height / 2f)
        }
        val labelOffsetY = if (flipped) {
            -height / 2f + padding / 2f + label.height / 2f
        } else {
            height / 2f - label.height / 2f - padding / 2f
        }
        return centeredShape(listOf(closedPath(points)), points, ScenePoint(0f, labelOffsetY))
    }

    private fun windowPane(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val width = label.width + paddingX * 2f + 10f
        val height = label.height + paddingY * 2f + 10f
        val outline = rectanglePoints(width, height)
        return centeredShape(
            paths = listOf(
                closedPath(outline),
                openStroke(-width / 2f to -height / 2f + 10f, width / 2f to -height / 2f + 10f),
                openStroke(-width / 2f + 10f to -height / 2f, -width / 2f + 10f to height / 2f),
            ),
            outline = outline,
            labelCenter = ScenePoint(5f, 5f),
        )
    }

    private fun filledCircle(): MermaidShapeLayout {
        val outline = ellipsePoints(0f, 0f, 7f, 7f)
        return centeredShape(
            listOf(
                closedPath(
                    outline,
                    fill = SceneShapePaint.Stroke,
                    stroke = SceneShapePaint.None,
                ),
            ),
            outline,
            showsLabel = false,
        )
    }

    private fun trapezoidalPentagon(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val width = label.width + paddingX * 2f
        val height = label.height + paddingY * 2f
        val points = points(
            -width * 0.4f to -height / 2f,
            width * 0.4f to -height / 2f,
            width / 2f to -height * 0.3f,
            width / 2f to height / 2f,
            -width / 2f to height / 2f,
            -width / 2f to -height * 0.3f,
        )
        return centeredShape(listOf(closedPath(points)), points)
    }

    private fun slopedRectangle(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val width = label.width + paddingX * 2f
        val baseHeight = label.height + paddingY * 2f
        val points = points(
            -width / 2f to -baseHeight / 4f,
            -width / 2f to baseHeight * 0.75f,
            width / 2f to baseHeight * 0.75f,
            width / 2f to -baseHeight * 0.75f,
        )
        return centeredShape(
            listOf(closedPath(points)),
            points,
            ScenePoint(0f, baseHeight / 4f),
        )
    }

    private fun multiRectangle(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val offset = if (look == NEO) 10f else 5f
        val totalWidth = label.width + paddingX * 2f + offset * 2f
        val totalHeight = label.height + paddingY * 2f + offset * 2f
        val width = totalWidth - offset * 2f
        val height = totalHeight - offset * 2f
        val x = -width / 2f
        val y = -height / 2f
        val outer = points(
            x - offset to y + offset,
            x - offset to y + height + offset,
            x + width - offset to y + height + offset,
            x + width - offset to y + height,
            x + width to y + height,
            x + width to y + height - offset,
            x + width + offset to y + height - offset,
            x + width + offset to y - offset,
            x + offset to y - offset,
            x + offset to y,
            x to y,
            x to y + offset,
        )
        val inner = points(
            x to y + offset,
            x + width - offset to y + offset,
            x + width - offset to y + height,
            x + width to y + height,
            x + width to y,
            x to y,
        )
        return centeredShape(
            listOf(closedPath(outer), closedPath(inner)),
            outer,
            ScenePoint(-offset, offset),
        )
    }

    private fun waveRectangle(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 20f else padding
        val width = label.width + paddingX * 2f
        val height = label.height + paddingY
        val amplitude = height / 8f
        val finalHeight = height + amplitude * 2f
        val points =
            listOf(ScenePoint(-width / 2f, finalHeight / 2f)) +
                sineWave(-width / 2f, finalHeight / 2f, width / 2f, finalHeight / 2f, amplitude, 1f) +
                listOf(ScenePoint(width / 2f, -finalHeight / 2f)) +
                sineWave(width / 2f, -finalHeight / 2f, -width / 2f, -finalHeight / 2f, amplitude, -1f)
        return centeredShape(listOf(closedPath(points)), points)
    }

    private fun bowTie(label: SceneSize, padding: Float, look: String): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val height = label.height + paddingY
        val radiusY = height / 2f
        val radiusX = radiusY / (2.5f + height / 50f)
        val major = max(radiusX, radiusY)
        val minor = min(radiusX, radiusY)
        val sagitta = minor * (1f - sqrt(max(0f, 1f - (height / major / 2f).let { it * it })))
        val totalWidth = label.width + paddingX * 2f + sagitta
        val bodyWidth = totalWidth - sagitta
        val leftArc = ellipseArcBetween(
            ScenePoint(-bodyWidth / 2f, -height / 2f),
            ScenePoint(-bodyWidth / 2f, height / 2f),
            radiusX,
            radiusY,
            clockwise = false,
        )
        val rightArc = ellipseArcBetween(
            ScenePoint(bodyWidth / 2f, height / 2f),
            ScenePoint(bodyWidth / 2f, -height / 2f),
            radiusX,
            radiusY,
            clockwise = true,
        )
        val points = listOf(
            ScenePoint(bodyWidth / 2f, -height / 2f),
            ScenePoint(-bodyWidth / 2f, -height / 2f),
        ) + leftArc + ScenePoint(bodyWidth / 2f, height / 2f) + rightArc
        val shifted = points.map { ScenePoint(it.x + radiusX / 2f, it.y) }
        return centeredShape(listOf(closedPath(shifted)), shifted)
    }

    private fun crossedCircle(): MermaidShapeLayout {
        val radius = 30f
        val outline = ellipsePoints(0f, 0f, radius, radius)
        val diagonal = radius * cos(PI.toFloat() / 4f)
        return centeredShape(
            paths = listOf(
                closedPath(outline),
                openStroke(-diagonal to diagonal, diagonal to -diagonal),
                openStroke(diagonal to diagonal, -diagonal to -diagonal),
            ),
            outline = outline,
            showsLabel = false,
        )
    }

    private fun taggedRectangle(
        label: SceneSize,
        padding: Float,
        look: String,
    ): MermaidShapeLayout {
        val paddingX = if (look == NEO) 16f else padding
        val paddingY = if (look == NEO) 12f else padding
        val height = label.height + paddingY * 2f
        val tagWidth = height * 0.2f
        val totalWidth = label.width + paddingX * 2f + tagWidth
        val outline = rectanglePoints(totalWidth, height)
        val tag = points(
            totalWidth / 2f - tagWidth to height / 2f,
            totalWidth / 2f to height / 2f,
            totalWidth / 2f to height / 2f - tagWidth,
        )
        return centeredShape(
            listOf(closedPath(outline), closedPath(tag)),
            outline,
        )
    }

    private fun icon(
        node: FlowNode,
        label: SceneSize,
        form: IconForm,
    ): MermaidShapeLayout {
        val iconSize = max(node.assetWidth ?: 48f, node.assetHeight ?: 48f)
        val halfPadding = node.padding / 2f
        val shapeSize = when (form) {
            IconForm.Plain -> iconSize
            IconForm.Circle -> iconSize * sqrt(2f) + 40f
            IconForm.Square, IconForm.Rounded -> iconSize + halfPadding * 2f
        }
        val labelPadding = if (node.label.isNotEmpty()) 8f else 0f
        val width = max(shapeSize, label.width)
        val height = shapeSize + label.height + labelPadding
        val topLabel = node.position == "t"
        val shapeCenterY = if (topLabel) {
            label.height / 2f + labelPadding / 2f
        } else {
            -label.height / 2f - labelPadding / 2f
        }
        val labelCenterY = if (topLabel) {
            -height / 2f + label.height / 2f
        } else {
            height / 2f - label.height / 2f
        }
        val visible = when (form) {
            IconForm.Plain -> invisiblePath(rectanglePoints(shapeSize, shapeSize, centerY = shapeCenterY))
            IconForm.Circle -> closedPath(
                ellipsePoints(0f, shapeCenterY, shapeSize / 2f, shapeSize / 2f),
            )
            IconForm.Square -> closedPath(
                roundedRectanglePoints(shapeSize, shapeSize, 0.1f, centerY = shapeCenterY),
            )
            IconForm.Rounded -> closedPath(
                roundedRectanglePoints(shapeSize, shapeSize, 5f, centerY = shapeCenterY),
            )
        }
        val outer = rectanglePoints(width, height)
        return centeredShape(
            paths = listOf(visible, invisiblePath(outer)),
            outline = outer,
            labelCenter = ScenePoint(0f, labelCenterY),
            assetCenter = ScenePoint(0f, shapeCenterY),
        )
    }

    private fun image(node: FlowNode, label: SceneSize): MermaidShapeLayout {
        val imageWidth = node.assetWidth ?: 48f
        val imageHeight = node.assetHeight ?: 48f
        val labelPadding = if (node.label.isNotEmpty()) 8f else 0f
        val width = max(imageWidth, label.width)
        val height = imageHeight + label.height + labelPadding
        val topLabel = node.position == "t"
        val imageCenterY = if (topLabel) {
            height / 2f - imageHeight / 2f
        } else {
            -height / 2f + imageHeight / 2f
        }
        val labelCenterY = if (topLabel) {
            -imageHeight / 2f - labelPadding / 2f
        } else {
            imageHeight / 2f + labelPadding / 2f
        }
        val imageBounds = rectanglePoints(imageWidth, imageHeight, centerY = imageCenterY)
        val outer = rectanglePoints(width, height)
        return centeredShape(
            paths = listOf(closedPath(imageBounds), invisiblePath(outer)),
            outline = outer,
            labelCenter = ScenePoint(0f, labelCenterY),
            assetCenter = ScenePoint(0f, imageCenterY),
        )
    }

    private fun centeredShape(
        paths: List<SceneShapePath>,
        outline: List<ScenePoint>,
        labelCenter: ScenePoint = ScenePoint(0f, 0f),
        showsLabel: Boolean = true,
        assetCenter: ScenePoint? = null,
    ): MermaidShapeLayout {
        val allPoints = paths.flatMap(SceneShapePath::points)
        val boundsPoints = if (allPoints.isEmpty()) outline else allPoints
        val minX = boundsPoints.minOfOrNull(ScenePoint::x) ?: 0f
        val maxX = boundsPoints.maxOfOrNull(ScenePoint::x) ?: 0.1f
        val minY = boundsPoints.minOfOrNull(ScenePoint::y) ?: 0f
        val maxY = boundsPoints.maxOfOrNull(ScenePoint::y) ?: 0.1f
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        fun ScenePoint.normalize() = ScenePoint(x - centerX, y - centerY)
        return MermaidShapeLayout(
            size = SceneSize(max(maxX - minX, 0.1f), max(maxY - minY, 0.1f)),
            labelOffset = labelCenter.normalize(),
            geometry = SceneShapeGeometry(
                paths = paths.map { path ->
                    path.copy(points = path.points.map(ScenePoint::normalize))
                },
                outline = outline.map(ScenePoint::normalize),
            ),
            showsLabel = showsLabel,
            assetOffset = assetCenter?.normalize(),
        )
    }

    private fun rectanglePoints(
        width: Float,
        height: Float,
        centerX: Float = 0f,
        centerY: Float = 0f,
    ): List<ScenePoint> = points(
        centerX - width / 2f to centerY - height / 2f,
        centerX + width / 2f to centerY - height / 2f,
        centerX + width / 2f to centerY + height / 2f,
        centerX - width / 2f to centerY + height / 2f,
    )

    /**
     * Mermaid: rendering-elements/shapes/bang.ts and cloud.ts
     * -> updateNodeBounds followed by intersect.rect(node, point).
     */
    private fun boundingRectangle(points: List<ScenePoint>): List<ScenePoint> {
        val minX = points.minOf(ScenePoint::x)
        val maxX = points.maxOf(ScenePoint::x)
        val minY = points.minOf(ScenePoint::y)
        val maxY = points.maxOf(ScenePoint::y)
        return points(
            minX to minY,
            maxX to minY,
            maxX to maxY,
            minX to maxY,
        )
    }

    private fun roundedRectanglePoints(
        width: Float,
        height: Float,
        radius: Float,
        centerX: Float = 0f,
        centerY: Float = 0f,
    ): List<ScenePoint> {
        val r = min(radius, min(width, height) / 2f)
        return arcPoints(centerX + width / 2f - r, centerY - height / 2f + r, r, r, -90f, 0f, 9) +
            arcPoints(centerX + width / 2f - r, centerY + height / 2f - r, r, r, 0f, 90f, 9).drop(1) +
            arcPoints(centerX - width / 2f + r, centerY + height / 2f - r, r, r, 90f, 180f, 9).drop(1) +
            arcPoints(centerX - width / 2f + r, centerY - height / 2f + r, r, r, 180f, 270f, 9).drop(1)
    }

    private fun ellipsePoints(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        count: Int = 50,
    ): List<ScenePoint> = (0 until count).map { index ->
        val angle = 2f * PI.toFloat() * index / count
        ScenePoint(
            centerX + radiusX * cos(angle),
            centerY + radiusY * sin(angle),
        )
    }

    private fun circlePoints(
        centerX: Float,
        centerY: Float,
        radius: Float,
        count: Int,
        startAngle: Float,
        endAngle: Float,
    ): List<ScenePoint> =
        arcPoints(centerX, centerY, radius, radius, startAngle, endAngle, count)

    private fun mermaidCirclePoints(
        centerX: Float,
        centerY: Float,
        radius: Float,
        count: Int,
        startAngle: Float,
        endAngle: Float,
    ): List<ScenePoint> = arcPoints(
        centerX = -centerX,
        centerY = -centerY,
        radiusX = radius,
        radiusY = radius,
        startAngle = startAngle + 180f,
        endAngle = endAngle + 180f,
        count = count,
    )

    private fun arcPoints(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        startAngle: Float,
        endAngle: Float,
        count: Int,
    ): List<ScenePoint> = (0 until count).map { index ->
        val ratio = if (count <= 1) 0f else index.toFloat() / (count - 1)
        val angle = (startAngle + (endAngle - startAngle) * ratio) * PI.toFloat() / 180f
        ScenePoint(
            centerX + radiusX * cos(angle),
            centerY + radiusY * sin(angle),
        )
    }

    private fun sineWave(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        amplitude: Float,
        cycles: Float,
    ): List<ScenePoint> {
        val deltaX = x2 - x1
        val midY = y1 + (y2 - y1) / 2f
        val frequency = 2f * PI.toFloat() / (deltaX / cycles)
        return (0..50).map { index ->
            val ratio = index / 50f
            val x = x1 + ratio * deltaX
            ScenePoint(x, midY + amplitude * sin(frequency * (x - x1)))
        }
    }

    private fun ellipseArcBetween(
        start: ScenePoint,
        end: ScenePoint,
        radiusX: Float,
        radiusY: Float,
        clockwise: Boolean,
    ): List<ScenePoint> {
        val midpointX = (start.x + end.x) / 2f
        val midpointY = (start.y + end.y) / 2f
        val angle = atan2(end.y - start.y, end.x - start.x)
        val transformedX = (end.x - start.x) / 2f / radiusX
        val transformedY = (end.y - start.y) / 2f / radiusY
        val distance = hypot(transformedX, transformedY)
        val centerDistance = sqrt(max(0f, 1f - distance * distance))
        val sign = if (clockwise) -1f else 1f
        val centerX = midpointX + centerDistance * radiusY * sin(angle) * sign
        val centerY = midpointY - centerDistance * radiusX * cos(angle) * sign
        val startAngle = atan2((start.y - centerY) / radiusY, (start.x - centerX) / radiusX)
        val endAngle = atan2((end.y - centerY) / radiusY, (end.x - centerX) / radiusX)
        var range = endAngle - startAngle
        if (clockwise && range < 0f) range += 2f * PI.toFloat()
        if (!clockwise && range > 0f) range -= 2f * PI.toFloat()
        return (0 until 20).map { index ->
            val theta = startAngle + range * index / 19f
            ScenePoint(
                centerX + radiusX * cos(theta),
                centerY + radiusY * sin(theta),
            )
        }
    }

    private fun points(vararg values: Pair<Float, Float>): List<ScenePoint> =
        values.map { (x, y) -> ScenePoint(x, y) }

    private fun List<ScenePoint>.toPairs(): Array<Pair<Float, Float>> =
        map { it.x to it.y }.toTypedArray()

    private fun closedPath(
        points: List<ScenePoint>,
        fill: SceneShapePaint = SceneShapePaint.Fill,
        stroke: SceneShapePaint = SceneShapePaint.Stroke,
        strokeWidth: Float? = null,
        dashIntervals: List<Float> = emptyList(),
        opacity: Float = 1f,
        strokeColor: SceneColor? = null,
    ) = SceneShapePath(
        points = points,
        closed = true,
        fill = fill,
        stroke = stroke,
        strokeWidth = strokeWidth,
        dashIntervals = dashIntervals,
        opacity = opacity,
        strokeColor = strokeColor,
    )

    private fun openPath(
        points: List<ScenePoint>,
        stroke: SceneShapePaint = SceneShapePaint.Stroke,
    ) = SceneShapePath(
        points = points,
        closed = false,
        fill = SceneShapePaint.None,
        stroke = stroke,
    )

    private fun openStroke(
        vararg values: Pair<Float, Float>,
        pattern: SceneStrokePattern = SceneStrokePattern.Solid,
        strokeWidth: Float? = null,
        dashIntervals: List<Float> = emptyList(),
        strokeColor: SceneColor? = null,
    ) = SceneShapePath(
        points = points(*values),
        closed = false,
        fill = SceneShapePaint.None,
        stroke = SceneShapePaint.Stroke,
        strokeWidth = strokeWidth,
        strokePattern = pattern,
        dashIntervals = dashIntervals,
        strokeColor = strokeColor,
    )

    private fun invisiblePath(points: List<ScenePoint>) = SceneShapePath(
        points = points,
        closed = true,
        fill = SceneShapePaint.None,
        stroke = SceneShapePaint.None,
    )

    private class SvgPointBuilder(start: ScenePoint) {
        val points = mutableListOf(start)
        private var current = start

        fun arcBy(
            radiusX: Float,
            radiusY: Float,
            rotationDegrees: Float,
            largeArc: Boolean,
            sweep: Boolean,
            deltaX: Float,
            deltaY: Float,
        ) {
            val end = ScenePoint(current.x + deltaX, current.y + deltaY)
            points += svgArc(
                start = current,
                end = end,
                inputRadiusX = radiusX,
                inputRadiusY = radiusY,
                rotationDegrees = rotationDegrees,
                largeArc = largeArc,
                sweep = sweep,
            ).drop(1)
            current = end
        }
    }

    private fun svgArc(
        start: ScenePoint,
        end: ScenePoint,
        inputRadiusX: Float,
        inputRadiusY: Float,
        rotationDegrees: Float,
        largeArc: Boolean,
        sweep: Boolean,
        count: Int = 16,
    ): List<ScenePoint> {
        var radiusX = abs(inputRadiusX)
        var radiusY = abs(inputRadiusY)
        if (radiusX == 0f || radiusY == 0f || start == end) {
            return listOf(start, end)
        }
        val rotation = rotationDegrees * PI.toFloat() / 180f
        val rotationCos = cos(rotation)
        val rotationSin = sin(rotation)
        val deltaX = (start.x - end.x) / 2f
        val deltaY = (start.y - end.y) / 2f
        val xPrime = rotationCos * deltaX + rotationSin * deltaY
        val yPrime = -rotationSin * deltaX + rotationCos * deltaY
        val scale = xPrime * xPrime / (radiusX * radiusX) +
            yPrime * yPrime / (radiusY * radiusY)
        if (scale > 1f) {
            val root = sqrt(scale)
            radiusX *= root
            radiusY *= root
        }
        val numerator = max(
            0f,
            radiusX * radiusX * radiusY * radiusY -
                radiusX * radiusX * yPrime * yPrime -
                radiusY * radiusY * xPrime * xPrime,
        )
        val denominator = radiusX * radiusX * yPrime * yPrime +
            radiusY * radiusY * xPrime * xPrime
        val sign = if (largeArc == sweep) -1f else 1f
        val factor = if (denominator == 0f) 0f else sign * sqrt(numerator / denominator)
        val centerPrimeX = factor * radiusX * yPrime / radiusY
        val centerPrimeY = factor * -radiusY * xPrime / radiusX
        val centerX = rotationCos * centerPrimeX -
            rotationSin * centerPrimeY +
            (start.x + end.x) / 2f
        val centerY = rotationSin * centerPrimeX +
            rotationCos * centerPrimeY +
            (start.y + end.y) / 2f

        fun vectorAngle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
            val dot = ux * vx + uy * vy
            val length = hypot(ux, uy) * hypot(vx, vy)
            val unsigned = kotlin.math.acos((dot / length).coerceIn(-1f, 1f))
            return if (ux * vy - uy * vx < 0f) -unsigned else unsigned
        }

        val ux = (xPrime - centerPrimeX) / radiusX
        val uy = (yPrime - centerPrimeY) / radiusY
        val vx = (-xPrime - centerPrimeX) / radiusX
        val vy = (-yPrime - centerPrimeY) / radiusY
        val startAngle = vectorAngle(1f, 0f, ux, uy)
        var deltaAngle = vectorAngle(ux, uy, vx, vy)
        if (!sweep && deltaAngle > 0f) deltaAngle -= 2f * PI.toFloat()
        if (sweep && deltaAngle < 0f) deltaAngle += 2f * PI.toFloat()
        return (0..count).map { index ->
            val theta = startAngle + deltaAngle * index / count
            ScenePoint(
                centerX +
                    rotationCos * radiusX * cos(theta) -
                    rotationSin * radiusY * sin(theta),
                centerY +
                    rotationSin * radiusX * cos(theta) +
                    rotationCos * radiusY * sin(theta),
            )
        }
    }

    private enum class LeanDirection {
        Right,
        Left,
        TrapezoidBottom,
    }

    private enum class BraceKind {
        Left,
        Right,
        Both,
    }

    private enum class DocumentKind {
        Single,
        Lined,
        Multi,
        Tagged,
    }

    private enum class IconForm {
        Plain,
        Circle,
        Square,
        Rounded,
    }

    private const val NEO = "neo"
}

internal data class MermaidShapeLayout(
    val size: SceneSize,
    val labelOffset: ScenePoint,
    val geometry: SceneShapeGeometry,
    val showsLabel: Boolean,
    val assetOffset: ScenePoint? = null,
)
