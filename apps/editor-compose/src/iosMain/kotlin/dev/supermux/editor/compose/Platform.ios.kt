package dev.supermux.editor.compose

internal actual fun detectApplePlatform(): Boolean = true


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? = null

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier = this

internal actual val platformTextToolbarPreferred: Boolean = true
