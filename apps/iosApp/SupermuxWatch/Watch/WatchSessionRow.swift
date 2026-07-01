import SwiftUI

/// One session row on the watch: the unified status indicator (working spinner › git glyph
/// › neutral) + name + last-message preview, mirroring the iPhone's SessionRow/SessionStatusRail.
struct WatchSessionRow: View {
    let session: SessionInfo

    private var working: Bool { isWorking(session.phase) }
    private var unread: Bool { session.unread ?? false }
    private var preview: String { session.lastText ?? session.agent ?? "" }

    var body: some View {
        HStack(spacing: 8) {
            statusRail.frame(width: 14, alignment: .center)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    if unread { Circle().fill(Color.accentColor).frame(width: 6, height: 6) }
                    Text(session.name)
                        .font(.headline).fontWeight(unread ? .bold : .semibold).lineLimit(1)
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

    @ViewBuilder private var statusRail: some View {
        if working {
            ProgressView().controlSize(.mini)
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
