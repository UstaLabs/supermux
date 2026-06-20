import SwiftUI

/// The iPad header's pane show/hide cluster — native port of the PWA `PaneSwitcher`
/// "header-cluster" mode (`src/web-app/src/components/PaneSwitcher.vue`): four inline icon
/// toggles (chat · terminal · editor · display) in a rounded capsule. Each routes through
/// `WorkspaceCommand.apply(to:)` so the "never empty" invariant is reused, not re-implemented.
/// Open panes read as filled/teal; the chat toggle disables when it's the only open pane.
struct PaneToggleCluster: View {
    @Bindable var layout: WorkspaceLayoutModel

    /// Icons match ChatView's TabView (bubble.left / terminal / chevron…/display) so the
    /// toggle for a pane looks like the pane's own tab icon on the compact path.
    private struct Pane: Identifiable {
        let id: String
        let icon: String
        let label: String
        let command: WorkspaceCommand
        let isOpen: (WorkspaceLayoutModel) -> Bool
    }

    private let panes: [Pane] = [
        Pane(id: "chat", icon: "bubble.left", label: "Chat",
             command: .toggleChat, isOpen: { $0.chatOpen }),
        Pane(id: "terminal", icon: "terminal", label: "Terminal",
             command: .toggleTerminal, isOpen: { $0.terminalOpen }),
        Pane(id: "editor", icon: "chevron.left.forwardslash.chevron.right", label: "Editor",
             command: .toggleEditor, isOpen: { $0.editorOpen }),
        Pane(id: "display", icon: "display", label: "Display",
             command: .toggleDisplay, isOpen: { $0.displayOpen }),
    ]

    /// Chat can't be hidden when it's the last visible pane (PWA `chatToggleDisabled`).
    private var chatToggleDisabled: Bool {
        !layout.editorOpen && !layout.terminalOpen && !layout.displayOpen
    }

    var body: some View {
        HStack(spacing: 2) {
            ForEach(panes) { pane in
                let open = pane.isOpen(layout)
                Button {
                    pane.command.apply(to: layout)
                } label: {
                    Image(systemName: pane.icon)
                        .font(.system(size: 14, weight: .semibold))
                        .frame(width: 30, height: 28)
                        .foregroundStyle(open ? Color.white : Color.secondary)
                        .background {
                            if open {
                                RoundedRectangle(cornerRadius: 7, style: .continuous).fill(Theme.teal)
                            }
                        }
                        .contentShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(pane.id == "chat" && chatToggleDisabled)
                .hoverEffect(.highlight)
                .accessibilityLabel(pane.id == "chat" ? "Toggle chat" : "Toggle \(pane.label.lowercased())")
                .accessibilityValue(open ? "Shown" : "Hidden")
            }
        }
        .padding(2)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color(.tertiarySystemFill))
        )
        .animation(.snappy(duration: 0.2), value: layout.chatOpen)
        .animation(.snappy(duration: 0.2), value: layout.terminalOpen)
        .animation(.snappy(duration: 0.2), value: layout.editorOpen)
        .animation(.snappy(duration: 0.2), value: layout.displayOpen)
    }
}
