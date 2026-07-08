import SwiftUI
import Shared

/// Leading per-session state: working spinner (top priority), else the git/cloud status.
/// Worktree: ✓ done / ⎇ not-done / neutral pristine. Remote: cloud-done / cloud-off + ↑N ↓N counts.
/// `bgOpen` > 0 adds a static mono "⧗N" badge (open background tasks) — static because the
/// session list is a high-frequency surface; the pulse lives in the chat, not here.
struct SessionStatusRail: View {
    let git: GitLiteStatusDto?
    var working: Bool = false
    var bgOpen: Int = 0

    var body: some View {
        HStack(spacing: 4) {
            if bgOpen > 0 {
                Text("⧗\(bgOpen)")
                    .font(.caption2.monospaced().weight(.medium))
                    .foregroundStyle(.orange)
            }
            mainIndicator
        }
    }

    @ViewBuilder private var mainIndicator: some View {
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
