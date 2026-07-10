import SwiftUI
import Shared
import SwiftTerm
#if canImport(UIKit)
import UIKit
#endif

/// One terminal: a SwiftTerm view + a connection status chip. The live state (websocket
/// + emulator scrollback) lives in a `TerminalHost` cached in `BrokerSession`, so toggling
/// this pane off/on or remounting it (tab/session switch) REUSES the warm terminal instead
/// of reconnecting + re-rendering. Used for both scratch shells and the agent viewer.
struct TerminalPane: View {
    let broker: BrokerSession
    let session: SessionInfo
    let kind: String                 // "scratch" | "agent"
    let terminalId: String?          // scratch tab id; nil for agent
    var onExit: () -> Void = {}

    @State private var ended = false
    #if os(iOS)
    @State private var keyboardHeight: CGFloat = 0
    #endif

    // The cached, persistent terminal (live connection + scrollback). Computed, but always
    // returns the SAME instance for this (session, kind, terminalId) — the cache owns it.
    private var host: TerminalHost {
        broker.terminalHost(sessionId: session.id, kind: kind, terminalId: terminalId)
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Theme.terminalBackground.ignoresSafeArea()
            if ended {
                endedState
            } else {
                SwiftTermView(view: host.view)
                    .ignoresSafeArea(.container, edges: .bottom)
                StatusChip(status: host.session.status)
                    .padding(8)
            }
        }
        #if os(iOS)
        // Software-keyboard dismiss affordance — meaningless on a hardware-keyboard Mac
        // (the whole subsystem: state + notifications + overlay is iOS-only, matching EditorPane).
        .overlay(alignment: .bottom) { keyboardDismissOverlay }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { note in
            if let f = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect { keyboardHeight = f.height }
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardHeight = 0 }
        #endif
        .onAppear {
            // The cache owns the connection lifecycle (created + started on first access).
            // We only (re)point onExit at THIS mount's callback — the host outlives remounts.
            host.session.onExit = {
                ended = true
                onExit()
                broker.dropTerminalHost(sessionId: session.id, kind: kind, terminalId: terminalId)
            }
        }
        // No onDisappear stop(): toggling the pane off must NOT tear down the live terminal.
    }

    // Floating ⌄ button to dismiss the terminal keyboard. SwiftTerm owns a UIKit keyboard, so
    // SwiftUI tap/scroll dismissal and the global resignFirstResponder don't reach it — we
    // resign the cached TerminalView directly. Placed just above the keyboard via its frame
    // height. SM_KBD=1 fakes a height for headless screenshot verification.
    // iOS-only: a Mac always has a hardware keyboard, so there is nothing to dismiss.
    #if os(iOS)
    private var effectiveKbHeight: CGFloat {
        keyboardHeight > 0 ? keyboardHeight : (ProcessInfo.processInfo.environment["SM_KBD"] == "1" ? 320 : 0)
    }
    @ViewBuilder private var keyboardDismissOverlay: some View {
        if effectiveKbHeight > 0 && !ended {
            GeometryReader { geo in
                Button {
                    host.view.resignFirstResponder()
                } label: {
                    Image(systemName: "keyboard.chevron.compact.down")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.teal)
                        .frame(width: 44, height: 44)
                        .glassEffect(.regular, in: Circle())
                }
                .buttonStyle(.plain)
                // Layout-based positioning (NOT .offset, which leaves the tap target behind):
                // padding pushes the button up by the keyboard height so it sits just above it
                // AND its hit region moves with it.
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .padding(.trailing, 16)
                .padding(.bottom, max(0, effectiveKbHeight - geo.safeAreaInsets.bottom) + 8)
            }
            .ignoresSafeArea(.keyboard)
        }
    }
    #endif

    private var endedState: some View {
        VStack(spacing: 10) {
            Image(systemName: kind == "agent" ? "terminal.fill" : "xmark.circle")
                .font(.largeTitle).foregroundStyle(.secondary)
            Text(kind == "agent" ? "Agent view unavailable" : "Terminal ended")
                .font(.callout).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Connecting / Connected / Disconnected pill (parity with Android's StatusChip).
struct StatusChip: View {
    let status: TerminalSession.Status
    var body: some View {
        let (label, tint): (String, SwiftUI.Color) = switch status {
        case .connecting: ("Connecting…", .orange)
        case .connected: ("Connected", Theme.teal)
        case .disconnected: ("Disconnected", .secondary)
        }
        return HStack(spacing: 6) {
            Circle().fill(tint).frame(width: 6, height: 6)
            Text(label).font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 8).padding(.vertical, 3)
        .background(.ultraThinMaterial, in: Capsule())
    }
}
