import SwiftUI
import Shared

/// Collapsed sidebar — a 56-pt vertical rail of session avatars (PWA parity with
/// `src/web-app/src/components/SidebarRail.vue`). Shown in place of `SessionsListView`
/// when `layout.sidebarCollapsed` is true. Top: expand + new-session buttons; below,
/// a flat (ungrouped) scroll of tappable avatars with a selection ring, a working dot,
/// and a context menu (Mute / Rename / Kill) mirroring the full list's row actions.
struct SessionsRailView: View {
    let broker: BrokerSession
    @Binding var selected: String?
    var onExpand: () -> Void
    var onNewSession: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Button(action: onExpand) {
                Image(systemName: "sidebar.left").font(.title3).foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Expand sidebar")

            Button(action: onNewSession) {
                Image(systemName: "plus.circle.fill").font(.title2).foregroundStyle(Theme.teal)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Start a new session")

            ScrollView {
                VStack(spacing: 10) {
                    ForEach(broker.sessions, id: \.id) { s in avatar(s) }
                }
                .padding(.vertical, 2)
            }
            .scrollIndicators(.hidden)
        }
        .frame(width: 56)
        .padding(.top, 8)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(.bar)
    }

    @ViewBuilder private func avatar(_ s: SessionInfo) -> some View {
        let muted = s.mute?.boolValue ?? false
        let selectedNow = s.id == selected
        Button { selected = s.id } label: {
            AgentLogo(agent: s.agent, size: 40)
                .overlay(alignment: .topTrailing) {
                    if working(s) {
                        Circle().fill(Theme.teal).frame(width: 9, height: 9)
                            .overlay(Circle().strokeBorder(Color(.systemBackground), lineWidth: 1.5))
                            .offset(x: 3, y: -3)
                    }
                }
                .overlay {
                    RoundedRectangle(cornerRadius: 8, style: .continuous)
                        .strokeBorder(selectedNow ? Theme.teal : .clear, lineWidth: 2)
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(s.name)
        .contextMenu {
            Button { broker.toggleMute(s) } label: {
                Label(muted ? "Unmute" : "Mute", systemImage: muted ? "bell.slash" : "bell")
            }
            // Rename needs a text field; surface it by re-expanding to the full list.
            Button { onExpand() } label: { Label("Rename", systemImage: "pencil") }
            Button(role: .destructive) { broker.kill(s.id) } label: { Label("Kill", systemImage: "xmark.circle") }
        }
    }

    private func working(_ s: SessionInfo) -> Bool { broker.agentWorking[s.id] == true }
}
