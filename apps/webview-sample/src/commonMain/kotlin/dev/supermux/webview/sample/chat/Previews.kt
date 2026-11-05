package dev.supermux.webview.sample.chat

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebOrigin
import dev.supermux.webview.WebResource
import dev.supermux.webview.WebResourceProvider
import dev.supermux.webview.sample.resources.Res
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Where every preview is served: `https://previews.invalid`, one origin for all of them. They are one bundle each (a
 * page of its own, [PreviewSpec.entry]) on one site, in the default profile, so every preview shares one pool of
 * engines and its spares, and none of them needs the ephemeral profile's wipe when its engine is reused.
 */
val PREVIEW_ORIGIN: WebOrigin = WebOrigin("previews")

/** What a preview is made with: the caption's badge. */
enum class PreviewKind(val label: String) {
    Three("three.js"),
    Css("CSS"),
    Canvas("Canvas"),
    Svg("SVG"),
    Interactive("Interactive"),
}

/** What a preview tells the app over the bridge, and what the app answers. */
enum class PreviewBridge {
    None,

    /** `counter.get` and `counter.add {delta}`: calls the app answers with its count. */
    Counter,

    /** `colour.live {hex, name}` while the sliders move, `colour.pick {hex, name}` on "Send to app". */
    Colour,

    /** `game.level {level, score}` and `game.over {score, level}`. */
    Game,

    /** `form.submit {name, email, topic, message, updates}`. */
    Form,

    /** `code.copy {lines}`. */
    Code,
}

/**
 * One live preview an agent made: a page of the bundle, built from the template `files/previews/<template>.html` in its
 * [variant]. Built once, in the chat's model: [content] is what the list item's web view shows, the same object every
 * time the item is composed.
 * @property height the view's fixed height, or null for a view that follows its page's height (up to [MAX_WRAP]).
 * @property initial a wrap-content view's height until its page reports one, on a phone's card (up to [MID_WIDTH]):
 *   its page's own height there, never less, so the first layout is the real one ([initialFor]).
 * @property initialMid the same for a card from [MID_WIDTH] to [WIDE_WIDTH] wide: some pages fill their card at their
 *   full size from there (a card, a clock face), others wrap less.
 * @property initialWide the same for a card [WIDE_WIDTH] wide and more, a tablet's or a desktop window's: the pages'
 *   wide layout (`@media (min-width: 460px)`).
 * @property pane whether the page takes every touch (iOS) and the wheel (macOS): drag-to-rotate, a game, a card that
 *   scrolls inside itself. Embeds leave a drag to the chat.
 * @property stage the page's own background, top and bottom, for its placeholder; null when the page follows the theme.
 */
@Immutable
class PreviewSpec(
    val slug: String,
    val template: String,
    val variant: String,
    val title: String,
    val kind: PreviewKind,
    val height: Dp?,
    val accent: Color,
    val stage: List<Color>? = null,
    val initial: Dp = 220.dp,
    val pane: Boolean = false,
    val hint: String? = null,
    val bridge: PreviewBridge = PreviewBridge.None,
    val initialMid: Dp = initial,
    val initialWide: Dp = initialMid,
) {
    /** The page's path on [PREVIEW_ORIGIN]. */
    val entry: String = "$slug.html"

    val content: WebContent.Bundle = WebContent.Bundle(PREVIEW_ORIGIN, PreviewFiles(this), entry)

    /**
     * A wrap-content view's first height in a card [width] wide: its page's height there or a little more, never less.
     * A view laid out shorter than its page grows when the page reports, which moves the rows after it; one laid out a
     * little taller keeps its height while it shows (the library takes a smaller height once the row has scrolled out).
     * Between a phone's card and [MID_WIDTH] the pages change from one to the other: the larger of the two.
     */
    fun initialFor(width: Dp): Dp = when {
        width >= WIDE_WIDTH -> initialWide
        width >= MID_WIDTH -> initialMid
        width > PHONE_WIDTH -> maxOf(initial, initialMid)
        else -> initial
    }

    /** The same preview as another page, [slug]: a new content, for a preview the agent makes again. */
    fun again(slug: String): PreviewSpec =
        PreviewSpec(slug, template, variant, title, kind, height, accent, stage, initial, pane, hint, bridge, initialMid, initialWide)

    companion object {
        /** The tallest a wrap-content preview grows: a taller page scrolls inside it. */
        val MAX_WRAP: Dp = 560.dp

        /** A phone's card: the iPhone 15 Pro's, 393 pt less the chat's margins and the agent's avatar. */
        val PHONE_WIDTH: Dp = 329.dp

        /** From here the flip cards and clock faces are at their full size, and the mixer as short as on a desktop. */
        val MID_WIDTH: Dp = 393.dp

        /** The pages' wide layout: `@media (min-width: 460px)` (the form's two columns, the loaders' one row). */
        val WIDE_WIDTH: Dp = 460.dp
    }
}

/**
 * Serves one preview's page and the files every preview shares, from the app's resources. The page is its template with
 * the variant written into it (`window.PREVIEW_VARIANT`), before any of its scripts runs.
 */
class PreviewFiles(private val spec: PreviewSpec) : WebResourceProvider {
    override suspend fun load(path: String): WebResource? = when (path) {
        spec.entry, "" -> page()
        else -> PreviewAssets.shared(path)
    }

    private suspend fun page(): WebResource? {
        val template = PreviewAssets.bytes("${spec.template}.html")?.decodeToString() ?: return null
        val html = template.replaceFirst("<head>", "<head><script>window.PREVIEW_VARIANT=\"${spec.variant}\";</script>")
        return WebResource(PreviewMarkers.into(html).encodeToByteArray(), HTML)
    }

    private companion object {
        const val HTML = "text/html; charset=utf-8"
    }
}

/**
 * Lines that show where a preview's page is against its card, for the macOS checks and runs that measure it (the
 * trackpad checks, `-chat-skew`): while [enabled], every preview page draws a magenta line [MAGENTA] points above its
 * bottom and a yellow one [YELLOW] points above it, over everything it draws, and every preview's caption a line along
 * its top, which is the bottom of the page's slot: cyan while the page itself shows, blue while Compose draws a picture
 * of it. In its place, the page's lines are exactly that far above the cyan one. Each line is [THICKNESS] points thick,
 * across the whole width. Off for people.
 */
object PreviewMarkers {
    @Volatile
    var enabled: Boolean = false

    /** From the page's bottom to its magenta line's top, CSS px (points). */
    const val MAGENTA: Int = 40

    /** From the page's bottom to its yellow line's top: seen when the page is too far off for the magenta one. */
    const val YELLOW: Int = 150

    const val THICKNESS: Int = 3

    /** [html] with the page's lines, before its `</body>`, when [enabled]. */
    fun into(html: String): String {
        if (!enabled) return html
        val end = html.lastIndexOf("</body>")
        if (end < 0) return html
        fun line(fromBottom: Int, colour: String) =
            "<div style=\"position:fixed;left:0;right:0;bottom:${fromBottom - THICKNESS}px;height:${THICKNESS}px;" +
                "background:$colour;z-index:2147483647;pointer-events:none\"></div>"
        return html.substring(0, end) + line(MAGENTA, "#ff00ff") + line(YELLOW, "#ffff00") + html.substring(end)
    }
}

/**
 * The preview files read from the app's resources, each once and then kept: three.js is 750 KB, asked for by every 3D
 * page. Only the shared files ([SHARED]) and the templates are served; anything else is a 404.
 */
object PreviewAssets {
    private val mutex = Mutex()
    private val cache = HashMap<String, ByteArray>()

    private val SHARED = mapOf(
        "lib/kit.css" to "text/css; charset=utf-8",
        "lib/kit.js" to JS,
        "lib/three.module.min.js" to JS,
        "lib/three.core.min.js" to JS,
        "lib/three-LICENSE.txt" to "text/plain; charset=utf-8",
    )

    /** A shared file, or null for any other path. */
    suspend fun shared(path: String): WebResource? {
        val type = SHARED[path] ?: return null
        return bytes(path)?.let { WebResource(it, type) }
    }

    /** `files/previews/<file>` from the resources, or null if there is none. */
    suspend fun bytes(file: String): ByteArray? {
        mutex.withLock { cache[file] }?.let { return it }
        val read = try {
            Res.readBytes("files/previews/$file")
        } catch (e: Exception) {
            return null
        }
        return mutex.withLock { cache.getOrPut(file) { read } }
    }

    /** Reads the files every 3D preview asks for, so the first one doesn't wait for them. */
    suspend fun warm() {
        for (path in SHARED.keys) bytes(path)
    }
}

private const val JS = "text/javascript; charset=utf-8"
