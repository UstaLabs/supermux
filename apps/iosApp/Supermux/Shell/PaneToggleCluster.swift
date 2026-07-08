import SwiftUI

/// The iPad header's pane show/hide cluster — native port of the PWA `PaneSwitcher`
/// "header-cluster" mode (`src/web-app/src/components/PaneSwitcher.vue`): four inline icon
/// toggles (chat · terminal · editor · display) in a rounded capsule. Each routes through
/// `WorkspaceCommand.apply(to:session:)` so the "never empty" invariant is reused, not
/// re-implemented. Open/closed state is read from the CURRENT session's panes (PWA
/// `panelsFor(sessionId)`). Open panes read as filled/teal; the chat toggle disables when it's
/// the only open pane.
struct PaneToggleCluster: View {
    @Bindable var layout: WorkspaceLayoutModel
    /// The session whose panes these toggles show/control (PWA `panels[sessionId]`).
    let sessionId: String

    /// Icons match ChatView's TabView (bubble.left / terminal / chevron…/display) so the
    /// toggle for a pane looks like the pane's own tab icon on the compact path.
    private struct Pane: Identifiable {
        let id: String
        let icon: String
        let label: String
        let command: WorkspaceCommand
        let isOpen: (PaneVisibility) -> Bool
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

    /// This session's pane state, re-read each render so toggles reflect the live selection.
    private var visibility: PaneVisibility { layout.panes(for: sessionId) }

    /// Chat can't be hidden when it's the last visible pane (PWA `chatToggleDisabled`).
    private var chatToggleDisabled: Bool {
        let v = visibility
        return !v.editorOpen && !v.terminalOpen && !v.displayOpen
    }

    var body: some View {
        let v = visibility
        HStack(spacing: 2) {
            ForEach(panes) { pane in
                let open = pane.isOpen(v)
                let locked = pane.id == "chat" && chatToggleDisabled
                Group {
                    if locked {
                        // Chat is the last visible pane: not toggleable, but it must still
                        // read as OPEN — `.disabled` dims the teal to gray, which looks broken.
                        chip(pane, open: open)
                    } else {
                        Button {
                            pane.command.apply(to: layout, session: sessionId)
                        } label: {
                            chip(pane, open: open)
                        }
                        .buttonStyle(.plain)
                        .smHoverHighlight()
                    }
                }
                .accessibilityLabel(pane.id == "chat" ? "Toggle chat" : "Toggle \(pane.label.lowercased())")
                .accessibilityValue(open ? "Shown" : "Hidden")
            }
        }
        .padding(2)
        .background(
            RoundedRectangle(cornerRadius: 9, style: .continuous).fill(Color.smTertiaryFill)
        )
        .animation(.snappy(duration: 0.2), value: v)
    }

    private func chip(_ pane: Pane, open: Bool) -> some View {
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
}
