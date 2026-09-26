package dev.supermux.editor.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.supermux.editor.syntax.NativeBackend

/**
 * The Android sample: the same [SampleApp] as the desktop window and the web page, edge to edge
 * (the app pads itself with the safe-drawing insets, the soft keyboard's included).
 * `:editor-sample:assembleDebug` builds it; the package is `dev.supermux.editor.sample`.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { SampleApp(loadBackend = { NativeBackend() }) }
    }
}
