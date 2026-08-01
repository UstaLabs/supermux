import SwiftUI
import Shared

/// Leading per-session state, priority order:
///  1. working spinner (hides unread — the agent is still busy)
///  2. unread green dot when idle with a newer message than last_read_at
///  3. git/cloud status icon (or a quiet gray neutral dot when pristine/unknown)
///
/// `bgOpen` > 0 adds a static mono "⧗N" badge (open background tasks) — static because the
/// session list is a high-frequency surface; the pulse lives in the chat, not here.
struct SessionStatusRail: View {
    let git: GitLiteStatusDto?
    var working: Bool = false
    var bgOpen: Int = 0
    /// Idle + unread (last message newer than server last_read_at). Suppressed while [working].
    var unread: Bool = false

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
        } else if unread {
            // Larger + solid green with soft ring — distinct from the quiet gray neutral.
            ZStack {
                Circle()
                    .stroke(Color.green.opacity(0.35), lineWidth: 1.5)
                    .frame(width: 10, height: 10)
                Circle()
                    .fill(Color.green)
                    .frame(width: 7, height: 7)
            }
            .accessibilityLabel("unread")
        } else if let st = GitBadgeKt.sessionStatus(git: git) {
            switch (st.kind, st.level) {
            case (.worktree, .done):    icon("checkmark", .green)
            case (.worktree, .notDone): icon("arrow.triangle.branch", .orange)
            case (.worktree, .pristine): neutralDot
            case (.remote, .done):      icon("checkmark.icloud", .green)
            case (.remote, _):          icon("icloud", .orange)
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
