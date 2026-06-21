import SwiftUI
import SwiftTerm
import GameController
import UIKit

/// Owns ONE persistent terminal — the `TerminalSession` (websocket) plus the SwiftTerm
/// `TerminalView` that holds the emulator buffer. Cached per (session, kind, terminalId)
/// in `BrokerSession` so the live connection AND on-screen scrollback survive SwiftUI
/// remounts / pane toggles. Keeping the SAME `TerminalView` instance alive is what
/// preserves the scrollback; `SwiftTermView` just re-parents it into the new mount.
@MainActor
final class TerminalHost {
    let session: TerminalSession
    let view: TerminalView

    private let delegate: TerminalCoordinator

    init(broker: BrokerSession, sessionId: String, kind: String, terminalId: String?) {
        // Build the persistent emulator view (the setup that used to live in
        // SwiftTermView.makeUIView). This instance must outlive any single mount.
        let tv = TerminalView(frame: .zero)
        tv.font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        tv.nativeBackgroundColor = UIColor(Theme.terminalBackground)
        tv.nativeForegroundColor = UIColor(Theme.terminalForeground)
        self.view = tv

        let session = TerminalSession(broker: broker, sessionId: sessionId,
                                      kind: kind, terminalId: terminalId)
        self.session = session

        // Coordinator owns the terminal-view delegate (send/sizeChanged → session) AND
        // the hardware-keyboard policy, wired once to the persistent view.
        self.delegate = TerminalCoordinator(session: session)
        tv.terminalDelegate = delegate
        delegate.attach(tv)

        // Feed pty output into the emulator on the main actor.
        session.onBytes = { [weak tv] bytes in
            tv?.feed(byteArray: ArraySlice(bytes))
        }
        session.start()
    }

    func stop() { session.stop() }
}

/// The persistent terminal's delegate + hardware-keyboard policy. Lives in `TerminalHost`
/// (tied to the long-lived `TerminalView`) rather than a per-mount SwiftUI coordinator, so
/// the policy and the FIFO input wiring survive remounts. Plain `NSObject` (not @MainActor)
/// so it satisfies SwiftTerm's nonisolated `TerminalViewDelegate` — the @MainActor session
/// is reached via `assumeIsolated` (we ARE on the main thread when SwiftTerm calls us).
final class TerminalCoordinator: NSObject, TerminalViewDelegate {
    let session: TerminalSession
    private weak var tv: TerminalView?
    private var savedAccessory: UIView?
    private var suppressed = false
    private lazy var emptyInputView = UIView(frame: .zero)

    init(session: TerminalSession) {
        self.session = session
        super.init()
        // A connected hardware keyboard means the on-screen keyboard (soft keys + the
        // terminal accessory toolbar) shouldn't eat the screen — but the terminal stays
        // first responder so HARDWARE keystrokes still reach it. Re-apply on plug/unplug.
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(hardwareKeyboardChanged),
                       name: .GCKeyboardDidConnect, object: nil)
        nc.addObserver(self, selector: #selector(hardwareKeyboardChanged),
                       name: .GCKeyboardDidDisconnect, object: nil)
    }
    deinit { NotificationCenter.default.removeObserver(self) }

    /// Bind the SwiftTerm view and apply the current keyboard policy.
    func attach(_ terminal: TerminalView) {
        tv = terminal
        savedAccessory = terminal.inputAccessoryView   // SwiftTerm's TerminalAccessory (set in its setup)
        applyKeyboardPolicy()
    }

    @objc private func hardwareKeyboardChanged() { applyKeyboardPolicy() }

    /// Hardware keyboard present → suppress the soft keyboard (empty inputView) and the
    /// accessory toolbar; absent → restore both. Reloads input views live when focused so a
    /// connect/disconnect takes effect immediately. No-op when already in the right state.
    private func applyKeyboardPolicy() {
        guard let tv else { return }
        let hardware = GCKeyboard.coalesced != nil
        if hardware, !suppressed {
            if savedAccessory == nil { savedAccessory = tv.inputAccessoryView }
            tv.inputView = emptyInputView
            tv.inputAccessoryView = nil
            suppressed = true
        } else if !hardware, suppressed {
            tv.inputView = nil
            tv.inputAccessoryView = savedAccessory
            suppressed = false
        } else {
            return
        }
        if tv.isFirstResponder { tv.reloadInputViews() }
    }

    // SwiftTerm invokes these delegate methods on the main thread, but the protocol
    // is nonisolated while `TerminalSession` is @MainActor. Use assumeIsolated (we ARE
    // on the main thread) to call SYNCHRONOUSLY in delivery order — a Task hop here
    // could reorder keystrokes.
    func send(source: TerminalView, data: ArraySlice<UInt8>) {
        let bytes = Array(data)
        MainActor.assumeIsolated { session.sendInput(bytes) }
    }
    func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
        MainActor.assumeIsolated { session.resize(cols: newCols, rows: newRows) }
    }
    // The remaining TerminalViewDelegate requirements — no-ops for our use.
    // (All 10 are required; SwiftTerm's protocol has no default implementations.)
    func setTerminalTitle(source: TerminalView, title: String) {}
    func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
    func scrolled(source: TerminalView, position: Double) {}
    func requestOpenLink(source: TerminalView, link: String, params: [String : String]) {}
    func bell(source: TerminalView) {}
    func clipboardCopy(source: TerminalView, content: Data) {}
    func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
    func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
}
