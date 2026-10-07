package com.swithun.cmpmermaid.core.cynefin

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidCynefinOptions
import com.swithun.cmpmermaid.core.MermaidError
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
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinBoundaries
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinBoundaryPath
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinDb
import com.swithun.cmpmermaid.core.cynefin.upstream.mermaid.CynefinDomainName
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/cynefin/cynefinRenderer.ts -> draw.
 */
internal class CynefinLayout {
    fun layout(
        db: CynefinDb,
        context: MermaidRenderContext,
    ): GMResult<MermaidScene, MermaidError> {
        when (val validation = validate(db.config, context.theme)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return validation
        }

        val config = db.config
        val padding = config.padding
        val totalWidth = config.width + padding * 2f
        val totalHeight = config.height + padding * 2f
        if (!totalWidth.isFinite() || !totalHeight.isFinite()) {
            return configurationError("viewport dimensions must be finite")
        }

        val layouts = domainLayouts(config.width, config.height)
        val elements = mutableListOf<SceneElement>()
        drawBackgrounds(elements, layouts, padding, context)
        drawBoundaries(elements, db, padding, context)
        drawLabels(elements, layouts, config, padding, context)
        drawItems(elements, db, layouts, padding, context)
        drawTransitions(elements, db, layouts, padding, context)
        drawTitle(elements, db.diagramTitle, config, padding, context)

        val scene = MermaidScene(
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
        )
        return GMResult.Ok(normalizeViewport(scene))
    }

    /**
     * Mermaid.js 12.0.0: cynefinRenderer.ts -> fixed viewBox.
     * Official reference isolation: official-mermaid.html -> normalizeViewBox.
     *
     * The reference unions the renderer viewBox with SVG getBBox(), then adds
     * 12 px on every side. This preserves long item labels outside the
     * configured Cynefin width.
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

    // Mermaid.js 12.0.0: cynefinRenderer.ts -> getDomainLayouts.
    private fun domainLayouts(
        width: Float,
        height: Float,
    ): Map<CynefinDomainName, DomainLayout> {
        val halfWidth = width / 2f
        val halfHeight = height / 2f
        return mapOf(
            CynefinDomainName.Complex to DomainLayout(
                center = ScenePoint(halfWidth / 2f, halfHeight / 2f),
                bounds = SceneRect(0f, 0f, halfWidth, halfHeight),
            ),
            CynefinDomainName.Complicated to DomainLayout(
                center = ScenePoint(halfWidth + halfWidth / 2f, halfHeight / 2f),
                bounds = SceneRect(halfWidth, 0f, width, halfHeight),
            ),
            CynefinDomainName.Chaotic to DomainLayout(
                center = ScenePoint(halfWidth / 2f, halfHeight + halfHeight / 2f),
                bounds = SceneRect(0f, halfHeight, halfWidth, height),
            ),
            CynefinDomainName.Clear to DomainLayout(
                center = ScenePoint(
                    halfWidth + halfWidth / 2f,
                    halfHeight + halfHeight / 2f,
                ),
                bounds = SceneRect(halfWidth, halfHeight, width, height),
            ),
            CynefinDomainName.Confusion to DomainLayout(
                center = ScenePoint(halfWidth, halfHeight),
                bounds = SceneRect(
                    left = halfWidth * 0.7f,
                    top = halfHeight * 0.7f,
                    right = halfWidth * 1.3f,
                    bottom = halfHeight * 1.3f,
                ),
            ),
        )
    }

    private fun drawBackgrounds(
        elements: MutableList<SceneElement>,
        layouts: Map<CynefinDomainName, DomainLayout>,
        padding: Float,
        context: MermaidRenderContext,
    ) {
        QUADRANT_DOMAINS.forEachIndexed { index, domainName ->
            val layout = layouts.getValue(domainName)
            elements += SceneShape(
                id = "cynefin-background-${domainName.sourceName}",
                bounds = layout.bounds.translate(padding, padding),
                kind = SceneShapeKind.Rectangle,
                fill = context.theme.cynefin.background(domainName).withOpacity(0.4f),
                stroke = TRANSPARENT,
                strokeWidth = 0f,
                cornerRadius = 0f,
                zIndex = BACKGROUND_Z + index,
            )
        }
    }

    private fun drawBoundaries(
        elements: MutableList<SceneElement>,
        db: CynefinDb,
        padding: Float,
        context: MermaidRenderContext,
    ) {
        val config = db.config
        val seed = CynefinBoundaries.resolveSeed(config.seed, db.sourceSeedIdentity)
        elements += CynefinBoundaries.generateFoldPath(
            width = config.width,
            height = config.height,
            seed = seed,
            amplitudeOverride = config.boundaryAmplitude,
        ).toScenePath(
            id = "cynefin-boundary-vertical",
            color = context.theme.cynefin.boundaryColor,
            strokeWidth = context.theme.cynefin.boundaryWidth,
            padding = padding,
            strokePattern = SceneStrokePattern.Dashed,
            dashIntervals = listOf(6f, 3f),
            zIndex = BOUNDARY_Z,
        )
        elements += CynefinBoundaries.generateHorizontalBoundary(
            width = config.width,
            height = config.height,
            seed = seed + 100.0,
            amplitudeOverride = config.boundaryAmplitude,
        ).toScenePath(
            id = "cynefin-boundary-horizontal",
            color = context.theme.cynefin.boundaryColor,
            strokeWidth = context.theme.cynefin.boundaryWidth,
            padding = padding,
            strokePattern = SceneStrokePattern.Dashed,
            dashIntervals = listOf(6f, 3f),
            zIndex = BOUNDARY_Z + 1,
        )
        elements += CynefinBoundaries.generateCliffPath(
            width = config.width,
            height = config.height,
        ).toScenePath(
            id = "cynefin-cliff",
            color = context.theme.cynefin.cliffColor,
            strokeWidth = context.theme.cynefin.cliffWidth,
            padding = padding,
            zIndex = BOUNDARY_Z + 2,
        )

        val confusion = CynefinBoundaries.generateConfusionPath(
            centerX = config.width / 2f,
            centerY = config.height / 2f,
            radiusX = config.width * 0.15f,
            radiusY = config.height * 0.15f,
        )
        elements += SceneShape(
            id = "cynefin-confusion",
            bounds = SceneRect(
                left = confusion.center.x - confusion.radiusX + padding,
                top = confusion.center.y - confusion.radiusY + padding,
                right = confusion.center.x + confusion.radiusX + padding,
                bottom = confusion.center.y + confusion.radiusY + padding,
            ),
            kind = SceneShapeKind.Ellipse,
            fill = context.theme.cynefin.confusionBackground.withOpacity(0.5f),
            stroke = context.theme.cynefin.boundaryColor,
            strokeWidth = 1.5f,
            strokePattern = SceneStrokePattern.Dashed,
            dashIntervals = listOf(4f, 2f),
            zIndex = BOUNDARY_Z + 3,
        )
    }

    private fun drawLabels(
        elements: MutableList<SceneElement>,
        layouts: Map<CynefinDomainName, DomainLayout>,
        config: MermaidCynefinOptions,
        padding: Float,
        context: MermaidRenderContext,
    ) {
        QUADRANT_DOMAINS.forEachIndexed { index, domainName ->
            val center = layouts.getValue(domainName).center
            elements += centeredText(
                id = "cynefin-label-${domainName.sourceName}",
                value = domainName.displayName,
                x = center.x + padding,
                y = center.y + padding -
                    if (config.showDomainDescriptions) 30f else 0f,
                color = context.theme.cynefin.labelColor,
                fontSize = context.theme.cynefin.domainFontSize,
                weight = SceneTextWeight.Bold,
                zIndex = LABEL_Z + index,
                context = context,
            )
        }
        val confusionCenter = layouts.getValue(CynefinDomainName.Confusion).center
        elements += centeredText(
            id = "cynefin-label-confusion",
            value = CynefinDomainName.Confusion.displayName,
            x = confusionCenter.x + padding,
            y = confusionCenter.y + padding -
                if (config.showDomainDescriptions) 10f else 0f,
            color = context.theme.cynefin.labelColor,
            fontSize = context.theme.cynefin.domainFontSize,
            weight = SceneTextWeight.Bold,
            zIndex = LABEL_Z + QUADRANT_DOMAINS.size,
            context = context,
        )

        if (!config.showDomainDescriptions) {
            return
        }
        QUADRANT_DOMAINS.forEachIndexed { index, domainName ->
            val center = layouts.getValue(domainName).center
            val metadata = DOMAIN_METADATA.getValue(domainName)
            elements += centeredText(
                id = "cynefin-model-${domainName.sourceName}",
                value = metadata.model,
                x = center.x + padding,
                y = center.y + padding - 10f,
                color = context.theme.cynefin.textColor,
                fontSize = context.theme.cynefin.itemFontSize - 1f,
                italic = true,
                zIndex = SUBTITLE_Z + index * 2,
                context = context,
            )
            elements += centeredText(
                id = "cynefin-practice-${domainName.sourceName}",
                value = metadata.practice,
                x = center.x + padding,
                y = center.y + padding + 5f,
                color = context.theme.cynefin.textColor,
                fontSize = context.theme.cynefin.itemFontSize - 1f,
                italic = true,
                zIndex = SUBTITLE_Z + index * 2 + 1,
                context = context,
            )
        }
        elements += centeredText(
            id = "cynefin-practice-confusion",
            value = DOMAIN_METADATA.getValue(CynefinDomainName.Confusion).practice,
            x = confusionCenter.x + padding,
            y = confusionCenter.y + padding + 8f,
            color = context.theme.cynefin.textColor,
            fontSize = context.theme.cynefin.itemFontSize - 1f,
            italic = true,
            zIndex = SUBTITLE_Z + QUADRANT_DOMAINS.size * 2,
            context = context,
        )
    }

    private fun drawItems(
        elements: MutableList<SceneElement>,
        db: CynefinDb,
        layouts: Map<CynefinDomainName, DomainLayout>,
        padding: Float,
        context: MermaidRenderContext,
    ) {
        ALL_DOMAINS.forEachIndexed { domainIndex, domainName ->
            val domain = db.getDomains()[domainName] ?: return@forEachIndexed
            if (domain.items.isEmpty()) {
                return@forEachIndexed
            }
            val layout = layouts.getValue(domainName)
            val confusion = domainName == CynefinDomainName.Confusion
            val items = if (confusion) {
                domain.items.take(MAX_CONFUSION_ITEMS)
            } else {
                domain.items
            }
            val overflowCount = if (confusion) {
                domain.items.size - items.size
            } else {
                0
            }
            val startY = layout.center.y + if (confusion) {
                if (db.config.showDomainDescriptions) 22f else 14f
            } else {
                if (db.config.showDomainDescriptions) 25f else 15f
            }
            items.forEachIndexed { itemIndex, item ->
                drawBadge(
                    elements = elements,
                    id = "cynefin-item-${domainName.sourceName}-$itemIndex",
                    value = item.label,
                    centerX = layout.center.x + padding,
                    top = startY + itemIndex * ITEM_STEP + padding,
                    fill = context.theme.cynefin.background(domainName).withOpacity(0.95f),
                    domainIndex = domainIndex,
                    itemIndex = itemIndex,
                    overflow = false,
                    context = context,
                )
            }
            if (overflowCount > 0) {
                drawBadge(
                    elements = elements,
                    id = "cynefin-item-confusion-overflow",
                    value = "+$overflowCount more",
                    centerX = layout.center.x + padding,
                    top = startY + items.size * ITEM_STEP + padding,
                    fill = context.theme.cynefin.confusionBackground.withOpacity(0.6f),
                    domainIndex = domainIndex,
                    itemIndex = items.size,
                    overflow = true,
                    context = context,
                )
            }
        }
    }

    private fun drawBadge(
        elements: MutableList<SceneElement>,
        id: String,
        value: String,
        centerX: Float,
        top: Float,
        fill: SceneColor,
        domainIndex: Int,
        itemIndex: Int,
        overflow: Boolean,
        context: MermaidRenderContext,
    ) {
        val metrics = measure(
            value = value,
            fontSize = context.theme.cynefin.itemFontSize,
            weight = SceneTextWeight.Normal,
            context = context,
        )
        val width = metrics.width + ITEM_PADDING_X * 2f
        val zIndex = ITEM_Z + domainIndex * ITEM_DOMAIN_Z_STRIDE + itemIndex * 2
        elements += SceneShape(
            id = "$id-badge",
            bounds = SceneRect(
                left = centerX - width / 2f,
                top = top,
                right = centerX + width / 2f,
                bottom = top + ITEM_HEIGHT,
            ),
            kind = SceneShapeKind.RoundedRectangle,
            fill = fill,
            stroke = context.theme.cynefin.boundaryColor,
            strokeWidth = 1f,
            strokePattern = if (overflow) {
                SceneStrokePattern.Dashed
            } else {
                SceneStrokePattern.Solid
            },
            dashIntervals = if (overflow) listOf(3f, 2f) else emptyList(),
            cornerRadius = 4f,
            zIndex = zIndex,
        )
        elements += centeredText(
            id = "$id-text",
            value = value,
            x = centerX,
            y = top + ITEM_HEIGHT / 2f,
            color = context.theme.cynefin.textColor,
            fontSize = context.theme.cynefin.itemFontSize,
            weight = SceneTextWeight.Normal,
            zIndex = zIndex + 1,
            context = context,
            measured = metrics,
        )
    }

    private fun drawTransitions(
        elements: MutableList<SceneElement>,
        db: CynefinDb,
        layouts: Map<CynefinDomainName, DomainLayout>,
        padding: Float,
        context: MermaidRenderContext,
    ) {
        db.getTransitions().forEachIndexed { index, transition ->
            val start = layouts.getValue(transition.from).center.translate(padding, padding)
            val end = layouts.getValue(transition.to).center.translate(padding, padding)
            val deltaX = end.x - start.x
            val deltaY = end.y - start.y
            val length = sqrt(deltaX * deltaX + deltaY * deltaY)
            if (!length.isFinite() || length == 0f) {
                return@forEachIndexed
            }
            val middleX = (start.x + end.x) / 2f
            val middleY = (start.y + end.y) / 2f
            val offset = length * 0.15f
            val control = ScenePoint(
                x = middleX - deltaY / length * offset,
                y = middleY + deltaX / length * offset,
            )
            elements += ScenePath(
                id = "cynefin-transition-$index",
                points = listOf(start, control, end),
                commands = listOf(
                    ScenePathCommand.MoveTo(start),
                    ScenePathCommand.QuadraticTo(control = control, end = end),
                ),
                color = context.theme.cynefin.arrowColor,
                strokeWidth = context.theme.cynefin.arrowWidth,
                arrowEnd = SceneArrowHead.Triangle,
                curve = CLASSIC_LOOK,
                look = CLASSIC_LOOK,
                animated = false,
                zIndex = TRANSITION_Z + index * 2,
            )
            transition.label?.let { label ->
                elements += baselineText(
                    id = "cynefin-transition-label-$index",
                    value = label,
                    x = control.x,
                    baselineY = control.y - 6f,
                    color = context.theme.cynefin.textColor,
                    fontSize = context.theme.cynefin.itemFontSize - 1f,
                    zIndex = TRANSITION_Z + index * 2 + 1,
                    context = context,
                )
            }
        }
    }

    private fun drawTitle(
        elements: MutableList<SceneElement>,
        title: String?,
        config: MermaidCynefinOptions,
        padding: Float,
        context: MermaidRenderContext,
    ) {
        title?.takeIf(String::isNotEmpty)?.let { value ->
            val fontSize = context.theme.cynefin.domainFontSize + 2f
            val measured = measure(value, fontSize, SceneTextWeight.Bold, context)
            elements += centeredText(
                id = "cynefin-title",
                value = value,
                x = padding + config.width / 2f,
                y = padding / 2f,
                color = context.theme.cynefin.labelColor,
                fontSize = fontSize,
                weight = SceneTextWeight.Bold,
                zIndex = TITLE_Z,
                context = context,
                measured = measured,
            )
        }
    }

    private fun centeredText(
        id: String,
        value: String,
        x: Float,
        y: Float,
        color: SceneColor,
        fontSize: Float,
        weight: SceneTextWeight = SceneTextWeight.Normal,
        italic: Boolean = false,
        zIndex: Int,
        context: MermaidRenderContext,
        measured: TextMetrics = measure(value, fontSize, weight, context),
    ): SceneText {
        val width = measured.width.coerceAtLeast(1f)
        val height = measured.height.coerceAtLeast(fontSize)
        return SceneText(
            text = value,
            bounds = SceneRect(
                left = x - width / 2f,
                top = y - height / 2f,
                right = x + width / 2f,
                bottom = y + height / 2f,
            ),
            color = color,
            fontSize = fontSize,
            lineHeight = TEXT_LINE_HEIGHT,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = weight,
            italic = italic,
            horizontalAlignment = SceneTextAlignment.Center,
            softWrap = false,
            zIndex = zIndex,
        )
    }

    private fun baselineText(
        id: String,
        value: String,
        x: Float,
        baselineY: Float,
        color: SceneColor,
        fontSize: Float,
        zIndex: Int,
        context: MermaidRenderContext,
    ): SceneText {
        val measured = measure(value, fontSize, SceneTextWeight.Normal, context)
        val width = measured.width.coerceAtLeast(1f)
        val height = measured.height.coerceAtLeast(fontSize)
        return SceneText(
            text = value,
            bounds = SceneRect(
                left = x - width / 2f,
                top = baselineY - height,
                right = x + width / 2f,
                bottom = baselineY,
            ),
            color = color,
            fontSize = fontSize,
            lineHeight = TEXT_LINE_HEIGHT,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = SceneTextWeight.Normal,
            horizontalAlignment = SceneTextAlignment.Center,
            softWrap = false,
            zIndex = zIndex,
        )
    }

    private fun measure(
        value: String,
        fontSize: Float,
        weight: SceneTextWeight,
        context: MermaidRenderContext,
    ): TextMetrics = context.textMetrics.measure(
        TextMetricsRequest(
            text = value,
            fontSize = fontSize,
            maxWidth = UNWRAPPED_TEXT_WIDTH,
            lineHeight = TEXT_LINE_HEIGHT,
            fontFamily = context.options.fontFamily ?: context.theme.fontFamily,
            weight = weight,
        ),
    )

    private fun validate(
        config: MermaidCynefinOptions,
        theme: MermaidTheme,
    ): GMResult<Unit, MermaidError> {
        listOf(
            "width" to config.width,
            "height" to config.height,
        ).firstOrNull { (_, value) -> !value.isFinite() || value <= 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be positive")
        }
        if (!config.padding.isFinite() || config.padding < 0f) {
            return configurationError("padding must be non-negative")
        }
        if (
            !config.boundaryAmplitude.isFinite() ||
            config.boundaryAmplitude !in 0f..50f
        ) {
            return configurationError("boundaryAmplitude must be between 0 and 50")
        }
        if (!config.seed.isFinite()) {
            return configurationError("seed must be finite")
        }
        listOf(
            "domainFontSize" to theme.cynefin.domainFontSize,
            "itemFontSize" to theme.cynefin.itemFontSize,
        ).firstOrNull { (_, value) -> !value.isFinite() || value <= 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be positive")
        }
        listOf(
            "boundaryWidth" to theme.cynefin.boundaryWidth,
            "cliffWidth" to theme.cynefin.cliffWidth,
            "arrowWidth" to theme.cynefin.arrowWidth,
        ).firstOrNull { (_, value) -> !value.isFinite() || value < 0f }?.let { invalid ->
            return configurationError("${invalid.first} must be non-negative")
        }
        return GMResult.Ok(Unit)
    }

    private fun <T> configurationError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Configuration("Invalid Cynefin configuration: $message"))

    private fun CynefinBoundaryPath.toScenePath(
        id: String,
        color: SceneColor,
        strokeWidth: Float,
        padding: Float,
        strokePattern: SceneStrokePattern = SceneStrokePattern.Solid,
        dashIntervals: List<Float> = emptyList(),
        zIndex: Int,
    ): ScenePath = ScenePath(
        id = id,
        points = points.map { point -> point.translate(padding, padding) },
        commands = commands.map { command -> command.translate(padding, padding) },
        color = color,
        strokeWidth = strokeWidth,
        strokePattern = strokePattern,
        dashIntervals = dashIntervals,
        curve = CLASSIC_LOOK,
        look = CLASSIC_LOOK,
        animated = false,
        zIndex = zIndex,
    )

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

    private fun com.swithun.cmpmermaid.core.MermaidCynefinTheme.background(
        domainName: CynefinDomainName,
    ): SceneColor = when (domainName) {
        CynefinDomainName.Complex -> complexBackground
        CynefinDomainName.Complicated -> complicatedBackground
        CynefinDomainName.Clear -> clearBackground
        CynefinDomainName.Chaotic -> chaoticBackground
        CynefinDomainName.Confusion -> confusionBackground
    }

    private fun SceneColor.withOpacity(opacity: Float): SceneColor {
        val alpha = ((argb ushr 24) and 0xFF).toInt()
        val adjusted = (alpha * opacity.coerceIn(0f, 1f)).roundToInt()
        return SceneColor((argb and 0x00FFFFFF) or (adjusted.toLong() shl 24))
    }

    private data class DomainLayout(
        val center: ScenePoint,
        val bounds: SceneRect,
    )

    private data class DomainMetadata(
        val model: String,
        val practice: String,
    )

    companion object {
        private val TRANSPARENT = SceneColor(0x00000000)
        private const val CLASSIC_LOOK = "classic"
        private const val UNWRAPPED_TEXT_WIDTH = 100_000f
        private const val TEXT_LINE_HEIGHT = 1.2f
        private const val ITEM_HEIGHT = 26f
        private const val ITEM_PADDING_X = 10f
        private const val ITEM_STEP = ITEM_HEIGHT + 4f
        private const val MAX_CONFUSION_ITEMS = 3
        private const val VIEWBOX_PADDING = 12f
        private const val BACKGROUND_Z = 0
        private const val BOUNDARY_Z = 10
        private const val LABEL_Z = 20
        private const val SUBTITLE_Z = 30
        private const val ITEM_Z = 100
        private const val ITEM_DOMAIN_Z_STRIDE = 100
        private const val TRANSITION_Z = 1_000
        private const val TITLE_Z = 2_000

        private val QUADRANT_DOMAINS = listOf(
            CynefinDomainName.Complex,
            CynefinDomainName.Complicated,
            CynefinDomainName.Chaotic,
            CynefinDomainName.Clear,
        )
        private val ALL_DOMAINS = QUADRANT_DOMAINS + CynefinDomainName.Confusion
        private val DOMAIN_METADATA = mapOf(
            CynefinDomainName.Complex to DomainMetadata(
                model = "Probe \u2192 Sense \u2192 Respond",
                practice = "Emergent Practices",
            ),
            CynefinDomainName.Complicated to DomainMetadata(
                model = "Sense \u2192 Analyse \u2192 Respond",
                practice = "Good Practices",
            ),
            CynefinDomainName.Clear to DomainMetadata(
                model = "Sense \u2192 Categorise \u2192 Respond",
                practice = "Best Practices",
            ),
            CynefinDomainName.Chaotic to DomainMetadata(
                model = "Act \u2192 Sense \u2192 Respond",
                practice = "Novel Practices",
            ),
            CynefinDomainName.Confusion to DomainMetadata(
                model = "",
                practice = "Disorder",
            ),
        )
    }
}
