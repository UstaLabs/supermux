import SwiftUI

/// Connecting / Connected / Disconnected / Needs-password pill for the Display pane.
/// Visual parity with `TerminalPane`'s `StatusChip` (colored dot + label inside a
/// `.ultraThinMaterial` capsule, `Theme.teal` for connected). Its own 4-state enum so
/// it is independent of `VncSession`/`ScrcpySession`; callers map their status into it.
struct DisplayStatusChip: View {
    enum State { case connecting, connected, disconnected, needsPassword }

    let state: State

    var body: some View {
        let (label, tint): (String, SwiftUI.Color) = switch state {
        case .connecting: ("Connecting…", .orange)
        case .connected: ("Connected", Theme.teal)
        case .disconnected: ("Disconnected", .secondary)
        case .needsPassword: ("Password required", .orange)
        }
        return HStack(spacing: 6) {
            Circle().fill(tint).frame(width: 6, height: 6)
            Text(label).font(.system(size: 11)).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 8).padding(.vertical, 3)
        .background(.ultraThinMaterial, in: Capsule())
    }
}
