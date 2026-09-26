package dev.supermux.editor.compose

internal actual fun detectApplePlatform(): Boolean = true


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? = null

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier = this

internal actual val platformTextToolbarPreferred: Boolean = true

internal actual val platformInputOnAnyFocus: Boolean = false

internal actual fun syncPlatformField(f: FieldText) {}

internal actual fun platformEditorClipboard(compose: EditorClipboard): EditorClipboard = compose

internal actual val platformSurfaceText: Boolean = true

internal actual fun platformFieldLabel(label: String) {}

internal actual val platformClearsFieldSemantics: Boolean = true

/**
 * iOS: Compose's `SoftwareKeyboardController.show()` makes the input view first responder, but only
 * while an input session exists; at the tap itself it does not yet (the session starts once the
 * field has recomposed with the touch's options), so the keyboard is asked for again here, after.
 */
@androidx.compose.runtime.Composable
internal actual fun rememberPlatformKeyboardShow(): (() -> Unit)? {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    return androidx.compose.runtime.remember(keyboard) { { keyboard?.show(); Unit } }
}
