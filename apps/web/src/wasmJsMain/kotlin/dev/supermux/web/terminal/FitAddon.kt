package dev.supermux.web.terminal

/**
 * `@xterm/addon-fit` — recomputes cols/rows from the container's pixel size. The surface calls
 * [fit] on attach and from a `ResizeObserver`; xterm then emits `onResize`, which is what resizes
 * the remote pty (the grid owns its own geometry, exactly like desktop/Android).
 */
@JsModule("@xterm/addon-fit")
external object FitAddonModule {
    class FitAddon : JsAny {
        fun fit()
    }
}

/** The package's named export (see [dev.supermux.web.terminal.Xterm] for why it is nested). */
typealias FitAddon = FitAddonModule.FitAddon
