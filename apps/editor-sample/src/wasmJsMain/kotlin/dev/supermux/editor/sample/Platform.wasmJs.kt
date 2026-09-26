package dev.supermux.editor.sample

actual fun platformNowMs(): Double = js("performance.now()")

/** index.html records every keydown's arrival (capture phase, before Compose sees it). */
actual fun lastInputEventMs(): Double = js("(window.__keyTs === undefined ? -1 : window.__keyTs)")

actual fun insideKeyEvent(): Boolean = js("window.__inKey === true")

actual fun lastInputKind(): String = js("String(window.__keyKind || '')")
