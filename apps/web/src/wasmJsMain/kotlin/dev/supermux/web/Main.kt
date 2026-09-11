package dev.supermux.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.supermux.state.jsHttpFactory
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.configureWebResources

/**
 * Plan 1: proves the toolchain end to end — Compose paints, Ktor's Js engine reaches the broker
 * on the page's own origin with the session cookie. Plan 2 replaces [HelloScreen] with the shared
 * `SupermuxApp` root behind `WebPlatform`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // The app draws its own context menus (spec carry-forward); the native one would cover them.
    document.addEventListener("contextmenu", { it.preventDefault() })
    // stageForBroker puts the Compose resource tree under assets/ so it inherits the broker's
    // immutable cache rule; the runtime must look for it there. The two MUST agree.
    configureWebResources { resourcePathMapping { path -> "assets/$path" } }
    ComposeViewport(document.body!!) { HelloScreen() }
}

@Composable
private fun HelloScreen() {
    // Hold the splash until Compose has actually painted: removing it before ComposeViewport mounts
    // leaves one blank frame. requestAnimationFrame runs after the first composition's frame.
    LaunchedEffect(Unit) { window.requestAnimationFrame { document.getElementById("splash")?.remove() } }
    val scope = rememberCoroutineScope()
    val http = remember { jsHttpFactory()(null) }
    var host by remember { mutableStateOf("(not asked yet)") }
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Supermux — Compose for Web", style = MaterialTheme.typography.headlineSmall)
                Text("origin: ${window.location.origin}")
                Button(onClick = {
                    scope.launch {
                        host = runCatching { http.get("${window.location.origin}/host").bodyAsText() }
                            .getOrElse { "error: $it" }
                    }
                }) { Text("GET /host") }
                Text(host, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
