package dev.supermux.editor.sample

actual fun platformNowMs(): Double = System.nanoTime() / 1e6

// The in-app benchmark's key-event driver exists on the desktop and the web only.
actual fun lastInputEventMs(): Double = -1.0

actual fun insideKeyEvent(): Boolean = false

actual fun lastInputKind(): String = ""

actual val platformFloatingCursorDrag: ((dx: Double, dy: Double) -> Boolean)? = null
