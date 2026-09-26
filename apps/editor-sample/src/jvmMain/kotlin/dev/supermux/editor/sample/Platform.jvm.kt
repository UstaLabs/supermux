package dev.supermux.editor.sample

actual fun platformNowMs(): Double = System.nanoTime() / 1e6

/** Set by the desktop typing driver when it posts a key event. */
@Volatile var lastKeyPostedMs: Double = -1.0

actual fun lastInputEventMs(): Double = lastKeyPostedMs

actual fun insideKeyEvent(): Boolean = false

actual fun lastInputKind(): String = "x"
