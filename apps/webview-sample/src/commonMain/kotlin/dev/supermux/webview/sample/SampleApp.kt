package dev.supermux.webview.sample

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.webview.runtime.ProvideWebViewOverlays
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.runtime.platformWebEngineFactory
import dev.supermux.webview.sample.chat.ChatRun
import dev.supermux.webview.sample.chat.ChatScreen
import dev.supermux.webview.sample.chat.ChatState
import kotlinx.coroutines.delay

/** The sample's screens. The chat demo comes first: it is what the app opens on. */
enum class Screen(val label: String) {
    Chat("Chat"),
    PerfLab("Perf lab"),
    Overlays("Overlays"),
    Checks("Checks"),
}

/** What the header says on the desktop, whose pages run on the library's fake engine. */
const val FAKE_ENGINE_LABEL: String = "fake engine"

/** What the header says on Android, whose pages run on the system WebView. */
const val ANDROID_ENGINE_LABEL: String = "Android WebView"

/** What the header says on iOS, whose pages run on WKWebView. */
const val IOS_ENGINE_LABEL: String = "WKWebView"

/** What the header says on Linux with `-Dwebview.sample.engine=wpe`, whose pages run on WPE WebKit's frames. */
const val LINUX_ENGINE_LABEL: String = "WPE WebKit"

/** What the header says on a Mac, whose pages run on WKWebView under the canvas. */
const val MACOS_ENGINE_LABEL: String = "WKWebView on macOS"

/** What the header says on Windows with `-Dwebview.sample.engine=webview2`, whose pages run on WebView2's frames. */
const val WINDOWS_ENGINE_LABEL: String = "WebView2 engine (Windows)"

/** What the header says in a browser, whose pages are iframes. It serves no `WebContent.Bundle`, so it has no chat. */
const val WEB_ENGINE_LABEL: String = "iframe engine"

/**
 * The sample app: the chat demo (live previews in a chat, the screen it opens on), the perf lab, a page under Compose
 * overlays, and the shared checks page. Everything runs on [lab]'s runtime. The window gets its own overlay registry,
 * as each window of a multi-window app should.
 * @param engineLabel what the header says the pages run on: the desktop's fake engine, the browser's iframes,
 *   Android's WebView, or iOS's WKWebView.
 * @param initialScreen the screen it opens on.
 * @param pickFiles the platform's file picker for the checks page, where the app has one.
 * @param safeArea what the lab's screens keep clear of (the status bar, a cutout, the keyboard where the platform pads
 *   for it). The chat draws its bars under the system's own and keeps its content clear of them itself.
 * @param chatRun a chat run to start 3 s after launch: it prints a `chat-perf:` line.
 * @param chatState the chat's state, for a host that drives the chat itself (iOS's screenshots); the app's own if null.
 */
@Composable
fun SampleApp(
    lab: PerfLabState,
    engineLabel: String = FAKE_ENGINE_LABEL,
    initialScreen: Screen = Screen.Chat,
    pickFiles: (suspend (dev.supermux.webview.FileChooserRequest) -> List<String>?)? = null,
    safeArea: WindowInsets = WindowInsets(0, 0, 0, 0),
    chatRun: ChatRun? = null,
    chatState: ChatState? = null,
) {
    val screens = screensFor(engineLabel)
    var screen by remember { mutableStateOf(if (initialScreen in screens) initialScreen else Screen.PerfLab) }
    // The chat outlives its screen: switching to the lab and back keeps its place, its count and its pages.
    val chat = remember(lab, chatState) { chatState ?: ChatState(lab) }
    if (chatRun != null) {
        LaunchedEffect(chatRun) {
            screen = Screen.Chat
            delay(3_000)
            chat.start(chatRun)
        }
    }
    SampleTheme {
        ProvideWebViewRuntime(lab.runtime) {
            ProvideWebViewOverlays {
                Surface(Modifier.fillMaxSize()) {
                    if (screen == Screen.Chat) {
                        ChatScreen(chat, onOpen = { screen = it })
                    } else {
                        LabScreens(lab, screen, screens, engineLabel, pickFiles, safeArea, onOpen = { screen = it })
                    }
                }
            }
        }
    }
}

/** The screens the app offers on [engineLabel]'s engine: the web serves no `WebContent.Bundle`, so it has no chat. */
internal fun screensFor(engineLabel: String): List<Screen> =
    if (engineLabel == WEB_ENGINE_LABEL) Screen.entries - Screen.Chat else Screen.entries

/** The lab's screens under their header, whose chips switch screens. System back returns to the chat. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LabScreens(
    lab: PerfLabState,
    screen: Screen,
    screens: List<Screen>,
    engineLabel: String,
    pickFiles: (suspend (dev.supermux.webview.FileChooserRequest) -> List<String>?)?,
    safeArea: WindowInsets,
    onOpen: (Screen) -> Unit,
) {
    BackHandler(enabled = Screen.Chat in screens) { onOpen(Screen.Chat) }
    Column(Modifier.fillMaxSize().windowInsetsPadding(safeArea)) {
        Row(
            // Scrolls on a phone-wide screen, where the title and the tabs don't fit.
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("webview-sample", style = MaterialTheme.typography.titleMedium)
            Text(
                engineLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            screens.forEach { s ->
                FilterChip(
                    selected = screen == s,
                    onClick = { onOpen(s) },
                    label = { Text(s.label) },
                    modifier = Modifier.testTag(SampleTags.screen(s)),
                )
            }
        }
        HorizontalDivider()
        when (screen) {
            Screen.PerfLab -> PerfLab(lab)
            Screen.Overlays -> OverlayDemo(hint = overlayHint(engineLabel))
            Screen.Checks -> ChecksScreen(pickFiles)
            Screen.Chat -> Unit
        }
    }
}

/** The overlay demo's hint for the engine the pages run on: what that engine does with Compose content over the page. */
internal fun overlayHint(engineLabel: String): String = when (engineLabel) {
    FAKE_ENGINE_LABEL -> FAKE_ENGINE_HINT
    ANDROID_ENGINE_LABEL, IOS_ENGINE_LABEL, LINUX_ENGINE_LABEL, WINDOWS_ENGINE_LABEL -> NATIVE_VIEW_HINT
    MACOS_ENGINE_LABEL -> MAC_HINT
    else -> CUTOUT_HINT
}

/** Material 3, light or dark as the system is: the web views' default placeholder follows the system too. */
@Composable
fun SampleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}

/**
 * The perf lab as the desktop app runs it: this platform's pool and cache sizes, on fake engines that are counted and
 * that nothing keeps once released. What the runtime's guards catch is printed.
 */
fun samplePerfLab(): PerfLabState {
    val engines = EngineCounter(fakeEngines())
    val runtime = WebViewRuntime(RuntimeConfig.platformDefault().copy(factory = engines))
    runtime.onError = { println("webview-sample: the runtime caught $it") }
    return PerfLabState(engines, runtime)
}

/**
 * The perf lab on this platform's own engines (iOS, macOS), counted, with this platform's pool and cache sizes. What
 * the runtime's guards catch is printed.
 */
fun platformPerfLab(): PerfLabState {
    val engines = EngineCounter(platformWebEngineFactory())
    val runtime = WebViewRuntime(RuntimeConfig.platformDefault().copy(factory = engines))
    runtime.onError = { println("webview-sample: the runtime caught $it") }
    return PerfLabState(engines, runtime)
}

/** The tags the sample's tests find things by. */
object SampleTags {
    const val Viewport = "perf-viewport"
    const val ContentWeb = "content-web"
    const val ContentImages = "content-images"
    const val Pressure = "memory-pressure"
    const val Recovered = "memory-recovered"
    const val StatsEngines = "stats-engines"
    const val StatsMemory = "stats-memory"
    const val StatsRun = "stats-run"
    const val OverlayPage = "overlay-page"
    const val OpenMenu = "open-menu"
    const val OpenDialog = "open-dialog"
    const val CloseDialog = "close-dialog"
    const val ChecksPage = "checks-page"
    const val ChecksPing = "checks-ping"
    const val ChecksMenu = "checks-menu"
    const val ChecksStatus = "checks-status"

    fun layout(layout: ListLayout) = "layout-${layout.name.lowercase()}"

    fun views(count: Int) = "views-$count"

    fun run(run: LabRun) = "run-${run.name.lowercase()}"

    fun demo(index: Int) = "demo-$index"

    fun pluginClicks(index: Int) = "plugin-clicks-$index"

    fun screen(screen: Screen) = "screen-${screen.name.lowercase()}"
}
