package dev.supermux.editor.compose

internal actual fun detectApplePlatform(): Boolean = true


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? = null
