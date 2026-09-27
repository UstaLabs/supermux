package dev.supermux.editor.compose

import androidx.compose.foundation.magnifier

internal actual fun detectApplePlatform(): Boolean = false


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? = null

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.magnifier(sourceCenter = { center() }))

internal actual val platformTextToolbarPreferred: Boolean = true

internal actual val platformInputOnAnyFocus: Boolean = false

internal actual fun syncPlatformField(c: EditorController, f: FieldText) {}

internal actual fun platformEditorClipboard(compose: EditorClipboard): EditorClipboard = compose

internal actual val platformSurfaceText: Boolean = true

internal actual fun platformFieldLabel(c: EditorController, label: String) {}

internal actual val platformClearsFieldSemantics: Boolean = false

@androidx.compose.runtime.Composable
internal actual fun rememberPlatformKeyboardShow(): (() -> Unit)? {
    val view = androidx.compose.ui.platform.LocalView.current
    return androidx.compose.runtime.remember(view) {
        {
            val imm = view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(view, 0)
            Unit
        }
    }
}

internal actual fun platformClipboardHasText(): Boolean? = null

internal actual fun platformAfterKeyboardShown() {}

internal actual fun platformFocusChanged(c: EditorController, focused: Boolean) {}
