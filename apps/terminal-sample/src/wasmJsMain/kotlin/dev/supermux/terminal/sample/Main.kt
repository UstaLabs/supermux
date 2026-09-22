package dev.supermux.terminal.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.ComposeViewport
import dev.supermux.terminal.TerminalRuntime
import kotlinx.browser.document

/**
 * The browser sample.
 *
 * The one thing a browser host must do that the others need not: **await
 * `TerminalRuntime.initialize()` before the first session**. The engine is a WebAssembly module
 * that is fetched and compiled asynchronously, so until it has loaded `createTerminalEngine` throws
 * `TerminalEngineUnavailableException(NOT_INITIALIZED)`. This file is therefore also the reference
 * for the error path: a failed load is shown, with its typed reason, instead of a blank page.
 *
 * The other browser requirement is a BUILD one and lives in `build.gradle.kts`: the Kotlin/Wasm
 * toolchain does not copy a dependency klib's resources next to the consumer's module, so the app
 * re-exports `terminal-loader.mjs` and `supermux-terminal.wasm` as its own wasmJs resources
 * (terminal-core/native/README.md, "Browser (wasmJs)").
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        var state by remember { mutableStateOf<EngineState>(EngineState.Loading) }
        LaunchedEffect(Unit) {
            state = try {
                TerminalRuntime.initialize()
                EngineState.Ready
            } catch (error: Throwable) {
                EngineState.Failed(error.message ?: error.toString())
            }
        }
        SampleTheme {
            when (val current = state) {
                EngineState.Loading -> Splash("loading the terminal engine (wasm)…", Color(0xFF8B9488))
                is EngineState.Failed -> Splash(
                    "the terminal engine could not start: ${current.message}",
                    Color(0xFFE06C75),
                )
                EngineState.Ready -> SampleRoot()
            }
        }
    }
}

private sealed interface EngineState {
    data object Loading : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

@Composable
private fun Splash(message: String, color: Color) {
    Box(Modifier.fillMaxSize().background(Color(0xFF101311)), contentAlignment = Alignment.Center) {
        Text(message, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(24.dp))
    }
}
