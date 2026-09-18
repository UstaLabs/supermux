// Nav3 scene strategy: full-pane overlays that keep underlying destinations composed.
// Pattern matches android/nav3-recipes BottomSheetSceneStrategy (OverlayScene + metadata mark).
package dev.supermux.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope

/**
 * Renders the top [NavEntry] as a full-size layer while [overlaidEntries] (typically [Route.Home])
 * stay in the composition underneath — so opening Settings does not tear down session panes.
 *
 * Mark entries with [fullPaneOverlay] metadata. Register this strategy **before** any non-overlay
 * strategies (NavDisplay falls through when we return null).
 */
class FullPaneOverlaySceneStrategy<T : Any> : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        val last = entries.lastOrNull() ?: return null
        if (!last.metadata.contains(FullPaneOverlayKey)) return null
        @Suppress("UNCHECKED_CAST")
        return FullPaneOverlayScene(
            key = last.contentKey as T,
            previousEntries = entries.dropLast(1),
            overlaidEntries = entries.dropLast(1),
            entry = last,
        )
    }

    companion object {
        /** Attach to a [NavEntry] so this strategy claims it as a full-pane overlay. */
        fun fullPaneOverlay(): Map<String, Any> = metadata {
            put(FullPaneOverlayKey, true)
        }

        object FullPaneOverlayKey : NavMetadataKey<Boolean>
    }
}

private data class FullPaneOverlayScene<T : Any>(
    override val key: T,
    override val previousEntries: List<NavEntry<T>>,
    override val overlaidEntries: List<NavEntry<T>>,
    private val entry: NavEntry<T>,
) : OverlayScene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable (() -> Unit) = {
        // Overlays draw outside the wide frame's inset-consuming Row, so an edge-to-edge Android
        // window would put their header under the status bar. Step inside the system bars here
        // (painting the strip behind them) and consume them so a screen that pads itself doesn't
        // pad twice. Desktop insets are zero.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .consumeWindowInsets(WindowInsets.systemBars),
            ) {
                entry.Content()
            }
        }
    }
}
