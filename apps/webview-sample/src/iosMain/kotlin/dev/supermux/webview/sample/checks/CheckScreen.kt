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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.WebEngine

/** The engine a check shows on screen. */
class CheckHost {
    var shown: WebEngine? by mutableStateOf(null)
}

/** Runs [checks] (or only the one named [only]), showing on screen the engine the running check asks to. */
@Composable
fun CheckScreen(checks: List<Check>, only: String?) {
    val host = remember { CheckHost() }
    LaunchedEffect(Unit) { runChecks(checks.filter { only == null || it.name == only }, host, this) }
    Box(Modifier.fillMaxSize().background(Color(0xFF202124)).padding(top = 80.dp), contentAlignment = Alignment.TopCenter) {
        val engine = host.shown
        if (engine != null) key(engine) { engine.Surface(Modifier.size(320.dp, 480.dp)) }
    }
}
