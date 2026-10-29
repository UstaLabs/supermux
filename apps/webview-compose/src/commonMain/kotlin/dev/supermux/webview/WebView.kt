package dev.supermux.webview

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.coerceAtMost
import dev.supermux.webview.runtime.LocalWebViewOverlays
import dev.supermux.webview.runtime.LocalWebViewRuntime
import dev.supermux.webview.runtime.windowOverlays
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The most one side of Compose's `Constraints` can hold, in px: no view is laid out taller. */
private const val MAX_LAYOUT_PX = 262_142f

/**
 * Shows [state]'s content with the platform's own web engine (spec §1).
 *
 * The view is a cheap handle: hundreds can be composed at once.
 * - It leases a live engine from [LocalWebViewRuntime]'s pool according to [activation], and gives it back when it is
 *   off screen past its grace, evicted, or leaves the composition, saving the page first.
 * - Until the engine has painted the current document, a cover hides it: the page's last snapshot, or [placeholder].
 *   So the page never shows a white or blank frame.
 * - Overlays and scrims marked in its window ([Modifier.webViewOverlay], [Modifier.webViewScrim]) are cut out of a page
 *   that sits above Compose.
 *
 * Give it a size through [modifier], or use [WebSizing.WrapContentHeight]: full width, and the page's height, capped.
 *
 * @param placeholder covers the page while there is no snapshot to show. The default is opaque, in the theme
 *   [settings] resolve to. A custom one should be opaque too, or the engine's unpainted surface may show through.
 */
@Composable
fun WebView(
    state: WebViewState,
    modifier: Modifier = Modifier,
    settings: WebSettings = WebSettings.Default,
    client: WebClient = WebClient.Default,
    activation: Activation = Activation.Eager,
    sizing: WebSizing = WebSizing.Fill,
    placeholder: @Composable (WebViewState) -> Unit = { DefaultPlaceholder(it, dark = settings.darkMode.isDark()) },
) {
    val runtime = LocalWebViewRuntime.current
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
        is WebSizing.WrapContentHeight -> modifier.fillMaxWidth().then(WrapContentHeightElement(state, sizing))
    }
    // A new host is a new view: its layout reports its position afresh.
    key(host) {
        Box(sized.then(HostElement(host))) {
            val engine = host.engine
            if (engine != null) key(engine) { engine.Surface(Modifier.matchParentSize()) }
            if (!host.painted) {
                // The page's snapshot, while it is of the content shown and in the theme shown: a snapshot of another
                // page, or a light one in a dark app, would be a wrong frame.
                val cover = host.cover?.takeIf { it.key.contentKey == state.content.key && it.key.dark == dark }
                if (cover != null) {
                    Image(
                        cover.image,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        alignment = Alignment.TopStart,
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.matchParentSize()) { placeholder(state) }
                }
            }
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

/** Tells [host] where the view is laid out, and when the user presses on it. */
private class HostElement(private val host: WebViewHost) : ModifierNodeElement<HostNode>() {
    override fun create() = HostNode(host)

    override fun update(node: HostNode) {
        node.host = host
    }

    override fun equals(other: Any?): Boolean = other is HostElement && other.host === host

    override fun hashCode(): Int = host.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "webViewHost"
    }
}

/**
 * One node, no coroutine: it passes layout positions on as they come, and sees presses before the page does (the
 * initial pass), without consuming them.
 */
private class HostNode(var host: WebViewHost) :
    Modifier.Node(), GlobalPositionAwareModifierNode, PointerInputModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) = host.onPositioned(coordinates)

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && pointerEvent.type == PointerEventType.Press) host.onInteraction()
    }

    override fun onCancelPointerInput() = Unit

    override fun onDetach() = host.onDetached()
}

/** [WebSizing.WrapContentHeight]'s height: the page's, capped, followed with a spring. */
private class WrapContentHeightElement(
    private val state: WebViewState,
    private val sizing: WebSizing.WrapContentHeight,
) : ModifierNodeElement<WrapContentHeightNode>() {
    override fun create() = WrapContentHeightNode(state, sizing)

    override fun update(node: WrapContentHeightNode) {
        node.state = state
        node.sizing = sizing
    }

    override fun equals(other: Any?): Boolean =
        other is WrapContentHeightElement && other.state === state && other.sizing == sizing

    override fun hashCode(): Int = 31 * state.hashCode() + sizing.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "webViewWrapContentHeight"
        properties["max"] = sizing.max
        properties["initial"] = sizing.initial
    }
}

/**
 * Lays the view out at the page's height: [WebSizing.WrapContentHeight.initial] until the page reports one, never
 * above [WebSizing.WrapContentHeight.max], nor above what Compose's `Constraints` can hold (262,142 px).
 *
 * It reads the page's size in layout, so a page that reports a new one relayouts the view without recomposing it.
 * The first height is taken at once, so a view that page memory gave its size shows at it with no jump; later ones are
 * animated, and the animation only runs while there is one: a view at rest has no coroutine.
 */
private class WrapContentHeightNode(
    var state: WebViewState,
    var sizing: WebSizing.WrapContentHeight,
) : Modifier.Node(), LayoutModifierNode {
    /** The height asked for, in px: NaN until the first measure. */
    private var target = Float.NaN
    private var animation: Animatable<Float, AnimationVector1D>? = null

    override fun onDetach() {
        target = Float.NaN
        animation = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val asked = (state.contentSize?.height ?: sizing.initial).coerceAtMost(sizing.max)
        val wanted = asked.toPx().coerceAtMost(MAX_LAYOUT_PX)
        val height = follow(wanted).roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        return layout(placeable.width, height) { placeable.place(0, 0) }
    }

    /** The height to lay out at now: [wanted] at once the first time; after that, animated to each new one. */
    private fun follow(wanted: Float): Float {
        val previous = target
        if (previous.isNaN()) {
            target = wanted
            return wanted
        }
        if (wanted != previous) {
            target = wanted
            val running = animation ?: Animatable(previous).also { animation = it }
            coroutineScope.launch { running.animateTo(wanted, spring(visibilityThreshold = 0.5f)) }
        }
        return animation?.value ?: wanted
    }
}
