package dev.supermux.editor.compose

import androidx.compose.foundation.magnifier

internal actual fun detectApplePlatform(): Boolean = false


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? = null

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier =
    this.then(androidx.compose.ui.Modifier.magnifier(sourceCenter = { center() }))
