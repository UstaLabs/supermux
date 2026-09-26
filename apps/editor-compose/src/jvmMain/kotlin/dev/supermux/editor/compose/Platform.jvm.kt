package dev.supermux.editor.compose

internal actual fun detectApplePlatform(): Boolean =
    System.getProperty("os.name").orEmpty().lowercase().let { it.startsWith("mac") || it.startsWith("darwin") }
