package dev.supermux.webview.sample.checks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.WebEngine

/** What a check shows on screen: an engine's surface, or a scene of its own. */
class CheckHost {
    var shown: WebEngine? by mutableStateOf(null)
    var scene: (@Composable () -> Unit)? by mutableStateOf(null)

    /** Where the shown engine's surface is laid out, in window pixels. */
    var pageBounds: Rect? = null
}

/** Runs [checks] (or only the one named [only]) on [platform], showing on screen the engine the running check asks to. */
@Composable
fun CheckScreen(checks: List<Check>, only: String?, platform: CheckPlatform) {
    val host = remember { CheckHost() }
    LaunchedEffect(Unit) { runChecks(checks.filter { only == null || it.name == only }, host, this, platform) }
    Box(Modifier.fillMaxSize().background(Color(0xFF202124)).padding(top = 80.dp), contentAlignment = Alignment.TopCenter) {
        val scene = host.scene
        val engine = host.shown
        if (scene != null) {
            scene()
        } else if (engine != null) {
            key(engine) {
                engine.Surface(Modifier.size(320.dp, 480.dp).onGloballyPositioned { host.pageBounds = it.boundsInWindow() })
            }
        }
    }
}
