import SwiftUI
import Shared

/// Collapsed sidebar — a 56-pt vertical rail of session avatars (PWA parity with
/// `src/web-app/src/components/SidebarRail.vue`). Shown in place of `SessionsListView`
/// when `layout.sidebarCollapsed` is true. Top: expand + new-session buttons; below,
/// a flat (ungrouped) scroll of tappable avatars with a selection ring, a working dot,
/// and a context menu (Mute / Rename / Settle) mirroring the full list's row actions.
struct SessionsRailView: View {
    let fleet: Fleet
    @Binding var selected: String?
    var onExpand: () -> Void
    var onNewSession: () -> Void
    var onSessionSelected: (String) -> Void = { _ in }

    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif
    @State private var continueTarget: SessionInfo?

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
            // SF-symbol-only button — no stable text to match on in UI tests.
            .accessibilityIdentifier("new-session")

            ScrollView {
                VStack(spacing: 10) {
                    // sortOrder only — match the expanded list; messages must not reshuffle avatars.
                    ForEach(sessionsByUserOrder(sessions: fleet.filteredSessions), id: \.id) { s in avatar(s) }
                }
                .padding(.vertical, 2)
            }
            .scrollIndicators(.hidden)
        }
        .frame(width: 56)
        .padding(.top, 8)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(.bar)
        .sheet(item: Binding(
            get: { continueTarget.map { ContinueSheetItem(session: $0) } },
            set: { continueTarget = $0?.session }
        )) { item in
            if let b = fleet.broker(for: item.session.id) {
                ContinueConversationSheet(
                    broker: b,
                    source: item.session,
                    onStarted: { id in
                        continueTarget = nil
                        selected = id
                        onSessionSelected(id)
                    },
                    onCancel: { continueTarget = nil }
                )
            }
        }
    }

    private struct ContinueSheetItem: Identifiable {
        let session: SessionInfo
        var id: String { session.id }
    }

    @ViewBuilder private func avatar(_ s: SessionInfo) -> some View {
        let b = fleet.broker(for: s.id)
        let muted = s.mute?.boolValue ?? false
        let selectedNow = s.id == selected
        Button {
            selected = s.id
            onSessionSelected(s.id)
        } label: {
            AgentLogo(agent: s.agent, size: 40)
                .overlay(alignment: .topTrailing) {
                    if working(s) {
                        Circle().fill(Theme.teal).frame(width: 9, height: 9)
                            .overlay(Circle().strokeBorder(Color.smBackground, lineWidth: 1.5))
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
            #if os(macOS)
            Button { openWindow(id: "session", value: s.id) } label: {
                Label("Open in New Window", systemImage: "macwindow.badge.plus")
            }
            Divider()
            #endif
            Button { b?.toggleMute(s) } label: {
                Label(muted ? "Unmute" : "Mute", systemImage: muted ? "bell.slash" : "bell")
            }
            // Rename needs a text field; surface it by re-expanding to the full list.
            Button { onExpand() } label: { Label("Rename", systemImage: "pencil") }
            Button { continueTarget = s } label: {
                Label("Continue in new conversation", systemImage: "bubble.left.and.text.bubble.right")
            }
            Button(role: .destructive) { b?.kill(s.id) } label: { Label("Settle", systemImage: "checkmark.circle") }
        }
    }

    private func working(_ s: SessionInfo) -> Bool { fleet.broker(for: s.id)?.agentWorking[s.id] == true }
}
