// iOS only: the macOS target has its own SwiftUI shell (SupermuxMacUI/) and no Compose root.
#if os(iOS)
import SwiftTerm
import SupermuxKit
import UIKit

/// The Compose shell's terminal: Swift vends a live SwiftTerm `TerminalView` and Kotlin hosts it in
/// a `UIKitView` (cluster H5).
///
/// This is the SMALLEST possible Swift surface for the job. It creates the view, points the shared
/// `TerminalCoordinator` at a Kotlin-backed `TerminalIO`, and hands the three verbs Kotlin needs
/// (`feed`, `setActive`, `dispose`) across the bridge. Everything else — connecting, reconnecting,
/// the input FIFO, the tabs, the key bar, the keep-alive and the focus rule — is shared Kotlin, and
/// everything hard about SwiftTerm specifically (hardware-keyboard suppression, the one-finger pan
/// that turns into SGR wheel bytes so tmux scrolls, predictive local echo) is `TerminalCoordinator`,
/// reused verbatim rather than re-derived.
///
/// What is deliberately NOT here: `TerminalSession` and `BrokerSession`. The Compose shell's bytes
/// come from the shared `TerminalClient`, driven by Kotlin, so this path never touches the Swift
/// reducer that H6 deletes.
final class ComposeTerminalVendor: NSObject, IosTerminalVendor {

    func make(
        onInput: @escaping (KotlinByteArray) -> Void,
        onSize: @escaping (KotlinInt, KotlinInt) -> Void
    ) -> any IosTerminalHandle {
        MainActor.assumeIsolated { ComposeTerminalHandle(onInput: onInput, onSize: onSize) }
    }
}

/// One live terminal for the Compose shell. Main-thread only, like every member of the Kotlin
/// interface it implements.
@MainActor
final class ComposeTerminalHandle: NSObject, IosTerminalHandle {

    private let terminal: TerminalView
    private let io: KotlinTerminalIO
    private let coordinator: TerminalCoordinator
    private var disposed = false

    /// The view Compose parents. Stable for the life of the handle — Kotlin remembers the handle
    /// across a `KeepAlivePanel` hide/show, so the emulator buffer and the scrollback survive a
    /// tab switch exactly as the SwiftUI shell's cached `TerminalHost` made them survive a remount.
    var view: UIView { terminal }

    init(onInput: @escaping (KotlinByteArray) -> Void, onSize: @escaping (KotlinInt, KotlinInt) -> Void) {
        let tv = TerminalView(frame: .zero)
        tv.font = PlatformFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        tv.nativeBackgroundColor = PlatformColor(TerminalTheme.background)
        tv.nativeForegroundColor = PlatformColor(TerminalTheme.foreground)
        self.terminal = tv

        let sink = KotlinTerminalIO(onInput: onInput, onSize: onSize)
        self.io = sink
        // hostDrawsAccessoryBar: the shared `TerminalKeyBar` is drawn by Kotlin above the keyboard,
        // so SwiftTerm's own TerminalAccessory must go — otherwise both rows appear, stacked.
        let coordinator = TerminalCoordinator(io: sink, hostDrawsAccessoryBar: true)
        self.coordinator = coordinator
        super.init()

        tv.terminalDelegate = coordinator
        coordinator.attach(tv)
    }

    /// Server bytes → the emulator, THROUGH the prediction pipeline (`handleOutput`), never as a
    /// bare `feed`: a confirmed prediction is confirmed by the authoritative bytes painting over
    /// its dim cell inside a `Passthrough` op, so a direct feed here would double-render.
    ///
    /// The `KotlinByteArray` is passed on untouched — the shared `PredictionEngine` takes it as-is,
    /// so the hot path costs zero per-byte bridge calls.
    func feed(bytes: KotlinByteArray) {
        guard !disposed else { return }
        coordinator.handleOutput(bytes)
    }

    /// Foreground pane, or not.
    ///
    /// `true` only PERMITS first-responder status; it does not take it. Auto-focusing on
    /// composition is the rule all three hosts reject (desktop: "no auto-focus on composition"),
    /// and here it would raise the soft keyboard every time a workspace drew a terminal tab.
    /// `false` resigns, so switching tabs or backgrounding the app drops the keyboard with the pane.
    func setActive(active: Bool) {
        guard !disposed, !active, terminal.isFirstResponder else { return }
        terminal.resignFirstResponder()
    }

    /// See the Kotlin KDoc: this exists so the predictive echo is skipped while the shared key bar
    /// has a modifier armed. The re-encoding is Kotlin's (`TerminalKeySink.applyArmedModifiers`).
    func setMods(ctrl: Bool, alt: Bool) {
        coordinator.modsArmed = ctrl || alt
    }

    func dispose() {
        guard !disposed else { return }
        disposed = true
        coordinator.teardownPrediction()
        terminal.terminalDelegate = nil
        terminal.removeFromSuperview()
    }
}

/// The Kotlin end of `TerminalIO`: SwiftTerm's keystrokes and measured grid, forwarded to the
/// shared `TerminalClient` through the two closures Kotlin supplied.
///
/// Ordering is why `sendInput` does no hopping: `TerminalClient.sendInput` is a non-suspending FIFO
/// enqueue and we are already on the main thread (SwiftTerm's delegate calls arrive there), so the
/// bytes reach the pty in the order the user typed them. `resize` is guarded on the KOTLIN side
/// (`cols > 0 && rows > 0`) because SwiftTerm's own guard only skips a size that is zero on both
/// axes — a transitional 0×H pass really does report zero columns.
@MainActor
final class KotlinTerminalIO: TerminalIO {
    private let onInput: (KotlinByteArray) -> Void
    private let onSize: (KotlinInt, KotlinInt) -> Void

    init(onInput: @escaping (KotlinByteArray) -> Void, onSize: @escaping (KotlinInt, KotlinInt) -> Void) {
        self.onInput = onInput
        self.onSize = onSize
    }

    func sendInput(_ bytes: [UInt8]) {
        onInput(bytes.toKotlin())
    }

    func resize(cols: Int, rows: Int) {
        onSize(KotlinInt(int: Int32(cols)), KotlinInt(int: Int32(rows)))
    }
}
#endif
