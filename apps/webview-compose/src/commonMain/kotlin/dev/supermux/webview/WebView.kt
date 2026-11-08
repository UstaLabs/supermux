package dev.supermux.webview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.UnplacedAwareModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceAtMost
import dev.supermux.webview.runtime.LocalWebViewOverlays
import dev.supermux.webview.runtime.LocalWebViewRuntime
import dev.supermux.webview.runtime.PlatformHost
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.runtime.windowOverlays
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The most one side of Compose's `Constraints` can hold, in px: no view is laid out taller. */
private const val MAX_LAYOUT_PX = 262_142f

/** [WebView]'s activation unless told otherwise: lists are the main use. */
private val DEFAULT_ACTIVATION: Activation = Activation.WhenVisible()

/**
 * Shows [state]'s content with the platform's own web engine (spec §1).
 *
 * The view is a cheap handle: hundreds can be composed at once.
 * - It leases a live engine from [LocalWebViewRuntime]'s pool according to [activation]. Off screen, it keeps the engine
 *   at the lowest priority, so scrolling back finds the page as it was; it gives the engine back, saving the page
 *   first, when a visible view needs it, when memory runs short, after
 *   [dev.supermux.webview.runtime.RuntimeConfig.hiddenReleaseMillis] off screen, or when it leaves the composition.
 * - Until the engine has painted a document the view started (when it goes live, or when [WebViewState.load] changes
 *   what it shows), a cover hides it: the page's last snapshot, or [placeholder]. Navigations the page makes itself,
 *   reloads and history navigations keep the previous page on screen until the next one paints, as engines hold paint.
 *   So the page never shows a white or blank frame.
 * - Overlays and scrims marked in its window ([Modifier.webViewOverlay], [Modifier.webViewScrim]) are cut out of a page
 *   that sits above Compose.
 *
 * Give it a size through [modifier], or use [WebSizing.WrapContentHeight]: full width, and the page's height, capped.
 *
 * @param activation when the view goes live. By default once it is on screen and has stopped moving
 *   ([Activation.WhenVisible]), as views in lists, the main use, need: never mid-scroll. Give a pane that should be live
 *   whenever it shows [Activation.Eager].
 * @param placeholder covers the page while there is no snapshot to show. The default is opaque, in the theme
 *   [settings] resolve to. A custom one should be opaque too, or the engine's unpainted surface may show through.
 */
@Composable
public fun WebView(
    state: WebViewState,
    modifier: Modifier = Modifier,
    settings: WebSettings = WebSettings.Default,
    client: WebClient = WebClient.Default,
    activation: Activation = DEFAULT_ACTIVATION,
    sizing: WebSizing = WebSizing.Fill,
    placeholder: @Composable (WebViewState) -> Unit = { DefaultPlaceholder(it, dark = settings.darkMode.isDark()) },
) {
    val runtime = LocalWebViewRuntime.current
    // Before the host's first decision: Android builds engines for the Activity this reports.
    PlatformHost(runtime)
    val overlays = windowOverlays(LocalWebViewOverlays.current, runtime)
    val host = remember(state, runtime) { WebViewHost(state, runtime) }
    val uriHandler = LocalUriHandler.current
    val dark = settings.darkMode.isDark()
    val density = LocalDensity.current.density
    SideEffect { host.update(settings, client, activation, uriHandler, dark, density, overlays) }
    LaunchedEffect(host) { host.run() }
    DisposableEffect(host) { onDispose { host.dispose() } }
    val sized = when (sizing) {
        WebSizing.Fill -> modifier
        is WebSizing.WrapContentHeight -> modifier.fillMaxWidth().then(WrapContentHeightElement(state, sizing, runtime))
    }
    // Whether a scrolling parent (a lazy list, a scrolling Column) measures the view: unbounded along its axis.
    val scrollingParent = remember { mutableStateOf(false) }
    val probe = remember(scrollingParent) {
        Modifier.layout { measurable, constraints ->
            val scrolling = !constraints.hasBoundedHeight || !constraints.hasBoundedWidth
            if (scrolling != scrollingParent.value) scrollingParent.value = scrolling
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
    }
    // What the app provides around the view wins; otherwise an embed is a view sized to its page, or in a scrolling list.
    val provided = LocalWebSurfaceHints.current
    val inList = sizing is WebSizing.WrapContentHeight || scrollingParent.value
    val hints = remember(provided, inList) {
        if (provided !== DefaultWebSurfaceHints) provided else WebSurfaceHints(inScrollingList = inList)
    }
    // A new host is a new view: its layout reports its position afresh.
    key(host) {
        // The host's node draws the snapshot over the placeholder when there is one (see HostNode).
        Box(probe.then(sized).then(HostElement(host, dark))) {
            val engine = host.engine
            if (engine != null) {
                key(engine) {
                    CompositionLocalProvider(LocalWebSurfaceHints provides hints) {
                        engine.Surface(Modifier.matchParentSize())
                    }
                    // The host hands the engine on only once its surface has left: never composed in two places.
                    DisposableEffect(engine) {
                        val presence = host.surfaceEntered(engine)
                        onDispose { host.surfaceLeft(presence) }
                    }
                }
            }
            // A cover hides the placeholder whole, so none is composed under one: on screen, a cold view with a
            // snapshot composes nothing but its handle.
            val painted = host.painted
            if (!painted && host.coverToDraw(dark) == null) Box(Modifier.matchParentSize()) { placeholder(state) }
            // The cover goes only once a composition shows the painted page (see WebViewHost.coverToDraw).
            SideEffect { host.composed(painted, engine) }
        }
    }
}

/** Whether pages are shown dark: [DarkMode.Auto] follows the system. */
@Composable
internal fun DarkMode.isDark(): Boolean = when (this) {
    DarkMode.Auto -> isSystemInDarkTheme()
    DarkMode.Light -> false
    DarkMode.Dark -> true
}

/**
 * Tells [host] where the view is laid out, when it no longer is, and when the user presses on it, and draws the cover
 * in [dark].
 */
private class HostElement(private val host: WebViewHost, private val dark: Boolean) : ModifierNodeElement<HostNode>() {
    override fun create() = HostNode(host, dark)

    override fun update(node: HostNode) {
        node.host = host
        node.dark = dark
    }

    override fun equals(other: Any?): Boolean = other is HostElement && other.host === host && other.dark == dark

    override fun hashCode(): Int = 31 * host.hashCode() + dark.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "webViewHost"
        properties["dark"] = dark
    }
}

/**
 * One node, no coroutine:
 * - it passes layout positions on as they come, and says when the view is no longer placed: detached, or kept composed
 *   by a lazy list without being placed ([WebViewHost.onUnplaced]), which no position reports;
 * - it sees presses before the page does (the initial pass), without consuming them;
 * - while no engine has painted, it draws the page's snapshot over everything under it (the surface and the
 *   placeholder), as long as the snapshot is of the content shown, in the profile and theme shown
 *   ([WebViewHost.coverToDraw]): another page's, another user's, or a light one in a dark app, would be a wrong frame.
 *
 * It draws the cover itself so that a view coming on screen shows its snapshot in that very frame: the host reads the
 * snapshot from the cache in layout, and this node redraws before the frame is drawn.
 */
private class HostNode(var host: WebViewHost, dark: Boolean) :
    Modifier.Node(), GlobalPositionAwareModifierNode, UnplacedAwareModifierNode, PointerInputModifierNode,
    DrawModifierNode {
    var dark: Boolean = dark
        set(value) {
            if (field == value) return
            field = value
            invalidateDraw()
        }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (host.onPositioned(coordinates)) invalidateDraw()
    }

    override fun onUnplaced() = host.onUnplaced()

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && pointerEvent.type == PointerEventType.Press) host.onInteraction()
    }

    override fun onCancelPointerInput() = Unit

    override fun onDetach() = host.onUnplaced()

    /** The cover's image the last time it was drawn, and the page's background along its edges: read once a cover. */
    private var backgroundOf: ImageBitmap? = null
    private var background = Color.Transparent

    override fun ContentDrawScope.draw() {
        drawContent()
        val cover = host.coverToDraw(dark) ?: return
        val image = cover.image
        if (image !== backgroundOf) {
            backgroundOf = image
            background = coverBackgroundOf(image)
        }
        drawCover(image, coverScale(image.width, image.height, size, density, cover.key.pixelsPerDp), background)
    }
}

/**
 * [image], a page's picture, at its true size: [scale] px of this area a pixel of it, from the top-left, as the page
 * was there; cut at the right and bottom, and [background], the page's own, where it doesn't reach (C2). Never scaled
 * to the area: a page that reflows keeps its text's size when its viewport changes, so a picture stretched to a new
 * size is never what the page will show, and one magnified hides the page's real layout from the user.
 */
internal fun DrawScope.drawCover(image: ImageBitmap, scale: Float, background: Color) {
    if (image.width <= 0 || image.height <= 0 || !(scale > 0f)) return
    val width = image.width * scale
    val height = image.height * scale
    clipRect {
        if (width < size.width) drawRect(background, topLeft = Offset(width, 0f), size = Size(size.width - width, min(height, size.height)))
        if (height < size.height) drawRect(background, topLeft = Offset(0f, height), size = Size(size.width, size.height - height))
        drawImage(
            image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(ceil(width).toInt(), ceil(height).toInt()),
        )
    }
}

/**
 * The scale a runtime cover is drawn at in an [area] of this view, now at [density]: [imageDensity] of its pixels made
 * a dp of the view it was taken of (its snapshot key's [dev.supermux.webview.runtime.SnapshotKey.pixelsPerDp], below
 * the screen's density for a snapshot kept smaller). A view of the picture's own shape is filled exactly (the same
 * view, or the same page at another density); any other keeps the picture's true size.
 */
internal fun coverScale(imageWidth: Int, imageHeight: Int, area: Size, density: Float, imageDensity: Float): Float {
    if (imageWidth <= 0 || imageHeight <= 0) return 1f
    val fill = area.width / imageWidth
    // Within a pixel: the picture's height at the area's width is the area's height.
    if (abs(imageHeight * fill - area.height) <= max(1f, fill)) return fill
    return if (density > 0f && imageDensity > 0f) density / imageDensity else fill
}

/**
 * The page's background as its picture shows it: the colour most of [image]'s edge pixels have (its left, right and
 * bottom edges; a header may run into its top), averaged within that colour's bucket, so a JPEG's noise doesn't split
 * it. Transparent for a transparent page, or one whose pixels can't be read.
 */
internal fun coverBackgroundOf(image: ImageBitmap): Color {
    val width = image.width
    val height = image.height
    if (width <= 0 || height <= 0) return Color.Transparent
    val edges = try {
        val column = IntArray(height)
        val row = IntArray(width)
        val all = IntArray(2 * height + width)
        image.readPixels(column, startX = 0, startY = 0, width = 1, height = height)
        column.copyInto(all, 0)
        image.readPixels(column, startX = width - 1, startY = 0, width = 1, height = height)
        column.copyInto(all, height)
        image.readPixels(row, startX = 0, startY = height - 1, width = width, height = 1)
        row.copyInto(all, 2 * height)
        all
    } catch (e: RuntimeException) {
        return Color.Transparent
    }
    // Buckets of 16 levels a channel; each keeps its count and its channels' sums.
    val buckets = HashMap<Int, LongArray>()
    for (argb in edges) {
        val key = (argb ushr 4) and 0x0F0F0F0F
        val sums = buckets.getOrPut(key) { LongArray(5) }
        sums[0]++
        sums[1] += (argb ushr 24 and 0xFF).toLong()
        sums[2] += (argb ushr 16 and 0xFF).toLong()
        sums[3] += (argb ushr 8 and 0xFF).toLong()
        sums[4] += (argb and 0xFF).toLong()
    }
    val top = buckets.values.maxByOrNull { it[0] } ?: return Color.Transparent
    val n = top[0]
    return Color(red = (top[2] / n).toInt(), green = (top[3] / n).toInt(), blue = (top[4] / n).toInt(), alpha = (top[1] / n).toInt())
}

/** [WebSizing.WrapContentHeight]'s height: the page's, capped, taken where nobody reading is disturbed by it. */
private class WrapContentHeightElement(
    private val state: WebViewState,
    private val sizing: WebSizing.WrapContentHeight,
    private val runtime: WebViewRuntime,
) : ModifierNodeElement<WrapContentHeightNode>() {
    override fun create() = WrapContentHeightNode(state, sizing, runtime)

    override fun update(node: WrapContentHeightNode) {
        node.state = state
        node.sizing = sizing
        node.runtime = runtime
    }

    override fun equals(other: Any?): Boolean =
        other is WrapContentHeightElement && other.state === state && other.sizing == sizing && other.runtime === runtime

    override fun hashCode(): Int = 31 * (31 * state.hashCode() + sizing.hashCode()) + runtime.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "webViewWrapContentHeight"
        properties["max"] = sizing.max
        properties["initial"] = sizing.initial
    }
}

/**
 * How long after the view last moved it counts as moving (its list scrolls or flings): a new height then is taken at
 * once, not animated against the scroll.
 */
private const val MOVING_MILLIS = 100L

/**
 * Lays the view out at the page's height: [WebSizing.WrapContentHeight.initial] until the page reports one, never
 * above [WebSizing.WrapContentHeight.max], nor above what Compose's `Constraints` can hold (262,142 px).
 *
 * It reads the page's size in layout, so a page that reports a new one relayouts the view without recomposing it. A
 * new height moves whatever its list lays out after the view, so it is taken where it disturbs nobody reading:
 * - The first height is taken at once, so a view that page memory gave its size shows at it with no jump.
 * - Where it shows, the view never shrinks by itself: a smaller height (a page shorter than its guess, the usual first
 *   report of a view laid out at `initial`) waits until the view has left: its row is no longer placed by its lazy
 *   list, which then takes it without moving anything on screen ([near]), or the view left the composition (page memory
 *   gives the next view of the page its height). Until then the page's own background fills the rest. Only a page the
 *   user is using (the runtime's focused view: pressed last, or holding the keyboard focus) shrinks in view, as they
 *   act on it.
 * - A taller height is taken: the page's content mustn't be cut. It is animated only while the view is on screen, still
 *   and painted (a page that grows as it streams, a section opened): off screen, under its cover (a placeholder, or a
 *   snapshot, which is no page to follow) or while its list moves, it is taken at once, so the rows after it move once
 *   rather than drift against the scroll for the spring's 200 ms.
 * The animation only runs while there is one: a view at rest has no coroutine.
 */
private class WrapContentHeightNode(
    var state: WebViewState,
    var sizing: WebSizing.WrapContentHeight,
    var runtime: WebViewRuntime,
) : Modifier.Node(), LayoutModifierNode, GlobalPositionAwareModifierNode, UnplacedAwareModifierNode {
    /** The height the view is laid out at, or animates to, in px: NaN until the first measure. */
    private var target = Float.NaN
    private var animation: Animatable<Float, AnimationVector1D>? = null
    private var animating: Job? = null

    /** A smaller height waits for the view to leave ([follow]). */
    private var held = false

    /**
     * Whether the view is laid out where a smaller height would move what is on screen: placed, and less than a window's
     * height from the window. A lazy list places a row only while some of it shows: a row whose web view is clipped out
     * may still be its first visible row, the one it keeps in place, so shrinking it would move every row it lays out
     * after it. Once the row isn't placed (it scrolled out of the list, or left the composition), nothing on screen
     * depends on its height. A plain scrolling Column places a view however far off screen it is: far, it is taken too.
     */
    private var near = false

    // Whether any of the view is in the window, and when it last moved there (the runtime's clock).
    private var visible = false
    private var position = Offset.Unspecified
    private var movedAt = 0L

    override fun onDetach() {
        target = Float.NaN
        stopAnimation()
        held = false
        near = false
        visible = false
        position = Offset.Unspecified
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val at = coordinates.positionInWindow()
        if (at != position) {
            position = at
            movedAt = runtime.clock()
        }
        val shown = coordinates.boundsInWindow()
        visible = coordinates.isAttached && shown.width >= 1f && shown.height >= 1f
        val window = coordinates.findRootCoordinates().size.height.toFloat()
        val top = at.y
        val bottom = top + coordinates.size.height
        setNear(coordinates.isAttached && bottom > -window && top < 2 * window)
    }

    override fun onUnplaced() {
        visible = false
        setNear(false)
    }

    private fun setNear(placedNear: Boolean) {
        if (near == placedNear) return
        near = placedNear
        // Gone from where it shows: a height held back is taken, and nothing on screen moves.
        if (!placedNear && held) invalidateMeasurement()
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val asked = (state.contentSize?.height ?: sizing.initial).coerceAtMost(sizing.max)
        val wanted = asked.toPx().coerceAtMost(MAX_LAYOUT_PX)
        val height = follow(wanted).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        return layout(placeable.width, height) { placeable.place(0, 0) }
    }

    /**
     * The height to lay out at now: [wanted] at once the first time; after that, [wanted] when it may be taken (on
     * screen a smaller one waits), at once or animated as the class says.
     */
    private fun follow(wanted: Float): Float {
        val previous = target
        if (previous.isNaN()) {
            target = wanted
            return wanted
        }
        val owner = state.owner
        val inUse = owner != null && runtime.focused === owner
        held = wanted < previous && near && !inUse
        val next = if (held) previous else wanted
        if (next != previous) {
            target = next
            val watched = visible && runtime.clock() - movedAt >= MOVING_MILLIS && state.phase == WebViewPhase.Live
            if (!watched) {
                stopAnimation()
                return next
            }
            val running = animation ?: Animatable(previous).also { animation = it }
            animating?.cancel()
            animating = coroutineScope.launch { running.animateTo(next, spring(visibilityThreshold = 0.5f)) }
        }
        return animation?.value ?: target
    }

    private fun stopAnimation() {
        animating?.cancel()
        animating = null
        animation = null
    }
}
