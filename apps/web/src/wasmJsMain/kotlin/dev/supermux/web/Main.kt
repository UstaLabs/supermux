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

/**
 * Plan 1: proves the toolchain end to end — Compose paints, Ktor's Js engine reaches the broker
 * on the page's own origin with the session cookie. Plan 2 replaces [HelloScreen] with the shared
 * `SupermuxApp` root behind `WebPlatform`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // The app draws its own context menus (spec carry-forward); the native one would cover them.
    document.addEventListener("contextmenu", { it.preventDefault() })
    document.getElementById("splash")?.remove()
    ComposeViewport(document.body!!) { HelloScreen() }
}

@Composable
private fun HelloScreen() {
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
