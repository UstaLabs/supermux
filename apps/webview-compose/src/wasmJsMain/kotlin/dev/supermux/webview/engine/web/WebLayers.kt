package dev.supermux.webview.engine.web

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.HtmlElementView
import org.w3c.dom.HTMLElement

/**
 * One layer per Compose viewport for the web engine's frames: a `<div data-webview-layer>` right after the viewport's
 * interop container. CMP 1.12.2 puts that container after the canvas's shadow host, inside a `position:relative` div,
 * at its top-left. So the layer sits above the canvas and above the app's own `HtmlElementView`s, and Compose's window
 * coordinates are its CSS coordinates times the density.
 *
 * Viewports are told apart by their [WindowInfo]: each `ComposeViewport` provides its own.
 */
internal object WebLayers {
    const val LAYER_ATTRIBUTE: String = "data-webview-layer"

    private val layers = HashMap<WindowInfo, HTMLElement>()

    /** The layer found for [window], while it is still in the page. */
    fun known(window: WindowInfo): HTMLElement? = layers[window]?.takeIf(::connected)

    /**
     * The layer beside the interop container that holds [anchor]'s wrapper. It is made the first time, and is null
     * while the anchor isn't in such a container.
     */
    fun discover(window: WindowInfo, anchor: HTMLElement): HTMLElement? {
        val wrapper = parentOf(anchor) ?: return null
        val interop = parentOf(wrapper) ?: return null
        val host = parentOf(interop) ?: return null
        val existing = nextSiblingOf(interop)?.takeIf { hasAttribute(it, LAYER_ATTRIBUTE) }
        val layer = existing ?: newElement("div").also {
            attribute(it, LAYER_ATTRIBUTE, "")
            styleOf(it, "position", "absolute")
            styleOf(it, "left", "0px")
            styleOf(it, "top", "0px")
            styleOf(it, "width", "0px")
            styleOf(it, "height", "0px")
            insertChild(host, it, nextSiblingOf(interop))
        }
        layers[window] = layer
        return layer
    }
}

/**
 * The web engine layer of the viewport this composition is in. It is null until found: the first time, a 0×0
 * `HtmlElementView` anchor is composed here to find it, and it leaves the composition once the layer is known.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun rememberWebLayer(): HTMLElement? {
    val window = LocalWindowInfo.current
    var layer by remember(window) { mutableStateOf(WebLayers.known(window)) }
    if (layer == null) {
        HtmlElementView(
            factory = { newElement("div").also { attribute(it, "data-webview-anchor", "") } },
            modifier = Modifier.size(0.dp),
            update = { anchor -> if (layer == null) layer = WebLayers.discover(window, anchor) },
        )
    }
    return layer
}
