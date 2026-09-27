package dev.supermux.editor.sample

/** Milliseconds on the platform's event clock (the web: performance.now(), the timebase of DOM events). */
expect fun platformNowMs(): Double

/** When the last key event of the benchmark's typing driver arrived ([platformNowMs]), or -1. */
expect fun lastInputEventMs(): Double

/** True while a key event's DOM dispatch is running (the web; false elsewhere). */
expect fun insideKeyEvent(): Boolean

/** The key of the last key event ([lastInputEventMs]): the benchmark reports latency per key. */
expect fun lastInputKind(): String

/**
 * iOS: sends the focused input view a space-bar trackpad drag of (dx, dy) points (the editor's debug
 * driver), for the settings sheet's "floating cursor" probe; null where there is none.
 */
expect val platformFloatingCursorDrag: ((dx: Double, dy: Double) -> Boolean)?
