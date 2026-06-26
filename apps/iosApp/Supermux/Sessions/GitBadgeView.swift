import SwiftUI
import Shared

/// Per-session git badge: branch icon + `+N −M` for local (base), `↑N ↓M` for remote.
/// Renders nothing when `git` is nil (non-repo session).
struct GitBadgeView: View {
    let git: GitLiteStatusDto?

    var body: some View {
        if let badge = GitBadgeKt.gitBadge(git: git) {
            HStack(spacing: 3) {
                if badge.kind == .base {
                    Image(systemName: "arrow.triangle.branch")
                        .font(.system(size: 9, weight: .medium))
                }
                Text(badge.text)
                    .font(.caption2.monospaced())
            }
            .foregroundStyle(badge.tone == .muted ? Color.secondary : Color.primary)
            .lineLimit(1)
        }
    }
}
