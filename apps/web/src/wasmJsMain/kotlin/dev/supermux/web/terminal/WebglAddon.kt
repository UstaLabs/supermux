package dev.supermux.web.terminal

/**
 * `@xterm/addon-webgl` — GPU glyph rendering. Optional by construction: a machine with no WebGL2
 * context throws on load (or emits `onContextLoss` later), and xterm falls back to its canvas
 * renderer, so the surface loads this inside a `runCatching`.
 */
@JsModule("@xterm/addon-webgl")
external object WebglAddonModule {
    class WebglAddon : JsAny {
        /** Fires when the browser drops the GL context (GPU reset, too many contexts on the page).
         *  Disposing the addon from here is xterm's documented fallback to the canvas renderer. */
        fun onContextLoss(cb: () -> Unit)
        fun dispose()
    }
}

/** The package's named export (see [dev.supermux.web.terminal.Xterm] for why it is nested). */
typealias WebglAddon = WebglAddonModule.WebglAddon
