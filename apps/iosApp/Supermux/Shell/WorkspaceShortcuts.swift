import SwiftUI

/// PWA-parity ⌘ keyboard shortcuts for the regular-width iPad workspace.
/// Semantics + chords mirror the web PWA 1:1 — see `src/web-app/src/lib/keybindings/commands.ts`.
/// `apply(to:)` is PURE (synchronous, view-host-free) so the invariants are unit-testable;
/// `newSession` routing is intentionally NOT a layout mutation, so it's handled by the caller.
enum WorkspaceCommand: CaseIterable {
    case toggleSidebar    // ⌘B  workspace.toggleSidebar
    case toggleChat       // ⌘L  workspace.toggleChat
    case toggleTerminal   // ⌘T  workspace.toggleTerminal
    case toggleEditor     // ⌘E  workspace.toggleEditor
    case toggleDisplay    // ⌘D  workspace.toggleDisplay
    case newSession       // ⌘N  workspace.newSession (routing, not layout)

    /// Mutates `layout` per the PWA semantics. Never leaves the detail area empty:
    /// chat may only be hidden while a work pane is open, and closing the last work
    /// pane re-shows chat. `newSession` is a no-op here (routing is the caller's job).
    func apply(to layout: WorkspaceLayoutModel) {
        switch self {
        case .toggleSidebar:
            layout.sidebarCollapsed.toggle()
        case .toggleChat:
            // Chat can be hidden only when another pane is visible; it can always be re-shown.
            if layout.chatOpen {
                if layout.editorOpen || layout.terminalOpen || layout.displayOpen {
                    layout.chatOpen = false
                }
            } else {
                layout.chatOpen = true
            }
        case .toggleTerminal:
            layout.terminalOpen.toggle()
            ensureSomethingVisible(layout)
        case .toggleEditor:
            layout.editorOpen.toggle()
            ensureSomethingVisible(layout)
        case .toggleDisplay:
            layout.displayOpen.toggle()
            ensureSomethingVisible(layout)
        case .newSession:
            break   // routing handled by the caller's closure
        }
    }

    /// Guards the invariant "the detail area is never empty": if every pane is closed,
    /// fall back to chat (so closing the last work pane while chat is hidden re-shows it).
    private func ensureSomethingVisible(_ layout: WorkspaceLayoutModel) {
        if !layout.chatOpen && !layout.editorOpen && !layout.terminalOpen && !layout.displayOpen {
            layout.chatOpen = true
        }
    }

    /// Human-readable command name (parity with the PWA command labels). Used for the
    /// hidden shortcut buttons' titles + future discoverability.
    var title: String {
        switch self {
        case .toggleSidebar:  "Toggle session list"
        case .toggleChat:     "Toggle chat"
        case .toggleTerminal: "Toggle terminal"
        case .toggleEditor:   "Toggle editor"
        case .toggleDisplay:  "Toggle display"
        case .newSession:     "New session"
        }
    }

    /// The chord key paired with `.command` (mirrors the PWA `defaultChord.key`).
    var key: KeyEquivalent {
        switch self {
        case .toggleSidebar:  "b"
        case .toggleChat:     "l"
        case .toggleTerminal: "t"
        case .toggleEditor:   "e"
        case .toggleDisplay:  "d"
        case .newSession:     "n"
        }
    }
}

/// Attaches every `WorkspaceCommand` as a ⌘-chord via hidden, in-hierarchy titled buttons.
/// SwiftUI fires `keyboardShortcut` on in-hierarchy buttons even when they're not visible, so
/// the buttons are collapsed to zero size and made non-interactive (no layout/hit-test impact).
private struct WorkspaceShortcutsModifier: ViewModifier {
    @Bindable var layout: WorkspaceLayoutModel
    let onNewSession: () -> Void

    func body(content: Content) -> some View {
        content.background {
            ZStack {
                ForEach(Array(WorkspaceCommand.allCases.enumerated()), id: \.offset) { _, cmd in
                    Button(cmd.title) {
                        if cmd == .newSession { onNewSession() }
                        else { cmd.apply(to: layout) }
                    }
                    .keyboardShortcut(cmd.key, modifiers: .command)
                }
            }
            .frame(width: 0, height: 0)
            .opacity(0)
            .accessibilityHidden(true)
            .allowsHitTesting(false)
        }
    }
}

extension View {
    /// Wires the PWA-parity ⌘ shortcuts (⌘B/L/T/E/D/N) onto a workspace subtree.
    /// `onNewSession` runs for ⌘N (routing); the others mutate `layout` via `apply(to:)`.
    func workspaceShortcuts(
        layout: WorkspaceLayoutModel,
        onNewSession: @escaping () -> Void
    ) -> some View {
        modifier(WorkspaceShortcutsModifier(layout: layout, onNewSession: onNewSession))
    }
}
