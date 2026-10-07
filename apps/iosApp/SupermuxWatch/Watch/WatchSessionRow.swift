import SwiftUI

/// One session row on the watch: the unified status indicator (working spinner › unread green
/// › git glyph › neutral) + name + last-message preview, mirroring SessionStatusRail on phone.
struct WatchSessionRow: View {
    let session: SessionInfo

    private var working: Bool { isWorking(session.phase) }
    private var waiting: Bool { session.waiting == true }
    private var unread: Bool { session.unread ?? false }
    private var preview: String { session.lastText ?? session.agent ?? "" }

    var body: some View {
        HStack(spacing: 8) {
            statusRail.frame(width: 14, alignment: .center)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(session.name)
                        .font(.headline)
                        .fontWeight((!working && unread) ? .bold : .semibold)
                        .lineLimit(1)
                    if session.mute ?? false {
                        Image(systemName: "bell.slash.fill").font(.caption2).foregroundStyle(.tertiary)
                    }
                    Spacer(minLength: 0)
                }
                if !preview.isEmpty {
                    Text(preview).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .padding(.vertical, 2)
    }

    /// Priority: working spinner › idle+unread green › waiting hourglass › git › gray neutral.
    @ViewBuilder private var statusRail: some View {
        if working {
            ProgressView().controlSize(.mini)
        } else if unread {
            // Larger solid green + soft ring — distinct from the quiet gray neutral.
            ZStack {
                Circle()
                    .stroke(Color.green.opacity(0.4), lineWidth: 1.5)
                    .frame(width: 10, height: 10)
                Circle()
                    .fill(Color.green)
                    .frame(width: 7, height: 7)
            }
            .accessibilityLabel("unread")
        } else if waiting {
            // Idle but background tasks still running — the harness will wake the agent.
            glyph("hourglass", .orange)
        } else if let st = sessionStatus(session.git) {
            switch (st.kind, st.level) {
            case (.worktree, .done):     glyph("checkmark", .green)
            case (.worktree, .notDone):  glyph("arrow.triangle.branch", .orange)
            case (.worktree, .pristine): neutralDot
            case (.remote, .done):       glyph("checkmark.icloud", .green)
            case (.remote, .notDone):    glyph("icloud", .orange)
            case (.remote, .pristine):   neutralDot   // unreachable (remote is never pristine); keeps the switch exhaustive
            }
        } else {
            neutralDot
        }
    }
    private func glyph(_ name: String, _ color: Color) -> some View {
        Image(systemName: name).font(.caption2.weight(.semibold)).foregroundStyle(color)
    }
    private var neutralDot: some View {
        Circle().fill(Color.secondary.opacity(0.3)).frame(width: 6, height: 6)
    }
}
