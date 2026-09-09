package dev.supermux.ios

import platform.UIKit.UIView

/**
 * The Swift half of the terminal (cluster H5).
 *
 * WHY Swift vends the view at all, when the editor's browser is pure Kotlin/Native: SwiftTerm has
 * no Kotlin binding, and `TerminalCoordinator.swift` + `PredictionAdapter.swift` are ~470 lines of working
 * POLICY on top of it — the hardware-keyboard suppression, the one-finger pan → SGR wheel bytes
 * bridge that makes tmux scroll at all, the pre-send predictive echo. Re-deriving that against a
 * cinterop'd Objective-C surface would be a rewrite of the only part of the iOS terminal that was
 * ever hard. So Swift keeps the emulator and the input policy; Kotlin keeps everything that is the
 * SAME on three hosts — the `TerminalClient`, the tabs, the key bar, the keep-alive, the focus rule.
 *
 * The division is exactly the one `TerminalViewFactory` already documents for termlib and JediTerm:
 * **the grid owns its geometry**. SwiftTerm measures itself in `layoutSubviews`, reports through
 * [IosTerminalVendor.make]'s `onSize`, and Kotlin forwards that to `TerminalClient.resize` — never
 * the other way round.
 */
interface IosTerminalVendor {
    /**
     * Build one live terminal view, not yet parented (Compose's `UIKitView` does the parenting).
     *
     * @param onInput every byte the user typed — SwiftTerm's `send(source:data:)`, AFTER Swift's
     *   predictive echo has painted it. Called on the main thread, synchronously, in keystroke
     *   order: the shared `TerminalClient.sendInput` is a non-suspending FIFO enqueue, so order is
     *   preserved all the way to the pty.
     * @param onSize the grid SwiftTerm just measured itself into. Kotlin guards `cols > 0 &&
     *   rows > 0` before resizing the remote pty — SwiftTerm's own guard
     *   (`AppleTerminalView.processSizeChange`) only skips a size that is zero on BOTH axes, so a
     *   transitional 0×H layout pass really does report `cols = 0`.
     */
    fun make(
        onInput: (ByteArray) -> Unit,
        onSize: (cols: Int, rows: Int) -> Unit,
    ): IosTerminalHandle
}

/** One live Swift-vended terminal. Every member is main-thread only. */
interface IosTerminalHandle {
    /** The SwiftTerm view, for `UIKitView` to host. Stable for the life of the handle. */
    val view: UIView

    /**
     * Server bytes → the emulator, through Swift's prediction adapter (which is what CONFIRMS a
     * predicted keystroke, so this must not bypass it).
     *
     * The `ByteArray` crosses whole and is handed to the shared `PredictionEngine` as-is; nothing
     * on this path reads it element by element from Swift (see [bytesFrom] for why that matters).
     */
    fun feed(bytes: ByteArray)

    /**
     * Foreground pane, or not.
     *
     * `true` merely PERMITS first-responder status — it does not grab it. That is the rule all
     * three hosts share (`JediTermTerminalView`: "no auto-focus on composition"; Android's termlib
     * view likewise), and on iOS taking it would pop the soft keyboard every time a workspace tab
     * was drawn. `false` resigns it, so switching tabs drops the keyboard with the pane.
     */
    fun setActive(active: Boolean)

    /**
     * Tell the emulator whether a shared-key-bar modifier is armed right now.
     *
     * It needs to know for ONE reason: predictive local echo. Swift predicts a keystroke before the
     * bytes leave, and an armed Ctrl means the byte that actually leaves is a control code, not the
     * letter that was typed — so predicting the letter would paint a glyph the server never sends.
     * Android skips the echo on exactly this condition; this is how iOS learns to.
     *
     * The re-encoding itself is NOT done here: it is `TerminalKeySink.applyArmedModifiers`, shared
     * by both hosts, applied in Kotlin on the way through.
     */
    fun setMods(ctrl: Boolean, alt: Boolean)

    /** Release the view and its policy observers. Idempotent; the Kotlin client is stopped separately. */
    fun dispose()
}
