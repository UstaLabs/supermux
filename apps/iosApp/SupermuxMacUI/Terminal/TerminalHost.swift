import SwiftTerm
import SwiftUI
import Shared

/// The Mac shell's terminal owner — split out of `Supermux/Terminal/TerminalHost.swift` by the H6
/// cutover, which left only `TerminalIO` and `TerminalCoordinator` (both of which the Compose
/// shell's `TerminalVendor` drives) on the iOS side. `TerminalHost` and `TerminalSession` take a
/// `BrokerSession`, which is this shell's connection type; Compose gets its `TerminalClient` from
/// Kotlin instead.

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
        tv.font = PlatformFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        tv.nativeBackgroundColor = PlatformColor(TerminalTheme.background)
        tv.nativeForegroundColor = PlatformColor(TerminalTheme.foreground)
        self.view = tv

        let session = TerminalSession(broker: broker, sessionId: sessionId,
                                      kind: kind, terminalId: terminalId)
        self.session = session

        // Coordinator owns the terminal-view delegate (send/sizeChanged → session), the
        // hardware-keyboard policy, AND the predictive-echo pipeline (engine + adapter),
        // wired once to the persistent view.
        let coordinator = TerminalCoordinator(io: session)
        self.delegate = coordinator
        tv.terminalDelegate = coordinator
        coordinator.attach(tv)

        // Feed pty output through the predictive-echo pipeline on the main actor. The engine
        // reconciles predictions against the authoritative bytes and re-emits them inside a
        // Passthrough op, so there is NO separate tv.feed here — handleOutput does all the
        // writing (mirrors the web output handler that dropped its separate term.write).
        session.onBytes = { [weak coordinator] bytes in
            coordinator?.handleOutput(bytes)
        }
        session.start()
    }

    func stop() {
        session.stop()
        delegate.teardownPrediction()   // drop the engine + adapter (web parity: predictor = null)
    }
}

/// Where a terminal's keystrokes and measured grid go. `TerminalSession` (the SwiftUI shell's
/// websocket controller) is one implementation; `KotlinTerminalIO` (the Compose shell's bridge to
/// the shared `TerminalClient`) is the other.
///
/// It exists so `TerminalCoordinator` — the hardware-keyboard policy, the pan→wheel scroll bridge
/// and the predictive-echo pipeline, which is the only genuinely hard part of the iOS terminal —
/// is written ONCE and reused by both shells instead of being forked for H5. @MainActor because
/// both implementations are, and the coordinator reaches them under `assumeIsolated`.
@MainActor

extension TerminalSession: TerminalIO {}
