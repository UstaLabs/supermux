package dev.supermux.editor.compose

import androidx.compose.foundation.magnifier

internal actual fun detectApplePlatform(): Boolean = false


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? = null

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.magnifier(sourceCenter = { center() }))

internal actual val platformTextToolbarPreferred: Boolean = true

internal actual val platformInputOnAnyFocus: Boolean = false

internal actual fun syncPlatformField(f: FieldText) {}

internal actual fun platformEditorClipboard(compose: EditorClipboard): EditorClipboard = compose

internal actual val platformSurfaceText: Boolean = true

internal actual fun platformFieldLabel(label: String) {}

internal actual val platformClearsFieldSemantics: Boolean = false
