package dev.supermux.editor.sample

import platform.Foundation.NSProcessInfo

actual fun platformNowMs(): Double = NSProcessInfo.processInfo.systemUptime * 1000.0

// The in-app benchmark's key-event driver exists on the desktop and the web only.
actual fun lastInputEventMs(): Double = -1.0

actual fun insideKeyEvent(): Boolean = false

actual fun lastInputKind(): String = ""

actual val platformFloatingCursorDrag: ((dx: Double, dy: Double) -> Boolean)? = { dx, dy -> dev.supermux.editor.compose.debugDriveFloatingCursor(dx, dy) }
