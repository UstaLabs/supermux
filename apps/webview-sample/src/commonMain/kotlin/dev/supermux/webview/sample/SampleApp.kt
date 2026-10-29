package dev.supermux.webview.sample

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.webview.runtime.ProvideWebViewOverlays
import dev.supermux.webview.runtime.ProvideWebViewRuntime
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime

/** The sample's screens. */
enum class Screen(val label: String) {
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

/**
 * The sample app: the perf lab, a page under Compose overlays, and the shared checks page. Everything runs on [lab]'s
 * runtime. The window gets its own overlay registry, as each window of a multi-window app should.
 * @param engineLabel what the header says the pages run on: the desktop's fake engine, the browser's iframes,
 *   Android's WebView, or iOS's WKWebView.
 * @param initialScreen the screen it opens on.
 * @param pickFiles the platform's file picker for the checks page, where the app has one.
 */
@Composable
fun SampleApp(
    lab: PerfLabState,
    engineLabel: String = FAKE_ENGINE_LABEL,
    initialScreen: Screen = Screen.PerfLab,
    pickFiles: (suspend (dev.supermux.webview.FileChooserRequest) -> List<String>?)? = null,
) {
    var screen by remember { mutableStateOf(initialScreen) }
    SampleTheme {
        ProvideWebViewRuntime(lab.runtime) {
            ProvideWebViewOverlays {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            // Scrolls on a phone-wide screen, where the title and the three tabs don't fit.
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
                            Screen.entries.forEach { s ->
                                FilterChip(
                                    selected = screen == s,
                                    onClick = { screen = s },
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
                        }
                    }
                }
            }
        }
    }
}

/** The overlay demo's hint for the engine the pages run on: what that engine does with Compose content over the page. */
internal fun overlayHint(engineLabel: String): String = when (engineLabel) {
    FAKE_ENGINE_LABEL -> FAKE_ENGINE_HINT
    ANDROID_ENGINE_LABEL, IOS_ENGINE_LABEL -> NATIVE_VIEW_HINT
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
    val runtime = WebViewRuntime(factory = engines, config = RuntimeConfig.platformDefault())
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
