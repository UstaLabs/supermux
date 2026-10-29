package dev.supermux.webview.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
}

/**
 * The sample app: the perf lab, and a page under Compose overlays. Everything runs on [lab]'s runtime. The window gets
 * its own overlay registry, as each window of a multi-window app should.
 */
@Composable
fun SampleApp(lab: PerfLabState) {
    var screen by remember { mutableStateOf(Screen.PerfLab) }
    SampleTheme {
        ProvideWebViewRuntime(lab.runtime) {
            ProvideWebViewOverlays {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("webview-sample", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "fake engine",
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
                            Screen.Overlays -> OverlayDemo()
                        }
                    }
                }
            }
        }
    }
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

    fun layout(layout: ListLayout) = "layout-${layout.name.lowercase()}"

    fun views(count: Int) = "views-$count"

    fun run(run: LabRun) = "run-${run.name.lowercase()}"

    fun demo(index: Int) = "demo-$index"

    fun pluginClicks(index: Int) = "plugin-clicks-$index"

    fun screen(screen: Screen) = "screen-${screen.name.lowercase()}"
}
