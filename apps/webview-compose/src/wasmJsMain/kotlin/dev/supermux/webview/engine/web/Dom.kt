package dev.supermux.webview.engine.web

/** The app's origin, as the browser writes it (`"null"` for an opaque one). */
internal fun pageOrigin(): String = js("String(window.location.origin)")
