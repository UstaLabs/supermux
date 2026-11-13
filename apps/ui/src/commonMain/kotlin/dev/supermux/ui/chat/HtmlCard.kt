// #104 car A: an agent's html card (show_html) in the chat. The page is fetched as text (GET /html/<id>), never loaded
// from the broker's origin, and shown live through webview-compose: its own opaque origin in an ephemeral profile, the
// theme as --sm-* variables, and window.supermux, whose send() is the user's message only right after a real click on
// a button in it.
//
// TODO(#104 car B): full screen (a Browser tab on wide screens, a view over the chat on a phone) and the ⋯ menu (Open
// in a Browser tab, View source, Save as .html, Copy source); Telegram gets the page as a document.
package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.proto.FromCardDto
import dev.supermux.proto.HtmlCardDto
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.browser.AppWebViews
import dev.supermux.ui.browser.LocalAppWebViews
import dev.supermux.ui.oklchToArgb
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.SupermuxSemantics
import dev.supermux.webview.DarkMode
import dev.supermux.webview.LocalWebSurfaceHints
import dev.supermux.webview.WebSurfaceHints
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebClient
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebSizing
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.rememberWebViewState
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** What a chat's html cards reach: their pages by id, and a send from a card. Null where the chat has neither. */
class HtmlCardHost(
    val page: suspend (id: String) -> String?,
    /** Null once the broker took the message, else why not. */
    val send: suspend (card: FromCardDto, text: String) -> String?,
    /** The page if it is already in memory, so a card scrolled back in builds at once. */
    val peek: (id: String) -> String? = { null },
)

val LocalHtmlCards = staticCompositionLocalOf<HtmlCardHost?> { null }

/** The chat's visible height, for a card's cap. Unspecified outside a chat. */
val LocalChatViewportHeight = compositionLocalOf { Dp.Unspecified }

/** Test-only: told each time a named part of the chat composes ("transcript", "card"), so a test can count recompositions. */
internal val LocalChatCompositionProbe = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** The --sm-* variables a page gets, in the order the description names them. */
internal val CARD_THEME_VARIABLES: List<String> =
    listOf("--sm-fg", "--sm-muted-fg", "--sm-card", "--sm-border", "--sm-muted", "--sm-primary") + (1..5).map { "--sm-chart-$it" }

/** The longest text a card may send, as the broker allows. */
internal const val CARD_SEND_MAX = 2000

/**
 * A card's share of the chat at rest, and the least its cap is: as the webview sample caps a preview that follows its
 * page (560 dp), so a phone, whose 70 % is about 455 pt, shows a page of up to 560 whole rather than scrolling it
 * inside. Also the cap where the chat's height isn't known.
 */
private const val CARD_MAX_FRACTION = 0.7f
private val CARD_MAX_FLOOR = 560.dp

/** A card's height before its page reports, when the broker measured none. */
private val CARD_DEFAULT_HEIGHT = 280.dp

/** The theme a card's page gets: `data-theme`, `color-scheme`, and [vars] (name to CSS colour). */
internal data class CardTheme(val dark: Boolean, val vars: List<Pair<String, String>>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("dark", dark)
        putJsonObject("vars") { vars.forEach { (name, value) -> put(name, value) } }
    }
}

/** [color] as CSS: `#rrggbb`, or `rgba(…)` when it isn't opaque. */
internal fun cssColor(color: Color): String {
    val argb = color.toArgb()
    val a = (argb ushr 24) and 0xFF
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    if (a == 0xFF) return "#" + listOf(r, g, b).joinToString("") { it.toString(16).padStart(2, '0') }
    return "rgba($r,$g,$b,${(a / 255.0 * 1000).toInt() / 1000.0})"
}

/** The app's MaterialTheme colours as the page's theme. Chart 4 and 5 have no Material role: info and a fixed violet. */
internal fun cardTheme(cs: ColorScheme, semantics: SupermuxSemantics): CardTheme {
    val dark = cs.background.luminance() < 0.5f
    val violet = Color(if (dark) oklchToArgb(0.70, 0.12, 300.0) else oklchToArgb(0.50, 0.13, 300.0))
    val colors = listOf(
        cs.onSurface, cs.onSurfaceVariant, cs.surface, cs.outline, cs.surfaceVariant, cs.primary,
        cs.primary, cs.tertiary, cs.error, semantics.info, violet,
    )
    return CardTheme(dark, CARD_THEME_VARIABLES.zip(colors.map(::cssColor)))
}

private val DOCTYPE = Regex("^\\s*<!doctype[^>]*>", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("<html[\\s>]", RegexOption.IGNORE_CASE)
private val HEAD_TAG = Regex("<head(?=[\\s>])[^>]*>", RegexOption.IGNORE_CASE)

/**
 * The document a card shows: [page] with the theme and `window.supermux` before any of its own code, framed as the
 * broker's check frames it (src/core/show-html/check.ts), so the heights it measured hold. It always starts with a
 * doctype, after which the library's web engine puts its bridge shim, ahead of this prelude.
 */
internal fun cardDocument(page: String, theme: CardTheme): String {
    val scheme = if (theme.dark) "dark" else "light"
    val prelude = cardPrelude(theme)
    val doctype = DOCTYPE.find(page)
    if (doctype == null && !HTML_TAG.containsMatchIn(page)) {
        return "<!doctype html><html data-theme=\"$scheme\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">$prelude</head><body>$page</body></html>"
    }
    val rest = if (doctype != null) page.substring(doctype.range.last + 1) else page
    val head = HEAD_TAG.find(rest)
    val body = if (head != null) {
        rest.substring(0, head.range.last + 1) + prelude + rest.substring(head.range.last + 1)
    } else {
        prelude + rest
    }
    return (doctype?.value?.trimStart() ?: "<!doctype html>") + body
}

/**
 * The theme sheet and the page API. `supermux.send(text)` calls the host's `send` over the library's bridge with
 * `callAfterClick`, which carries whether the user just clicked a button in the card ([cardSendAnswer]); `theme` events
 * restyle the page live.
 */
private fun cardPrelude(theme: CardTheme): String {
    val scheme = if (theme.dark) "dark" else "light"
    val vars = theme.vars.joinToString(";") { (name, value) -> "$name:$value" }
    return "<style id=\"sm-base\">:root{color-scheme:$scheme;$vars;--sm-radius:8px;" +
        "--sm-font:\"Geist\",system-ui,-apple-system,\"Segoe UI\",sans-serif;--sm-mono:\"Geist Mono\",ui-monospace,monospace}" +
        "html,body{margin:0;background:transparent;overscroll-behavior:none;color:var(--sm-fg);font:14px/1.45 var(--sm-font)}</style>" +
        "<script id=\"sm-api\">" + CARD_API_SCRIPT.replace("\$SCHEME", scheme) + "</script>"
}

/**
 * The event a card's page posts (`{pane: true}`) once it has an element that takes touches itself, and the host's
 * question, which the page answers so, each time a view shows the page.
 */
internal const val CARD_TOUCH_EVENT = "touch"

/** Whether a [CARD_TOUCH_EVENT] asks for the card to be a pane: `{pane: true}` and nothing else. */
internal fun cardWantsPane(data: JsonElement): Boolean =
    ((data as? JsonObject)?.get("pane") as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true

/** A card's cap for a chat of resting height [viewport]: 70 % of it, and never under 560 dp. */
internal fun cardMaxHeight(viewport: Dp): Dp =
    if (viewport == Dp.Unspecified) CARD_MAX_FLOOR else (viewport * CARD_MAX_FRACTION).coerceAtLeast(CARD_MAX_FLOOR)

/**
 * The surface hints of a card's view (the webview sample's). An embed: a drag that starts on the page scrolls the chat
 * (UIKit interop Cooperative on iOS). A pane: the page takes every touch (NonCooperative), for a page that handles
 * drags itself, such as a three.js canvas with `touch-action: none`, which an embed's drag up or down would scroll away.
 */
private val CARD_EMBED = WebSurfaceHints(inScrollingList = true)
private val CARD_PANE = WebSurfaceHints(inScrollingList = false)

internal val CARD_API_SCRIPT = """
(function () {
  var bridge = window.hostBridge, root = document.documentElement;
  root.setAttribute('data-theme', '${'$'}SCHEME');
  function restyle(t) {
    if (!t || typeof t !== 'object') return;
    var scheme = t.dark ? 'dark' : 'light';
    root.setAttribute('data-theme', scheme);
    root.style.setProperty('color-scheme', scheme);
    var vars = t.vars || {};
    for (var name in vars) if (name.indexOf('--sm-') === 0) root.style.setProperty(name, String(vars[name]));
  }
  if (bridge) bridge.on('theme', restyle);
  function send(text) {
    if (!bridge) return Promise.resolve({ ok: false, reason: 'this card cannot send' });
    // The library's shim (or WebKit's relay, in its own world) puts its click verdict on the call: a trusted click on a
    // button in this card within the last second, unused, with the engine's user activation.
    return bridge.callAfterClick('send', { text: String(text) }).then(function (r) {
      return r && r.ok === true ? { ok: true } : { ok: false, reason: String((r && r.reason) || 'refused') };
    }, function (e) {
      return { ok: false, reason: String((e && e.message) || e) };
    });
  }
  // A page with an element that handles touches itself (a computed touch-action that blocks panning one way or both:
  // none, pinch-zoom, pan-x or pan-y alone) tells the host, which then gives it every touch. Looked for once the
  // document is parsed, at load, and as the page changes for a while after (a canvas a script adds), once found no more.
  // The host asks again (a '$CARD_TOUCH_EVENT' event) each time a view shows the page, which may be a new view adopting it.
  if (bridge) (function () {
    var found = false, timer = 0, observer = null, MAX = 5000;
    function blocks(v) {
      if (!v || v === 'auto' || v === 'manipulation') return false;
      return !(/\bpan-x\b/.test(v) && /\bpan-y\b/.test(v));
    }
    function scan() {
      timer = 0;
      if (found) return;
      var all = document.getElementsByTagName('*');
      for (var i = 0; i < all.length && i < MAX; i++) {
        if (blocks(getComputedStyle(all[i]).touchAction)) {
          found = true;
          if (observer) observer.disconnect();
          bridge.post('$CARD_TOUCH_EVENT', { pane: true });
          return;
        }
      }
    }
    bridge.on('$CARD_TOUCH_EVENT', function () { if (found) bridge.post('$CARD_TOUCH_EVENT', { pane: true }); });
    function soon() { if (!found && !timer) timer = setTimeout(scan, 100); }
    document.addEventListener('DOMContentLoaded', function () {
      scan();
      if (found || typeof MutationObserver !== 'function') return;
      observer = new MutationObserver(soon);
      observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
      setTimeout(function () { if (observer) observer.disconnect(); observer = null; }, 10000);
    });
    window.addEventListener('load', soon);
  })();
  Object.defineProperty(window, 'supermux', { value: Object.freeze({ send: send }), writable: false, configurable: false, enumerable: false });
  var self = document.currentScript;
  if (self && self.parentNode) self.parentNode.removeChild(self);
})();
""".trimIndent()

private val SENT: JsonObject = JsonObject(mapOf("ok" to JsonPrimitive(true)))

private fun refused(reason: String): JsonObject {
    println("[HtmlCard] send refused: $reason")
    return JsonObject(mapOf("ok" to JsonPrimitive(false), "reason" to JsonPrimitive(reason)))
}

/**
 * The host's answer to a page's `supermux.send(text)`: [send] runs only for a call right after the user's trusted click
 * on a button in this card ([clicked], the verdict the bridge's `handleAfterButtonClick` gets from the page side, spec
 * open decision 4 option c) with 1 to [CARD_SEND_MAX] characters, and answers what became of it: sent only once the
 * broker took the message, else its reason. The host's input stamp plays no part, so a screen reader's activation, which
 * clicks the button but delivers no input to the view, sends as a tap does.
 */
internal suspend fun cardSendAnswer(params: JsonElement, clicked: Boolean, send: suspend (String) -> String?): JsonObject {
    val text = ((params as? JsonObject)?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
    return when {
        !clicked -> refused("no click on a button in the card")
        text.isNullOrEmpty() -> refused("the text is empty")
        text.length > CARD_SEND_MAX -> refused("the text is over $CARD_SEND_MAX characters")
        else -> send(text)?.let(::refused) ?: SENT
    }
}

/** A link the user clicks with `target=_blank` opens outside the app; the default policy already does that for the rest. */
private object CardClient : WebClient {
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision =
        if (request.isUserGesture) NewWindowDecision.OpenExternally else NewWindowDecision.Block
}

/** The broker's measured height for this width: the phone's on a compact window, the desktop column's otherwise. */
internal fun cardStoredHeight(card: HtmlCardDto, compact: Boolean): Dp? =
    (if (compact) card.height else card.desktopHeight ?: card.height)?.dp

private sealed interface CardPage {
    data object Loading : CardPage
    data object Missing : CardPage
    class Ready(val content: WebContent.Html) : CardPage
}

/**
 * An agent's html card: its title, then its page live. The page sizes to its content, capped at the larger of 560 dp and
 * 70 % of the chat at rest ([cardMaxHeight]; taller pages scroll inside), and the stored height is reserved until it
 * reports, so the list doesn't jump. An embed in the chat, or a pane once its page says it takes touches itself.
 */
@Composable
internal fun HtmlCardView(entryId: String, card: HtmlCardDto, modifier: Modifier = Modifier) {
    val host = LocalHtmlCards.current
    val cs = MaterialTheme.colorScheme
    val theme = cardTheme(cs, LocalSemantics.current)
    val currentTheme by rememberUpdatedState(theme)
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    LocalChatCompositionProbe.current?.let { probe -> SideEffect { probe("card") } }
    val max = cardMaxHeight(LocalChatViewportHeight.current)
    val reserved = (cardStoredHeight(card, compact) ?: CARD_DEFAULT_HEIGHT).coerceAtMost(max)
    // Built once per card, with the theme of the moment, off the main thread (Html hashes its text); a later theme
    // change reaches the page over the bridge.
    val page by produceState<CardPage>(CardPage.Loading, card.id, host) {
        val html = host?.peek(card.id) ?: host?.page(card.id)
        value = if (html == null) CardPage.Missing else CardPage.Ready(withContext(Dispatchers.Default) { WebContent.Html(cardDocument(html, currentTheme)) })
    }
    Column(modifier.fillMaxWidth().testTag("html-card:$entryId")) {
        Text(
            card.title,
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Space.xs),
        )
        when (val p = page) {
            is CardPage.Ready -> CardPageView(entryId, p.content, card, theme, reserved, max, host)
            CardPage.Loading -> Box(Modifier.fillMaxWidth().height(reserved).testTag("html-card-page:$entryId"))
            CardPage.Missing -> Box(Modifier.fillMaxWidth().height(reserved).testTag("html-card-page:$entryId"), contentAlignment = Alignment.Center) {
                Text("This card's page isn't available.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CardPageView(entryId: String, content: WebContent.Html, card: HtmlCardDto, theme: CardTheme, reserved: Dp, max: Dp, host: HtmlCardHost?) {
    val provided = LocalAppWebViews.current
    val webViews by remember(provided) { derivedStateOf { provided ?: AppWebViews.app } }
    val state = rememberWebViewState(content)
    val currentHost by rememberUpdatedState(host)
    DisposableEffect(state, card.id, card.title) {
        val from = FromCardDto(card.id, card.title)
        val off = state.bridge.handleAfterButtonClick("send") { params, clicked ->
            // The host's send answers null once the broker took the message, else why not: a null is a send, never a refusal.
            cardSendAnswer(params, clicked) { text -> currentHost.let { h -> if (h == null) "this card cannot send" else h.send(from, text) } }
        }
        onDispose { off() }
    }
    // An embed until its page says it takes touches itself; nothing is kept, the page says so each time it's shown.
    var pane by remember(state) { mutableStateOf(false) }
    DisposableEffect(state) {
        val off = state.bridge.on(CARD_TOUCH_EVENT) { data -> if (cardWantsPane(data)) pane = true }
        onDispose { off() }
    }
    // Again whenever the page goes live: a post while the view has no engine is lost. The touch question too, for a
    // page this state adopted, which said so to another one.
    val live = state.phase == WebViewPhase.Live
    LaunchedEffect(state, theme, live) { if (live) state.bridge.post("theme", theme.toJson()) }
    LaunchedEffect(state, live) { if (live) state.bridge.post(CARD_TOUCH_EVENT) }
    // The page has no background of its own. Where it lies under the Compose canvas (a Mac, iOS, the web), its clear
    // pixels would show what is under the canvas (a Mac window's black backing), not the chat: its underlay is the
    // chat's background, which the engine paints behind it.
    val underlay = MaterialTheme.colorScheme.chatBackground
    val settings = remember(theme.dark, underlay) {
        WebSettings(
            profile = WebProfile.Ephemeral,
            transparentBackground = true,
            darkMode = if (theme.dark) DarkMode.Dark else DarkMode.Light,
            underlay = underlay,
        )
    }
    ProvideWebViewRuntime(webViews.runtime) {
        CompositionLocalProvider(LocalWebSurfaceHints provides if (pane) CARD_PANE else CARD_EMBED) {
            WebView(
                state,
                Modifier.fillMaxWidth().testTag("html-card-page:$entryId"),
                settings = settings,
                client = CardClient,
                sizing = WebSizing.WrapContentHeight(max = max, initial = reserved),
            )
        }
    }
}
