package dev.supermux.ui.whatsnew

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalWindowControlsInsets
import dev.supermux.ui.browser.AppWebViews
import dev.supermux.ui.browser.LocalAppWebViews
import dev.supermux.ui.chat.CardTheme
import dev.supermux.ui.chat.cardDocument
import dev.supermux.ui.chat.cardTheme
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.whatsnew_page_title
import dev.supermux.ui.settings.kit.KitTopBar
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.webview.DarkMode
import dev.supermux.webview.NewWindowDecision
import dev.supermux.webview.NewWindowRequest
import dev.supermux.webview.WebClient
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.WebSettings
import dev.supermux.webview.WebView
import dev.supermux.webview.WebViewPhase
import dev.supermux.webview.rememberWebViewState
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.supermux.ui.i18n.localized

/** What the page can ask the app for, by the name it uses (`data-actions`, `hostBridge.call('whatsnew', {action})`). */
enum class WhatsNewAction(val id: String) {
    Done("done"), Notes("notes"), Settings("settings"), ResumeSetting("resume-setting"), Browser("browser"), CardPrompt("card"),
}

/** The action a page's call names, or null for anything else. */
fun whatsNewAction(params: JsonElement): WhatsNewAction? {
    val name = ((params as? JsonObject)?.get("action") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return WhatsNewAction.entries.firstOrNull { it.id == name }
}

private val HTML_OPEN = Regex("<html(?=[\\s>])", RegexOption.IGNORE_CASE)

/**
 * The page an edition shows: [page] told its [platform] (`data-platform`, empty where unknown) and the [actions] the
 * app takes (`data-actions`), with the theme a card gets.
 */
internal fun whatsNewDocument(page: String, theme: CardTheme, platform: ReleasePlatform?, actions: Set<WhatsNewAction>): String {
    val names = actions.sortedBy { it.ordinal }.joinToString(" ") { it.id }
    val told = HTML_OPEN.replaceFirst(page, "<html data-platform=\"${platform?.id.orEmpty()}\" data-actions=\"$names\"")
    return cardDocument(told, theme)
}

/** Links on the page go to the system browser; nothing opens a window by itself. */
private object WhatsNewClient : WebClient {
    override fun onNewWindow(request: NewWindowRequest): NewWindowDecision =
        if (request.isUserGesture) NewWindowDecision.OpenExternally else NewWindowDecision.Block
}

/**
 * What's new (#90): [version]'s edition, full pane on every width, with the app's own top bar and Back. The page plays
 * each chapter itself; the OS's reduced-motion setting reaches it as `prefers-reduced-motion`.
 */
@Composable
fun WhatsNewPage(
    version: String,
    platform: ReleasePlatform?,
    actions: Set<WhatsNewAction>,
    onAction: (WhatsNewAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = kitColors()
    val controls = LocalWindowControlsInsets.current
    val theme = cardTheme(MaterialTheme.colorScheme, LocalSemantics.current)
    val page by produceState<String?>(null, version) { value = runCatching { loadEdition(version) }.getOrNull() }
    Column(modifier.fillMaxSize().background(c.pane).testTag("whats_new_page")) {
        KitTopBar(
            localized(Res.string.whatsnew_page_title),
            onBack = onBack,
            modifier = Modifier.padding(start = controls.start, end = controls.end, top = if (controls.band > 48.dp) controls.band - 48.dp else 0.dp),
            backTag = "whats_new_back",
            titleTag = "whats_new_title",
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val html = page ?: return@Box
            val content = remember(html, theme, platform, actions) { WebContent.Html(whatsNewDocument(html, theme, platform, actions)) }
            EditionView(content, theme, onAction)
        }
    }
}

@Composable
private fun EditionView(content: WebContent.Html, theme: CardTheme, onAction: (WhatsNewAction) -> Unit) {
    val provided = LocalAppWebViews.current
    val webViews = provided ?: AppWebViews.app
    val state = rememberWebViewState(content)
    val act by rememberUpdatedState(onAction)
    DisposableEffect(state) {
        val off = state.bridge.handle("whatsnew") { params ->
            whatsNewAction(params)?.let(act)
            JsonNull
        }
        onDispose { off() }
    }
    val live = state.phase == WebViewPhase.Live
    LaunchedEffect(state, theme, live) { if (live) state.bridge.post("theme", theme.toJson()) }
    val settings = remember(theme.dark) {
        WebSettings(profile = WebProfile.Ephemeral, transparentBackground = true, darkMode = if (theme.dark) DarkMode.Dark else DarkMode.Light)
    }
    ProvideWebViewRuntime(webViews.runtime) {
        WebView(state, Modifier.fillMaxSize().testTag("whats_new_edition"), settings = settings, client = WhatsNewClient)
    }
}
