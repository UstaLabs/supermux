import SwiftUI
import Shared

/// Leading session status: a colored rail + check/branch icon (green = done, amber = not-done).
/// Renders nothing for sessions without a worktree status.
struct SessionStatusRail: View {
    let git: GitLiteStatusDto?

    var body: some View {
        if let state = GitBadgeKt.sessionDoneState(git: git) {
            let color: Color = state == .done ? .green : .orange
            HStack(spacing: 4) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(color)
                    .frame(width: 3, height: 20)
                Image(systemName: state == .done ? "checkmark" : "arrow.triangle.branch")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(color)
            }
        }
    }
}
