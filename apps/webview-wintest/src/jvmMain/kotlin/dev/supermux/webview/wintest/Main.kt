package dev.supermux.webview.wintest

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.GraphicsEnvironment
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * The capture test app. `-Dwv2.scenario=<name>` runs one scripted scenario and exits; `manual` (the default) only shows
 * the page, to poke at by hand in the VM's console. Every knob is a `-Dwv2.*` property (TestOptions).
 */
fun main() {
    val options = TestOptions.fromSystemProperties()
    WinNative.load(options.dll)
    val web = WinWebView(options)
    // 1280x820 dp, shrunk to the screen's usable area (the taskbar excluded) so the whole window is on screen: the
    // scenarios click every part of it. The x64 test VM's screen is 1280x800.
    val usable = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val position = WindowPosition((usable.x + 24).dp, (usable.y + 16).dp)
    val size = DpSize(minOf(1280, usable.width - 48).dp, minOf(820, usable.height - 32).dp)
    application {
        val windowState = rememberWindowState(position = position, size = size)
        Window(
            onCloseRequest = {
                WinNative.stop()
                exitApplication()
            },
            state = windowState,
            title = "wv2 capture test - ${options.scenario}",
        ) {
            val scope = rememberCoroutineScope()
            val app = remember { AppState(window, web, options, scope) }
            TestScreen(app)
            LaunchedEffect(Unit) {
                app.attach()
                if (options.scenario != "manual") {
                    runScenario(app) { code ->
                        WinNative.stop()
                        exitProcess(code)
                    }
                }
            }
        }
    }
}

@Composable
private fun TestScreen(app: AppState) {
    app.focusManager = LocalFocusManager.current
    app.density = LocalDensity.current.density
    MaterialTheme {
        Column(
            Modifier.fillMaxSize().padding(8.dp).onPreviewKeyEvent { event ->
                if (event.key == Key.Tab && event.type == KeyEventType.KeyDown) app.lastTabShift = event.isShiftPressed
                false
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = app.fieldA,
                    onValueChange = { app.fieldA = it },
                    label = { Text("compose A") },
                    singleLine = true,
                    modifier = Modifier.width(360.dp).focusRequester(app.focusA)
                        .onGloballyPositioned { app.fieldAInWindow = it.rectInWindow() },
                )
                Spacer(Modifier.width(16.dp))
                Text(statusLine(app), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                val fixed = app.options.surfaceWidth > 0 && app.options.surfaceHeight > 0
                val surfaceModifier = if (fixed) {
                    with(LocalDensity.current) {
                        // unbounded: the 1080p surface may be larger than the window; the window clips what it shows.
                        Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                            .requiredSize(app.options.surfaceWidth.toDp(), app.options.surfaceHeight.toDp())
                    }
                } else {
                    Modifier.fillMaxSize()
                }
                CaptureSurface(app, surfaceModifier)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = app.fieldB,
                    onValueChange = { app.fieldB = it },
                    label = { Text("compose B") },
                    singleLine = true,
                    modifier = Modifier.width(360.dp).focusRequester(app.focusB)
                        .onGloballyPositioned { app.fieldBInWindow = it.rectInWindow() },
                )
                Spacer(Modifier.width(16.dp))
                Button(onClick = { app.buttonClicks++ }) { Text("compose button") }
            }
        }
    }
}

private fun statusLine(app: AppState): String {
    val web = app.web
    val state = web.failure ?: web.readyInfo?.substringBefore('|')?.let { "WebView2 $it" } ?: "starting"
    return "$state · page focus=${web.webFocused} · ${app.options.label}"
}

private fun LayoutCoordinates.rectInWindow(): BoundsDebouncer.Rect {
    val origin = positionInWindow()
    return BoundsDebouncer.Rect(origin.x.roundToInt(), origin.y.roundToInt(), size.width, size.height)
}
