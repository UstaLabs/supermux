package dev.supermux.editor.compose

private fun appleNavigator(): Boolean =
    js("/Mac|iPhone|iPad|iPod/.test((navigator.platform || '') + ' ' + (navigator.userAgent || ''))")

internal actual fun detectApplePlatform(): Boolean = appleNavigator()
