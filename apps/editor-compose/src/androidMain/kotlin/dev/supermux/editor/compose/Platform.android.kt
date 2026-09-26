package dev.supermux.editor.compose

internal actual fun detectApplePlatform(): Boolean = false


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? = null
