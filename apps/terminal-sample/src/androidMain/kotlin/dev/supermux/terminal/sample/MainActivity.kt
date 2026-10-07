package dev.supermux.terminal.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier

/**
 * The Android sample.
 *
 * The whole host is this file: the terminal, the fixtures and the diagnostics are common code, and
 * nothing about the surface is Android-specific — no `TextView`, no `SurfaceView`, no native widget
 * anywhere in the rendering path.
 *
 * `:terminal-sample:assembleDebug` builds it. RUNNING it needs a device or an emulator; see
 * `benchmarks/2026-09-terminal.md` for what was and was not exercised on hardware.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The terminal draws its own background edge to edge; the insets belong to the sample's
            // chrome, so they are consumed here rather than inside the package.
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .consumeWindowInsets(WindowInsets.safeDrawing),
            ) {
                SampleRoot()
            }
        }
    }
}
