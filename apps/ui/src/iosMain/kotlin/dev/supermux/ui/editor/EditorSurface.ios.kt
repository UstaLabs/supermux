package dev.supermux.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.ui.editor.engine.EditorEngine

/**
 * Draws NOTHING, on purpose, and is never reached today.
 *
 * `IosPlatform.editorEngine` is `UnavailableEditorEngineFactory`, so the factory's state is
 * `EngineState.Failed` and [EditorSurface] takes the native-fallback branch — the BasicTextField —
 * without ever composing a host. This actual exists only to satisfy the expect.
 *
 * H5 replaces it with a `UIKitView` over a `WKWebView` running the same `EditorWeb` cm6 bundle the
 * other two hosts load, at which point it gains the Android host's two behaviours (attach a couple
 * of frames late, stay invisible until cm6 first-paints).
 */
@Composable
actual fun EditorEngineHost(engine: EditorEngine, visible: Boolean, modifier: Modifier) {
    // No engine can exist on iOS until H5; see the KDoc.
}
