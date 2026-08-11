package dev.supermux.desktop.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.delay

/**
 * Live, test-source-only smoke for the complete editor path: bundled JBR/JCEF, file:// extraction,
 * CodeMirror 6 initialization, and the message-router `onReady` bridge.
 */
fun main() {
    check(org.cef.CefApp::class.java.module.name == "jcef") {
        "Expected JCEF classes from the bundled JBR module, got ${org.cef.CefApp::class.java.module}"
    }
    println(
        "JCEF_EDITOR_SMOKE apiModule=${org.cef.CefApp::class.java.module.name} " +
            "source=${org.cef.CefApp::class.java.protectionDomain.codeSource?.location ?: "JBR image"}",
    )
    var editorBecameReady = false
    var failure: String? = null

    application {
        var shuttingDown by remember { mutableStateOf(false) }
        var editorReady by remember { mutableStateOf(false) }
        val jcefState by JcefRuntime.state.collectAsState()

        LaunchedEffect(editorReady) {
            if (editorReady) {
                editorBecameReady = true
                println("JCEF_EDITOR_SMOKE ready")
                delay(250)
                shuttingDown = true
            }
        }
        LaunchedEffect(jcefState) {
            if (jcefState is JcefState.Error) {
                failure = (jcefState as JcefState.Error).msg
                shuttingDown = true
            }
        }
        LaunchedEffect(Unit) {
            delay(20_000)
            if (!editorReady) {
                failure = "timed out waiting for CodeMirror; JCEF state=${JcefRuntime.state.value}"
                shuttingDown = true
            }
        }
        LaunchedEffect(shuttingDown) {
            if (shuttingDown) {
                delay(100)
                exitApplication()
            }
        }

        Window(onCloseRequest = { shuttingDown = true }, title = "JCEF editor smoke") {
            if (shuttingDown) return@Window

            MaterialTheme {
                EditorSurface(
                    jcefState = jcefState,
                    content = "fun main() = println(\"JCEF editor smoke\")\n",
                    filename = "Smoke.kt",
                    lineWrap = true,
                    fontSize = 14,
                    scrollTop = 0,
                    revealLine = null,
                    onChange = {},
                    onSave = {},
                    onRevealConsumed = {},
                    onFontSize = {},
                    onEngineReadyChange = { editorReady = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    JcefRuntime.dispose()
    check(editorBecameReady) { failure ?: "CodeMirror did not become ready" }
}
