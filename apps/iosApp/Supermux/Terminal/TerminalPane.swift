import SwiftUI
import Shared

/// One terminal: a SwiftTerm view + a connection status chip, lifecycle-bound to
/// a `TerminalSession`. Used for both scratch shells and the agent viewer.
struct TerminalPane: View {
    let broker: BrokerSession
    let session: SessionInfo
    let kind: String                 // "scratch" | "agent"
    let terminalId: String?          // scratch tab id; nil for agent
    var onExit: () -> Void = {}

    @State private var term: TerminalSession?
    @State private var ended = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Theme.terminalBackground.ignoresSafeArea()
            if ended {
                endedState
            } else if let term {
                SwiftTermView(session: term)
                    .ignoresSafeArea(.container, edges: .bottom)
                StatusChip(status: term.status)
                    .padding(8)
            }
        }
        .onAppear {
            guard term == nil else { return }
            let t = TerminalSession(broker: broker, sessionName: session.name,
                                    kind: kind, terminalId: terminalId)
            t.onExit = {
                ended = true
                onExit()
            }
            t.start()
            term = t
        }
        .onDisappear {
            term?.stop()
            term = nil
        }
    }

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
        let (label, tint): (String, Color) = switch status {
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
