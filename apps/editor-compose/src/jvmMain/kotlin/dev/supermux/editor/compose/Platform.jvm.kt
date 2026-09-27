package dev.supermux.editor.compose

internal actual fun detectApplePlatform(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let { it.startsWith("mac") || it.startsWith("darwin") }


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? = null

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier = this

internal actual val platformTextToolbarPreferred: Boolean = false

internal actual val platformInputOnAnyFocus: Boolean = true

internal actual fun syncPlatformField(f: FieldText) {}

internal actual fun platformEditorClipboard(compose: EditorClipboard): EditorClipboard = compose

internal actual val platformSurfaceText: Boolean = true

internal actual fun platformFieldLabel(label: String) {}

internal actual val platformClearsFieldSemantics: Boolean = false

@androidx.compose.runtime.Composable
internal actual fun rememberPlatformKeyboardShow(): (() -> Unit)? = null

internal actual fun platformClipboardHasText(): Boolean? = null
