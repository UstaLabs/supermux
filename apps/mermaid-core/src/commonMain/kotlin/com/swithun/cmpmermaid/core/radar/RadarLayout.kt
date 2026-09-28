package com.swithun.cmpmermaid.core.radar

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRadarOptions
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneColor
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
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarAxis
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarCurve
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarDb
import com.swithun.cmpmermaid.core.radar.upstream.mermaid.RadarGraticule
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/radar/renderer.ts and styles.ts.
 */
internal class RadarLayout {
    fun layout(
        db: RadarDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val config = db.config
        when (val validation = validate(config)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        val axes = db.getAxes()
        val curves = db.getCurves()
        val options = db.getOptions()
        val totalWidth = config.width + config.marginLeft + config.marginRight
        val totalHeight = config.height + config.marginTop + config.marginBottom
        if (!totalWidth.isFinite() || !totalHeight.isFinite()) {
            return configurationError("viewport dimensions must be finite")
        }
        val center = ScenePoint(
            x = config.marginLeft + config.width / 2f,
            y = config.marginTop + config.height / 2f,
        )
        val radius = min(config.width, config.height) / 2f
        val maxValue = options.max ?: curves
            .flatMap(RadarCurve::entries)
            .maxOrNull()
            ?: Double.NEGATIVE_INFINITY
        val hasDrawableCurve = curves.any { curve -> curve.entries.size == axes.size }
        if (hasDrawableCurve && (!maxValue.isFinite() || maxValue == options.min)) {
            return GMResult.Err(
                MermaidError.Layout(
                    "Mermaid Radar requires a finite non-zero value range",
                ),
            )
        }

        val elements = mutableListOf<SceneElement>()
        drawGraticule(
            elements = elements,
            center = center,
            axes = axes,
            radius = radius,
            ticks = options.ticks,
            graticule = options.graticule,
            context = context,
        )
        drawAxes(elements, center, axes, radius, config, context)
        drawCurves(
            elements = elements,
            center = center,
            axes = axes,
            curves = curves,
            radius = radius,
            minValue = options.min,
            maxValue = maxValue,
            graticule = options.graticule,
            config = config,
            context = context,
        )
        drawLegend(elements, center, curves, options.showLegend, config, context)
        drawTitle(elements, center, db.diagramTitle.orEmpty(), config, context)

        return GMResult.Ok(
            normalizeViewport(
                MermaidScene(
                    width = totalWidth,
                    height = totalHeight,
                    background = context.theme.background,
                    elements = elements,
                    title = db.diagramTitle?.takeIf(String::isNotEmpty),
                    accessibilityTitle = db.accessibilityTitle,
                    accessibilityDescription = db.accessibilityDescription,
                    viewportPadding = 0f,
                    viewportSizing = if (config.useMaxWidth) {
                        MermaidSceneViewportSizing.ResponsiveMaxWidth
                    } else {
                        MermaidSceneViewportSizing.Intrinsic
                    },
                ),
            ),
        )
    }

    /**
     * Mermaid.js 12.0.0: radar/renderer.ts -> draw and final svg overflow.
     * Official reference isolation: official-mermaid.html -> normalizeViewBox.
     *
     * Radar labels may extend beyond the renderer viewport. The browser
     * reference includes those bounds and adds 12 px on every side.
     */
    private fun normalizeViewport(scene: MermaidScene): MermaidScene {
        val rendererViewport = SceneRect(
            left = 0f,
            top = 0f,
            right = scene.width,
            bottom = scene.height,
        )
        val contentBounds = scene.elements
            .mapNotNull(::elementBounds)
            .reduceOrNull(SceneRect::union)
        val union = contentBounds?.let(rendererViewport::union) ?: rendererViewport
        val left = union.left - VIEWBOX_PADDING
        val top = union.top - VIEWBOX_PADDING
        val right = union.right + VIEWBOX_PADDING
        val bottom = union.bottom + VIEWBOX_PADDING
        return scene.copy(
            width = (right - left).coerceAtLeast(1f),
            height = (bottom - top).coerceAtLeast(1f),
            elements = scene.elements.map { element ->
                element.translate(dx = -left, dy = -top)
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

    // Mermaid.js 12.0.0: radar/renderer.ts -> drawGraticule.
    private fun drawGraticule(
        elements: MutableList<SceneElement>,
        center: ScenePoint,
        axes: List<RadarAxis>,
        radius: Float,
        ticks: Double,
        graticule: RadarGraticule,
        context: MermaidRenderContext,
    ) {
        var index = 0
        while (index.toDouble() < ticks && index < MAX_TICKS) {
            val ringRadius = radius * (index + 1).toFloat() / ticks.toFloat()
            when (graticule) {
                RadarGraticule.Circle -> elements += SceneShape(
                    id = "radar-graticule-$index",
                    bounds = SceneRect(
                        left = center.x - ringRadius,
                        top = center.y - ringRadius,
                        right = center.x + ringRadius,
                        bottom = center.y + ringRadius,
                    ),
                    kind = SceneShapeKind.Circle,
                    fill = context.theme.radar.graticuleColor.withOpacity(
                        context.theme.radar.graticuleOpacity,
                    ),
                    stroke = context.theme.radar.graticuleColor,
                    strokeWidth = context.theme.radar.graticuleStrokeWidth,
                    cornerRadius = 0f,
                    shadow = null,
                    zIndex = elements.size + 1,
                )
                RadarGraticule.Polygon -> {
                    val points = radialPoints(axes.size, center, ringRadius)
                    elements += closedLinearPath(
                        id = "radar-graticule-$index",
                        points = points,
                        stroke = context.theme.radar.graticuleColor,
                        strokeWidth = context.theme.radar.graticuleStrokeWidth,
                        fill = context.theme.radar.graticuleColor.withOpacity(
                            context.theme.radar.graticuleOpacity,
                        ),
                        look = context.options.look,
                        zIndex = elements.size + 1,
                    )
                }
            }
            index += 1
        }
    }

    // Mermaid.js 12.0.0: radar/renderer.ts -> drawAxes.
    private fun drawAxes(
        elements: MutableList<SceneElement>,
        center: ScenePoint,
        axes: List<RadarAxis>,
        radius: Float,
        config: MermaidRadarOptions,
        context: MermaidRenderContext,
    ) {
        axes.forEachIndexed { index, axis ->
            val angle = radarAngle(index, axes.size)
            val cosAngle = cos(angle).toFloat()
            val sinAngle = sin(angle).toFloat()
            val endpoint = ScenePoint(
                x = center.x + radius * config.axisScaleFactor * cosAngle,
                y = center.y + radius * config.axisScaleFactor * sinAngle,
            )
            elements += openLine(
                id = "radar-axis-$index",
                start = center,
                end = endpoint,
                color = context.theme.radar.axisColor,
                width = context.theme.radar.axisStrokeWidth,
                look = context.options.look,
                zIndex = elements.size + 1,
            )
            val labelDistance = radius * config.axisLabelFactor + LABEL_PADDING
            val labelPoint = ScenePoint(
                x = center.x + labelDistance * cosAngle,
                y = center.y + labelDistance * sinAngle,
            )
            val horizontal = when {
                cosAngle > ANCHOR_EPSILON -> SceneTextAlignment.Start
                cosAngle < -ANCHOR_EPSILON -> SceneTextAlignment.End
                else -> SceneTextAlignment.Center
            }
            val vertical = when {
                sinAngle > ANCHOR_EPSILON -> RadarTextBaseline.Hanging
                sinAngle < -ANCHOR_EPSILON -> RadarTextBaseline.Auto
                else -> RadarTextBaseline.Central
            }
            elements += anchoredText(
                value = axis.label,
                point = labelPoint,
                fontSize = context.theme.radar.axisLabelFontSize,
                horizontal = horizontal,
                vertical = vertical,
                context = context,
                zIndex = elements.size + 1,
            )
        }
    }

    // Mermaid.js 12.0.0: radar/renderer.ts -> drawCurves.
    private fun drawCurves(
        elements: MutableList<SceneElement>,
        center: ScenePoint,
        axes: List<RadarAxis>,
        curves: List<RadarCurve>,
        radius: Float,
        minValue: Double,
        maxValue: Double,
        graticule: RadarGraticule,
        config: MermaidRadarOptions,
        context: MermaidRenderContext,
    ) {
        curves.forEachIndexed { index, curve ->
            if (curve.entries.size != axes.size) {
                return@forEachIndexed
            }
            val points = curve.entries.mapIndexed { axisIndex, entry ->
                val angle = radarAngle(axisIndex, axes.size)
                val entryRadius = radarRelativeRadius(entry, minValue, maxValue, radius)
                ScenePoint(
                    x = center.x + entryRadius * cos(angle).toFloat(),
                    y = center.y + entryRadius * sin(angle).toFloat(),
                )
            }
            val color = context.theme.radar.colors.getOrNull(index)
            val stroke = color ?: TRANSPARENT
            val fill = (color ?: context.theme.textColor).withOpacity(
                if (color == null) 1f else context.theme.radar.curveOpacity,
            )
            elements += when (graticule) {
                RadarGraticule.Circle -> ScenePath(
                    id = "radar-curve-$index",
                    points = points,
                    commands = radarClosedRoundCurve(points, config.curveTension),
                    color = stroke,
                    strokeWidth = if (color == null) 0f else {
                        context.theme.radar.curveStrokeWidth
                    },
                    strokePattern = SceneStrokePattern.Solid,
                    arrowStart = SceneArrowHead.None,
                    arrowEnd = SceneArrowHead.None,
                    curve = "catmull-rom",
                    look = context.options.look,
                    animated = false,
                    zIndex = elements.size + 1,
                    fillColor = fill,
                    closed = true,
                )
                RadarGraticule.Polygon -> closedLinearPath(
                    id = "radar-curve-$index",
                    points = points,
                    stroke = stroke,
                    strokeWidth = if (color == null) 0f else {
                        context.theme.radar.curveStrokeWidth
                    },
                    fill = fill,
                    look = context.options.look,
                    zIndex = elements.size + 1,
                )
            }
        }
    }

    // Mermaid.js 12.0.0: radar/renderer.ts -> drawLegend.
    private fun drawLegend(
        elements: MutableList<SceneElement>,
        center: ScenePoint,
        curves: List<RadarCurve>,
        showLegend: Boolean,
        config: MermaidRadarOptions,
        context: MermaidRenderContext,
    ) {
        if (!showLegend) {
            return
        }
        val legendX = center.x + ((config.width / 2f + config.marginRight) * 3f) / 4f
        val legendY = center.y - ((config.height / 2f + config.marginTop) * 3f) / 4f
        curves.forEachIndexed { index, curve ->
            val color = context.theme.radar.colors.getOrNull(index)
            val y = legendY + index * LEGEND_LINE_HEIGHT
            elements += SceneShape(
                id = "radar-legend-box-$index",
                bounds = SceneRect(
                    left = legendX,
                    top = y,
                    right = legendX + UPSTREAM_LEGEND_BOX_SIZE,
                    bottom = y + UPSTREAM_LEGEND_BOX_SIZE,
                ),
                kind = SceneShapeKind.Rectangle,
                fill = (color ?: context.theme.textColor).withOpacity(
                    if (color == null) 1f else context.theme.radar.curveOpacity,
                ),
                stroke = color ?: TRANSPARENT,
                strokeWidth = if (color == null) 0f else 1f,
                cornerRadius = 0f,
                shadow = null,
                zIndex = elements.size + 1,
            )
            elements += anchoredText(
                value = curve.label,
                point = ScenePoint(legendX + LEGEND_TEXT_OFFSET, y),
                fontSize = context.theme.radar.legendFontSize,
                horizontal = SceneTextAlignment.Start,
                vertical = RadarTextBaseline.Hanging,
                context = context,
                zIndex = elements.size + 1,
            )
        }
    }

    private fun drawTitle(
        elements: MutableList<SceneElement>,
        center: ScenePoint,
        title: String,
        config: MermaidRadarOptions,
        context: MermaidRenderContext,
    ) {
        if (title.isEmpty()) {
            return
        }
        elements += anchoredText(
            value = title,
            point = ScenePoint(
                x = center.x,
                y = center.y - config.height / 2f - config.marginTop,
            ),
            fontSize = context.theme.fontSize,
            horizontal = SceneTextAlignment.Center,
            vertical = RadarTextBaseline.Hanging,
            context = context,
            zIndex = elements.size + 1,
        )
    }

    private fun anchoredText(
        value: String,
        point: ScenePoint,
        fontSize: Float,
        horizontal: SceneTextAlignment,
        vertical: RadarTextBaseline,
        context: MermaidRenderContext,
        zIndex: Int,
    ): SceneText {
        val metrics = measure(value, fontSize, context)
        val left = when (horizontal) {
            SceneTextAlignment.Start -> point.x - TEXT_ALIGNMENT_INSET
            SceneTextAlignment.Center -> point.x - metrics.width / 2f
            SceneTextAlignment.End -> point.x - metrics.width
        }
        val right = when (horizontal) {
            SceneTextAlignment.Start -> point.x + metrics.width
            SceneTextAlignment.Center -> point.x + metrics.width / 2f
            SceneTextAlignment.End -> point.x + TEXT_ALIGNMENT_INSET
        }
        val top = when (vertical) {
            // SVG getBBox() extends hanging text slightly above its anchor.
            RadarTextBaseline.Hanging -> point.y - fontSize * SVG_HANGING_TOP_FACTOR
            // SVG auto uses the font ascent, not the full Compose line box.
            RadarTextBaseline.Auto -> point.y - fontSize * SVG_AUTO_ASCENT_FACTOR
            RadarTextBaseline.Central -> point.y - metrics.height / 2f
        }
        return SceneText(
            text = value,
            bounds = SceneRect(
                left = left,
                top = top,
                right = right,
                bottom = top + metrics.height,
            ),
            color = context.theme.textColor,
            fontSize = fontSize,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            horizontalAlignment = horizontal,
            softWrap = false,
            zIndex = zIndex,
        )
    }

    private fun measure(
        value: String,
        fontSize: Float,
        context: MermaidRenderContext,
    ): TextMetrics = context.textMetrics.measure(
        TextMetricsRequest(
            text = value,
            fontSize = fontSize,
            maxWidth = UNWRAPPED_TEXT_WIDTH,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
        ),
    )

    private fun validate(config: MermaidRadarOptions): GMResult<Unit, MermaidError> {
        listOf(
            "width" to config.width,
            "height" to config.height,
        ).firstOrNull { (_, value) -> !value.isFinite() || value < 1f }?.let { invalid ->
            return configurationError("${invalid.first} must be at least 1")
        }
        listOf(
            "marginTop" to config.marginTop,
            "marginRight" to config.marginRight,
            "marginBottom" to config.marginBottom,
            "marginLeft" to config.marginLeft,
            "axisScaleFactor" to config.axisScaleFactor,
            "axisLabelFactor" to config.axisLabelFactor,
        ).firstOrNull { (_, value) -> !value.isFinite() || value < 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be non-negative")
        }
        if (!config.curveTension.isFinite() || config.curveTension !in 0f..1f) {
            return configurationError("curveTension must be between 0 and 1")
        }
        return GMResult.Ok(Unit)
    }

    private fun configurationError(message: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid Radar $message"))

    private enum class RadarTextBaseline {
        Hanging,
        Auto,
        Central,
    }

    private companion object {
        val TRANSPARENT = SceneColor(0x00000000)
        const val MAX_TICKS = 32
        const val LABEL_PADDING = 4f
        const val ANCHOR_EPSILON = 0.01f
        const val LEGEND_LINE_HEIGHT = 20f
        const val UPSTREAM_LEGEND_BOX_SIZE = 12f
        const val LEGEND_TEXT_OFFSET = 16f
        const val TEXT_ALIGNMENT_INSET = 4f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        const val VIEWBOX_PADDING = 12f
        const val SVG_HANGING_TOP_FACTOR = 0.1875f
        const val SVG_AUTO_ASCENT_FACTOR = 0.9f
    }
}

internal fun radarRelativeRadius(
    value: Double,
    minValue: Double,
    maxValue: Double,
    radius: Float,
): Float {
    val clipped = min(max(value, minValue), maxValue)
    return (radius * (clipped - minValue) / (maxValue - minValue)).toFloat()
}

internal fun radarClosedRoundCurve(
    points: List<ScenePoint>,
    tension: Float,
): List<ScenePathCommand> {
    if (points.isEmpty()) {
        return emptyList()
    }
    return buildList {
        add(ScenePathCommand.MoveTo(points.first()))
        points.indices.forEach { index ->
            val p0 = points[(index - 1 + points.size) % points.size]
            val p1 = points[index]
            val p2 = points[(index + 1) % points.size]
            val p3 = points[(index + 2) % points.size]
            add(
                ScenePathCommand.CubicTo(
                    control1 = ScenePoint(
                        x = p1.x + (p2.x - p0.x) * tension,
                        y = p1.y + (p2.y - p0.y) * tension,
                    ),
                    control2 = ScenePoint(
                        x = p2.x - (p3.x - p1.x) * tension,
                        y = p2.y - (p3.y - p1.y) * tension,
                    ),
                    end = p2,
                ),
            )
        }
    }
}

private fun radialPoints(
    count: Int,
    center: ScenePoint,
    radius: Float,
): List<ScenePoint> = List(count) { index ->
    val angle = radarAngle(index, count)
    ScenePoint(
        x = center.x + radius * cos(angle).toFloat(),
        y = center.y + radius * sin(angle).toFloat(),
    )
}

private fun radarAngle(
    index: Int,
    count: Int,
): Double = 2.0 * index * PI / count - PI / 2.0

private fun closedLinearPath(
    id: String,
    points: List<ScenePoint>,
    stroke: SceneColor,
    strokeWidth: Float,
    fill: SceneColor,
    look: String,
    zIndex: Int,
): ScenePath = ScenePath(
    id = id,
    points = points,
    commands = buildList {
        points.firstOrNull()?.let { first ->
            add(ScenePathCommand.MoveTo(first))
            points.drop(1).forEach { point ->
                add(ScenePathCommand.LineTo(point))
            }
        }
    },
    color = stroke,
    strokeWidth = strokeWidth,
    strokePattern = SceneStrokePattern.Solid,
    arrowStart = SceneArrowHead.None,
    arrowEnd = SceneArrowHead.None,
    curve = "linear",
    look = look,
    animated = false,
    zIndex = zIndex,
    fillColor = fill,
    closed = true,
)

private fun openLine(
    id: String,
    start: ScenePoint,
    end: ScenePoint,
    color: SceneColor,
    width: Float,
    look: String,
    zIndex: Int,
): ScenePath = ScenePath(
    id = id,
    points = listOf(start, end),
    commands = listOf(
        ScenePathCommand.MoveTo(start),
        ScenePathCommand.LineTo(end),
    ),
    color = color,
    strokeWidth = width,
    strokePattern = SceneStrokePattern.Solid,
    arrowStart = SceneArrowHead.None,
    arrowEnd = SceneArrowHead.None,
    curve = "linear",
    look = look,
    animated = false,
    zIndex = zIndex,
)

private fun SceneColor.withOpacity(opacity: Float): SceneColor {
    val alpha = ((argb ushr 24) and 0xFF).toInt()
    val adjusted = (alpha * opacity.coerceIn(0f, 1f)).roundToInt()
    return SceneColor((argb and 0x00FFFFFF) or (adjusted.toLong() shl 24))
}
