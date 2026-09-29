package com.swithun.cmpmermaid.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextGeometricTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderErrorInfo
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.MermaidSceneViewportSizing
import com.swithun.cmpmermaid.core.MermaidTheme
import com.swithun.cmpmermaid.core.SceneAffineTransform
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneAssetKind
import com.swithun.cmpmermaid.core.SceneBlendMode
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneElement
import com.swithun.cmpmermaid.core.SceneLinearGradient
import com.swithun.cmpmermaid.core.SceneNodeInteraction
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathCommand
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneShapeKind
import com.swithun.cmpmermaid.core.SceneShapePaint
import com.swithun.cmpmermaid.core.SceneShapePath
import com.swithun.cmpmermaid.core.SceneShapeViewportFit
import com.swithun.cmpmermaid.core.SceneShadow
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.SceneStrokeCap
import com.swithun.cmpmermaid.core.SceneStrokeJoin
import com.swithun.cmpmermaid.core.SceneStrokePattern
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.SceneTextAlignment
import com.swithun.cmpmermaid.core.SceneTextBaselineShift
import com.swithun.cmpmermaid.core.SceneTextFontFamily
import com.swithun.cmpmermaid.core.SceneTextWeight
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.TextMetricsRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun MermaidDiagram(
    source: String,
    modifier: Modifier = Modifier,
    theme: MermaidTheme = MermaidTheme.FlowchartDefault,
    options: MermaidRenderOptions = MermaidRenderOptions(),
    fontFamilyResolver: MermaidFontFamilyResolver? = null,
    contentDescription: String = "Mermaid diagram",
    assetProvider: MermaidAssetProvider? = null,
    onAssetError: ((SceneAsset, MermaidAssetError) -> Unit)? = null,
    onNodeInteraction: ((SceneNodeInteraction) -> Unit)? = null,
    respectSourceViewportSizing: Boolean = true,
    onRenderResult: ((GMResult<MermaidScene, MermaidError>) -> Unit)? = null,
) {
    MermaidDiagram(
        source = source,
        modifier = modifier,
        theme = theme,
        options = options,
        fontFamilyResolver = fontFamilyResolver,
        contentDescription = contentDescription,
        assetProvider = assetProvider,
        onAssetError = onAssetError,
        onNodeInteraction = onNodeInteraction,
        respectSourceViewportSizing = respectSourceViewportSizing,
        onRenderResult = onRenderResult,
        onError = null,
    )
}

@Composable
fun MermaidDiagram(
    source: String,
    modifier: Modifier = Modifier,
    theme: MermaidTheme = MermaidTheme.FlowchartDefault,
    options: MermaidRenderOptions = MermaidRenderOptions(),
    fontFamilyResolver: MermaidFontFamilyResolver? = null,
    contentDescription: String = "Mermaid diagram",
    assetProvider: MermaidAssetProvider? = null,
    onAssetError: ((SceneAsset, MermaidAssetError) -> Unit)? = null,
    onNodeInteraction: ((SceneNodeInteraction) -> Unit)? = null,
    respectSourceViewportSizing: Boolean = true,
    onError: ((MermaidRenderErrorInfo) -> Unit)?,
    onRenderResult: ((GMResult<MermaidScene, MermaidError>) -> Unit)? = null,
) {
    val platformAssetProvider = rememberPlatformMermaidAssetProvider()
    val effectiveAssetProvider = remember(assetProvider, platformAssetProvider) {
        (assetProvider ?: platformAssetProvider)?.cached()
    }
    var assetMetrics by remember(source) {
        mutableStateOf<Map<String, SceneSize>>(emptyMap())
    }
    val sceneResult = rememberMermaidScene(
        source = source,
        theme = theme,
        options = options,
        fontFamilyResolver = fontFamilyResolver,
        assetMetrics = assetMetrics,
    ).value
    val currentRenderResultHandler by rememberUpdatedState(onRenderResult)
    val currentErrorHandler by rememberUpdatedState(onError)
    LaunchedEffect(sceneResult) {
        sceneResult?.let { result ->
            if (result is GMResult.Err) {
                reportMermaidRenderError(result.error, source, currentErrorHandler)
            }
            currentRenderResultHandler?.invoke(result)
        }
    }
    when (sceneResult) {
        null -> Box(modifier = modifier)
        is GMResult.Ok -> MermaidSceneCanvasInternal(
            scene = sceneResult.value,
            renderErrorKey = source,
            modifier = modifier,
            contentDescription = contentDescription,
            fontFamilyResolver = fontFamilyResolver,
            assetProvider = effectiveAssetProvider,
            onAssetError = onAssetError,
            onAssetResolved = { asset, resolved ->
                if (asset.kind == SceneAssetKind.Image) {
                    val size = SceneSize(
                        width = resolved.intrinsicWidth.toFloat(),
                        height = resolved.intrinsicHeight.toFloat(),
                    )
                    if (assetMetrics[asset.source] != size) {
                        assetMetrics = assetMetrics + (asset.source to size)
                    }
                }
            },
            onNodeInteraction = onNodeInteraction,
            respectSourceViewportSizing = respectSourceViewportSizing,
            onRenderException = { error ->
                reportMermaidRenderError(error, source, currentErrorHandler)
            },
        )
        is GMResult.Err -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = sceneResult.error.message,
                style = TextStyle(color = Color(0xFFB91C1C), fontSize = 13.sp),
            )
        }
    }
}

internal fun reportMermaidRenderError(
    error: MermaidError,
    source: String,
    onError: ((MermaidRenderErrorInfo) -> Unit)?,
) {
    onError?.invoke(
        MermaidRenderErrorInfo(
            type = error.renderErrorType,
            message = error.message,
            source = source,
        ),
    )
}

@Composable
fun rememberMermaidScene(
    source: String,
    theme: MermaidTheme = MermaidTheme.FlowchartDefault,
    options: MermaidRenderOptions = MermaidRenderOptions(),
    engine: MermaidEngine = remember { MermaidEngine() },
    fontFamilyResolver: MermaidFontFamilyResolver? = null,
    assetMetrics: Map<String, SceneSize> = emptyMap(),
): State<GMResult<MermaidScene, com.swithun.cmpmermaid.core.MermaidError>?> {
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val effectiveFontFamilyResolver = rememberMermaidFontFamilyResolver(fontFamilyResolver)
    val monospaceFontFamily = rememberMermaidMonospaceFontFamily()
    val cjkFontFamily = if (fontFamilyResolver == null && source.hasCjkFontCharacters()) {
        rememberMermaidCjkFontFamily()
    } else {
        null
    }
    val symbolFontFamily = if (
        fontFamilyResolver == null &&
        source.hasBundledSymbolFontCharacters()
    ) {
        rememberMermaidSymbolFontFamily()
    } else {
        null
    }
    val metrics = remember(
        textMeasurer,
        density,
        effectiveFontFamilyResolver,
        monospaceFontFamily,
        cjkFontFamily,
        symbolFontFamily,
    ) {
        TextMetricProvider { request ->
            val style = request.toTextStyle(
                density = density.density,
                fontScale = density.fontScale,
                fontFamilyResolver = effectiveFontFamilyResolver,
            )
            val result = textMeasurer.measure(
                text = request.text.toAnnotatedString(
                    spans = request.spans,
                    monospaceFontFamily = monospaceFontFamily,
                    cjkFontFamily = cjkFontFamily,
                    symbolFontFamily = symbolFontFamily,
                ),
                style = style,
                softWrap = true,
                maxLines = 8,
                constraints = Constraints(
                    maxWidth = request.maxWidth.mermaidTextConstraint(request.spans),
                ),
            )
            TextMetrics(
                width = result.size.width.toFloat(),
                height = result.size.height.toFloat(),
                lineCount = result.lineCount,
            )
        }
    }
    return produceState<GMResult<MermaidScene, MermaidError>?>(
        initialValue = null,
        source,
        theme,
        options,
        engine,
        metrics,
        effectiveFontFamilyResolver,
        assetMetrics,
    ) {
        value = null
        value = withContext(mermaidRenderDispatcher()) {
            engine.render(
                source,
                MermaidRenderContext(
                    textMetrics = metrics,
                    theme = theme,
                    options = options,
                    assetMetrics = assetMetrics,
                ),
            )
        }
    }
}

@Composable
fun MermaidSceneCanvas(
    scene: MermaidScene,
    modifier: Modifier = Modifier,
    contentDescription: String = "Mermaid diagram",
    fontFamilyResolver: MermaidFontFamilyResolver? = null,
    assetProvider: MermaidAssetProvider? = null,
    onAssetError: ((SceneAsset, MermaidAssetError) -> Unit)? = null,
    onAssetResolved: ((SceneAsset, MermaidResolvedAsset) -> Unit)? = null,
    onNodeInteraction: ((SceneNodeInteraction) -> Unit)? = null,
    respectSourceViewportSizing: Boolean = true,
) {
    MermaidSceneCanvasInternal(
        scene = scene,
        renderErrorKey = scene,
        modifier = modifier,
        contentDescription = contentDescription,
        fontFamilyResolver = fontFamilyResolver,
        assetProvider = assetProvider,
        onAssetError = onAssetError,
        onAssetResolved = onAssetResolved,
        onNodeInteraction = onNodeInteraction,
        respectSourceViewportSizing = respectSourceViewportSizing,
        onRenderException = null,
    )
}

@Composable
private fun MermaidSceneCanvasInternal(
    scene: MermaidScene,
    renderErrorKey: Any?,
    modifier: Modifier,
    contentDescription: String,
    fontFamilyResolver: MermaidFontFamilyResolver?,
    assetProvider: MermaidAssetProvider?,
    onAssetError: ((SceneAsset, MermaidAssetError) -> Unit)?,
    onAssetResolved: ((SceneAsset, MermaidResolvedAsset) -> Unit)?,
    onNodeInteraction: ((SceneNodeInteraction) -> Unit)?,
    respectSourceViewportSizing: Boolean,
    onRenderException: ((MermaidError.Unexpected) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 256)
    val effectiveFontFamilyResolver = rememberMermaidFontFamilyResolver(fontFamilyResolver)
    val monospaceFontFamily = rememberMermaidMonospaceFontFamily()
    val hasCjkText = remember(scene) {
        scene.elements
            .asSequence()
            .filterIsInstance<SceneText>()
            .any { it.text.hasCjkFontCharacters() }
    }
    val cjkFontFamily = if (fontFamilyResolver == null && hasCjkText) {
        rememberMermaidCjkFontFamily()
    } else {
        null
    }
    val hasSymbolText = remember(scene) {
        scene.elements
            .asSequence()
            .filterIsInstance<SceneText>()
            .any { it.text.hasBundledSymbolFontCharacters() }
    }
    val symbolFontFamily = if (fontFamilyResolver == null && hasSymbolText) {
        rememberMermaidSymbolFontFamily()
    } else {
        null
    }
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val currentInteractionHandler by rememberUpdatedState(onNodeInteraction)
    val currentAssetErrorHandler by rememberUpdatedState(onAssetError)
    val currentAssetResolvedHandler by rememberUpdatedState(onAssetResolved)
    val currentRenderExceptionHandler by rememberUpdatedState(onRenderException)
    val renderExceptionReporter = remember(scene, renderErrorKey) {
        MermaidRenderExceptionReporter()
    }
    var viewport by remember(scene) { mutableStateOf(DiagramViewport()) }
    val sceneAssets = remember(scene) {
        scene.elements.filterIsInstance<SceneAsset>()
    }
    val initialAssetStates: Map<String, MermaidAssetState> = remember(sceneAssets) {
        sceneAssets.associate { asset -> asset.id to MermaidAssetState.Loading }
    }
    val assetStates by produceState(
        initialValue = initialAssetStates,
        sceneAssets,
        assetProvider,
    ) {
        val assetsById = sceneAssets.associateBy(SceneAsset::id)
        resolveMermaidAssets(sceneAssets, assetProvider) { id, state ->
            value = value + (id to state)
            if (state is MermaidAssetState.Failed) {
                assetsById[id]?.let { asset ->
                    currentAssetErrorHandler?.invoke(asset, state.error)
                }
            } else if (state is MermaidAssetState.Resolved) {
                assetsById[id]?.let { asset ->
                    currentAssetResolvedHandler?.invoke(asset, state.asset)
                }
            }
        }
    }
    val hasAnimatedPath = remember(scene) {
        scene.elements.any { element -> element is ScenePath && element.animated }
    }
    val animationTimeMillis = if (hasAnimatedPath) {
        val transition = rememberInfiniteTransition(label = "Mermaid edge animation clock")
        val value by transition.animateFloat(
            initialValue = 0f,
            targetValue = ANIMATION_CLOCK_DURATION_MILLIS.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = ANIMATION_CLOCK_DURATION_MILLIS,
                    easing = LinearEasing,
                ),
            ),
            label = "Mermaid edge animation time",
        )
        value
    } else {
        0f
    }

    Canvas(
        modifier = (if (respectSourceViewportSizing) {
            modifier.mermaidSceneViewportSize(scene)
        } else {
            modifier
        })
            .clipToBounds()
            .semantics { this.contentDescription = contentDescription }
            .pointerInput(scene, touchSlop) {
                awaitEachGesture {
                    var tapStart: Offset? = null
                    var tapPosition: Offset? = null
                    var tapMoved = false
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size == 1 && !multiTouch) {
                            val position = pressed.single().position
                            val start = tapStart
                            if (start == null) {
                                tapStart = position
                            } else if ((position - start).getDistance() > touchSlop) {
                                tapMoved = true
                            }
                            tapPosition = position
                        }
                        if (pressed.size >= 2) {
                            multiTouch = true
                            tapStart = null
                            val viewportPadding = scene.viewportPadding.coerceAtLeast(0f)
                            val paddedSceneWidth = scene.width + viewportPadding * 2f
                            val paddedSceneHeight = scene.height + viewportPadding * 2f
                            val centroid = pressed
                                .map { it.position }
                                .reduce(Offset::plus) / pressed.size.toFloat()
                            val fitScale = min(
                                size.width / paddedSceneWidth,
                                size.height / paddedSceneHeight,
                            )
                            viewport = viewport.applyGesture(
                                viewportWidth = size.width.toFloat(),
                                viewportHeight = size.height.toFloat(),
                                fittedContentWidth = paddedSceneWidth * fitScale,
                                fittedContentHeight = paddedSceneHeight * fitScale,
                                centroidX = centroid.x,
                                centroidY = centroid.y,
                                gesturePanX = event.calculatePan().x,
                                gesturePanY = event.calculatePan().y,
                                zoomChange = event.calculateZoom(),
                            )
                            event.changes.forEach { it.consume() }
                        }
                        if (event.changes.none { it.pressed }) {
                            val position = tapPosition
                            if (!multiTouch && !tapMoved && position != null) {
                                scene.interactionAt(
                                    screenPosition = position,
                                    viewportWidth = size.width.toFloat(),
                                    viewportHeight = size.height.toFloat(),
                                    viewport = viewport,
                                )?.let { interaction ->
                                    currentInteractionHandler?.invoke(interaction)
                                }
                            }
                            break
                        }
                    }
                }
            },
    ) {
        if (renderExceptionReporter.hasFailed) {
            return@Canvas
        }
        try {
            drawRect(scene.background.toComposeColor())
            if (scene.width <= 0f || scene.height <= 0f) {
                return@Canvas
            }
            val viewportPadding = scene.viewportPadding.coerceAtLeast(0f)
            val paddedSceneWidth = scene.width + viewportPadding * 2f
            val paddedSceneHeight = scene.height + viewportPadding * 2f
            val fitScale = min(size.width / paddedSceneWidth, size.height / paddedSceneHeight)
            val scale = fitScale * viewport.zoom
            val contentWidth = paddedSceneWidth * scale
            val contentHeight = paddedSceneHeight * scale
            val baseOffset = Offset(
                x = (size.width - contentWidth) / 2f,
                y = (size.height - contentHeight) / 2f,
            )

            withTransform({
                translate(
                    baseOffset.x + viewport.panX + viewportPadding * scale,
                    baseOffset.y + viewport.panY + viewportPadding * scale,
                )
                scale(scale, scale, Offset.Zero)
            }) {
                scene.elements.forEach { element ->
                    when (element) {
                        is SceneAsset -> when (val state = assetStates[element.id]) {
                            is MermaidAssetState.Resolved -> drawSceneAsset(
                                asset = element,
                                image = state.asset.image,
                            )
                            is MermaidAssetState.Failed -> drawSceneAssetFailure(
                                asset = element,
                                textMeasurer = textMeasurer,
                                density = density.density,
                                fontScale = density.fontScale,
                            )
                            MermaidAssetState.Loading, null -> drawSceneAssetLoading(element)
                        }
                        is SceneShape -> drawSceneShape(element)
                        is ScenePath -> drawScenePath(element, animationTimeMillis)
                        is SceneText -> drawSceneText(
                            element = element,
                            textMeasurer = textMeasurer,
                            density = density.density,
                            fontScale = density.fontScale,
                            fontFamilyResolver = effectiveFontFamilyResolver,
                            monospaceFontFamily = monospaceFontFamily,
                            cjkFontFamily = cjkFontFamily,
                            symbolFontFamily = symbolFontFamily,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            renderExceptionReporter.report(exception, currentRenderExceptionHandler)
        }
    }
}

internal class MermaidRenderExceptionReporter {
    private var failure: MermaidError.Unexpected? = null
    val hasFailed: Boolean
        get() = failure != null

    fun report(
        exception: Exception,
        onRenderException: ((MermaidError.Unexpected) -> Unit)?,
    ) {
        if (failure != null) {
            return
        }
        val error = MermaidError.Unexpected.from(exception)
        failure = error
        onRenderException?.invoke(error)
    }
}

private fun DrawScope.drawSceneAssetLoading(
    asset: SceneAsset,
) {
    val bounds = asset.bounds.toComposeRect()
    drawRect(
        color = Color(0xFFE5E7EB),
        topLeft = bounds.topLeft,
        size = bounds.size,
    )
}

private fun DrawScope.drawSceneAssetFailure(
    asset: SceneAsset,
    textMeasurer: TextMeasurer,
    density: Float,
    fontScale: Float,
) {
    val bounds = asset.bounds.toComposeRect()
    if (asset.kind == SceneAssetKind.Icon) {
        drawRect(
            color = Color(0xFF087EBF),
            topLeft = bounds.topLeft,
            size = bounds.size,
        )
        val result = textMeasurer.measure(
            text = "?",
            style = TextStyle(
                color = Color.White,
                fontSize = normalizedSp(
                    bounds.height * UNKNOWN_ICON_FONT_SCALE,
                    density,
                    fontScale,
                ),
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center,
            ),
        )
        drawText(
            textLayoutResult = result,
            topLeft = Offset(
                x = bounds.center.x - result.size.width / 2f,
                y = bounds.center.y - result.size.height / 2f,
            ),
        )
        return
    }
    val color = Color(0xFFB91C1C)
    val strokeWidth = min(bounds.width, bounds.height).coerceAtLeast(1f) * 0.06f
    drawRect(
        color = Color(0xFFFFF1F2),
        topLeft = bounds.topLeft,
        size = bounds.size,
    )
    drawRect(
        color = color,
        topLeft = bounds.topLeft,
        size = bounds.size,
        style = Stroke(width = strokeWidth),
    )
    drawLine(
        color = color,
        start = bounds.topLeft,
        end = bounds.bottomRight,
        strokeWidth = strokeWidth,
    )
    drawLine(
        color = color,
        start = Offset(bounds.right, bounds.top),
        end = Offset(bounds.left, bounds.bottom),
        strokeWidth = strokeWidth,
    )
}

private fun DrawScope.drawSceneAsset(
    asset: SceneAsset,
    image: ImageBitmap,
) {
    val width = asset.bounds.width.roundToInt().coerceAtLeast(1)
    val height = asset.bounds.height.roundToInt().coerceAtLeast(1)
    drawImage(
        image = image,
        dstOffset = IntOffset(
            x = asset.bounds.left.roundToInt(),
            y = asset.bounds.top.roundToInt(),
        ),
        dstSize = IntSize(width, height),
        colorFilter = if (asset.kind == SceneAssetKind.Icon) {
            asset.tint?.let { ColorFilter.tint(it.toComposeColor()) }
        } else {
            null
        },
    )
}

/**
 * Mermaid 12.0.0: setupGraphViewbox.js -> calculateSvgSizeAttrs.
 *
 * The source sizing policy selects the Compose host's preferred size. Explicit
 * caller constraints still win, and Canvas keeps aspect-fitting within the
 * allocated host just as an SVG viewBox does.
 */
private fun Modifier.mermaidSceneViewportSize(
    scene: MermaidScene,
): Modifier {
    if (scene.viewportSizing == MermaidSceneViewportSizing.Fit) {
        return this
    }
    return layout { measurable, constraints ->
        val padding = scene.viewportPadding.coerceAtLeast(0f)
        val naturalWidth = (scene.width + padding * 2f).roundToInt().coerceAtLeast(1)
        val naturalHeight = (scene.height + padding * 2f).roundToInt().coerceAtLeast(1)
        val preferredWidth = when (scene.viewportSizing) {
            MermaidSceneViewportSizing.Fit -> constraints.maxWidth
            MermaidSceneViewportSizing.ResponsiveMaxWidth ->
                if (constraints.hasBoundedWidth) {
                    min(naturalWidth, constraints.maxWidth)
                } else {
                    naturalWidth
                }
            MermaidSceneViewportSizing.Intrinsic -> naturalWidth
        }
        val width = preferredWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
        val heightScale = width.toFloat() / naturalWidth
        val preferredHeight = (naturalHeight * heightScale).roundToInt().coerceAtLeast(1)
        val height = preferredHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(width, height))
        layout(width, height) {
            placeable.place(0, 0)
        }
    }
}

internal fun MermaidScene.interactionAt(
    screenPosition: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
    viewport: DiagramViewport,
): SceneNodeInteraction? {
    if (width <= 0f || height <= 0f || viewportWidth <= 0f || viewportHeight <= 0f) {
        return null
    }
    val padding = viewportPadding.coerceAtLeast(0f)
    val paddedWidth = width + padding * 2f
    val paddedHeight = height + padding * 2f
    val fitScale = min(viewportWidth / paddedWidth, viewportHeight / paddedHeight)
    val scale = fitScale * viewport.zoom
    if (scale <= 0f) {
        return null
    }
    val contentWidth = paddedWidth * scale
    val contentHeight = paddedHeight * scale
    val baseOffset = Offset(
        x = (viewportWidth - contentWidth) / 2f,
        y = (viewportHeight - contentHeight) / 2f,
    )
    val scenePoint = ScenePoint(
        x = (screenPosition.x - baseOffset.x - viewport.panX - padding * scale) / scale,
        y = (screenPosition.y - baseOffset.y - viewport.panY - padding * scale) / scale,
    )
    return interactions.asReversed().firstOrNull { interaction ->
        scenePoint.x in interaction.bounds.left..interaction.bounds.right &&
            scenePoint.y in interaction.bounds.top..interaction.bounds.bottom
    }
}

private fun DrawScope.drawSceneShape(shape: SceneShape) {
    val bounds = shape.bounds.toComposeRect()
    val fill = shape.fill.toComposeColor()
    val stroke = shape.stroke.toComposeColor()
    val strokeGradient = shape.strokeGradient?.toComposeBrush(bounds)
    val geometry = shape.geometry
    shape.shadow?.let { shadow ->
        drawSceneShapeShadow(shape, bounds, shadow)
    }
    if (geometry == null) {
        val strokeStyle = Stroke(
            width = shape.strokeWidth,
            cap = StrokeCap.Butt,
            join = StrokeJoin.Miter,
            pathEffect = shape.dashIntervals.toPathEffect()
                ?: shape.strokePattern.toPathEffect(),
        )
        when (shape.kind.primitiveCanvasShape()) {
            PrimitiveCanvasShape.Oval -> {
                drawOval(fill, bounds.topLeft, bounds.size, style = Fill)
                if (strokeGradient == null) {
                    drawOval(
                        color = stroke,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        style = strokeStyle,
                    )
                } else {
                    drawOval(
                        brush = strokeGradient,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        style = strokeStyle,
                    )
                }
            }
            PrimitiveCanvasShape.RoundedRectangle -> {
                val radius = CornerRadius(shape.cornerRadius, shape.cornerRadius)
                drawRoundRect(fill, bounds.topLeft, bounds.size, radius, style = Fill)
                if (strokeGradient == null) {
                    drawRoundRect(
                        color = stroke,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        cornerRadius = radius,
                        style = strokeStyle,
                    )
                } else {
                    drawRoundRect(
                        brush = strokeGradient,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        cornerRadius = radius,
                        style = strokeStyle,
                    )
                }
            }
            PrimitiveCanvasShape.Rectangle -> {
                drawRect(fill, bounds.topLeft, bounds.size, style = Fill)
                if (strokeGradient == null) {
                    drawRect(
                        color = stroke,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        style = strokeStyle,
                    )
                } else {
                    drawRect(
                        brush = strokeGradient,
                        topLeft = bounds.topLeft,
                        size = bounds.size,
                        style = strokeStyle,
                    )
                }
            }
        }
        return
    }

    geometry.paths.forEach { primitive ->
        if (primitive.points.isEmpty() && primitive.pathData.isNullOrBlank()) {
            return@forEach
        }
        val path = when (
            val result = primitive.toComposePath(
                bounds = bounds,
                viewBox = geometry.viewBox?.toComposeRect(),
                viewportFit = geometry.viewportFit,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return@forEach
        }
        val opacity = primitive.opacity.coerceIn(0f, 1f)
        val primitiveFill = primitive.fillColor?.toComposeColor()
        val primitiveFillGradient = primitive.fillGradient?.toComposeBrush(bounds)
        val primitiveStroke = primitive.strokeColor?.toComposeColor()
        when (primitive.fill) {
            SceneShapePaint.None -> Unit
            SceneShapePaint.Fill -> when {
                primitiveFillGradient != null -> drawPath(
                    path = path,
                    brush = primitiveFillGradient,
                    alpha = opacity,
                    style = Fill,
                )
                else -> drawPath(
                    path = path,
                    color = (primitiveFill ?: fill).let { color ->
                        color.copy(alpha = color.alpha * opacity)
                    },
                    style = Fill,
                )
            }
            SceneShapePaint.Stroke -> drawPath(
                path = path,
                color = (primitiveFill ?: stroke).let { color ->
                    color.copy(alpha = color.alpha * opacity)
                },
                style = Fill,
            )
        }
        when (primitive.stroke) {
            SceneShapePaint.None -> Unit
            SceneShapePaint.Fill -> drawPath(
                path,
                (primitiveStroke ?: fill).let { color ->
                    color.copy(alpha = color.alpha * opacity)
                },
                style = primitive.strokeStyle(shape, bounds, geometry.viewBox, geometry.viewportFit),
            )
            SceneShapePaint.Stroke -> when {
                primitiveStroke != null -> drawPath(
                    path = path,
                    color = primitiveStroke.copy(alpha = primitiveStroke.alpha * opacity),
                    style = primitive.strokeStyle(
                        shape,
                        bounds,
                        geometry.viewBox,
                        geometry.viewportFit,
                    ),
                )
                strokeGradient != null -> drawPath(
                    path = path,
                    brush = strokeGradient,
                    alpha = opacity,
                    style = primitive.strokeStyle(
                        shape,
                        bounds,
                        geometry.viewBox,
                        geometry.viewportFit,
                    ),
                )
                else -> drawPath(
                    path = path,
                    color = stroke.copy(alpha = stroke.alpha * opacity),
                    style = primitive.strokeStyle(
                        shape,
                        bounds,
                        geometry.viewBox,
                        geometry.viewportFit,
                    ),
                )
            }
        }
    }
}

private fun SceneLinearGradient.toComposeBrush(bounds: Rect): Brush {
    val startOffset = Offset(
        x = bounds.left + bounds.width * start.x,
        y = bounds.top + bounds.height * start.y,
    )
    val endOffset = Offset(
        x = bounds.left + bounds.width * end.x,
        y = bounds.top + bounds.height * end.y,
    )
    return if (colorStops.isEmpty()) {
        Brush.linearGradient(
            colors = listOf(startColor.toComposeColor(), endColor.toComposeColor()),
            start = startOffset,
            end = endOffset,
        )
    } else {
        Brush.linearGradient(
            *colorStops.map { stop ->
                stop.offset.coerceIn(0f, 1f) to stop.color.toComposeColor()
            }.toTypedArray(),
            start = startOffset,
            end = endOffset,
        )
    }
}

private fun DrawScope.drawSceneShapeShadow(
    shape: SceneShape,
    bounds: Rect,
    shadow: SceneShadow,
) {
    val shadowColor = shadow.color.toComposeColor()
    val primitive = shape.geometry
        ?.paths
        ?.firstOrNull { path ->
            (path.points.isNotEmpty() || !path.pathData.isNullOrBlank()) &&
                (path.fill != SceneShapePaint.None || path.stroke != SceneShapePaint.None)
        }
    val primitivePath = primitive?.let { path ->
        when (
            val result = path.toComposePath(
                bounds = bounds,
                viewBox = shape.geometry?.viewBox?.toComposeRect(),
                viewportFit = shape.geometry?.viewportFit ?: SceneShapeViewportFit.Stretch,
            )
        ) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> null
        }
    }
    shadow.samples().forEach { sample ->
        val color = shadowColor.copy(alpha = shadowColor.alpha * sample.alpha)
        withTransform({
            translate(sample.offset.x, sample.offset.y)
        }) {
            when {
                primitivePath == null &&
                    shape.kind.primitiveCanvasShape() == PrimitiveCanvasShape.Oval -> drawOval(
                    color = color,
                    topLeft = bounds.topLeft,
                    size = bounds.size,
                    style = Fill,
                )
                primitivePath == null -> drawRect(
                    color = color,
                    topLeft = bounds.topLeft,
                    size = bounds.size,
                    style = Fill,
                )
                primitive.fill != SceneShapePaint.None -> drawPath(
                    path = primitivePath,
                    color = color,
                    style = Fill,
                )
                else -> drawPath(
                    path = primitivePath,
                    color = color,
                    style = primitive.strokeStyle(
                        shape,
                        bounds,
                        shape.geometry?.viewBox,
                        shape.geometry?.viewportFit ?: SceneShapeViewportFit.Stretch,
                    ),
                )
            }
        }
    }
}

internal data class SceneSvgPathError(
    val cause: Exception,
)

internal fun SceneShapePath.toComposePath(
    bounds: Rect,
    viewBox: Rect?,
    viewportFit: SceneShapeViewportFit,
): GMResult<Path, SceneSvgPathError> = try {
    val svgPathData = pathData
    if (svgPathData == null) {
        GMResult.Ok(
            Path().apply {
                points.forEachIndexed { index, point ->
                    val x = bounds.center.x + point.x
                    val y = bounds.center.y + point.y
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                if (closed) close()
            },
        )
    } else {
        val path = PathParser().parsePathString(svgPathData).toPath()
        path.fillType = when (fillRule) {
            com.swithun.cmpmermaid.core.ScenePathFillRule.NonZero -> PathFillType.NonZero
            com.swithun.cmpmermaid.core.ScenePathFillRule.EvenOdd -> PathFillType.EvenOdd
        }
        transform?.let { path.transform(it.toComposeMatrix()) }
        viewBox?.let { source ->
            path.transform(source.toViewportMatrix(bounds, viewportFit))
        }
        GMResult.Ok(path)
    }
} catch (failure: Exception) {
    GMResult.Err(SceneSvgPathError(failure))
}

private fun SceneShadow.samples(): List<ShadowSample> {
    if (blurRadius <= 0f) {
        return listOf(ShadowSample(Offset(offsetX, offsetY), 1f))
    }
    val diagonal = blurRadius * 0.70710677f
    return listOf(
        ShadowSample(Offset(offsetX, offsetY), 0.4f),
        ShadowSample(Offset(offsetX - blurRadius, offsetY), 0.1f),
        ShadowSample(Offset(offsetX + blurRadius, offsetY), 0.1f),
        ShadowSample(Offset(offsetX, offsetY - blurRadius), 0.1f),
        ShadowSample(Offset(offsetX, offsetY + blurRadius), 0.1f),
        ShadowSample(Offset(offsetX - diagonal, offsetY - diagonal), 0.05f),
        ShadowSample(Offset(offsetX + diagonal, offsetY - diagonal), 0.05f),
        ShadowSample(Offset(offsetX - diagonal, offsetY + diagonal), 0.05f),
        ShadowSample(Offset(offsetX + diagonal, offsetY + diagonal), 0.05f),
    )
}

private data class ShadowSample(
    val offset: Offset,
    val alpha: Float,
)

private fun SceneShapePath.strokeStyle(
    shape: SceneShape,
    bounds: Rect,
    viewBox: com.swithun.cmpmermaid.core.SceneRect?,
    viewportFit: SceneShapeViewportFit,
): Stroke = Stroke(
    width = (strokeWidth ?: shape.strokeWidth) * strokeScale(bounds, viewBox, viewportFit),
    cap = when (strokeCap) {
        SceneStrokeCap.Butt -> StrokeCap.Butt
        SceneStrokeCap.Round -> StrokeCap.Round
        SceneStrokeCap.Square -> StrokeCap.Square
    },
    join = when (strokeJoin) {
        SceneStrokeJoin.Miter -> StrokeJoin.Miter
        SceneStrokeJoin.Round -> StrokeJoin.Round
        SceneStrokeJoin.Bevel -> StrokeJoin.Bevel
    },
    pathEffect = when {
        dashIntervals.size >= 2 && dashIntervals.all { it.isFinite() && it > 0f } ->
            PathEffect.dashPathEffect(
                dashIntervals.map { interval ->
                    interval * strokeScale(bounds, viewBox, viewportFit)
                }.toFloatArray(),
            )
        strokePattern != SceneStrokePattern.Solid -> strokePattern.toPathEffect()
        shape.dashIntervals.size >= 2 && shape.dashIntervals.all { it.isFinite() && it > 0f } ->
            PathEffect.dashPathEffect(shape.dashIntervals.toFloatArray())
        else -> shape.strokePattern.toPathEffect()
    },
)

private fun SceneShapePath.strokeScale(
    bounds: Rect,
    viewBox: com.swithun.cmpmermaid.core.SceneRect?,
    viewportFit: SceneShapeViewportFit,
): Float {
    if (pathData == null || viewBox == null || viewBox.width <= 0f || viewBox.height <= 0f) {
        return 1f
    }
    val scaleX = bounds.width / viewBox.width
    val scaleY = bounds.height / viewBox.height
    return when (viewportFit) {
        SceneShapeViewportFit.Stretch -> min(scaleX, scaleY)
        SceneShapeViewportFit.MeetStart,
        SceneShapeViewportFit.MeetCenter,
        -> min(scaleX, scaleY)
    }
}

internal fun SceneAffineTransform.toComposeMatrix(): Matrix = Matrix().also { matrix ->
    matrix[0, 0] = scaleX
    matrix[0, 1] = skewY
    matrix[1, 0] = skewX
    matrix[1, 1] = scaleY
    matrix[3, 0] = translateX
    matrix[3, 1] = translateY
}

internal fun Rect.toViewportMatrix(
    bounds: Rect,
    viewportFit: SceneShapeViewportFit,
): Matrix {
    if (width <= 0f || height <= 0f) {
        return Matrix()
    }
    val rawScaleX = bounds.width / width
    val rawScaleY = bounds.height / height
    val scaleX: Float
    val scaleY: Float
    val alignX: Float
    val alignY: Float
    when (viewportFit) {
        SceneShapeViewportFit.Stretch -> {
            scaleX = rawScaleX
            scaleY = rawScaleY
            alignX = 0f
            alignY = 0f
        }
        SceneShapeViewportFit.MeetStart -> {
            scaleX = min(rawScaleX, rawScaleY)
            scaleY = scaleX
            alignX = 0f
            alignY = 0f
        }
        SceneShapeViewportFit.MeetCenter -> {
            scaleX = min(rawScaleX, rawScaleY)
            scaleY = scaleX
            alignX = (bounds.width - width * scaleX) / 2f
            alignY = (bounds.height - height * scaleY) / 2f
        }
    }
    return Matrix().also { matrix ->
        matrix[0, 0] = scaleX
        matrix[1, 1] = scaleY
        matrix[3, 0] = bounds.left + alignX - left * scaleX
        matrix[3, 1] = bounds.top + alignY - top * scaleY
    }
}

private fun List<Float>.toPathEffect(): PathEffect? =
    takeIf { size >= 2 && all { interval -> interval.isFinite() && interval > 0f } }
        ?.let { PathEffect.dashPathEffect(it.toFloatArray()) }

private fun DrawScope.drawScenePath(
    element: ScenePath,
    animationTimeMillis: Float,
) {
    if (element.commands.isEmpty()) {
        return
    }
    val paths = element.toComposeContours()
    val useNeoMarkerMargin = shouldApplyNeoMarkerMargins(
        look = element.look,
        animated = element.animated,
        dashIntervals = element.dashIntervals,
    ) && !element.closed
    element.fillColor?.let { fillColor ->
        paths.forEach { path ->
            drawPath(
                path = path,
                color = fillColor.toComposeColor(),
                alpha = element.opacity.coerceIn(0f, 1f),
                style = Fill,
                blendMode = element.blendMode.toComposeBlendMode(),
            )
        }
    }
    val visiblePaths = if (useNeoMarkerMargin) {
        listOf(paths.withMermaidNeoMarkerGaps(element))
    } else {
        paths
    }
    val pathBounds = element.points.toComposeBounds()
    val strokeGradient = element.strokeGradient?.toComposeBrush(pathBounds)
    visiblePaths.forEach { visiblePath ->
        val stroke = Stroke(
            width = element.strokeWidth,
            cap = if (element.animated) StrokeCap.Round else StrokeCap.Butt,
            join = StrokeJoin.Miter,
            pathEffect = if (
                !useNeoMarkerMargin &&
                element.dashIntervals.size >= 2 &&
                element.dashIntervals.all { it.isFinite() && it > 0f }
            ) {
                PathEffect.dashPathEffect(
                    intervals = element.dashIntervals.toFloatArray(),
                    phase = element.animationDashPhase(animationTimeMillis),
                )
            } else if (!useNeoMarkerMargin) {
                element.strokePattern.toPathEffect()
            } else null,
        )
        if (strokeGradient == null) {
            drawPath(
                path = visiblePath,
                color = element.color.toComposeColor(),
                alpha = element.opacity.coerceIn(0f, 1f),
                style = stroke,
                blendMode = element.blendMode.toComposeBlendMode(),
            )
        } else {
            drawPath(
                path = visiblePath,
                brush = strokeGradient,
                alpha = element.opacity.coerceIn(0f, 1f),
                style = stroke,
                blendMode = element.blendMode.toComposeBlendMode(),
            )
        }
    }
    drawArrowHead(
        type = element.arrowStart,
        commands = element.commands,
        position = MarkerPosition.Start,
        useMargin = useNeoMarkerMargin,
        color = (element.arrowColor ?: element.color).toComposeColor(),
        markerBackground = element.markerBackground?.toComposeColor() ?: Color.White,
        strokeWidth = element.strokeWidth,
    )
    drawArrowHead(
        type = element.arrowEnd,
        commands = element.commands,
        position = MarkerPosition.End,
        useMargin = useNeoMarkerMargin,
        color = (element.arrowColor ?: element.color).toComposeColor(),
        markerBackground = element.markerBackground?.toComposeColor() ?: Color.White,
        strokeWidth = element.strokeWidth,
    )
}

private fun List<ScenePoint>.toComposeBounds(): Rect {
    if (isEmpty()) {
        return Rect.Zero
    }
    return Rect(
        left = minOf(ScenePoint::x),
        top = minOf(ScenePoint::y),
        right = maxOf(ScenePoint::x),
        bottom = maxOf(ScenePoint::y),
    )
}

private fun SceneBlendMode.toComposeBlendMode(): BlendMode = when (this) {
    SceneBlendMode.SourceOver -> BlendMode.SrcOver
    SceneBlendMode.Multiply -> BlendMode.Multiply
}

internal fun shouldApplyNeoMarkerMargins(
    look: String,
    animated: Boolean,
    dashIntervals: List<Float>,
): Boolean = look == "neo" &&
    !animated &&
    !dashIntervals.hasValidDashIntervals()

private fun List<Float>.hasValidDashIntervals(): Boolean =
    size >= 2 && all { interval -> interval.isFinite() && interval > 0f }

private fun ScenePath.toComposeContours(): List<Path> {
    val contours = mutableListOf<Path>()
    var active: Path? = null
    var current: ScenePoint? = null

    fun path(): Path = active ?: Path().also { active = it }

    commands.forEach { command ->
        when (command) {
            is ScenePathCommand.MoveTo -> {
                active?.let(contours::add)
                active = Path().apply {
                    moveTo(command.point.x, command.point.y)
                }
                current = command.point
            }
            is ScenePathCommand.LineTo -> {
                path().lineTo(command.point.x, command.point.y)
                current = command.point
            }
            is ScenePathCommand.QuadraticTo -> {
                path().quadraticBezierTo(
                    command.control.x,
                    command.control.y,
                    command.end.x,
                    command.end.y,
                )
                current = command.end
            }
            is ScenePathCommand.CubicTo -> {
                path().cubicTo(
                    command.control1.x,
                    command.control1.y,
                    command.control2.x,
                    command.control2.y,
                    command.end.x,
                    command.end.y,
                )
                current = command.end
            }
            is ScenePathCommand.ArcTo -> {
                val start = current
                if (start == null || command.radius <= 0f) {
                    path().moveTo(command.end.x, command.end.y)
                } else {
                    val center = ScenePoint(
                        x = (start.x + command.end.x) / 2f,
                        y = (start.y + command.end.y) / 2f,
                    )
                    val startAngle = (
                        atan2(start.y - center.y, start.x - center.x) *
                            180f / PI.toFloat()
                        )
                    path().arcTo(
                        rect = Rect(
                            left = center.x - command.radius,
                            top = center.y - command.radius,
                            right = center.x + command.radius,
                            bottom = center.y + command.radius,
                        ),
                        startAngleDegrees = startAngle,
                        sweepAngleDegrees = if (command.clockwise) 180f else -180f,
                        forceMoveTo = false,
                    )
                }
                current = command.end
            }
        }
    }
    if (closed) {
        active?.close()
    }
    active?.let(contours::add)
    return contours
}

private fun ScenePath.animationDashPhase(animationTimeMillis: Float): Float {
    if (!animated) {
        return 0f
    }
    val duration = animationDurationMillis
        ?.takeIf { it > 0 }
        ?: DEFAULT_EDGE_ANIMATION_DURATION_MILLIS
    val progress = (animationTimeMillis % duration.toFloat()) / duration.toFloat()
    return EDGE_ANIMATION_DASH_OFFSET * (1f - progress)
}

private fun List<Path>.withMermaidNeoMarkerGaps(element: ScenePath): Path {
    val measures = map { path ->
        PathMeasure().apply {
            setPath(path, forceClosed = false)
        }
    }
    val contourLengths = measures.map(PathMeasure::length)
    val length = contourLengths.sum()
    val startOffset = element.arrowStart.neoMarkerOffset()
    val endOffset = element.arrowEnd.neoMarkerOffset()
    val dashIntervals = if (
        element.strokePattern == SceneStrokePattern.Dashed ||
        element.strokePattern == SceneStrokePattern.Dotted
    ) {
        val middleLength = length - startOffset - endOffset
        val pairCount = (middleLength / 4f).toInt().coerceAtLeast(0)
        buildList {
            add(0f)
            add(startOffset)
            repeat(pairCount) {
                add(2f)
                add(2f)
            }
            add(endOffset)
        }
    } else {
        listOf(0f, startOffset, (length - startOffset - endOffset).coerceAtLeast(0f), endOffset)
    }
    val result = Path()
    mermaidDashSegments(contourLengths, dashIntervals).forEach { segment ->
        measures[segment.contourIndex].getSegment(
            startDistance = segment.start,
            stopDistance = segment.end,
            destination = result,
            startWithMoveTo = true,
        )
    }
    return result
}

internal fun mermaidDashSegments(
    contourLengths: List<Float>,
    sourceIntervals: List<Float>,
): List<MermaidContourSegment> {
    if (
        contourLengths.isEmpty() ||
        contourLengths.any { length -> !length.isFinite() || length < 0f } ||
        sourceIntervals.isEmpty() ||
        sourceIntervals.any { interval -> !interval.isFinite() || interval < 0f }
    ) {
        return emptyList()
    }
    val pathLength = contourLengths.sum()
    if (pathLength <= 0f) {
        return emptyList()
    }
    val intervals = if (sourceIntervals.size % 2 == 0) {
        sourceIntervals
    } else {
        sourceIntervals + sourceIntervals
    }
    if (intervals.none { it > 0f }) {
        return emptyList()
    }

    val contourStarts = buildList {
        var start = 0f
        contourLengths.forEach { length ->
            add(start)
            start += length
        }
    }
    val result = mutableListOf<MermaidContourSegment>()
    var distance = 0f
    var intervalIndex = 0
    var draw = true
    while (distance < pathLength) {
        val interval = intervals[intervalIndex]
        val nextDistance = min(pathLength, distance + interval)
        if (draw && nextDistance > distance) {
            contourLengths.forEachIndexed { contourIndex, contourLength ->
                val contourStart = contourStarts[contourIndex]
                val contourEnd = contourStart + contourLength
                val overlapStart = max(distance, contourStart)
                val overlapEnd = min(nextDistance, contourEnd)
                if (overlapEnd > overlapStart) {
                    result += MermaidContourSegment(
                        contourIndex = contourIndex,
                        start = overlapStart - contourStart,
                        end = overlapEnd - contourStart,
                    )
                }
            }
        }
        distance = nextDistance
        draw = !draw
        intervalIndex = (intervalIndex + 1) % intervals.size
    }
    return result
}

internal data class MermaidContourSegment(
    val contourIndex: Int,
    val start: Float,
    val end: Float,
)

private fun SceneArrowHead.neoMarkerOffset(): Float = when (this) {
    SceneArrowHead.None -> 0f
    SceneArrowHead.Triangle -> 4f
    SceneArrowHead.Circle,
    SceneArrowHead.Cross,
    -> 12.5f
    SceneArrowHead.Open,
    SceneArrowHead.Async,
    SceneArrowHead.SequenceCross,
    SceneArrowHead.HalfTriangleTop,
    SceneArrowHead.HalfTriangleBottom,
    SceneArrowHead.HalfOpenTop,
    SceneArrowHead.HalfOpenBottom,
    SceneArrowHead.ClassAggregation,
    SceneArrowHead.ClassExtension,
    SceneArrowHead.ClassComposition,
    SceneArrowHead.ClassDependency,
    SceneArrowHead.ClassLollipop,
    SceneArrowHead.ErOnlyOne,
    SceneArrowHead.ErZeroOrOne,
    SceneArrowHead.ErOneOrMore,
    SceneArrowHead.ErZeroOrMore,
    SceneArrowHead.RequirementArrow,
    SceneArrowHead.RequirementContains,
    -> 0f
}

private fun SceneStrokePattern.toPathEffect(): PathEffect? = when (this) {
    SceneStrokePattern.Solid -> null
    SceneStrokePattern.Dashed -> PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
    SceneStrokePattern.Dotted -> PathEffect.dashPathEffect(floatArrayOf(2f, 2f))
}

private fun DrawScope.drawArrowHead(
    type: SceneArrowHead,
    commands: List<ScenePathCommand>,
    position: MarkerPosition,
    useMargin: Boolean,
    color: Color,
    markerBackground: Color,
    strokeWidth: Float,
) {
    if (type == SceneArrowHead.None) {
        return
    }
    val tangent = commands.markerTangent(position) ?: return
    when (type) {
        SceneArrowHead.Triangle -> drawPointMarker(tangent, position, useMargin, color)
        SceneArrowHead.Circle -> drawCircleMarker(tangent, position, useMargin, color)
        SceneArrowHead.Cross -> drawCrossMarker(tangent, position, useMargin, color)
        SceneArrowHead.SequenceCross ->
            drawSequenceCrossMarker(tangent, position, color)
        SceneArrowHead.Open,
        SceneArrowHead.Async,
        SceneArrowHead.HalfTriangleTop,
        SceneArrowHead.HalfTriangleBottom,
        SceneArrowHead.HalfOpenTop,
        SceneArrowHead.HalfOpenBottom,
        -> drawSequenceArrowMarker(type, tangent, position, color)
        SceneArrowHead.ClassAggregation,
        SceneArrowHead.ClassExtension,
        SceneArrowHead.ClassComposition,
        SceneArrowHead.ClassDependency,
        SceneArrowHead.ClassLollipop,
        -> drawClassMarker(type, tangent, position, color)
        SceneArrowHead.ErOnlyOne,
        SceneArrowHead.ErZeroOrOne,
        SceneArrowHead.ErOneOrMore,
        SceneArrowHead.ErZeroOrMore,
        -> drawErMarker(
            type = type,
            tangent = tangent,
            position = position,
            color = color,
            background = markerBackground,
            strokeWidth = strokeWidth,
        )
        SceneArrowHead.RequirementArrow,
        SceneArrowHead.RequirementContains,
        -> drawRequirementMarker(
            type = type,
            tangent = tangent,
            position = position,
            color = color,
            strokeWidth = strokeWidth,
        )
        SceneArrowHead.None -> Unit
    }
}

/**
 * Canvas translation of Mermaid 12.0.0's requirement_arrow and
 * requirement_contains marker definitions.
 */
private fun DrawScope.drawRequirementMarker(
    type: SceneArrowHead,
    tangent: MarkerTangent,
    position: MarkerPosition,
    color: Color,
    strokeWidth: Float,
) {
    val inward = if (position == MarkerPosition.Start) {
        tangent
    } else {
        tangent.copy(unitX = -tangent.unitX, unitY = -tangent.unitY)
    }
    val width = strokeWidth.coerceAtLeast(1f)

    fun point(distance: Float, perpendicular: Float = 0f): Offset =
        inward.transform(
            point = ScenePoint(distance, perpendicular),
            reference = ScenePoint(0f, 0f),
            scale = 1f,
        )

    when (type) {
        SceneArrowHead.RequirementArrow -> {
            drawLine(
                color = color,
                start = point(20f, -10f),
                end = point(0f),
                strokeWidth = width,
                cap = StrokeCap.Butt,
            )
            drawLine(
                color = color,
                start = point(0f),
                end = point(20f, 10f),
                strokeWidth = width,
                cap = StrokeCap.Butt,
            )
        }
        SceneArrowHead.RequirementContains -> {
            val center = point(10f)
            drawCircle(
                color = color,
                radius = 9f,
                center = center,
                style = Stroke(width = width),
            )
            drawLine(
                color = color,
                start = point(1f),
                end = point(19f),
                strokeWidth = width,
                cap = StrokeCap.Butt,
            )
            drawLine(
                color = color,
                start = point(10f, -9f),
                end = point(10f, 9f),
                strokeWidth = width,
                cap = StrokeCap.Butt,
            )
        }
        else -> Unit
    }
}

/**
 * Canvas translation of Mermaid 12.0.0's only_one, zero_or_one,
 * one_or_more and zero_or_more marker definitions.
 */
private fun DrawScope.drawErMarker(
    type: SceneArrowHead,
    tangent: MarkerTangent,
    position: MarkerPosition,
    color: Color,
    background: Color,
    strokeWidth: Float,
) {
    val inward = if (position == MarkerPosition.Start) {
        tangent
    } else {
        tangent.copy(unitX = -tangent.unitX, unitY = -tangent.unitY)
    }
    val width = strokeWidth.coerceAtLeast(1f)

    fun point(distance: Float, perpendicular: Float = 0f): Offset =
        inward.transform(
            point = ScenePoint(distance, perpendicular),
            reference = ScenePoint(0f, 0f),
            scale = 1f,
        )

    fun bar(distance: Float) {
        drawLine(
            color = color,
            start = point(distance, -9f),
            end = point(distance, 9f),
            strokeWidth = width,
            cap = StrokeCap.Butt,
        )
    }

    fun circle(distance: Float) {
        drawCircle(
            color = background,
            radius = 6f,
            center = point(distance),
            style = Fill,
        )
        drawCircle(
            color = color,
            radius = 6f,
            center = point(distance),
            style = Stroke(width = width),
        )
    }

    fun crowFoot() {
        val root = point(18f)
        val firstControl = point(0f, -18f)
        val coveredTip = point(-18f)
        val secondControl = point(0f, 18f)
        val path = Path().apply {
            moveTo(root.x, root.y)
            quadraticBezierTo(
                firstControl.x,
                firstControl.y,
                coveredTip.x,
                coveredTip.y,
            )
            quadraticBezierTo(
                secondControl.x,
                secondControl.y,
                root.x,
                root.y,
            )
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = width, cap = StrokeCap.Butt),
        )
    }

    when (type) {
        SceneArrowHead.ErOnlyOne -> {
            bar(9f)
            bar(15f)
        }
        SceneArrowHead.ErZeroOrOne -> {
            bar(9f)
            circle(21f)
        }
        SceneArrowHead.ErOneOrMore -> {
            crowFoot()
            bar(24f)
        }
        SceneArrowHead.ErZeroOrMore -> {
            crowFoot()
            circle(28f)
        }
        else -> Unit
    }
}

private fun DrawScope.drawClassMarker(
    type: SceneArrowHead,
    tangent: MarkerTangent,
    position: MarkerPosition,
    color: Color,
) {
    if (type == SceneArrowHead.ClassLollipop) {
        val reference = ScenePoint(
            x = if (position == MarkerPosition.Start) 13f else 1f,
            y = 7f,
        )
        val center = tangent.transform(
            point = ScenePoint(7f, 7f),
            reference = reference,
            scale = 1f,
        )
        drawCircle(
            color = color,
            radius = 6f,
            center = center,
            style = Stroke(width = 1f),
        )
        return
    }

    val definition = when (type) {
        SceneArrowHead.ClassAggregation,
        SceneArrowHead.ClassComposition,
        -> ClassMarkerDefinition(
            points = listOf(
                ScenePoint(18f, 7f),
                ScenePoint(9f, 13f),
                ScenePoint(1f, 7f),
                ScenePoint(9f, 1f),
            ),
            reference = ScenePoint(
                x = if (position == MarkerPosition.Start) 18f else 1f,
                y = 7f,
            ),
            filled = type == SceneArrowHead.ClassComposition,
        )
        SceneArrowHead.ClassExtension -> ClassMarkerDefinition(
            points = if (position == MarkerPosition.Start) {
                listOf(
                    ScenePoint(1f, 7f),
                    ScenePoint(18f, 13f),
                    ScenePoint(18f, 1f),
                )
            } else {
                listOf(
                    ScenePoint(1f, 1f),
                    ScenePoint(1f, 13f),
                    ScenePoint(18f, 7f),
                )
            },
            reference = ScenePoint(
                x = if (position == MarkerPosition.Start) 18f else 1f,
                y = 7f,
            ),
            filled = false,
        )
        SceneArrowHead.ClassDependency -> ClassMarkerDefinition(
            points = if (position == MarkerPosition.Start) {
                listOf(
                    ScenePoint(5f, 7f),
                    ScenePoint(9f, 13f),
                    ScenePoint(1f, 7f),
                    ScenePoint(9f, 1f),
                )
            } else {
                listOf(
                    ScenePoint(18f, 7f),
                    ScenePoint(9f, 13f),
                    ScenePoint(14f, 7f),
                    ScenePoint(9f, 1f),
                )
            },
            reference = ScenePoint(
                x = if (position == MarkerPosition.Start) 6f else 13f,
                y = 7f,
            ),
            filled = true,
        )
        else -> return
    }
    val path = Path().apply {
        definition.points.forEachIndexed { index, point ->
            val transformed = tangent.transform(
                point = point,
                reference = definition.reference,
                scale = 1f,
            )
            if (index == 0) {
                moveTo(transformed.x, transformed.y)
            } else {
                lineTo(transformed.x, transformed.y)
            }
        }
        close()
    }
    if (definition.filled) {
        drawPath(path, color = color, style = Fill)
    }
    drawPath(
        path,
        color = color,
        style = Stroke(
            width = 1f,
            cap = StrokeCap.Butt,
            join = StrokeJoin.Miter,
        ),
    )
}

private fun DrawScope.drawSequenceCrossMarker(
    tangent: MarkerTangent,
    position: MarkerPosition,
    color: Color,
) {
    val orientedTangent = if (position == MarkerPosition.Start) {
        tangent.copy(unitX = -tangent.unitX, unitY = -tangent.unitY)
    } else {
        tangent
    }
    val reference = ScenePoint(4f, 4.5f)
    val scale = 1.5f
    val segments = listOf(
        ScenePoint(1f, 2f) to ScenePoint(6f, 7f),
        ScenePoint(6f, 2f) to ScenePoint(1f, 7f),
    )
    segments.forEach { (start, end) ->
        val transformedStart = orientedTangent.transform(start, reference, scale)
        val transformedEnd = orientedTangent.transform(end, reference, scale)
        drawLine(
            color = color,
            start = Offset(transformedStart.x, transformedStart.y),
            end = Offset(transformedEnd.x, transformedEnd.y),
            strokeWidth = 2f,
            cap = StrokeCap.Butt,
        )
    }
}

private fun DrawScope.drawSequenceArrowMarker(
    type: SceneArrowHead,
    tangent: MarkerTangent,
    position: MarkerPosition,
    color: Color,
) {
    val definition = when (type) {
        SceneArrowHead.Open -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(10f, 5f),
                ScenePoint(0f, 10f),
            ),
            reference = ScenePoint(10f, 5f),
            scale = 1f,
            strokeWidth = 1.5f,
        )
        SceneArrowHead.Async -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(18f, 7f),
                ScenePoint(9f, 13f),
                ScenePoint(14f, 7f),
                ScenePoint(9f, 1f),
            ),
            reference = ScenePoint(15.5f, 7f),
            scale = 1.5f,
            strokeWidth = 0f,
        )
        SceneArrowHead.HalfTriangleTop -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(10f, 8f),
                ScenePoint(0f, 8f),
            ),
            reference = ScenePoint(7.9f, 7.25f),
            scale = 1f,
            strokeWidth = 0f,
        )
        SceneArrowHead.HalfTriangleBottom -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(10f, 0f),
                ScenePoint(0f, 8f),
            ),
            reference = ScenePoint(7.9f, 0.75f),
            scale = 1f,
            strokeWidth = 0f,
        )
        SceneArrowHead.HalfOpenTop -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(7f, 7f),
            ),
            reference = ScenePoint(7.5f, 7f),
            scale = 1f,
            strokeWidth = 1.5f,
        )
        SceneArrowHead.HalfOpenBottom -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 7f),
                ScenePoint(7f, 0f),
            ),
            reference = ScenePoint(7.5f, 0f),
            scale = 1f,
            strokeWidth = 1.5f,
        )
        else -> return
    }
    val orientedTangent = if (position == MarkerPosition.Start) {
        tangent.copy(unitX = -tangent.unitX, unitY = -tangent.unitY)
    } else {
        tangent
    }
    val path = Path().apply {
        definition.points.forEachIndexed { index, point ->
            val transformed = orientedTangent.transform(
                point = point,
                reference = definition.reference,
                scale = definition.scale,
            )
            if (index == 0) {
                moveTo(transformed.x, transformed.y)
            } else {
                lineTo(transformed.x, transformed.y)
            }
        }
        if (definition.strokeWidth == 0f) {
            close()
        }
    }
    if (definition.strokeWidth == 0f) {
        drawPath(path, color, style = Fill)
    } else {
        drawPath(
            path,
            color,
            style = Stroke(
                width = definition.strokeWidth,
                cap = StrokeCap.Butt,
                join = StrokeJoin.Miter,
            ),
        )
    }
}

private fun DrawScope.drawPointMarker(
    tangent: MarkerTangent,
    position: MarkerPosition,
    useMargin: Boolean,
    color: Color,
) {
    val definition = when {
        useMargin && position == MarkerPosition.End -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(11.5f, 7f),
                ScenePoint(0f, 14f),
            ),
            reference = ScenePoint(11.5f, 7f),
            scale = 10.5f / 11.5f,
            strokeWidth = 0f,
        )
        useMargin -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 7f),
                ScenePoint(11.5f, 14f),
                ScenePoint(11.5f, 0f),
            ),
            reference = ScenePoint(1f, 7f),
            scale = 1f,
            strokeWidth = 0f,
        )
        position == MarkerPosition.End -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 0f),
                ScenePoint(10f, 5f),
                ScenePoint(0f, 10f),
            ),
            reference = ScenePoint(5f, 5f),
            scale = 0.8f,
            strokeWidth = 0.8f,
        )
        else -> MarkerPathDefinition(
            points = listOf(
                ScenePoint(0f, 5f),
                ScenePoint(10f, 10f),
                ScenePoint(10f, 0f),
            ),
            reference = ScenePoint(4.5f, 5f),
            scale = 0.8f,
            strokeWidth = 0.8f,
        )
    }
    val path = Path().apply {
        definition.points.forEachIndexed { index, point ->
            val transformed = tangent.transform(
                point = point,
                reference = definition.reference,
                scale = definition.scale,
            )
            if (index == 0) {
                moveTo(transformed.x, transformed.y)
            } else {
                lineTo(transformed.x, transformed.y)
            }
        }
        close()
    }
    drawPath(path, color, style = Fill)
    if (definition.strokeWidth > 0f) {
        drawPath(
            path,
            color,
            style = Stroke(
                width = definition.strokeWidth,
                cap = StrokeCap.Butt,
                join = StrokeJoin.Miter,
            ),
        )
    }
}

private fun DrawScope.drawCircleMarker(
    tangent: MarkerTangent,
    position: MarkerPosition,
    useMargin: Boolean,
    color: Color,
) {
    val referenceX = when {
        useMargin && position == MarkerPosition.End -> 12.25f
        useMargin -> -2f
        position == MarkerPosition.End -> 11f
        else -> -1f
    }
    val scale = if (useMargin) 1.4f else 1.1f
    val center = tangent.transform(
        point = ScenePoint(5f, 5f),
        reference = ScenePoint(referenceX, 5f),
        scale = scale,
    )
    val radius = 5f * scale
    drawCircle(color = color, radius = radius, center = center, style = Fill)
    if (!useMargin) {
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = scale),
        )
    }
}

private fun DrawScope.drawCrossMarker(
    tangent: MarkerTangent,
    position: MarkerPosition,
    useMargin: Boolean,
    color: Color,
) {
    val definition = if (useMargin) {
        MarkerCrossDefinition(
            firstStart = ScenePoint(1f, 1f),
            firstEnd = ScenePoint(14f, 14f),
            secondStart = ScenePoint(1f, 14f),
            secondEnd = ScenePoint(14f, 1f),
            reference = ScenePoint(
                if (position == MarkerPosition.End) 17.7f else -3.5f,
                7.5f,
            ),
            scale = 0.8f,
            strokeWidth = 2f,
        )
    } else {
        MarkerCrossDefinition(
            firstStart = ScenePoint(1f, 1f),
            firstEnd = ScenePoint(10f, 10f),
            secondStart = ScenePoint(10f, 1f),
            secondEnd = ScenePoint(1f, 10f),
            reference = ScenePoint(
                if (position == MarkerPosition.End) 12f else -1f,
                5.2f,
            ),
            scale = 1f,
            strokeWidth = 2f,
        )
    }
    drawLine(
        color = color,
        start = tangent.transform(definition.firstStart, definition.reference, definition.scale),
        end = tangent.transform(definition.firstEnd, definition.reference, definition.scale),
        strokeWidth = definition.strokeWidth,
    )
    drawLine(
        color = color,
        start = tangent.transform(definition.secondStart, definition.reference, definition.scale),
        end = tangent.transform(definition.secondEnd, definition.reference, definition.scale),
        strokeWidth = definition.strokeWidth,
    )
}

private fun List<ScenePathCommand>.markerTangent(position: MarkerPosition): MarkerTangent? {
    var current: ScenePoint? = null
    var start: MarkerTangent? = null
    var end: MarkerTangent? = null
    forEach { command ->
        when (command) {
            is ScenePathCommand.MoveTo -> current = command.point
            is ScenePathCommand.LineTo -> {
                val from = current
                if (from != null) {
                    if (start == null) {
                        start = MarkerTangent.create(from, command.point, anchorAtStart = true)
                    }
                    end = MarkerTangent.create(from, command.point, anchorAtStart = false) ?: end
                }
                current = command.point
            }
            is ScenePathCommand.QuadraticTo -> {
                val from = current
                if (from != null) {
                    val startTangent = MarkerTangent.create(
                        from,
                        command.control,
                        anchorAtStart = true,
                    ) ?: MarkerTangent.create(from, command.end, anchorAtStart = true)
                    val endTangent = MarkerTangent.create(
                        command.control,
                        command.end,
                        anchorAtStart = false,
                    ) ?: MarkerTangent.create(from, command.end, anchorAtStart = false)
                    if (start == null && startTangent != null) start = startTangent
                    if (endTangent != null) end = endTangent
                }
                current = command.end
            }
            is ScenePathCommand.CubicTo -> {
                val from = current
                if (from != null) {
                    val startTangent = MarkerTangent.create(
                        from,
                        command.control1,
                        anchorAtStart = true,
                    ) ?: MarkerTangent.create(from, command.end, anchorAtStart = true)
                    val endTangent = MarkerTangent.create(
                        command.control2,
                        command.end,
                        anchorAtStart = false,
                    ) ?: MarkerTangent.create(from, command.end, anchorAtStart = false)
                    if (start == null && startTangent != null) start = startTangent
                    if (endTangent != null) end = endTangent
                }
                current = command.end
            }
            is ScenePathCommand.ArcTo -> {
                val from = current
                if (from != null) {
                    if (start == null) {
                        start = MarkerTangent.create(
                            from,
                            command.end,
                            anchorAtStart = true,
                        )
                    }
                    end = MarkerTangent.create(
                        from,
                        command.end,
                        anchorAtStart = false,
                    ) ?: end
                }
                current = command.end
            }
        }
    }
    return if (position == MarkerPosition.Start) start else end
}

private enum class MarkerPosition {
    Start,
    End,
}

private const val DEFAULT_EDGE_ANIMATION_DURATION_MILLIS = 20_000
private const val ANIMATION_CLOCK_DURATION_MILLIS = 100_000
private const val MERMAID_FONT_WIDTH_SCALE = 1.06f
// Mermaid's browser HTML labels use the user-agent sub/sup baseline geometry.
private const val HTML_SUBSCRIPT_BASELINE_SHIFT = -0.19f
private const val HTML_SUPERSCRIPT_BASELINE_SHIFT = 0.46f
private const val UNKNOWN_ICON_FONT_SCALE = 67.75f / 80f
private const val EDGE_ANIMATION_DASH_OFFSET = 900f

private data class MarkerTangent(
    val anchor: ScenePoint,
    val unitX: Float,
    val unitY: Float,
) {
    fun transform(
        point: ScenePoint,
        reference: ScenePoint,
        scale: Float,
    ): Offset {
        val localX = (point.x - reference.x) * scale
        val localY = (point.y - reference.y) * scale
        return Offset(
            x = anchor.x + unitX * localX - unitY * localY,
            y = anchor.y + unitY * localX + unitX * localY,
        )
    }

    companion object {
        fun create(
            from: ScenePoint,
            to: ScenePoint,
            anchorAtStart: Boolean,
        ): MarkerTangent? {
            val deltaX = to.x - from.x
            val deltaY = to.y - from.y
            val length = hypot(deltaX, deltaY)
            if (length <= 0.0001f) {
                return null
            }
            return MarkerTangent(
                anchor = if (anchorAtStart) from else to,
                unitX = deltaX / length,
                unitY = deltaY / length,
            )
        }
    }
}

private data class MarkerPathDefinition(
    val points: List<ScenePoint>,
    val reference: ScenePoint,
    val scale: Float,
    val strokeWidth: Float,
)

private data class ClassMarkerDefinition(
    val points: List<ScenePoint>,
    val reference: ScenePoint,
    val filled: Boolean,
)

private data class MarkerCrossDefinition(
    val firstStart: ScenePoint,
    val firstEnd: ScenePoint,
    val secondStart: ScenePoint,
    val secondEnd: ScenePoint,
    val reference: ScenePoint,
    val scale: Float,
    val strokeWidth: Float,
)

private fun DrawScope.drawSceneText(
    element: SceneText,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: Float,
    fontScale: Float,
    fontFamilyResolver: MermaidFontFamilyResolver,
    monospaceFontFamily: FontFamily,
    cjkFontFamily: FontFamily?,
    symbolFontFamily: FontFamily?,
) {
    val style = TextStyle(
        color = element.color.toComposeColor(),
        fontSize = normalizedSp(element.fontSize, density, fontScale),
        lineHeight = normalizedSp(element.fontSize * element.lineHeight, density, fontScale),
        textGeometricTransform = TextGeometricTransform(
            scaleX = mermaidTextHorizontalScale(element.horizontalScale),
        ),
        fontFamily = element.fontFamily
            ?.let(fontFamilyResolver::resolve)
            ?: FontFamily.Default,
        fontWeight = element.weight.toComposeWeight(),
        fontStyle = if (element.italic) FontStyle.Italic else null,
        textAlign = when (element.horizontalAlignment) {
            SceneTextAlignment.Start -> TextAlign.Start
            SceneTextAlignment.Center -> TextAlign.Center
            SceneTextAlignment.End -> TextAlign.End
        },
    )
    val layout = textMeasurer.measure(
        text = element.text.toAnnotatedString(
            spans = element.spans,
            monospaceFontFamily = monospaceFontFamily,
            cjkFontFamily = cjkFontFamily,
            symbolFontFamily = symbolFontFamily,
        ),
        style = style,
        softWrap = element.softWrap,
        maxLines = mermaidTextMaxLines(element.text, element.softWrap),
        constraints = if (element.softWrap) {
            Constraints(maxWidth = element.bounds.width.roundToInt().coerceAtLeast(1))
        } else {
            Constraints()
        },
    )
    val outlineLayout = element.outlineColor
        ?.takeIf { element.outlineWidth > 0f }
        ?.let { outlineColor ->
            textMeasurer.measure(
                text = element.text.toAnnotatedString(
                    spans = element.spans,
                    monospaceFontFamily = monospaceFontFamily,
                    cjkFontFamily = cjkFontFamily,
                    symbolFontFamily = symbolFontFamily,
                ),
                style = style.copy(
                    color = outlineColor.toComposeColor(),
                    drawStyle = Stroke(width = element.outlineWidth),
                ),
                softWrap = element.softWrap,
                maxLines = mermaidTextMaxLines(element.text, element.softWrap),
                constraints = if (element.softWrap) {
                    Constraints(maxWidth = element.bounds.width.roundToInt().coerceAtLeast(1))
                } else {
                    Constraints()
                },
            )
        }
    val x = when (element.horizontalAlignment) {
        SceneTextAlignment.Start -> element.bounds.left + 4f
        SceneTextAlignment.Center -> element.bounds.center.x - layout.size.width / 2f
        SceneTextAlignment.End -> element.bounds.right - layout.size.width - 4f
    }
    val y = element.bounds.center.y - layout.size.height / 2f
    fun DrawScope.drawLayouts() {
        outlineLayout?.let { outlined ->
            drawText(outlined, topLeft = Offset(x, y))
        }
        drawText(layout, topLeft = Offset(x, y))
    }
    withTransform({
        rotate(
            degrees = element.rotationDegrees,
            pivot = (element.rotationPivot ?: element.bounds.center).let { point ->
                Offset(point.x, point.y)
            },
        )
    }) {
        if (element.clipToBounds) {
            clipRect(
                left = element.bounds.left,
                top = element.bounds.top,
                right = element.bounds.right,
                bottom = element.bounds.bottom,
            ) {
                drawLayouts()
            }
        } else {
            drawLayouts()
        }
    }
}

internal enum class PrimitiveCanvasShape {
    Rectangle,
    RoundedRectangle,
    Oval,
}

internal fun SceneShapeKind.primitiveCanvasShape(): PrimitiveCanvasShape = when (this) {
    SceneShapeKind.Circle,
    SceneShapeKind.Ellipse,
    -> PrimitiveCanvasShape.Oval
    SceneShapeKind.RoundedRectangle -> PrimitiveCanvasShape.RoundedRectangle
    else -> PrimitiveCanvasShape.Rectangle
}

internal fun mermaidTextMaxLines(
    text: String,
    softWrap: Boolean,
): Int = if (softWrap) {
    MAX_SOFT_TEXT_LINES
} else {
    text.count { character -> character == '\n' } + 1
}

internal fun mermaidTextHorizontalScale(override: Float?): Float =
    override ?: MERMAID_FONT_WIDTH_SCALE

private fun TextMetricsRequest.toTextStyle(
    density: Float,
    fontScale: Float,
    fontFamilyResolver: MermaidFontFamilyResolver,
): TextStyle = TextStyle(
    fontSize = normalizedSp(fontSize, density, fontScale),
    lineHeight = normalizedSp(fontSize * lineHeight, density, fontScale),
    textGeometricTransform = TextGeometricTransform(
        scaleX = mermaidTextHorizontalScale(horizontalScale),
    ),
    fontFamily = fontFamily
        ?.let(fontFamilyResolver::resolve)
        ?: FontFamily.Default,
    fontWeight = weight.toComposeWeight(),
)

private fun normalizedSp(
    sceneUnits: Float,
    density: Float,
    fontScale: Float,
) = (sceneUnits / density / fontScale).sp

private fun Float.mermaidTextConstraint(
    spans: List<com.swithun.cmpmermaid.core.SceneTextSpan>,
): Int {
    val widthScale = if (
        spans.any { span -> span.fontFamily == SceneTextFontFamily.Monospace }
    ) {
        MERMAID_FONT_WIDTH_SCALE
    } else {
        1f
    }
    return (this / widthScale).roundToInt().coerceAtLeast(1)
}

private fun SceneTextWeight.toComposeWeight(): FontWeight = when (this) {
    SceneTextWeight.Normal -> FontWeight.Normal
    SceneTextWeight.Medium -> FontWeight.Medium
    SceneTextWeight.Bold -> FontWeight.Bold
}

private fun String.toAnnotatedString(
    spans: List<com.swithun.cmpmermaid.core.SceneTextSpan>,
    monospaceFontFamily: FontFamily,
    cjkFontFamily: FontFamily?,
    symbolFontFamily: FontFamily?,
): AnnotatedString = buildAnnotatedString {
    append(this@toAnnotatedString)
    spans.forEach { span ->
        if (span.start < 0 || span.end > length || span.start >= span.end) {
            return@forEach
        }
        addStyle(
            style = SpanStyle(
                fontWeight = span.weight?.toComposeWeight(),
                fontStyle = if (span.italic) FontStyle.Italic else null,
                textDecoration = span.toComposeTextDecoration(),
                color = span.color?.toComposeColor() ?: Color.Unspecified,
                background = span.background?.toComposeColor() ?: Color.Unspecified,
                fontFamily = when (span.fontFamily) {
                    SceneTextFontFamily.Monospace -> monospaceFontFamily
                    null -> null
                },
                baselineShift = when (span.baselineShift) {
                    SceneTextBaselineShift.Subscript ->
                        BaselineShift(HTML_SUBSCRIPT_BASELINE_SHIFT)
                    SceneTextBaselineShift.Superscript ->
                        BaselineShift(HTML_SUPERSCRIPT_BASELINE_SHIFT)
                    null -> null
                },
                fontSize = if (span.fontSizeScale == 1f) {
                    TextUnit.Unspecified
                } else {
                    span.fontSizeScale.em
                },
            ),
            start = span.start,
            end = span.end,
        )
    }
    if (cjkFontFamily != null) {
        cjkFontRanges().forEach { range ->
            addStyle(
                style = SpanStyle(fontFamily = cjkFontFamily),
                start = range.first,
                end = range.last + 1,
            )
        }
    }
    if (symbolFontFamily != null) {
        bundledSymbolFontRanges().forEach { range ->
            addStyle(
                style = SpanStyle(fontFamily = symbolFontFamily),
                start = range.first,
                end = range.last + 1,
            )
        }
    }
}

internal fun String.cjkFontRanges(): List<IntRange> =
    fontRanges(Int::usesCjkFont)

internal fun String.bundledSymbolFontRanges(): List<IntRange> =
    fontRanges(Int::usesBundledSymbolFont)

private fun String.fontRanges(
    predicate: (Int) -> Boolean,
): List<IntRange> = buildList {
    var rangeStart = -1
    var index = 0
    while (index < length) {
        val first = this@fontRanges[index].code
        val hasSurrogatePair =
            first in HIGH_SURROGATE_RANGE &&
                index + 1 < length &&
                this@fontRanges[index + 1].code in LOW_SURROGATE_RANGE
        val codePoint = if (hasSurrogatePair) {
            val second = this@fontRanges[index + 1].code
            SUPPLEMENTARY_CODE_POINT_OFFSET +
                ((first - HIGH_SURROGATE_START) shl 10) +
                (second - LOW_SURROGATE_START)
        } else {
            first
        }
        val characterLength = if (hasSurrogatePair) 2 else 1
        if (predicate(codePoint)) {
            if (rangeStart < 0) {
                rangeStart = index
            }
        } else if (rangeStart >= 0) {
            add(rangeStart until index)
            rangeStart = -1
        }
        index += characterLength
    }
    if (rangeStart >= 0) {
        add(rangeStart until length)
    }
}

private fun String.hasCjkFontCharacters(): Boolean = cjkFontRanges().isNotEmpty()

private fun Int.usesCjkFont(): Boolean =
    this in 0x2E80..0x303F ||
        this in 0x3040..0x30FF ||
        this in 0x3100..0x312F ||
        this in 0x31A0..0x31EF ||
        this in 0x3400..0x4DBF ||
        this in 0x4E00..0x9FFF ||
        this in 0xAC00..0xD7AF ||
        this in 0xF900..0xFAFF ||
        this in 0xFE30..0xFE4F ||
        this in 0xFF00..0xFFEF ||
        this in 0x20000..0x2FA1F

private fun String.hasBundledSymbolFontCharacters(): Boolean =
    bundledSymbolFontRanges().isNotEmpty()

private fun Int.usesBundledSymbolFont(): Boolean =
    this in 0x2700..0x2704 ||
        this in 0x2706..0x2709 ||
        this in 0x270B..0x271C ||
        this == 0x2764

private val HIGH_SURROGATE_RANGE = 0xD800..0xDBFF
private val LOW_SURROGATE_RANGE = 0xDC00..0xDFFF
private const val HIGH_SURROGATE_START = 0xD800
private const val LOW_SURROGATE_START = 0xDC00
private const val SUPPLEMENTARY_CODE_POINT_OFFSET = 0x10000
private const val MAX_SOFT_TEXT_LINES = 8

private fun com.swithun.cmpmermaid.core.SceneTextSpan.toComposeTextDecoration(): TextDecoration? {
    val decorations = buildList {
        if (underline) {
            add(TextDecoration.Underline)
        }
        if (lineThrough) {
            add(TextDecoration.LineThrough)
        }
    }
    return decorations.takeIf(List<TextDecoration>::isNotEmpty)
        ?.let(TextDecoration::combine)
}

private fun SceneRect.toComposeRect(): Rect = Rect(left, top, right, bottom)

private fun SceneColor.toComposeColor(): Color = Color(argb.toInt())
