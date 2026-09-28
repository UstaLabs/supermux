package com.swithun.cmpmermaid.core.treemap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTreemapOptions
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricsRequest
import com.swithun.cmpmermaid.core.flowchart.FlowNodeStyle
import com.swithun.cmpmermaid.core.flowchart.FlowStyleAdapter
import com.swithun.cmpmermaid.core.mermaidDarken
import com.swithun.cmpmermaid.core.mermaidLighten
import com.swithun.cmpmermaid.core.treemap.upstream.d3.D3NumberFormat
import com.swithun.cmpmermaid.core.treemap.upstream.d3.D3TreemapLayout
import com.swithun.cmpmermaid.core.treemap.upstream.d3.D3TreemapNode
import com.swithun.cmpmermaid.core.treemap.upstream.mermaid.TreemapDb
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/treemap/renderer.ts and styles.ts.
 */
internal class TreemapLayout {
    fun layout(
        db: TreemapDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        val config = db.config
        when (val validation = validate(config)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }
        val width = config.nodeWidth * SECTION_INNER_PADDING
        val height = config.nodeHeight * SECTION_INNER_PADDING
        val titleHeight = if (db.diagramTitle.isNullOrEmpty()) 0f else TITLE_HEIGHT
        val valueFormatter = treemapValueFormatter(config.valueFormat)
        val root = D3TreemapLayout.layout(
            data = db.getRoot(),
            width = width.toDouble(),
            height = height.toDouble(),
            paddingInner = config.padding.toDouble(),
        )
        val colors = TreemapOrdinalColors(context)
        val elements = mutableListOf<SceneElement>()
        db.diagramTitle?.takeIf(String::isNotEmpty)?.let { title ->
            val metrics = context.textMetrics.measure(
                TextMetricsRequest(
                    text = title,
                    fontSize = TITLE_FONT_SIZE,
                    maxWidth = UNWRAPPED_TEXT_WIDTH,
                    fontFamily = context.theme.fontFamily,
                    weight = SceneTextWeight.Normal,
                ),
            )
            elements += SceneText(
                text = title,
                bounds = SceneRect(
                    left = width / 2f - metrics.width / 2f,
                    top = titleHeight / 2f - metrics.height / 2f,
                    right = width / 2f + metrics.width / 2f,
                    bottom = titleHeight / 2f + metrics.height / 2f,
                ),
                color = context.theme.textColor,
                fontSize = TITLE_FONT_SIZE,
                fontFamily = context.theme.fontFamily,
                weight = SceneTextWeight.Normal,
                horizontalAlignment = SceneTextAlignment.Center,
                softWrap = false,
                zIndex = elements.size + 1,
            )
        }
        val branches = root.descendants().filter { node ->
            node.children?.isNotEmpty() == true
        }
        branches.forEachIndexed { index, node ->
            if (node.depth == 0) {
                colors.fill(node.data.name)
                colors.peer(node.data.name)
                return@forEachIndexed
            }
            val style = when (val parsed = style(node, "Treemap section '${node.data.name}'")) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val bounds = node.bounds(titleHeight)
            val fill = (style.fill ?: colors.fill(node.data.name)).withOpacity(
                SECTION_FILL_OPACITY,
            )
            val stroke = (style.stroke ?: colors.peer(node.data.name)).withOpacity(
                SECTION_STROKE_OPACITY,
            )
            elements += SceneShape(
                id = "treemap-section-$index",
                bounds = bounds,
                kind = SceneShapeKind.Rectangle,
                fill = fill,
                stroke = stroke,
                strokeWidth = style.strokeWidth ?: SECTION_STROKE_WIDTH,
                strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
                dashIntervals = style.dashIntervals,
                cornerRadius = 0f,
                zIndex = elements.size + 1,
            )
            drawSectionText(
                node = node,
                bounds = bounds,
                style = style,
                colors = colors,
                config = config,
                valueFormatter = valueFormatter,
                context = context,
                elements = elements,
            )
        }

        val leaves = root.leaves()
        val leafSizing = LeafSizing(complex = leaves.size > COMPLEX_LEAF_COUNT)
        leaves.forEachIndexed { index, node ->
            val style = when (val parsed = style(node, "Treemap leaf '${node.data.name}'")) {
                is GMResult.Ok -> parsed.value
                is GMResult.Err -> return parsed
            }
            val colorKey = node.parent?.data?.name ?: node.data.name
            val bounds = node.bounds(titleHeight)
            val inherited = colors.fill(colorKey)
            elements += SceneShape(
                id = "treemap-leaf-$index",
                bounds = bounds,
                kind = SceneShapeKind.Rectangle,
                fill = (style.fill ?: inherited).withOpacity(LEAF_FILL_OPACITY),
                stroke = style.stroke ?: inherited,
                strokeWidth = style.strokeWidth ?: LEAF_STROKE_WIDTH,
                strokePattern = style.strokePattern ?: SceneStrokePattern.Solid,
                dashIntervals = style.dashIntervals,
                cornerRadius = 0f,
                zIndex = elements.size + 1,
            )
            drawLeafText(
                node = node,
                bounds = bounds,
                style = style,
                colors = colors,
                sizing = leafSizing,
                showValues = config.showValues,
                valueFormatter = valueFormatter,
                context = context,
                elements = elements,
            )
        }
        return GMResult.Ok(normalizeScene(elements, db, config, context))
    }

    private fun drawSectionText(
        node: D3TreemapNode,
        bounds: SceneRect,
        style: FlowNodeStyle,
        colors: TreemapOrdinalColors,
        config: MermaidTreemapOptions,
        valueFormatter: (Double) -> String,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val valueText = if (config.showValues && jsTruthy(node.value)) {
            valueFormatter(node.value)
        } else {
            ""
        }
        val valueReservation = if (valueText.isNotEmpty()) {
            ESTIMATED_SECTION_VALUE_WIDTH + SECTION_LABEL_VALUE_GAP
        } else {
            0f
        }
        val availableWidth = max(
            MIN_SECTION_LABEL_WIDTH,
            bounds.width -
                SECTION_LABEL_X -
                SECTION_LABEL_RIGHT_PADDING -
                valueReservation,
        )
        val labelSize = style.fontSize ?: SECTION_LABEL_FONT_SIZE
        val label = truncateToWidth(
            text = node.data.name,
            availableWidth = availableWidth,
            fontSize = labelSize,
            fontFamily = style.fontFamily ?: context.theme.fontFamily,
            weight = style.fontWeight ?: SceneTextWeight.Bold,
            context = context,
        )
        elements += SceneText(
            text = label,
            bounds = SceneRect(
                left = bounds.left + SECTION_LABEL_X - TEXT_ALIGNMENT_INSET,
                top = bounds.top,
                right = bounds.left + SECTION_LABEL_X + availableWidth - TEXT_ALIGNMENT_INSET,
                bottom = bounds.top + SECTION_HEADER_HEIGHT,
            ),
            color = style.text ?: colors.label(node.data.name),
            fontSize = labelSize,
            fontFamily = style.fontFamily ?: context.theme.fontFamily,
            weight = style.fontWeight ?: SceneTextWeight.Bold,
            italic = style.italic ?: false,
            horizontalAlignment = SceneTextAlignment.Start,
            softWrap = false,
            clipToBounds = true,
            zIndex = elements.size + 1,
        )
        if (valueText.isNotEmpty()) {
            elements += SceneText(
                text = valueText,
                bounds = SceneRect(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right - SECTION_VALUE_RIGHT_PADDING + TEXT_ALIGNMENT_INSET,
                    bottom = bounds.top + SECTION_HEADER_HEIGHT,
                ),
                color = style.text ?: colors.label(node.data.name),
                fontSize = style.fontSize ?: SECTION_VALUE_FONT_SIZE,
                fontFamily = style.fontFamily ?: context.theme.fontFamily,
                weight = style.fontWeight ?: SceneTextWeight.Normal,
                italic = style.italic ?: true,
                horizontalAlignment = SceneTextAlignment.End,
                softWrap = false,
                clipToBounds = true,
                zIndex = elements.size + 1,
            )
        }
    }

    private fun drawLeafText(
        node: D3TreemapNode,
        bounds: SceneRect,
        style: FlowNodeStyle,
        colors: TreemapOrdinalColors,
        sizing: LeafSizing,
        showValues: Boolean,
        valueFormatter: (Double) -> String,
        context: MermaidRenderContext,
        elements: MutableList<SceneElement>,
    ) {
        val availableWidth = bounds.width - 2f * sizing.labelPadding
        val availableHeight = bounds.height - 2f * sizing.labelPadding
        if (
            availableWidth < sizing.minDisplayThreshold ||
            availableHeight < sizing.minDisplayThreshold
        ) {
            return
        }
        var labelFontSize = style.fontSize ?: sizing.baseLabelFontSize
        val fontFamily = style.fontFamily ?: context.theme.fontFamily
        val weight = style.fontWeight ?: SceneTextWeight.Normal
        while (
            measureWidth(node.data.name, labelFontSize, fontFamily, weight, context) >
            availableWidth &&
            labelFontSize > sizing.minLabelFontSize
        ) {
            labelFontSize -= 1f
        }
        var valueFontSize = sizing.valueFontSize(labelFontSize)
        var combinedHeight = labelFontSize + sizing.spacingBetweenLabelAndValue + valueFontSize
        while (combinedHeight > availableHeight && labelFontSize > sizing.minLabelFontSize) {
            labelFontSize -= 1f
            valueFontSize = sizing.valueFontSize(labelFontSize)
            combinedHeight = labelFontSize + sizing.spacingBetweenLabelAndValue + valueFontSize
        }
        val labelWidth = measureWidth(
            node.data.name,
            labelFontSize,
            fontFamily,
            weight,
            context,
        )
        val labelVisible = if (sizing.complex) {
            labelFontSize >= sizing.minLabelFontSize &&
                availableHeight >= sizing.minLabelFontSize
        } else {
            labelWidth <= availableWidth &&
                labelFontSize >= sizing.minLabelFontSize &&
                availableHeight >= labelFontSize
        }
        if (!labelVisible) {
            return
        }
        val labelColor = style.text ?: colors.label(node.data.name)
        elements += SceneText(
            text = node.data.name,
            bounds = SceneRect(
                left = bounds.left + sizing.labelPadding,
                top = bounds.center.y - labelFontSize / 2f,
                right = bounds.right - sizing.labelPadding,
                bottom = bounds.center.y + labelFontSize / 2f,
            ),
            color = labelColor,
            fontSize = labelFontSize,
            fontFamily = fontFamily,
            weight = weight,
            italic = style.italic ?: false,
            horizontalAlignment = style.textAlignment ?: SceneTextAlignment.Center,
            softWrap = false,
            clipToBounds = true,
            zIndex = elements.size + 1,
        )
        if (!showValues || !jsTruthy(node.value)) {
            return
        }
        val valueText = valueFormatter(node.value)
        val valueTop = bounds.center.y +
            labelFontSize / 2f +
            sizing.spacingBetweenLabelAndValue
        val maxValueBottom = bounds.bottom - LEAF_VALUE_BOTTOM_PADDING
        val valueWidth = measureWidth(
            text = valueText,
            fontSize = valueFontSize,
            fontFamily = fontFamily,
            weight = weight,
            context = context,
        )
        if (
            valueWidth > availableWidth ||
            valueTop + valueFontSize > maxValueBottom ||
            valueFontSize < sizing.minValueFontSize
        ) {
            return
        }
        elements += SceneText(
            text = valueText,
            bounds = SceneRect(
                left = bounds.left + sizing.labelPadding,
                top = valueTop,
                right = bounds.right - sizing.labelPadding,
                bottom = valueTop + valueFontSize,
            ),
            color = labelColor,
            fontSize = valueFontSize,
            fontFamily = fontFamily,
            weight = weight,
            italic = style.italic ?: false,
            horizontalAlignment = style.textAlignment ?: SceneTextAlignment.Center,
            softWrap = false,
            clipToBounds = true,
            zIndex = elements.size + 1,
        )
    }

    private fun style(
        node: D3TreemapNode,
        owner: String,
    ): GMResult<FlowNodeStyle, MermaidError> =
        FlowStyleAdapter.parse(node.data.cssCompiledStyles.orEmpty(), owner)

    private fun truncateToWidth(
        text: String,
        availableWidth: Float,
        fontSize: Float,
        fontFamily: String,
        weight: SceneTextWeight,
        context: MermaidRenderContext,
    ): String {
        if (measureWidth(text, fontSize, fontFamily, weight, context) <= availableWidth) {
            return text
        }
        var length = text.length
        while (length > 0) {
            length -= 1
            val candidate = text.substring(0, length) + ELLIPSIS
            if (measureWidth(candidate, fontSize, fontFamily, weight, context) <= availableWidth) {
                return candidate
            }
        }
        return ELLIPSIS.takeIf {
            measureWidth(it, fontSize, fontFamily, weight, context) <= availableWidth
        }.orEmpty()
    }

    private fun measureWidth(
        text: String,
        fontSize: Float,
        fontFamily: String,
        weight: SceneTextWeight,
        context: MermaidRenderContext,
    ): Float = context.textMetrics.measure(
        TextMetricsRequest(
            text = text,
            fontSize = fontSize,
            maxWidth = UNWRAPPED_TEXT_WIDTH,
            fontFamily = fontFamily,
            weight = weight,
        ),
    ).width

    private fun treemapValueFormatter(source: String): (Double) -> String {
        val requested = when {
            source == "\$0,0" -> D3NumberFormat.formatter(",")?.let { format ->
                { value: Double -> "$" + format(value) }
            }
            source.startsWith('$') && source.contains(',') -> {
                val precision = PRECISION.find(source)?.value.orEmpty()
                D3NumberFormat.formatter(",$precision")?.let { format ->
                    { value: Double -> "$" + format(value) }
                }
            }
            source.startsWith('$') -> {
                val rest = source.drop(1)
                D3NumberFormat.formatter(rest)?.let { format ->
                    { value: Double -> "$" + format(value) }
                }
            }
            else -> D3NumberFormat.formatter(source)
        }
        return requested ?: D3NumberFormat.formatter(",") ?: { value -> value.toString() }
    }

    private fun validate(config: MermaidTreemapOptions): GMResult<Unit, MermaidError> {
        listOf(
            "nodeWidth" to config.nodeWidth,
            "nodeHeight" to config.nodeHeight,
        ).firstOrNull { (_, value) -> !value.isFinite() || value <= 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be positive")
        }
        listOf(
            "padding" to config.padding,
            "diagramPadding" to config.diagramPadding,
            "borderWidth" to config.borderWidth,
            "valueFontSize" to config.valueFontSize,
            "labelFontSize" to config.labelFontSize,
        ).firstOrNull { (_, value) -> !value.isFinite() || value < 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be non-negative")
        }
        return GMResult.Ok(Unit)
    }

    private fun configurationError(message: String): GMResult.Err<MermaidError> =
        GMResult.Err(MermaidError.Configuration("Mermaid Treemap $message"))

    private fun normalizeScene(
        elements: List<SceneElement>,
        db: TreemapDb,
        config: MermaidTreemapOptions,
        context: MermaidRenderContext,
    ): MermaidScene {
        val bounds = elements.mapNotNull { element ->
            when (element) {
                is SceneShape -> element.bounds
                is SceneText -> element.bounds
                else -> null
            }
        }.reduceOrNull(SceneRect::union) ?: SceneRect(0f, 0f, 1f, 1f)
        return MermaidScene(
            width = bounds.width.coerceAtLeast(1f),
            height = bounds.height.coerceAtLeast(1f),
            background = context.theme.background,
            elements = elements.map { element ->
                when (element) {
                    is SceneShape ->
                        element.copy(bounds = element.bounds.translate(-bounds.left, -bounds.top))
                    is SceneText ->
                        element.copy(bounds = element.bounds.translate(-bounds.left, -bounds.top))
                    else -> element
                }
            },
            title = db.diagramTitle?.takeIf(String::isNotEmpty),
            accessibilityTitle = db.accessibilityTitle,
            accessibilityDescription = db.accessibilityDescription,
            viewportPadding = config.diagramPadding,
            viewportSizing = if (config.useMaxWidth) {
                MermaidSceneViewportSizing.ResponsiveMaxWidth
            } else {
                MermaidSceneViewportSizing.Intrinsic
            },
        )
    }

    private fun D3TreemapNode.bounds(titleHeight: Float): SceneRect = SceneRect(
        left = x0.toFloat(),
        top = y0.toFloat() + titleHeight,
        right = x1.toFloat(),
        bottom = y1.toFloat() + titleHeight,
    )

    private fun SceneColor.withOpacity(opacity: Float): SceneColor {
        val sourceAlpha = ((argb ushr 24) and 0xFF).toInt()
        val alpha = (sourceAlpha * opacity.coerceIn(0f, 1f)).roundToInt()
        return SceneColor((argb and 0x00FFFFFFL) or (alpha.toLong() shl 24))
    }

    private fun jsTruthy(value: Double): Boolean = value != 0.0 && !value.isNaN()

    private class TreemapOrdinalColors(
        private val context: MermaidRenderContext,
    ) {
        private val fillDomain = linkedMapOf<String, Int>()
        private val peerDomain = linkedMapOf<String, Int>()
        private val labelDomain = linkedMapOf<String, Int>()

        fun fill(key: String): SceneColor {
            val index = fillDomain.getOrPut(key) { fillDomain.size }
            if (index == 0) {
                return TRANSPARENT
            }
            return context.theme.mindmap.sectionFills.getOrElse((index - 1) % PALETTE_SIZE) {
                context.theme.groupFill
            }
        }

        fun peer(key: String): SceneColor {
            val index = peerDomain.getOrPut(key) { peerDomain.size }
            if (index == 0) {
                return TRANSPARENT
            }
            val fill = context.theme.mindmap.sectionFills.getOrElse((index - 1) % PALETTE_SIZE) {
                context.theme.groupStroke
            }
            return if (isDark(context.theme.background)) {
                fill.mermaidLighten(10.0)
            } else {
                fill.mermaidDarken(10.0)
            }
        }

        fun label(key: String): SceneColor {
            val index = labelDomain.getOrPut(key) { labelDomain.size }
            return context.theme.mindmap.sectionLabelColors.getOrElse(index % PALETTE_SIZE) {
                context.theme.textColor
            }
        }

        private fun isDark(color: SceneColor): Boolean {
            val red = ((color.argb ushr 16) and 0xFF).toDouble()
            val green = ((color.argb ushr 8) and 0xFF).toDouble()
            val blue = (color.argb and 0xFF).toDouble()
            return red * 0.299 + green * 0.587 + blue * 0.114 < 128.0
        }
    }

    private data class LeafSizing(
        val complex: Boolean,
    ) {
        val baseLabelFontSize: Float = if (complex) 16f else 38f
        val baseValueFontSize: Float = if (complex) 14f else 28f
        val minLabelFontSize: Float = if (complex) 4f else 8f
        val minValueFontSize: Float = if (complex) 4f else 6f
        val labelPadding: Float = if (complex) 2f else 4f
        val minDisplayThreshold: Float = if (complex) 8f else 10f
        val spacingBetweenLabelAndValue: Float = if (complex) 1f else 2f

        fun valueFontSize(labelFontSize: Float): Float =
            max(
                minValueFontSize,
                min(baseValueFontSize, (labelFontSize * VALUE_SCALE_FACTOR).roundToInt().toFloat()),
            )
    }

    private companion object {
        val TRANSPARENT = SceneColor(0x00000000)
        val PRECISION = Regex("""\.\d+""")
        const val SECTION_INNER_PADDING = 10f
        const val SECTION_HEADER_HEIGHT = 25f
        const val TITLE_HEIGHT = 30f
        const val TITLE_FONT_SIZE = 14f
        const val SECTION_LABEL_FONT_SIZE = 12f
        const val SECTION_VALUE_FONT_SIZE = 10f
        const val SECTION_LABEL_X = 6f
        const val SECTION_LABEL_RIGHT_PADDING = 6f
        const val SECTION_VALUE_RIGHT_PADDING = 10f
        const val SECTION_LABEL_VALUE_GAP = 10f
        const val ESTIMATED_SECTION_VALUE_WIDTH = 30f
        const val MIN_SECTION_LABEL_WIDTH = 15f
        const val SECTION_FILL_OPACITY = 0.6f
        const val SECTION_STROKE_OPACITY = 0.4f
        const val SECTION_STROKE_WIDTH = 2f
        const val LEAF_FILL_OPACITY = 0.3f
        const val LEAF_STROKE_WIDTH = 3f
        const val LEAF_VALUE_BOTTOM_PADDING = 4f
        const val VALUE_SCALE_FACTOR = 0.6f
        const val COMPLEX_LEAF_COUNT = 20
        const val PALETTE_SIZE = 12
        const val TEXT_ALIGNMENT_INSET = 4f
        const val UNWRAPPED_TEXT_WIDTH = 100_000f
        const val ELLIPSIS = "..."
    }
}
