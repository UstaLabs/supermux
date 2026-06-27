import SwiftUI
import Shared

/// Leading per-session state: working spinner (top priority), else the git/cloud status.
/// Worktree: ✓ done / ⎇ not-done / neutral pristine. Remote: cloud-done / cloud-off + ↑N ↓N counts.
struct SessionStatusRail: View {
    let git: GitLiteStatusDto?
    var working: Bool = false

    var body: some View {
        if working {
            ProgressView().controlSize(.mini)
        } else if let st = GitBadgeKt.sessionStatus(git: git) {
            switch (st.kind, st.level) {
            case (.worktree, .done):    icon("checkmark", .green)
            case (.worktree, .notDone): icon("arrow.triangle.branch", .orange)
            case (.worktree, .pristine): neutralDot
            case (.remote, .done):      icon("checkmark.icloud", .green)
            case (.remote, _):
                HStack(spacing: 4) {
                    Image(systemName: "icloud").font(.system(size: 11, weight: .semibold)).foregroundStyle(.orange)
                    if let text = GitBadgeKt.gitBadge(git: git)?.text, !text.isEmpty {
                        Text(text).font(.caption2.monospaced()).foregroundStyle(.orange)
                    }
                }
            default: neutralDot
            }
        } else {
            neutralDot
        }
    }

    private func icon(_ name: String, _ color: Color) -> some View {
        Image(systemName: name).font(.system(size: 11, weight: .semibold)).foregroundStyle(color)
    }
    private var neutralDot: some View {
        Circle().fill(Color.secondary.opacity(0.3)).frame(width: 6, height: 6)
    }
}
