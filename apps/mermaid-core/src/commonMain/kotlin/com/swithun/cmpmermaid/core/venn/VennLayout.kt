package com.swithun.cmpmermaid.core.venn

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
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
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.mermaidDarken
import com.swithun.cmpmermaid.core.mermaidLighten
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennData
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennDb
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennStyleData
import com.swithun.cmpmermaid.core.venn.upstream.mermaid.VennTextData
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennArc
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennCircle
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennLayoutArea
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennLayoutEngine
import com.swithun.cmpmermaid.core.venn.upstream.vennjs.VennPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/venn/vennRenderer.ts and styles.ts.
 */
internal class VennLayout {
    fun layout(
        db: VennDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val config = db.config
        if (!config.width.isFinite() || config.width <= 0f) {
            return configurationError("width must be positive")
        }
        if (!config.height.isFinite() || config.height <= 0f) {
            return configurationError("height must be positive")
        }
        if (!config.padding.isFinite() || config.padding < 0f) {
            return configurationError("padding must be non-negative")
        }
        val styles = when (val parsed = buildStyleByKey(db.getStyleData())) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val renderSets = VennLayoutEngine.ensurePairwiseSubsets(db.getSubsetData())
        val width = config.width
        val height = config.height
        val scale = width / REFERENCE_WIDTH
        val titleHeight = if (db.diagramTitle.isNullOrEmpty()) 0f else TITLE_HEIGHT * scale
        val layoutHeight = height - titleHeight
        if (layoutHeight <= 0f) {
            return configurationError("height is too small for the title")
        }
        val circleAreas = when (
            val result = VennLayoutEngine.layout(
                source = renderSets,
                width = width.toDouble(),
                height = layoutHeight.toDouble(),
                padding = VENN_JS_DEFAULT_PADDING,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        val textAreas = when (
            val result = VennLayoutEngine.layout(
                source = renderSets,
                width = width.toDouble(),
                height = layoutHeight.toDouble(),
                padding = config.padding.toDouble(),
            )
        ) {
            is GMResult.Ok -> result.value.associateBy { area ->
                stableSetsKey(area.data.sets.sorted())
            }
            is GMResult.Err -> return result
        }
        val elements = mutableListOf<SceneElement>()
        drawTitle(db.diagramTitle, width, scale, context, elements)
        val themeColors = vennPalette(context)
        val themeDark = isDark(context.theme.background)
        val defaultTextColor = themeColor(
            context = context,
            key = "vennSetTextColor",
            fallback = context.theme.textColor,
        )
        circleAreas.forEachIndexed { index, area ->
            val key = stableSetsKey(area.data.sets.sorted())
            val style = styles[key] ?: VennResolvedStyle()
            if (area.data.sets.size == 1) {
                val paletteColor = themeColors.getOrNull(index % themeColors.size.coerceAtLeast(1))
                val baseColor = style.fill ?: paletteColor ?: context.theme.nodeFill
                elements += areaPath(
                    id = "venn-circle-$index",
                    area = area,
                    titleHeight = titleHeight,
                    fill = baseColor.withOpacity(style.fillOpacity ?: CIRCLE_FILL_OPACITY),
                    stroke = (style.stroke ?: baseColor).withOpacity(CIRCLE_STROKE_OPACITY),
                    strokeWidth = style.strokeWidth ?: CIRCLE_STROKE_WIDTH * scale,
                    zIndex = elements.size + 1,
                )
                val label = areaLabel(area.data)
                if (label.isNotEmpty()) {
                    val color = style.color ?: if (themeDark) {
                        baseColor.mermaidLighten(TEXT_CONTRAST_AMOUNT)
                    } else {
                        baseColor.mermaidDarken(TEXT_CONTRAST_AMOUNT)
                    }
                    drawAreaLabel(
                        label = label,
                        area = area,
                        titleHeight = titleHeight,
                        color = color,
                        fontSize = AREA_FONT_SIZE * scale,
                        context = context,
                        elements = elements,
                    )
                }
            } else {
                elements += areaPath(
                    id = "venn-intersection-$index",
                    area = area,
                    titleHeight = titleHeight,
                    fill = style.fill ?: TRANSPARENT,
                    stroke = TRANSPARENT,
                    strokeWidth = 0f,
                    zIndex = elements.size + 1,
                )
                val label = areaLabel(area.data)
                if (label.isNotEmpty()) {
                    drawAreaLabel(
                        label = label,
                        area = area,
                        titleHeight = titleHeight,
                        color = style.color ?: defaultTextColor,
                        fontSize = AREA_FONT_SIZE * scale,
                        context = context,
                        elements = elements,
                    )
                }
            }
        }
        drawTextNodes(
            textNodes = db.getTextData(),
            layoutByKey = textAreas,
            styles = styles,
            titleHeight = titleHeight,
            scale = scale,
            defaultTextColor = defaultTextColor,
            debug = config.useDebugLayout,
            context = context,
            elements = elements,
        )
        return GMResult.Ok(
            normalizeViewport(
                MermaidScene(
                    width = width,
                    height = height,
                    background = context.theme.background,
                    elements = elements,
                    title = db.diagramTitle?.takeIf(String::isNotEmpty),
                    viewportSizing = if (config.useMaxWidth) {
                        MermaidSceneViewportSizing.ResponsiveMaxWidth
                    } else {
                        MermaidSceneViewportSizing.Intrinsic
                    },
                ),
            ),
        )
    }

    private fun drawTitle(
        title: String?,
        width: Float,
        scale: Float,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        title?.takeIf(String::isNotEmpty)?.let { value ->
            // Mermaid.js 12.0.0: styles.ts .venn-title overrides the scaled
            // renderer attribute with a fixed 32px font size.
            val fontSize = TITLE_FONT_SIZE
            val centerY = TITLE_Y * scale
            val metrics = context.textMetrics.measure(
                TextMetricsRequest(
                    text = value,
                    fontSize = fontSize,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    horizontalScale = VENN_TITLE_HORIZONTAL_SCALE,
                ),
            )
            val titleWidth = metrics.width * VENN_TITLE_METRIC_CORRECTION
            elements += SceneText(
                text = value,
                bounds = SceneRect(
                    left = width / 2f - titleWidth / 2f,
                    top = centerY - metrics.height / 2f,
                    right = width / 2f + titleWidth / 2f,
                    bottom = centerY + metrics.height / 2f,
                ),
                color = themeColor(
                    context = context,
                    key = "vennTitleTextColor",
                    fallback = context.theme.textColor,
                ),
                fontSize = fontSize,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                horizontalAlignment = SceneTextAlignment.Center,
                softWrap = false,
                horizontalScale = VENN_TITLE_HORIZONTAL_SCALE,
                zIndex = elements.size + 1,
            )
        }
    }

    /**
     * Mermaid.js 12.0.0: vennRenderer.ts -> draw and final svg overflow.
     * Official reference isolation: official-mermaid.html -> normalizeViewBox.
     *
     * The reference renderer unions its configured viewBox with SVG getBBox()
     * and adds 12 px on every side. This is visible when a long Venn title
     * extends beyond the configured width. The title's SVG x="50%" is resolved
     * again against the normalized width after the viewBox changes.
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
        val normalizedWidth = (right - left).coerceAtLeast(1f)
        val titleIndex = scene.title?.let { title ->
            scene.elements.indexOfFirst { element ->
                element is SceneText && element.text == title
            }
        } ?: -1
        val titlePercentageShift = (normalizedWidth - scene.width) / 2f
        return scene.copy(
            width = normalizedWidth,
            height = (bottom - top).coerceAtLeast(1f),
            elements = scene.elements.mapIndexed { index, element ->
                element.translate(
                    dx = -left + if (index == titleIndex) titlePercentageShift else 0f,
                    dy = -top,
                )
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

    private fun drawAreaLabel(
        label: String,
        area: VennLayoutArea,
        titleHeight: Float,
        color: SceneColor,
        fontSize: Float,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val maximumWidth = area.circles.firstOrNull()?.radius?.toFloat() ?: 50f
        // @upsetjs/venn.js 2.0.0: src/diagram.js -> wrapText. Mermaid invokes
        // it on a detached dummy SVG, where getComputedTextLength() is zero.
        val metrics = context.textMetrics.measure(
            TextMetricsRequest(
                text = label,
                fontSize = fontSize,
                maxWidth = maximumWidth,
                lineHeight = AREA_LINE_HEIGHT,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
            ),
        )
        val centerX = floor(area.text.x).toFloat()
        val centerY = floor(area.text.y).toFloat() + titleHeight
        elements += SceneText(
            text = label,
            bounds = SceneRect(
                left = centerX - max(maximumWidth, metrics.width) / 2f,
                top = centerY - metrics.height / 2f,
                right = centerX + max(maximumWidth, metrics.width) / 2f,
                bottom = centerY + metrics.height / 2f,
            ),
            color = color,
            fontSize = fontSize,
            lineHeight = AREA_LINE_HEIGHT,
            fontFamily = context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            horizontalAlignment = SceneTextAlignment.Center,
            softWrap = false,
            zIndex = elements.size + 1,
        )
    }

    private fun drawTextNodes(
        textNodes: List<VennTextData>,
        layoutByKey: Map<String, VennLayoutArea>,
        styles: Map<String, VennResolvedStyle>,
        titleHeight: Float,
        scale: Float,
        defaultTextColor: SceneColor,
        debug: Boolean,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        textNodes.groupBy { node -> stableSetsKey(node.sets) }.forEach { (key, nodes) ->
            val area = layoutByKey[key] ?: return@forEach
            if (area.circles.isEmpty()) {
                return@forEach
            }
            val centerX = area.text.x
            val centerY = area.text.y
            val minimumRadius = area.circles.minOf(VennCircle::radius)
            val rawRadius = area.circles.minOf { circle ->
                circle.radius - hypot(centerX - circle.x, centerY - circle.y)
            }
            var innerRadius = if (rawRadius.isFinite()) max(0.0, rawRadius) else 0.0
            if (innerRadius == 0.0 && minimumRadius.isFinite()) {
                innerRadius = minimumRadius * 0.6
            }
            if (debug) {
                elements += SceneShape(
                    id = "venn-text-debug-circle-${elements.size}",
                    bounds = SceneRect(
                        left = (centerX - innerRadius).toFloat(),
                        top = (centerY - innerRadius).toFloat() + titleHeight,
                        right = (centerX + innerRadius).toFloat(),
                        bottom = (centerY + innerRadius).toFloat() + titleHeight,
                    ),
                    kind = SceneShapeKind.Circle,
                    fill = TRANSPARENT,
                    stroke = DEBUG_CIRCLE_COLOR,
                    strokeWidth = 1.5f * scale,
                    strokePattern = SceneStrokePattern.Dashed,
                    dashIntervals = listOf(6f * scale, 4f * scale),
                    zIndex = elements.size + 1,
                )
            }
            val innerWidth = max(80.0 * scale, innerRadius * 2.0 * 0.95)
            val innerHeight = max(60.0 * scale, innerRadius * 2.0 * 0.95)
            val hasLabel = !area.data.label.isNullOrEmpty()
            val baseOffset = if (hasLabel) min(32.0 * scale, innerRadius * 0.25) else 0.0
            val labelOffset = baseOffset + if (nodes.size <= 2) 30.0 * scale else 0.0
            val startX = centerX - innerWidth / 2.0
            val startY = centerY - innerHeight / 2.0 + labelOffset
            val columns = max(1, ceil(sqrt(nodes.size.toDouble())).toInt())
            val rows = max(1, ceil(nodes.size.toDouble() / columns).toInt())
            val cellWidth = innerWidth / columns
            val cellHeight = innerHeight / rows
            nodes.forEachIndexed { index, node ->
                val column = index % columns
                val row = floor(index.toDouble() / columns).toInt()
                val x = startX + cellWidth * (column + 0.5)
                val y = startY + cellHeight * (row + 0.5)
                if (debug) {
                    elements += SceneShape(
                        id = "venn-text-debug-cell-${elements.size}",
                        bounds = SceneRect(
                            left = (startX + cellWidth * column).toFloat(),
                            top = (startY + cellHeight * row).toFloat() + titleHeight,
                            right = (startX + cellWidth * (column + 1)).toFloat(),
                            bottom = (startY + cellHeight * (row + 1)).toFloat() + titleHeight,
                        ),
                        kind = SceneShapeKind.Rectangle,
                        fill = TRANSPARENT,
                        stroke = DEBUG_CELL_COLOR,
                        strokeWidth = scale,
                        strokePattern = SceneStrokePattern.Dashed,
                        dashIntervals = listOf(4f * scale, 3f * scale),
                        cornerRadius = 0f,
                        zIndex = elements.size + 1,
                    )
                }
                val boxWidth = cellWidth * 0.9
                val boxHeight = cellHeight * 0.9
                val fontSize = TEXT_NODE_FONT_SIZE * scale
                elements += SceneText(
                    text = wrapTextNode(
                        label = node.label ?: node.id,
                        width = boxWidth.toFloat(),
                        fontSize = fontSize,
                        context = context,
                    ),
                    bounds = SceneRect(
                        left = (x - boxWidth / 2.0).toFloat(),
                        top = (y - boxHeight / 2.0).toFloat() + titleHeight,
                        right = (x + boxWidth / 2.0).toFloat(),
                        bottom = (y + boxHeight / 2.0).toFloat() + titleHeight,
                    ),
                    color = styles[node.id]?.color ?: defaultTextColor,
                    fontSize = fontSize,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                    horizontalAlignment = SceneTextAlignment.Center,
                    softWrap = false,
                    clipToBounds = false,
                    zIndex = elements.size + 1,
                )
            }
        }
    }

    private fun areaPath(
        id: String,
        area: VennLayoutArea,
        titleHeight: Float,
        fill: SceneColor,
        stroke: SceneColor,
        strokeWidth: Float,
        zIndex: Int,
    ): ScenePath {
        val points = sampleArea(area.arcs).map { point ->
            ScenePoint(point.x.toFloat(), point.y.toFloat() + titleHeight)
        }
        val commands = if (points.isEmpty()) {
            listOf(ScenePathCommand.MoveTo(ScenePoint(0f, titleHeight)))
        } else {
            buildList<ScenePathCommand> {
                add(ScenePathCommand.MoveTo(points.first()))
                points.drop(1).forEach { point -> add(ScenePathCommand.LineTo(point)) }
            }
        }
        return ScenePath(
            id = id,
            points = points,
            commands = commands,
            color = stroke,
            strokeWidth = strokeWidth,
            look = "classic",
            animated = false,
            fillColor = fill,
            closed = points.isNotEmpty(),
            zIndex = zIndex,
        )
    }

    private fun sampleArea(arcs: List<VennArc>): List<VennPoint> {
        if (arcs.isEmpty()) {
            return emptyList()
        }
        if (arcs.size == 1 && arcs.first().width >= arcs.first().circle.radius * 2.0 - 1e-6) {
            val circle = arcs.first().circle
            return (0 until FULL_CIRCLE_SEGMENTS).map { index ->
                val angle = PI + 2.0 * PI * index / FULL_CIRCLE_SEGMENTS
                VennPoint(
                    x = circle.x + circle.radius * kotlin.math.cos(angle),
                    y = circle.y + circle.radius * kotlin.math.sin(angle),
                )
            }
        }
        val points = mutableListOf<VennPoint>()
        arcs.forEachIndexed { arcIndex, arc ->
            val start = atan2(
                arc.p2.y - arc.circle.y,
                arc.p2.x - arc.circle.x,
            )
            var end = atan2(
                arc.p1.y - arc.circle.y,
                arc.p1.x - arc.circle.x,
            )
            while (end < start) {
                end += 2.0 * PI
            }
            var sweep = end - start
            if (arc.large && sweep < PI) {
                sweep += 2.0 * PI
            } else if (!arc.large && sweep > PI) {
                sweep -= 2.0 * PI
            }
            val segments = max(1, ceil(kotlin.math.abs(sweep) / MAX_ARC_STEP).toInt())
            for (index in if (arcIndex == 0) 0..segments else 1..segments) {
                val angle = start + sweep * index / segments
                points += VennPoint(
                    x = arc.circle.x + arc.circle.radius * kotlin.math.cos(angle),
                    y = arc.circle.y + arc.circle.radius * kotlin.math.sin(angle),
                )
            }
        }
        return points
    }

    private fun wrapTextNode(
        label: String,
        width: Float,
        fontSize: Float,
        context: MermaidRenderContext,
    ): String {
        val words = label.split(WHITESPACE).filter(String::isNotEmpty)
        if (words.size <= 1) {
            return label
        }
        val lines = mutableListOf<String>()
        var line = words.first()
        words.drop(1).forEach { word ->
            val candidate = "$line $word"
            val measured = context.textMetrics.measure(
                TextMetricsRequest(
                    text = candidate,
                    fontSize = fontSize,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                ),
            ).width
            if (measured > width) {
                lines += line
                line = word
            } else {
                line = candidate
            }
        }
        lines += line
        return lines.joinToString("\n")
    }

    private fun buildStyleByKey(
        styleData: List<VennStyleData>,
    ): GMResult<Map<String, VennResolvedStyle>, MermaidError> {
        val declarations = linkedMapOf<String, MutableMap<String, String>>()
        styleData.forEach { entry ->
            declarations.getOrPut(stableSetsKey(entry.targets)) { linkedMapOf() }
                .putAll(entry.styles)
        }
        val result = linkedMapOf<String, VennResolvedStyle>()
        declarations.forEach { (key, styles) ->
            val fill = when (val source = styles["fill"]) {
                null -> null
                else -> CssColorParser.parse(source)
                    ?: return invalidStyle(key, "fill", source)
            }
            val stroke = when (val source = styles["stroke"]) {
                null -> null
                else -> CssColorParser.parse(source)
                    ?: return invalidStyle(key, "stroke", source)
            }
            val color = when (val source = styles["color"]) {
                null -> null
                else -> CssColorParser.parse(source)
                    ?: return invalidStyle(key, "color", source)
            }
            val opacity = when (val source = styles["fill-opacity"]) {
                null -> null
                else -> source.toFloatOrNull()
                    ?.takeIf(Float::isFinite)
                    ?.coerceIn(0f, 1f)
                    ?: return invalidStyle(key, "fill-opacity", source)
            }
            val strokeWidth = when (val source = styles["stroke-width"]) {
                null -> null
                else -> source.removeSuffix("px").trim().toFloatOrNull()
                    ?.takeIf { value -> value.isFinite() && value >= 0f }
                    ?: return invalidStyle(key, "stroke-width", source)
            }
            result[key] = VennResolvedStyle(
                fill = fill,
                color = color,
                stroke = stroke,
                strokeWidth = strokeWidth,
                fillOpacity = opacity,
            )
        }
        return GMResult.Ok(result)
    }

    private fun vennPalette(context: MermaidRenderContext): List<SceneColor> {
        val overrides = (1..8).mapNotNull { index ->
            context.options.themeVariables["venn$index"]?.let(CssColorParser::parse)
        }
        if (overrides.isNotEmpty()) {
            return overrides
        }
        if (context.theme.borderColorArray.isNotEmpty()) {
            return List(8) { index ->
                context.theme.borderColorArray[index % context.theme.borderColorArray.size]
            }
        }
        return when (context.options.themeName?.lowercase()) {
            "neo", "neo-dark", "redux", "redux-dark" -> emptyList()
            else -> context.theme.mindmap.sectionFills.take(8)
        }
    }

    private fun themeColor(
        context: MermaidRenderContext,
        key: String,
        fallback: SceneColor,
    ): SceneColor = context.options.themeVariables[key]
        ?.let(CssColorParser::parse)
        ?: fallback

    private fun areaLabel(data: VennData): String =
        data.label ?: data.sets.singleOrNull().orEmpty()

    private fun stableSetsKey(setIds: List<String>): String = setIds.joinToString("|")

    private fun SceneColor.withOpacity(opacity: Float): SceneColor {
        val alpha = ((argb ushr 24) and 0xFF).toInt()
        val resolved = (alpha * opacity.coerceIn(0f, 1f)).roundToInt()
        return SceneColor((argb and 0x00FFFFFFL) or (resolved.toLong() shl 24))
    }

    private fun isDark(color: SceneColor): Boolean {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value > 0.03928) {
                ((value + 0.055) / 1.055).pow(2.4)
            } else {
                value / 12.92
            }
        }
        val red = ((color.argb ushr 16) and 0xFF).toInt()
        val green = ((color.argb ushr 8) and 0xFF).toInt()
        val blue = (color.argb and 0xFF).toInt()
        val luminance = 0.2126 * linear(red) + 0.7152 * linear(green) + 0.0722 * linear(blue)
        return luminance < 0.5
    }

    private fun <T> invalidStyle(
        target: String,
        property: String,
        value: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Configuration(
            "Mermaid Venn style '$property' has invalid value '$value' on '$target'",
        ),
    )

    private fun <T> configurationError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid Venn $message"))

    private data class VennResolvedStyle(
        val fill: SceneColor? = null,
        val color: SceneColor? = null,
        val stroke: SceneColor? = null,
        val strokeWidth: Float? = null,
        val fillOpacity: Float? = null,
    )

    private companion object {
        const val REFERENCE_WIDTH = 1600f
        const val TITLE_HEIGHT = 48f
        const val TITLE_FONT_SIZE = 32f
        const val TITLE_Y = 32f
        // Compose's Trebuchet metrics need a narrower scale than the shared
        // Canvas default to match Mermaid's browser SVG title measurement.
        const val VENN_TITLE_HORIZONTAL_SCALE = 1.0524f
        const val VENN_TITLE_METRIC_CORRECTION = 0.99284f
        const val AREA_FONT_SIZE = 48f
        const val TEXT_NODE_FONT_SIZE = 40f
        const val AREA_LINE_HEIGHT = 1.1f
        const val CIRCLE_FILL_OPACITY = 0.1f
        const val CIRCLE_STROKE_OPACITY = 0.95f
        const val CIRCLE_STROKE_WIDTH = 5f
        const val TEXT_CONTRAST_AMOUNT = 30.0
        const val VENN_JS_DEFAULT_PADDING = 15.0
        const val VIEWBOX_PADDING = 12f
        const val FULL_CIRCLE_SEGMENTS = 96
        const val MAX_ARC_STEP = PI / 24.0
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        val WHITESPACE = Regex("""\s+""")
        val TRANSPARENT = SceneColor(0x00000000)
        val DEBUG_CIRCLE_COLOR = SceneColor(0xFF800080)
        val DEBUG_CELL_COLOR = SceneColor(0xFF008080)
    }
}
